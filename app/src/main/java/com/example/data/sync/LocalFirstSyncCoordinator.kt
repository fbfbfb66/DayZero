package com.example.data.sync

import android.util.Log
import com.example.data.local.dao.DailyRecordDao
import com.example.data.local.dao.SyncQueueDao
import com.example.data.local.entity.SyncQueueEntity
import com.example.domain.identity.CurrentIdentityProvider

class LocalFirstSyncCoordinator(
    private val syncQueueDao: SyncQueueDao,
    private val identityProvider: CurrentIdentityProvider,
    private val remoteSyncGateway: RemoteSyncGateway = NoopRemoteSyncGateway(),
    private val payloadParser: SyncPayloadParser = SyncPayloadParser(),
    private val dailyRecordDao: DailyRecordDao? = null,
    private val batchLimit: Int = DEFAULT_BATCH_LIMIT
) : SyncCoordinator {
    override suspend fun syncPending() = runOnce()

    override suspend fun runOnce() {
        val identity = identityProvider.currentIdentity()
        val pendingCount = syncQueueDao.getPendingCount()
        Log.d(DayZeroSyncConstants.LOG_PREFIX, "runOnce start")
        Log.d(DayZeroSyncConstants.LOG_PREFIX, "pending count $pendingCount")

        val pendingItems = syncQueueDao.getPending(limit = batchLimit)
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
                syncQueueDao.markRetryableFailure(
                    id = item.id,
                    error = e.message ?: e::class.java.simpleName
                )
                SyncTaskOutcome.RETRYABLE
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
        syncQueueDao.markProcessing(item.id)

        val payload = payloadParser.parse(item).getOrElse { error ->
            Log.e(DayZeroSyncConstants.LOG_PREFIX, "payload parse fatal id=${item.id}", error)
            syncQueueDao.markFatalFailure(
                id = item.id,
                error = error.message ?: error::class.java.simpleName
            )
            return SyncTaskOutcome.FATAL
        }

        val result = when (payload.operation) {
            DayZeroSyncConstants.OP_UPSERT_DAILY_RECORD -> remoteSyncGateway.upsertDailyRecord(payload)
            DayZeroSyncConstants.OP_UPSERT_MEAL -> remoteSyncGateway.upsertMeal(payload)
            DayZeroSyncConstants.OP_UPSERT_FOOD_ENTRY -> remoteSyncGateway.upsertFoodEntry(payload)
            DayZeroSyncConstants.OP_UPSERT_WEIGHT_RECORD -> remoteSyncGateway.upsertWeightRecord(payload)
            DayZeroSyncConstants.OP_SOFT_DELETE_RECORD -> remoteSyncGateway.softDeleteRecord(payload)
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
                syncQueueDao.markRetryableFailure(item.id, result.message)
                Log.d(DayZeroSyncConstants.LOG_PREFIX, "mark retryable failure id=${item.id} message=${result.message}")
                SyncTaskOutcome.RETRYABLE
            }

            is RemoteSyncResult.FatalFailure -> {
                syncQueueDao.markFatalFailure(item.id, result.message)
                Log.d(DayZeroSyncConstants.LOG_PREFIX, "mark fatal failure id=${item.id} message=${result.message}")
                SyncTaskOutcome.FATAL
            }

            is RemoteSyncResult.Skipped -> {
                if (result.reason == "waiting_for_auth") {
                    syncQueueDao.markWaitingForAuth(item.id, result.reason)
                    Log.d(DayZeroSyncConstants.LOG_PREFIX, "remote sync skipped waiting_for_auth id=${item.id}")
                } else {
                    syncQueueDao.markRetryableFailure(item.id, result.reason)
                    Log.d(DayZeroSyncConstants.LOG_PREFIX, "remote sync skipped ${result.reason} id=${item.id}")
                }
                SyncTaskOutcome.SKIPPED
            }
        }
    }

    private suspend fun markLocalSyncSuccess(payload: SyncPayload, syncedAt: Long) {
        if (payload.operation != DayZeroSyncConstants.OP_UPSERT_DAILY_RECORD) return
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
    }
}
