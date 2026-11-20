/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.playlist

import android.content.Context
import android.util.Log
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.util.DeviceUtils

/**
 * The dialogs of the "Find removed videos" feature: a progress dialog while checking, then a
 * checklist of the removed videos that were found, which can then be moved into the
 * `removedvideos` playlist.
 */
object RemovedVideosDialogs {
    private const val TAG = "RemovedVideosDialogs"
    private const val DIALOG_PADDING_DP = 20

    /**
     * Starts checking for removed videos.
     *
     * @param context the context to show the dialogs in
     * @param disposables disposables of the calling screen, so that the check is cancelled when
     * the screen is closed
     * @param onlyPlaylistId the playlist to check, or `null` to check all local playlists
     */
    @JvmStatic
    fun startScan(context: Context, disposables: CompositeDisposable, onlyPlaylistId: Long?) {
        val checker = RemovedVideosChecker(NewPipeDatabase.getInstance(context))

        val padding = DeviceUtils.dpToPx(DIALOG_PADDING_DP, context)
        val progressText = TextView(context)
        progressText.setText(R.string.removed_videos_collecting)
        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.isIndeterminate = true
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(padding, padding, padding, 0)
        layout.addView(progressText)
        layout.addView(progressBar)

        var scanDisposable: Disposable? = null
        val progressDialog = AlertDialog.Builder(context)
            .setTitle(R.string.removed_videos_checking_title)
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> scanDisposable?.dispose() }
            .show()
        // checking may take a while, so don't let the screen turn off in the meantime
        progressDialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val disposable = checker.scan(onlyPlaylistId)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { event ->
                    when (event) {
                        is RemovedVideosChecker.ScanEvent.Progress -> {
                            progressBar.isIndeterminate = false
                            progressBar.max = event.total
                            progressBar.progress = event.checked
                            progressText.text = context.getString(
                                R.string.removed_videos_progress,
                                event.checked,
                                event.total
                            )
                        }

                        is RemovedVideosChecker.ScanEvent.Finished -> {
                            dismissSafely(progressDialog)
                            showResult(context, disposables, checker, event.result)
                        }
                    }
                },
                { throwable ->
                    Log.e(TAG, "Checking for removed videos failed", throwable)
                    dismissSafely(progressDialog)
                    Toast.makeText(context, R.string.removed_videos_error, Toast.LENGTH_LONG)
                        .show()
                }
            )
        scanDisposable = disposable
        disposables.add(disposable)
        disposables.add(Disposable.fromAction { dismissSafely(progressDialog) })
    }

    private fun showResult(
        context: Context,
        disposables: CompositeDisposable,
        checker: RemovedVideosChecker,
        result: RemovedVideosChecker.ScanResult
    ) {
        val notes = ArrayList<String>()
        if (result.stoppedBecauseBlocked) {
            notes.add(context.getString(R.string.removed_videos_blocked))
        }
        if (result.notCheckedCount > 0) {
            notes.add(
                context.getString(R.string.removed_videos_not_checked, result.notCheckedCount)
            )
        }

        if (result.removedVideos.isEmpty()) {
            notes.add(0, context.getString(R.string.removed_videos_none_found))
            AlertDialog.Builder(context)
                .setTitle(R.string.find_removed_videos)
                .setMessage(notes.joinToString("\n\n"))
                .setPositiveButton(R.string.ok, null)
                .show()
        } else if (notes.isNotEmpty()) {
            AlertDialog.Builder(context)
                .setTitle(
                    context.getString(
                        R.string.removed_videos_found_title,
                        result.removedVideos.size
                    )
                )
                .setMessage(notes.joinToString("\n\n"))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.removed_videos_review) { _, _ ->
                    showReviewDialog(context, disposables, checker, result)
                }
                .show()
        } else {
            showReviewDialog(context, disposables, checker, result)
        }
    }

    private fun showReviewDialog(
        context: Context,
        disposables: CompositeDisposable,
        checker: RemovedVideosChecker,
        result: RemovedVideosChecker.ScanResult
    ) {
        val videos = result.removedVideos
        val labels = videos.map { video ->
            val details = listOf(video.stream.uploader, video.playlistNames.joinToString(", "))
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (details.isEmpty()) video.stream.title else video.stream.title + "\n" + details
        }.toTypedArray()
        val checked = BooleanArray(videos.size) { true }

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.removed_videos_found_title, videos.size))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.removed_videos_move) { _, _ ->
                val streamIds = videos.indices
                    .filter { checked[it] }
                    .map { videos[it].stream.uid }
                if (streamIds.isEmpty()) {
                    Toast.makeText(context, R.string.no_videos_selected, Toast.LENGTH_SHORT)
                        .show()
                } else {
                    move(context, disposables, checker, streamIds, result.scannedPlaylistIds)
                }
            }
            .show()
    }

    private fun move(
        context: Context,
        disposables: CompositeDisposable,
        checker: RemovedVideosChecker,
        streamIds: List<Long>,
        sourcePlaylistIds: List<Long>
    ) {
        disposables.add(
            checker.moveToRemovedVideosPlaylist(streamIds, sourcePlaylistIds)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    {
                        Toast.makeText(
                            context,
                            context.getString(R.string.removed_videos_moved, streamIds.size),
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    { throwable ->
                        Log.e(TAG, "Moving removed videos failed", throwable)
                        Toast.makeText(context, R.string.removed_videos_error, Toast.LENGTH_LONG)
                            .show()
                    }
                )
        )
    }

    private fun dismissSafely(dialog: AlertDialog) {
        try {
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        } catch (e: IllegalArgumentException) {
            // the screen the dialog belonged to is already gone
        }
    }
}
