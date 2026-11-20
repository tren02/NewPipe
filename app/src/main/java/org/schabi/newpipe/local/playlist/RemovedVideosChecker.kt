/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.playlist

import androidx.room3.withWriteTransaction
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Finds videos in local playlists that no longer exist (deleted, made private, taken down,
 * or whose channel was terminated) and moves them into a playlist called
 * [REMOVED_VIDEOS_PLAYLIST_NAME].
 *
 * Videos that still exist but cannot be watched (blocked in the user's country, age-restricted,
 * members-only, paid, upcoming premieres...) are never treated as removed.
 */
class RemovedVideosChecker(private val database: AppDatabase) {

    private val playlistTable = database.playlistDAO()
    private val playlistStreamTable = database.playlistStreamDAO()

    class RemovedVideo(
        val streamId: Long,
        val title: String,
        val uploader: String,
        val playlistNames: List<String>
    )

    class ScanResult(
        val removedVideos: List<RemovedVideo>,
        val scannedPlaylistIds: List<Long>,
        val notCheckedCount: Int,
        val stoppedBecauseBlocked: Boolean
    )

    private enum class Status { EXISTS, REMOVED, UNKNOWN, NETWORK_ERROR, BLOCKED }

    /**
     * Checks every video in the local playlists, one at a time. This blocks, so it must not be
     * called on the main thread.
     *
     * @param onlyPlaylistId if not null, only this playlist is checked; otherwise all local
     * playlists except [REMOVED_VIDEOS_PLAYLIST_NAME] are checked
     * @param isCancelled called before each check; the scan stops and returns `null` once it
     * returns true
     * @param onProgress called with the number of checked videos and the total
     */
    fun scan(
        onlyPlaylistId: Long?,
        isCancelled: () -> Boolean,
        onProgress: (checked: Int, total: Int) -> Unit
    ): ScanResult? {
        val playlists = playlistTable.getAll().blockingFirst()
            .filter { onlyPlaylistId == null || it.uid == onlyPlaylistId }
            .filter { !isRemovedVideosPlaylist(it.name) }

        // each video is checked only once, even if it is in several playlists
        val streams = LinkedHashMap<Long, StreamEntity>()
        val playlistNames = HashMap<Long, MutableList<String>>()
        for (playlist in playlists) {
            val entries = playlistStreamTable.getOrderedStreamsOf(playlist.uid).blockingFirst()
            for (entry in entries) {
                streams.putIfAbsent(entry.streamId, entry.streamEntity)
                val names = playlistNames.getOrPut(entry.streamId) { mutableListOf() }
                val name = playlist.name.orEmpty()
                if (!names.contains(name)) {
                    names.add(name)
                }
            }
        }

        val total = streams.size
        onProgress(0, total)

        val removed = ArrayList<RemovedVideo>()
        var processed = 0
        var notChecked = 0
        var consecutiveNetworkErrors = 0
        var blocked = false

        for ((streamId, stream) in streams) {
            if (isCancelled()) {
                return null
            }

            val status = checkStream(stream)
            processed++
            when (status) {
                Status.EXISTS -> consecutiveNetworkErrors = 0

                Status.REMOVED -> {
                    consecutiveNetworkErrors = 0
                    removed.add(
                        RemovedVideo(
                            streamId = streamId,
                            title = stream.title,
                            uploader = stream.uploader,
                            playlistNames = playlistNames[streamId].orEmpty()
                        )
                    )
                }

                Status.UNKNOWN -> {
                    consecutiveNetworkErrors = 0
                    notChecked++
                }

                Status.NETWORK_ERROR -> {
                    consecutiveNetworkErrors++
                    notChecked++
                }

                Status.BLOCKED -> {
                    blocked = true
                    notChecked++
                }
            }
            onProgress(processed, total)

            if (blocked || consecutiveNetworkErrors >= MAX_CONSECUTIVE_NETWORK_ERRORS) {
                break
            }

            try {
                Thread.sleep(DELAY_BETWEEN_CHECKS_MILLIS)
            } catch (e: InterruptedException) {
                return null
            }
        }

        return ScanResult(
            removedVideos = removed,
            scannedPlaylistIds = playlists.map { it.uid },
            // videos never reached because the scan stopped early were not checked either
            notCheckedCount = notChecked + (total - processed),
            stoppedBecauseBlocked = blocked
        )
    }

    private fun checkStream(stream: StreamEntity): Status {
        return try {
            StreamInfo.getInfo(NewPipe.getService(stream.serviceId), stream.url)
            Status.EXISTS
        } catch (e: Exception) {
            classify(e, stream.serviceId)
        }
    }

