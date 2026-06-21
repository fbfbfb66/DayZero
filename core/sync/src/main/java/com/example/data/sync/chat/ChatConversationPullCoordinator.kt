package com.example.data.sync.chat

import android.util.Log
import com.example.data.sync.ChatRemotePullGateway
import com.example.data.sync.ChatRemotePullResult
import com.example.domain.identity.CurrentIdentityProvider
import com.example.domain.model.sync.ChatSyncServerCursor
import kotlinx.coroutines.CancellationException

class ChatConversationPullCoordinator(
    private val identityProvider: CurrentIdentityProvider,
    private val remotePullGateway: ChatRemotePullGateway,
    private val remoteMerger: ChatConversationRemoteMerger,
    private val stateStore: ChatConversationPullStateStore
) {

    suspend fun pullConversations(limit: Int = 100): ChatConversationPullResult {
        val identity = identityProvider.currentIdentity()
        if (!identity.canRemoteSync) {
            return ChatConversationPullResult.Skipped("remote_sync_disabled")
        }
        val identityLocalId = identity.localOwnerId
        val remoteUserId = identity.remoteUserId ?: return ChatConversationPullResult.Skipped("no_remote_user_id")

        var currentCursor = stateStore.getCursor(remoteUserId)?.let { (time, id) -> ChatSyncServerCursor(time, id) }
        var totalStats = ChatConversationMergeStats()
        var hasMore = true
        var pagesFetched = 0

        while (hasMore) {
            Log.d("DayZeroChatPull", "fetching page with cursor $currentCursor")
            val pullResult = remotePullGateway.fetchConversationPage(identity, currentCursor, limit)

            when (pullResult) {
                is ChatRemotePullResult.Success -> {
                    val page = pullResult.data
                    if (page.items.isEmpty()) {
                        Log.d("DayZeroChatPull", "page empty, done")
                        break
                    }

                    try {
                        val pageStats = remoteMerger.mergeConversationPage(identityLocalId, page.items)
                        totalStats += pageStats
                        pagesFetched++

                        if (pageStats.immutableConflictCount > 0) {
                             Log.e("DayZeroChatPull", "immutable conflict found in page, stopping to prevent data corruption")
                             return ChatConversationPullResult.FatalFailure("immutable_conflict_detected")
                        }

                        // Save cursor ONLY after successful Room transaction
                        val nextCursor = page.nextCursor
                        if (nextCursor != null) {
                            stateStore.saveCursor(remoteUserId, nextCursor.serverUpdatedAt, nextCursor.id)
                            currentCursor = nextCursor
                        }

                        hasMore = page.hasMore
                    } catch (e: ImmutableConflictException) {
                        Log.e("DayZeroChatPull", "immutable conflict thrown in page, stopping to prevent data corruption: ${e.message}")
                        return ChatConversationPullResult.FatalFailure("immutable_conflict_detected")
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e("DayZeroChatPull", "Room merge failed", e)
                        return ChatConversationPullResult.RetryableFailure("room_merge_failed: ${e.message}")
                    }
                }
                is ChatRemotePullResult.Skipped -> {
                    Log.d("DayZeroChatPull", "remote pull skipped: ${pullResult.reason}")
                    return ChatConversationPullResult.Skipped(pullResult.reason)
                }
                is ChatRemotePullResult.RetryableFailure -> {
                    Log.w("DayZeroChatPull", "remote pull retryable failure: ${pullResult.message}")
                    return ChatConversationPullResult.RetryableFailure(pullResult.message)
                }
                is ChatRemotePullResult.FatalFailure -> {
                    Log.e("DayZeroChatPull", "remote pull fatal failure: ${pullResult.message}")
                    return ChatConversationPullResult.FatalFailure(pullResult.message)
                }
            }
        }

        return ChatConversationPullResult.Success(totalStats, pagesFetched)
    }
}

sealed class ChatConversationPullResult {
    data class Success(val stats: ChatConversationMergeStats, val pagesFetched: Int) : ChatConversationPullResult()
    data class Skipped(val reason: String) : ChatConversationPullResult()
    data class RetryableFailure(val reason: String) : ChatConversationPullResult()
    data class FatalFailure(val reason: String) : ChatConversationPullResult()
}
