/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.playlist

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.util.PermissionHelper

/**
 * The dialogs of the "Find removed videos" feature. The check itself runs in the background
 * in [RemovedVideosWorker]; its result is reviewed here and the chosen videos are moved into
 * the `removedvideos` playlist.
 */
object RemovedVideosDialogs {
    private const val TAG = "RemovedVideosDialogs"

    /**
     * Starts checking for removed videos in the background, unless a check is already running.
     * If the last check found removed videos that haven't been reviewed yet, the user is asked
     * whether to review them or to start a new check.
     *
     * @param activity the activity to show dialogs in
     * @param onlyPlaylistId the playlist to check, or `null` to check all local playlists
     */
    @JvmStatic
    fun startScan(activity: Activity, onlyPlaylistId: Long?) {
        if (RemovedVideosWorker.isRunning(activity)) {
            Toast.makeText(activity, R.string.removed_videos_already_running, Toast.LENGTH_LONG)
                .show()
            return
        }

        val pending = RemovedVideosWorker.loadResult(activity)
        if (pending != null && pending.removedVideos.isNotEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.find_removed_videos)
                .setMessage(
                    activity.getString(
                        R.string.removed_videos_unreviewed,
                        pending.removedVideos.size
                    )
                )
                .setPositiveButton(R.string.removed_videos_review) { _, _ ->
                    showResult(activity, pending)
                }
                .setNeutralButton(R.string.removed_videos_new_check) { _, _ ->
                    RemovedVideosWorker.clearResult(activity)
                    enqueue(activity, onlyPlaylistId)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            enqueue(activity, onlyPlaylistId)
        }
    }

    private fun enqueue(activity: Activity, onlyPlaylistId: Long?) {
        // the progress is shown in a notification, so ask for permission to show it
        PermissionHelper.checkPostNotificationsPermission(
            activity,
            PermissionHelper.POST_NOTIFICATIONS_REQUEST_CODE
        )
        RemovedVideosWorker.start(activity, onlyPlaylistId)
        Toast.makeText(activity, R.string.removed_videos_started, Toast.LENGTH_LONG).show()
    }

    /**
     * Shows the result of the last check, if there is one that hasn't been reviewed yet.
     * Called when the user taps the "removed videos found" notification.
     */
    @JvmStatic
    fun showPendingResult(context: Context) {
        val result = RemovedVideosWorker.loadResult(context)
        if (result == null || result.removedVideos.isEmpty()) {
            Toast.makeText(context, R.string.removed_videos_nothing_to_review, Toast.LENGTH_SHORT)
                .show()
            return
        }
        showResult(context, result)
    }

    private fun showResult(context: Context, result: RemovedVideosChecker.ScanResult) {
        val notes = ArrayList<String>()
        if (result.stoppedBecauseBlocked) {
            notes.add(context.getString(R.string.removed_videos_blocked))
        }
        if (result.notCheckedCount > 0) {
            notes.add(
                context.getString(R.string.removed_videos_not_checked, result.notCheckedCount)
            )
        }

        if (notes.isNotEmpty()) {
            AlertDialog.Builder(context)
                .setTitle(
                    context.getString(
                        R.string.removed_videos_found_title,
                        result.removedVideos.size
                    )
                )
                .setMessage(notes.joinToString("\n\n"))
                .setNegativeButton(R.string.removed_videos_later, null)
                .setPositiveButton(R.string.removed_videos_review) { _, _ ->
                    showReviewDialog(context, result)
                }
                .show()
        } else {
            showReviewDialog(context, result)
        }
    }

    private fun showReviewDialog(context: Context, result: RemovedVideosChecker.ScanResult) {
        val videos = result.removedVideos
        val labels = videos.map { video ->
            val details = listOf(video.uploader, video.playlistNames.joinToString(", "))
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (details.isEmpty()) video.title else video.title + "\n" + details
        }.toTypedArray()
        val checked = BooleanArray(videos.size) { true }

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.removed_videos_found_title, videos.size))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNegativeButton(R.string.removed_videos_later, null)
            .setNeutralButton(R.string.removed_videos_discard) { _, _ ->
                RemovedVideosWorker.clearResult(context)
            }
            .setPositiveButton(R.string.removed_videos_move) { _, _ ->
                val streamIds = videos.indices
                    .filter { checked[it] }
                    .map { videos[it].streamId }
                if (streamIds.isEmpty()) {
                    Toast.makeText(context, R.string.no_videos_selected, Toast.LENGTH_SHORT)
                        .show()
                } else {
                    move(context, streamIds, result.scannedPlaylistIds)
                }
            }
            .show()
    }

    private fun move(context: Context, streamIds: List<Long>, sourcePlaylistIds: List<Long>) {
        val appContext = context.applicationContext
        val checker = RemovedVideosChecker(NewPipeDatabase.getInstance(appContext))
        // not tied to the screen: moving is quick and should finish even if the screen closes
        checker.moveToRemovedVideosPlaylist(streamIds, sourcePlaylistIds)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                {
                    RemovedVideosWorker.clearResult(appContext)
                    Toast.makeText(
                        appContext,
                        appContext.getString(R.string.removed_videos_moved, streamIds.size),
                        Toast.LENGTH_LONG
                    ).show()
                },
                { throwable ->
                    Log.e(TAG, "Moving removed videos failed", throwable)
                    Toast.makeText(appContext, R.string.removed_videos_error, Toast.LENGTH_LONG)
                        .show()
                }
            )
    }
}
