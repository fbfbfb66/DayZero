package com.example.data.sync

import android.util.Log
import com.example.data.local.dao.ConversationDao
import com.example.data.local.dao.DailyRecordDao
import com.example.data.local.dao.SyncQueueDao
import com.example.data.local.entity.SyncQueueEntity
import com.example.data.sync.chat.ChatSyncQueueContract
import com.example.data.sync.chat.ChatSyncQueueWriter
import com.example.domain.identity.CurrentIdentityProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LocalFirstSyncCoordinator(
    private val syncQueueDao: SyncQueueDao,
    private val identityProvider: CurrentIdentityProvider,
    private val remoteSyncGateway: RemoteSyncGateway = NoopRemoteSyncGateway(),
    private val payloadParser: SyncPayloadParser = SyncPayloadParser(),
    private val dailyRecordDao: DailyRecordDao? = null,
    private val conversationDao: ConversationDao? = null,
    private val chatSyncQueueWriter: ChatSyncQueueWriter? = null,
    private val batchLimit: Int = DEFAULT_BATCH_LIMIT
) : SyncCoordinator {
    private val runMutex = Mutex()

    override suspend fun syncPending() = runOnce()

    override suspend fun runOnce() {
        if (runMutex.isLocked) {
            Log.d(DayZeroSyncConstants.LOG_PREFIX, "runOnce skipped already running")
            return
        }

        runMutex.withLock {
            try {
                runOnceLocked()
            } catch (e: Exception) {
                Log.e(DayZeroSyncConstants.LOG_PREFIX, "runOnce swallowed error reason=${e::class.java.simpleName}", e)
            }
        }
    }

    private suspend fun runOnceLocked() {
        val now = System.currentTimeMillis()
        val resetCount = syncQueueDao.resetStuckProcessingTasks(now - STUCK_PROCESSING_TIMEOUT_MS, now)
        if (resetCount > 0) {
            Log.d(DayZeroSyncConstants.LOG_PREFIX, "reset stuck processing count=$resetCount")
        }

        val cleanupCount = syncQueueDao.deleteDoneOlderThan(now - DONE_RETENTION_MS)
        if (cleanupCount > 0) {
            Log.d(DayZeroSyncConstants.LOG_PREFIX, "queue maintenance deletedDone=$cleanupCount")
        }

        val identity = identityProvider.currentIdentity()
        val pendingCount = syncQueueDao.getPendingCount()
        Log.d(DayZeroSyncConstants.LOG_PREFIX, "runOnce start")
        Log.d(DayZeroSyncConstants.LOG_PREFIX, "pending count $pendingCount")

        val pendingItems = syncQueueDao.getRunnableTasks(now = now, limit = batchLimit)
            .filter { RetryPolicy.canAttempt(now, it) }
        Log.d(DayZeroSyncConstants.LOG_PREFIX, "sync runOnce start items=${pendingItems.size}")
        if (pendingItems.isEmpty()) {
            Log.d(DayZeroSyncConstants.LOG_PREFIX, "runOnce finish processed=0 success=0 retryable=0 fatal=0 skipped=0")
            return
        }

        val canSync = remoteSyncGateway.canSync(identity)
        if (!canSync) {
            Log.d(DayZeroSyncConstants.LOG_PREFIX, "remote sync skipped waiting_for_auth")
        }

        var processed = 0
        var success = 0
        var retryable = 0
        var fatal = 0
        var skipped = 0

        pendingItems.forEach { item ->
            val outcome = try {
                processItem(item)
            } catch (e: Exception) {
                Log.e(DayZeroSyncConstants.LOG_PREFIX, "sync item error id=${item.id}", e)
                markRetryable(item, e.message ?: e::class.java.simpleName)
            }

            processed += 1
            when (outcome) {
                SyncTaskOutcome.SUCCESS -> success += 1
                SyncTaskOutcome.RETRYABLE -> retryable += 1
                SyncTaskOutcome.FATAL -> fatal += 1
                SyncTaskOutcome.SKIPPED -> skipped += 1
            }
        }

        Log.d(DayZeroSyncConstants.LOG_PREFIX, "pending count ${syncQueueDao.getPendingCount()}")
        Log.d(
            DayZeroSyncConstants.LOG_PREFIX,
            "runOnce finish processed=$processed success=$success retryable=$retryable fatal=$fatal skipped=$skipped"
        )
    }

    private suspend fun processItem(item: SyncQueueEntity): SyncTaskOutcome {
        Log.d(
            DayZeroSyncConstants.LOG_PREFIX,
            "processing task id=${item.id} operation=${item.operation} entityType=${item.entityType} clientId=${item.entityLocalId}"
        )
        val now = System.currentTimeMillis()
        val locked = syncQueueDao.markProcessing(item.id, now, "runOnce")
        if (locked == 0) {
            Log.d(DayZeroSyncConstants.LOG_PREFIX, "processing skipped stale task id=${item.id}")
            return SyncTaskOutcome.SKIPPED
        }

        val payload = payloadParser.parse(item).getOrElse { error ->
            Log.e(DayZeroSyncConstants.LOG_PREFIX, "payload parse fatal id=${item.id}", error)
            syncQueueDao.markFatalFailure(
                id = item.id,
                error = error.message ?: error::class.java.simpleName,
                reason = RetryFailureType.PAYLOAD_INVALID.name
            )
            return SyncTaskOutcome.FATAL
        }

        val result = when (payload.operation) {
            DayZeroSyncConstants.OP_UPSERT_DAILY_RECORD -> remoteSyncGateway.upsertDailyRecord(payload)
            DayZeroSyncConstants.OP_UPSERT_MEAL -> remoteSyncGateway.upsertMeal(payload)
            DayZeroSyncConstants.OP_UPSERT_FOOD_ENTRY -> remoteSyncGateway.upsertFoodEntry(payload)
            DayZeroSyncConstants.OP_UPSERT_WEIGHT_RECORD -> remoteSyncGateway.upsertWeightRecord(payload)
            DayZeroSyncConstants.OP_SOFT_DELETE_RECORD -> remoteSyncGateway.softDeleteRecord(payload)
            ChatSyncQueueContract.OP_UPSERT_CONVERSATION -> remoteSyncGateway.upsertChatConversation(payload)
            ChatSyncQueueContract.OP_UPSERT_MESSAGE -> remoteSyncGateway.upsertChatMessage(payload)
            else -> RemoteSyncResult.FatalFailure("unsupported_operation:${payload.operation}")
        }

        return when (result) {
            RemoteSyncResult.Success -> {
                val now = System.currentTimeMillis()
                syncQueueDao.markDone(item.id, now)
                markLocalSyncSuccess(payload, now)
                Log.d(DayZeroSyncConstants.LOG_PREFIX, "mark done id=${item.id}")
                SyncTaskOutcome.SUCCESS
            }

            is RemoteSyncResult.RetryableFailure -> {
                maybeReenqueueParentConversation(payload, result.message)
                markRetryable(item, result.message)
                Log.d(DayZeroSyncConstants.LOG_PREFIX, "mark retryable failure id=${item.id} message=${result.message}")
                SyncTaskOutcome.RETRYABLE
            }

            is RemoteSyncResult.FatalFailure -> {
                val failureType = RetryPolicy.classifyFailure(result.message)
                syncQueueDao.markFatalFailure(item.id, result.message, reason = failureType.name)
                Log.d(DayZeroSyncConstants.LOG_PREFIX, "mark fatal failure id=${item.id} message=${result.message}")
                SyncTaskOutcome.FATAL
            }

            is RemoteSyncResult.Skipped -> {
                if (result.reason == "waiting_for_auth") {
                    val nextAttemptAt = RetryPolicy.nextAttemptAt(
                        now = System.currentTimeMillis(),
                        retryCount = item.retryCount,
                        failureType = RetryFailureType.AUTH_WAITING
                    )
                    syncQueueDao.markWaitingForAuth(item.id, result.reason, nextAttemptAt = nextAttemptAt)
                    Log.d(DayZeroSyncConstants.LOG_PREFIX, "remote sync skipped waiting_for_auth id=${item.id}")
                } else {
                    markRetryable(item, result.reason)
                    Log.d(DayZeroSyncConstants.LOG_PREFIX, "remote sync skipped ${result.reason} id=${item.id}")
                }
                SyncTaskOutcome.SKIPPED
            }
        }
    }

    private suspend fun maybeReenqueueParentConversation(payload: SyncPayload, message: String?) {
        if (payload.operation != ChatSyncQueueContract.OP_UPSERT_MESSAGE) return
        if (message?.contains("http_409") != true) return
        val conversationId = payload.body.optString("conversationId").takeIf { it.isNotBlank() } ?: return
        val conversation = conversationDao?.getConversationById(conversationId) ?: return
        val identity = identityProvider.currentIdentity().copy(localOwnerId = payload.ownerLocalId)
        chatSyncQueueWriter?.enqueueConversationUpsert(conversation, identity)
        Log.d(DayZeroSyncConstants.LOG_PREFIX, "reenqueued parent chat conversation id=$conversationId")
    }

    private suspend fun markRetryable(item: SyncQueueEntity, message: String?): SyncTaskOutcome {
        val now = System.currentTimeMillis()
        val failureType = RetryPolicy.classifyFailure(message)
        val nextRetryCount = item.retryCount + 1
        if (RetryPolicy.shouldBecomeFatal(nextRetryCount, failureType)) {
            syncQueueDao.markFatalFailure(
                id = item.id,
                error = message,
                updatedAt = now,
                reason = failureType.name
            )
            Log.d(DayZeroSyncConstants.LOG_PREFIX, "mark fatal failure id=${item.id} reason=${failureType.name}")
            return SyncTaskOutcome.FATAL
        }

        syncQueueDao.markRetryableFailure(
            id = item.id,
            error = message,
            retryCount = nextRetryCount,
            updatedAt = now,
            nextAttemptAt = RetryPolicy.nextAttemptAt(now, nextRetryCount, failureType),
            reason = failureType.name
        )
        return SyncTaskOutcome.RETRYABLE
    }

    private suspend fun markLocalSyncSuccess(payload: SyncPayload, syncedAt: Long) {
        val shouldMarkDailyRecord = payload.operation == DayZeroSyncConstants.OP_UPSERT_DAILY_RECORD ||
            (payload.operation == DayZeroSyncConstants.OP_SOFT_DELETE_RECORD && payload.entityType == "daily_record")
        if (!shouldMarkDailyRecord) return
        dailyRecordDao?.markRecordSyncMetadata(
            recordId = payload.entityLocalId,
            syncStatus = DayZeroSyncConstants.STATUS_SYNCED,
            lastSyncedAt = syncedAt,
            remoteId = payload.body.optString("clientId").ifBlank { payload.entityLocalId }
        )
    }

    private enum class SyncTaskOutcome {
        SUCCESS,
        RETRYABLE,
        FATAL,
        SKIPPED
    }

    private companion object {
        private const val DEFAULT_BATCH_LIMIT = 50
        private const val STUCK_PROCESSING_TIMEOUT_MS = 15 * 60 * 1000L
        private const val DONE_RETENTION_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
