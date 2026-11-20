/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.playlist

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R

/**
 * Runs the "Find removed videos" check in the background, showing the progress in a
 * notification (like the subscriptions refresh does). When done, the result is saved and a
 * notification is shown; tapping it opens the app and shows the result for review.
 */
class RemovedVideosWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    // This is needed for API levels < 31 (Android S).
    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(0, 0)
    }

    override suspend fun doWork(): Result {
        val playlistId = inputData.getLong(KEY_PLAYLIST_ID, NO_PLAYLIST_ID)
        val onlyPlaylistId = if (playlistId == NO_PLAYLIST_ID) null else playlistId

        setForeground(createForegroundInfo(0, 0))

        val checker = RemovedVideosChecker(NewPipeDatabase.getInstance(applicationContext))
        var lastNotificationUpdate = 0L
        val result = try {
            withContext(Dispatchers.IO) {
                checker.scan(
                    onlyPlaylistId = onlyPlaylistId,
                    isCancelled = { isStopped },
                    onProgress = { checked, total ->
                        val now = System.currentTimeMillis()
                        if (checked == total || now - lastNotificationUpdate >= UPDATE_INTERVAL) {
                            lastNotificationUpdate = now
                            setForegroundAsync(createForegroundInfo(checked, total))
                        }
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Checking for removed videos failed", e)
            showFinishedNotification(
                applicationContext.getString(R.string.removed_videos_error),
                null
            )
            return Result.failure()
        }

        if (result == null) {
            // cancelled by the user
            return Result.success()
        }

        if (result.removedVideos.isEmpty()) {
            clearResult(applicationContext)
        } else {
            saveResult(applicationContext, result)
        }
        val title = if (result.removedVideos.isEmpty()) {
            applicationContext.getString(R.string.removed_videos_none_found)
        } else {
            applicationContext.getString(
                R.string.removed_videos_found_title,
                result.removedVideos.size
            )
        }
        val text = when {
            result.stoppedBecauseBlocked ->
                applicationContext.getString(R.string.removed_videos_notification_blocked)

            result.notCheckedCount > 0 ->
                applicationContext.getString(
                    R.string.removed_videos_notification_not_checked,
                    result.notCheckedCount
                )

            result.removedVideos.isNotEmpty() ->
                applicationContext.getString(R.string.removed_videos_notification_tap)

            else -> null
        }
        showFinishedNotification(title, text)
        return Result.success()
    }

    private fun createForegroundInfo(checked: Int, total: Int): ForegroundInfo {
        val notification = NotificationCompat
            .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_newpipe_triangle_white)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, checked, total == 0)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentTitle(
                applicationContext.getString(R.string.removed_videos_checking_title)
            )
            .addAction(
                R.drawable.ic_close,
                applicationContext.getString(R.string.cancel),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
            ).apply {
                if (total > 0) {
                    setContentText(
                        applicationContext.getString(
                            R.string.removed_videos_progress_left,
                            checked,
                            total,
                            total - checked
                        )
                    )
                    val progressText = "$checked/$total"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        setSubText(progressText)
                    } else {
                        setContentInfo(progressText)
                    }
                } else {
                    setContentText(applicationContext.getString(R.string.removed_videos_collecting))
                }
            }.build()

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        return ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification, serviceType)
    }

    private fun showFinishedNotification(title: String, text: String?) {
        val intent = Intent(applicationContext, MainActivity::class.java)
            .setAction(ACTION_SHOW_RESULT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntentCompat.getActivity(
            applicationContext,
            0,
            intent,
            0,
            false
        )
        val builder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_newpipe_triangle_white)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setContentTitle(title)
        if (text != null) {
            builder.setContentText(text)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }

        val notificationManager = NotificationManagerCompat.from(applicationContext)
        if (notificationManager.areNotificationsEnabled()) {
            try {
                notificationManager.notify(RESULT_NOTIFICATION_ID, builder.build())
            } catch (e: SecurityException) {
                Log.w(TAG, "Not allowed to show notification", e)
            }
        }
    }

    companion object {
        // Log tag length is limited to 23 characters on API levels < 24.
        private const val TAG = "RemovedVideosWorker"

        const val ACTION_SHOW_RESULT = "org.schabi.newpipe.SHOW_REMOVED_VIDEOS_RESULT"

        private const val WORK_NAME = "RemovedVideosWorker"
        private const val KEY_PLAYLIST_ID = "playlist_id"
        private const val NO_PLAYLIST_ID = -1L
        private const val PROGRESS_NOTIFICATION_ID = 4570
        private const val RESULT_NOTIFICATION_ID = 4571
        private const val NOTIFICATION_CHANNEL_ID = "newpipe"
        private const val UPDATE_INTERVAL = 400L

        private const val PREF_RESULT = "removed_videos_last_result"

        /**
         * Starts the check in the background.
         *
         * @param onlyPlaylistId the playlist to check, or `null` to check all local playlists
         */
        @JvmStatic
        fun start(context: Context, onlyPlaylistId: Long?) {
            val request = OneTimeWorkRequestBuilder<RemovedVideosWorker>()
                .setInputData(workDataOf(KEY_PLAYLIST_ID to (onlyPlaylistId ?: NO_PLAYLIST_ID)))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** @return whether a check is currently running or about to start */
        @JvmStatic
        fun isRunning(context: Context): Boolean {
            return try {
                WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(WORK_NAME)
                    .get()
                    .any { !it.state.isFinished && it.state != WorkInfo.State.BLOCKED }
            } catch (e: Exception) {
                false
            }
        }

        private fun saveResult(context: Context, result: RemovedVideosChecker.ScanResult) {
            val videos = JSONArray()
            for (video in result.removedVideos) {
                videos.put(
                    JSONObject()
                        .put("id", video.streamId)
                        .put("title", video.title)
                        .put("uploader", video.uploader)
                        .put("playlists", JSONArray(video.playlistNames))
                )
            }
            val json = JSONObject()
                .put("videos", videos)
                .put("playlists", JSONArray(result.scannedPlaylistIds))
                .put("notChecked", result.notCheckedCount)
                .put("blocked", result.stoppedBecauseBlocked)

            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putString(PREF_RESULT, json.toString())
            }
        }

        /** @return the result of the last check that hasn't been reviewed yet, if any */
        @JvmStatic
        fun loadResult(context: Context): RemovedVideosChecker.ScanResult? {
            val text = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_RESULT, null) ?: return null
            return try {
                val json = JSONObject(text)
                val videosJson = json.getJSONArray("videos")
                val videos = (0 until videosJson.length()).map { i ->
                    val video = videosJson.getJSONObject(i)
                    val playlistsJson = video.getJSONArray("playlists")
                    RemovedVideosChecker.RemovedVideo(
                        streamId = video.getLong("id"),
                        title = video.getString("title"),
                        uploader = video.getString("uploader"),
                        playlistNames = (0 until playlistsJson.length())
                            .map { playlistsJson.getString(it) }
                    )
                }
                val playlistsJson = json.getJSONArray("playlists")
                RemovedVideosChecker.ScanResult(
                    removedVideos = videos,
                    scannedPlaylistIds = (0 until playlistsJson.length())
                        .map { playlistsJson.getLong(it) },
                    notCheckedCount = json.getInt("notChecked"),
                    stoppedBecauseBlocked = json.getBoolean("blocked")
                )
            } catch (e: Exception) {
                Log.e(TAG, "Could not read the saved result", e)
                clearResult(context)
                null
            }
        }

        @JvmStatic
        fun clearResult(context: Context) {
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                remove(PREF_RESULT)
            }
            NotificationManagerCompat.from(context).cancel(RESULT_NOTIFICATION_ID)
        }
    }
}
