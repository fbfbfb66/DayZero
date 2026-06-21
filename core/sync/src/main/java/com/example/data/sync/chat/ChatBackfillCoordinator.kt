package com.example.data.sync.chat

import android.util.Log
import com.example.data.local.dao.AiChatMessageDao
import com.example.data.local.dao.ConversationDao
import com.example.domain.identity.CurrentIdentityProvider
import com.example.data.sync.BackfillStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ChatBackfillCoordinator(
    private val conversationDao: ConversationDao,
    private val messageDao: AiChatMessageDao,
    private val identityProvider: CurrentIdentityProvider,
    private val stateStore: ChatBackfillStateStore,
    private val queueWriter: ChatSyncQueueWriter,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val taskBatchLimit: Int = DEFAULT_TASK_BATCH_LIMIT
) {
    private val mutex = Mutex()

    suspend fun runOnce(): ChatBackfillStats {
        if (mutex.isLocked) {
            Log.d(LOG_PREFIX, "run skipped already running")
            return stateStore.snapshot().toStats()
        }
        return mutex.withLock { runBackfill() }
    }

    private suspend fun runBackfill(): ChatBackfillStats {
        val identity = identityProvider.currentIdentity()
        if (!identity.canRemoteSync || identity.remoteUserId.isNullOrBlank()) {
            Log.d(LOG_PREFIX, "identity waiting for auth")
            return stateStore.snapshot().toStats()
        }

        val snapshot = stateStore.snapshot()
        if (snapshot.status == BackfillStatus.COMPLETED &&
            snapshot.schemaVersion >= ChatBackfillStateStore.CURRENT_CHAT_BACKFILL_VERSION
        ) {
            Log.d(LOG_PREFIX, "run skipped already completed")
            return snapshot.toStats()
        }

        val startedAt = System.currentTimeMillis()
        stateStore.markRunning(startedAt)
        var stats = snapshot.toStats()
        var phase = snapshot.phase

        return try {
            if (phase == ChatBackfillPhase.CONVERSATIONS) {
                stats = scanConversations(snapshot, identity, stats)
                if (stats.enqueuedCount >= taskBatchLimit) return stats
                phase = ChatBackfillPhase.MESSAGES
                stateStore.moveToMessages(stats)
            }

            if (phase == ChatBackfillPhase.MESSAGES) {
                stats = scanMessages(stateStore.snapshot(), identity, stats)
            }

            stats
        } catch (e: IllegalArgumentException) {
            val reason = e.message ?: e::class.java.simpleName
            Log.e(LOG_PREFIX, "run fatal reason=$reason", e)
            stateStore.markFatalFailure(reason, stats)
            stats.copy(errorCount = stats.errorCount + 1)
        } catch (e: Exception) {
            val reason = e.message ?: e::class.java.simpleName
            Log.e(LOG_PREFIX, "run retryable reason=$reason", e)
            stateStore.markRetryableFailure(reason, stats)
            stats.copy(errorCount = stats.errorCount + 1)
        }
    }

    private suspend fun scanConversations(
        snapshot: ChatBackfillStateSnapshot,
        identity: com.example.domain.identity.AppIdentity,
        initialStats: ChatBackfillStats
    ): ChatBackfillStats {
        var stats = initialStats
        var cursorCreatedAt = snapshot.conversationCursorCreatedAt
        var cursorId = snapshot.conversationCursorId
        while (stats.enqueuedCount < taskBatchLimit) {
            val page = conversationDao.getConversationsForChatBackfill(cursorCreatedAt, cursorId, pageSize)
            if (page.isEmpty()) return stats

            page.forEach { conversation ->
                stats = stats.copy(scannedConversationCount = stats.scannedConversationCount + 1)
                val enqueued = queueWriter.enqueueConversationUpsert(conversation, identity)
                stats = if (enqueued) {
                    stats.copy(enqueuedConversationCount = stats.enqueuedConversationCount + 1)
                } else {
                    stats.copy(skippedDuplicateCount = stats.skippedDuplicateCount + 1)
                }
                cursorCreatedAt = conversation.createdAt
                cursorId = conversation.id
                stateStore.saveProgress(ChatBackfillPhase.CONVERSATIONS, cursorCreatedAt, cursorId, stats)
                if (stats.enqueuedCount >= taskBatchLimit) return stats
            }
        }
        return stats
    }

    private suspend fun scanMessages(
        snapshot: ChatBackfillStateSnapshot,
        identity: com.example.domain.identity.AppIdentity,
        initialStats: ChatBackfillStats
    ): ChatBackfillStats {
        var stats = initialStats
        var cursorCreatedAt = snapshot.messageCursorCreatedAt
        var cursorId = snapshot.messageCursorId
        while (stats.enqueuedCount < taskBatchLimit) {
            val page = messageDao.getMessagesForChatBackfill(cursorCreatedAt, cursorId, pageSize)
            if (page.isEmpty()) {
                stateStore.markCompleted(System.currentTimeMillis(), stats)
                Log.d(LOG_PREFIX, "run completed conversations=${stats.scannedConversationCount} messages=${stats.scannedMessageCount}")
                return stats
            }

            page.forEach { message ->
                stats = stats.copy(scannedMessageCount = stats.scannedMessageCount + 1)
                if (!queueWriter.isSyncableFinalMessage(message)) {
                    stats = stats.copy(skippedPlaceholderCount = stats.skippedPlaceholderCount + 1)
                } else {
                    val enqueued = queueWriter.enqueueMessageUpsert(message, identity)
                    stats = if (enqueued) {
                        stats.copy(enqueuedMessageCount = stats.enqueuedMessageCount + 1)
                    } else {
                        stats.copy(skippedDuplicateCount = stats.skippedDuplicateCount + 1)
                    }
                }
                cursorCreatedAt = message.createdAt
                cursorId = message.id
                stateStore.saveProgress(ChatBackfillPhase.MESSAGES, cursorCreatedAt, cursorId, stats)
                if (stats.enqueuedCount >= taskBatchLimit) return stats
            }
        }
        return stats
    }

    private fun ChatBackfillStateSnapshot.toStats(): ChatBackfillStats {
        return ChatBackfillStats(
            scannedConversationCount = scannedConversationCount,
            scannedMessageCount = scannedMessageCount,
            enqueuedConversationCount = enqueuedConversationCount,
            enqueuedMessageCount = enqueuedMessageCount,
            skippedPlaceholderCount = skippedPlaceholderCount,
            skippedDuplicateCount = skippedDuplicateCount
        )
    }

    private companion object {
        private const val LOG_PREFIX = "DayZeroChatBackfill"
        private const val DEFAULT_PAGE_SIZE = 100
        private const val DEFAULT_TASK_BATCH_LIMIT = 200
    }
}
