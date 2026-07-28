package com.goings.dayzero.data.sync.media

import android.util.Log
import com.goings.dayzero.domain.identity.CurrentIdentityProvider
import kotlinx.coroutines.CancellationException

/**
 * Drives incremental media-asset pull, mirroring
 * [com.goings.dayzero.data.sync.chat.ChatConversationPullCoordinator]. The cursor is advanced only
 * after a page merges successfully into Room.
 */
class MediaPullCoordinator(
    private val identityProvider: CurrentIdentityProvider,
    private val remotePullGateway: MediaRemotePullGateway,
    private val remoteMerger: MediaRemoteMerger,
    private val stateStore: MediaPullStateStore
) {

    suspend fun pullMedia(limit: Int = 100): MediaPullResult {
        val identity = identityProvider.currentIdentity()
        if (!identity.canRemoteSync) return MediaPullResult.Skipped("remote_sync_disabled")
        val remoteUserId = identity.remoteUserId ?: return MediaPullResult.Skipped("no_remote_user_id")

        var currentCursor = stateStore.getCursor(remoteUserId)
            ?.let { (time, id) -> MediaSyncServerCursor(time, id) }
        var totalStats = MediaMergeStats()
        var hasMore = true
        var pagesFetched = 0

        while (hasMore) {
            val pullResult = remotePullGateway.fetchMediaPage(identity, currentCursor, limit)
            when (pullResult) {
                is MediaRemotePullResult.Success -> {
                    val page = pullResult.data
                    if (page.items.isEmpty()) break
                    try {
                        totalStats += remoteMerger.mergeMediaPage(identity, page.items)
                        pagesFetched++
                        val nextCursor = page.nextCursor
                        if (nextCursor != null) {
                            stateStore.saveCursor(remoteUserId, nextCursor.serverUpdatedAt, nextCursor.id)
                            currentCursor = nextCursor
                        }
                        hasMore = page.hasMore
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("DayZeroMediaPull", "Room merge failed", e)
                        return MediaPullResult.RetryableFailure("room_merge_failed: ${e.message}")
                    }
                }
                is MediaRemotePullResult.Skipped -> return MediaPullResult.Skipped(pullResult.reason)
                is MediaRemotePullResult.RetryableFailure -> return MediaPullResult.RetryableFailure(pullResult.message)
                is MediaRemotePullResult.FatalFailure -> return MediaPullResult.FatalFailure(pullResult.message)
            }
        }
        return MediaPullResult.Success(totalStats, pagesFetched)
    }
}

sealed class MediaPullResult {
    data class Success(val stats: MediaMergeStats, val pagesFetched: Int) : MediaPullResult()
    data class Skipped(val reason: String) : MediaPullResult()
    data class RetryableFailure(val reason: String) : MediaPullResult()
    data class FatalFailure(val reason: String) : MediaPullResult()
}
