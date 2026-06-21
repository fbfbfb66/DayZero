package com.example.data.sync.chat

import android.util.Log
import com.example.data.sync.ChatRemotePullGateway
import com.example.data.sync.ChatRemotePullResult
import com.example.domain.identity.CurrentIdentityProvider
import com.example.domain.model.sync.ChatSyncServerCursor
import kotlinx.coroutines.CancellationException

class ChatMessagePullCoordinator(
    private val identityProvider: CurrentIdentityProvider,
    private val remotePullGateway: ChatRemotePullGateway,
    private val remoteMerger: ChatMessageRemoteMerger,
    private val stateStore: ChatMessagePullStateStore
) {

    suspend fun pullMessages(limit: Int = 100): ChatMessagePullResult {
        val identity = identityProvider.currentIdentity()
        if (!identity.canRemoteSync) {
            return ChatMessagePullResult.BlockedIdentity("remote_sync_disabled")
        }
        val remoteUserId = identity.remoteUserId ?: return ChatMessagePullResult.BlockedIdentity("no_remote_user_id")
        val identityLocalId = identity.localOwnerId

        var currentCursor = stateStore.getCursor(remoteUserId)?.let { (time, id) ->
            ChatSyncServerCursor(time, id)
        }
        var totalStats = ChatMessageMergeStats()
        var pagesFetched = 0

        while (true) {
            when (val pullResult = remotePullGateway.fetchMessagePage(identity, currentCursor, limit)) {
                is ChatRemotePullResult.Success -> {
                    val page = pullResult.data
                    if (page.items.isEmpty()) {
                        return ChatMessagePullResult.Success(totalStats, pagesFetched)
                    }

                    val pageStats = try {
                        remoteMerger.mergeMessagePage(identityLocalId, page.items)
                    } catch (e: MissingParentConversationException) {
                        Log.w(TAG, "message pull deferred missing parent message=${e.messageId.take(8)} parent=${e.conversationId.take(8)}")
                        return ChatMessagePullResult.DeferredMissingParent(e.conversationId)
                    } catch (e: ImmutableMessageConflictException) {
                        Log.e(TAG, "message pull immutable conflict id=${e.messageId.take(8)} field=${e.fieldName}")
                        return ChatMessagePullResult.ImmutableConflict(e.fieldName)
                    } catch (e: ImmutableMessageContentConflictException) {
                        Log.e(TAG, "message pull content conflict id=${e.messageId.take(8)} field=${e.fieldName}")
                        return ChatMessagePullResult.ImmutableContentConflict(e.fieldName)
                    } catch (e: InvalidRemoteMessageException) {
                        Log.e(TAG, "message pull invalid remote id=${e.messageId.take(8)} reason=${e.reason}")
                        return ChatMessagePullResult.InvalidRemoteMessage(e.reason)
                    } catch (e: CardMergeConflictException) {
                        Log.e(TAG, "message pull card conflict message=${e.messageId.take(8)} card=${e.cardId.take(8)} reason=${e.reason}")
                        return ChatMessagePullResult.CardConflict(e.reason)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "message pull merge failed", e)
                        return ChatMessagePullResult.RetryableFailure("room_merge_failed: ${e.message}")
                    }

                    totalStats += pageStats
                    pagesFetched++

                    if (pageStats.deferredLocalDirtyCount > 0) {
                        Log.d(TAG, "message pull page had dirty deferrals count=${pageStats.deferredLocalDirtyCount}")
                    }

                    try {
                        page.nextCursor?.let {
                            stateStore.saveCursor(remoteUserId, it.serverUpdatedAt, it.id)
                            currentCursor = it
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        return ChatMessagePullResult.RetryableFailure("cursor_save_failed: ${e.message}")
                    }

                    if (!page.hasMore) {
                        return ChatMessagePullResult.Success(totalStats, pagesFetched)
                    }
                }

                is ChatRemotePullResult.Skipped -> return ChatMessagePullResult.RetryableFailure(pullResult.reason)
                is ChatRemotePullResult.RetryableFailure -> return ChatMessagePullResult.RetryableFailure(pullResult.message)
                is ChatRemotePullResult.FatalFailure -> return ChatMessagePullResult.FatalFailure(pullResult.message)
            }
        }
    }

    companion object {
        private const val TAG = "DayZeroChatMsgPull"
    }
}

sealed class ChatMessagePullResult {
    data class Success(val stats: ChatMessageMergeStats, val pagesFetched: Int) : ChatMessagePullResult()
    data class RetryableFailure(val reason: String) : ChatMessagePullResult()
    data class FatalFailure(val reason: String) : ChatMessagePullResult()
    data class BlockedIdentity(val reason: String) : ChatMessagePullResult()
    data class DeferredMissingParent(val conversationId: String) : ChatMessagePullResult()
    data class ImmutableConflict(val fieldName: String) : ChatMessagePullResult()
    data class ImmutableContentConflict(val fieldName: String) : ChatMessagePullResult()
    data class InvalidRemoteMessage(val reason: String) : ChatMessagePullResult()
    data class CardConflict(val reason: String) : ChatMessagePullResult()
}
