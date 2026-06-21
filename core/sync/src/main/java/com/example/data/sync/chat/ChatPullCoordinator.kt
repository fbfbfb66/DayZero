package com.example.data.sync.chat

import android.util.Log
import kotlinx.coroutines.sync.Mutex

class ChatPullCoordinator(
    private val conversationPullCoordinator: ChatConversationPullCoordinator,
    private val messagePullCoordinator: ChatMessagePullCoordinator
) {
    private val mutex = Mutex()

    suspend fun pullAll(limit: Int = 100): ChatPullResult {
        if (!mutex.tryLock()) {
            Log.d(TAG, "Chat pull skipped, already running")
            return ChatPullResult.SkippedAlreadyRunning
        }

        try {
            Log.d(TAG, "Starting chat pull orchestration")

            // 1. Pull Conversations
            val convResult = conversationPullCoordinator.pullConversations(limit)

            when (convResult) {
                is ChatConversationPullResult.FatalFailure -> {
                    Log.e(TAG, "Conversation pull fatal failure: ${convResult.reason}")
                    return ChatPullResult.ConversationFatalFailure(convResult.reason)
                }
                is ChatConversationPullResult.RetryableFailure -> {
                    Log.w(TAG, "Conversation pull retryable failure: ${convResult.reason}")
                    return ChatPullResult.ConversationRetryableFailure(convResult.reason)
                }
                is ChatConversationPullResult.Skipped -> {
                    Log.d(TAG, "Conversation pull skipped: ${convResult.reason}")
                    return ChatPullResult.Skipped(convResult.reason)
                }
                is ChatConversationPullResult.Success -> {
                    Log.d(TAG, "Conversation pull success, pages=${convResult.pagesFetched}")
                }
            }

            // 2. Pull Messages (only if conversation pull succeeded)
            val msgResult = messagePullCoordinator.pullMessages(limit)

            return when (msgResult) {
                is ChatMessagePullResult.FatalFailure -> {
                    Log.e(TAG, "Message pull fatal failure: ${msgResult.reason}")
                    ChatPullResult.MessageFatalFailure(
                        reason = msgResult.reason,
                        conversationStats = (convResult as ChatConversationPullResult.Success).stats
                    )
                }
                is ChatMessagePullResult.RetryableFailure -> {
                    Log.w(TAG, "Message pull retryable failure: ${msgResult.reason}")
                    ChatPullResult.MessageRetryableFailure(
                        reason = msgResult.reason,
                        conversationStats = (convResult as ChatConversationPullResult.Success).stats
                    )
                }
                is ChatMessagePullResult.DeferredMissingParent -> {
                    Log.w(TAG, "Message pull deferred missing parent: ${msgResult.conversationId}")
                    ChatPullResult.MessageRetryableFailure(
                        reason = "deferred_missing_parent:${msgResult.conversationId}",
                        conversationStats = (convResult as ChatConversationPullResult.Success).stats
                    )
                }
                is ChatMessagePullResult.BlockedIdentity -> {
                    ChatPullResult.MessageRetryableFailure(
                        reason = "blocked_identity:${msgResult.reason}",
                        conversationStats = (convResult as ChatConversationPullResult.Success).stats
                    )
                }
                is ChatMessagePullResult.ImmutableConflict -> {
                    ChatPullResult.MessageFatalFailure(
                        reason = "immutable_conflict:${msgResult.fieldName}",
                        conversationStats = (convResult as ChatConversationPullResult.Success).stats
                    )
                }
                is ChatMessagePullResult.ImmutableContentConflict -> {
                    ChatPullResult.MessageFatalFailure(
                        reason = "immutable_content_conflict:${msgResult.fieldName}",
                        conversationStats = (convResult as ChatConversationPullResult.Success).stats
                    )
                }
                is ChatMessagePullResult.InvalidRemoteMessage -> {
                    ChatPullResult.MessageFatalFailure(
                        reason = "invalid_remote_message:${msgResult.reason}",
                        conversationStats = (convResult as ChatConversationPullResult.Success).stats
                    )
                }
                is ChatMessagePullResult.CardConflict -> {
                    ChatPullResult.MessageFatalFailure(
                        reason = "card_conflict:${msgResult.reason}",
                        conversationStats = (convResult as ChatConversationPullResult.Success).stats
                    )
                }
                is ChatMessagePullResult.Success -> {
                    Log.d(TAG, "Message pull success, pages=${msgResult.pagesFetched}")
                    ChatPullResult.Success(
                        conversationStats = (convResult as ChatConversationPullResult.Success).stats,
                        messageStats = msgResult.stats
                    )
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    companion object {
        private const val TAG = "DayZeroChatPullOrchestrator"
    }
}

sealed class ChatPullResult {
    data class Success(
        val conversationStats: ChatConversationMergeStats,
        val messageStats: ChatMessageMergeStats
    ) : ChatPullResult()

    data class ConversationRetryableFailure(val reason: String) : ChatPullResult()
    data class ConversationFatalFailure(val reason: String) : ChatPullResult()

    data class MessageRetryableFailure(
        val reason: String,
        val conversationStats: ChatConversationMergeStats
    ) : ChatPullResult()

    data class MessageFatalFailure(
        val reason: String,
        val conversationStats: ChatConversationMergeStats
    ) : ChatPullResult()

    data class Skipped(val reason: String) : ChatPullResult()
    object SkippedAlreadyRunning : ChatPullResult()
}