    private fun classify(e: Exception, serviceId: Int): Status {
        return when {
            e is ReCaptchaException || e is SignInConfirmNotBotException -> Status.BLOCKED

            e is PrivateContentException || e is AccountTerminatedException -> Status.REMOVED

            // a plain ContentNotAvailableException (not one of its more specific subclasses
            // like GeographicRestrictionException or AgeRestrictedContentException)
            e.javaClass == ContentNotAvailableException::class.java ->
                if (serviceId != ServiceList.YouTube.serviceId ||
                    e.message.orEmpty().contains(YOUTUBE_ERROR_STATUS)
                ) {
                    // YouTube reports deleted and taken down videos with the ERROR status
                    Status.REMOVED
                } else {
                    // e.g. upcoming premieres or other statuses we can't be sure about
                    Status.UNKNOWN
                }

            // the video exists, but can't be watched (country, age, paid, members only...)
            e is ContentNotAvailableException -> Status.EXISTS

            e is IOException -> Status.NETWORK_ERROR

            else -> Status.UNKNOWN
        }
    }

    /**
     * Moves the given videos into the [REMOVED_VIDEOS_PLAYLIST_NAME] playlist (creating it if
     * needed) and removes them from the given playlists.
     */
    fun moveToRemovedVideosPlaylist(
        streamIds: List<Long>,
        sourcePlaylistIds: List<Long>
    ): Completable {
        return Completable.fromAction {
            val toMove = LinkedHashSet(streamIds)
            if (toMove.isEmpty()) {
                return@fromAction
            }

            val allPlaylists = playlistTable.getAll().blockingFirst()
            val target = allPlaylists.firstOrNull { isRemovedVideosPlaylist(it.name) }

            // read everything first, then write everything in one transaction
            val changedPlaylists = ArrayList<Pair<PlaylistEntity, List<Long>>>()
            for (playlist in allPlaylists) {
                if (playlist.uid == target?.uid || playlist.uid !in sourcePlaylistIds) {
                    continue
                }
                val current = playlistStreamTable.getOrderedStreamsOf(playlist.uid)
                    .blockingFirst()
                    .map { it.streamId }
                val kept = current.filter { it !in toMove }
                if (kept.size != current.size) {
                    changedPlaylists.add(Pair(playlist, kept))
                }
            }

            val alreadyInTarget = if (target == null) {
                emptySet()
            } else {
                playlistStreamTable.getOrderedStreamsOf(target.uid)
                    .blockingFirst()
                    .map { it.streamId }
                    .toSet()
            }
            val nextTargetIndex = if (target == null) {
                0
            } else {
                playlistStreamTable.getMaximumIndexOf(target.uid).blockingFirst() + 1
            }
            val newInTarget = toMove.filter { it !in alreadyInTarget }

            runBlocking {
                database.withWriteTransaction<Unit> {
                    for ((playlist, kept) in changedPlaylists) {
                        playlistStreamTable.deleteBatch(playlist.uid)
                        playlistStreamTable.insertAll(
                            kept.mapIndexed { index, streamId ->
                                PlaylistStreamEntity(playlist.uid, streamId, index)
                            }
                        )
                        if (!playlist.isThumbnailPermanent &&
                            playlist.thumbnailStreamId in toMove
                        ) {
                            playlist.thumbnailStreamId =
                                kept.firstOrNull() ?: PlaylistEntity.DEFAULT_THUMBNAIL_ID
                            playlistTable.update(playlist)
                        }
                    }

                    if (newInTarget.isNotEmpty()) {
                        val targetId = target?.uid ?: playlistTable.insert(
                            PlaylistEntity(
                                name = REMOVED_VIDEOS_PLAYLIST_NAME,
                                isThumbnailPermanent = false,
                                thumbnailStreamId = newInTarget[0],
                                displayIndex = -1
                            )
                        )
                        playlistStreamTable.insertAll(
                            newInTarget.mapIndexed { index, streamId ->
                                PlaylistStreamEntity(targetId, streamId, nextTargetIndex + index)
                            }
                        )
                    }
                }
            }
        }.subscribeOn(Schedulers.io())
    }

    companion object {
        const val REMOVED_VIDEOS_PLAYLIST_NAME = "removedvideos"

        /** Pause between two checks, so that YouTube doesn't think we are a bot. */
        private const val DELAY_BETWEEN_CHECKS_MILLIS = 500L

        /** Stop the scan if the connection seems to be gone. */
        private const val MAX_CONSECUTIVE_NETWORK_ERRORS = 5

        private const val YOUTUBE_ERROR_STATUS = "Got error ERROR"

        @JvmStatic
        fun isRemovedVideosPlaylist(name: String?): Boolean {
            return name != null && name.trim().equals(REMOVED_VIDEOS_PLAYLIST_NAME, true)
        }
    }
}
