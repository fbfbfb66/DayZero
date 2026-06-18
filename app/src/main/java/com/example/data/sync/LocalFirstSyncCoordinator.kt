package com.example.data.sync

import android.util.Log
import com.example.data.local.dao.SyncQueueDao
import com.example.data.local.entity.SyncQueueEntity
import com.example.domain.identity.CurrentIdentityProvider

class LocalFirstSyncCoordinator(
    private val syncQueueDao: SyncQueueDao,
    private val identityProvider: CurrentIdentityProvider,
    private val remoteSyncGateway: RemoteSyncGateway = NoopRemoteSyncGateway(),
    private val payloadParser: SyncPayloadParser = SyncPayloadParser()
) : SyncCoordinator {
    override suspend fun syncPending() = runOnce()

    override suspend fun runOnce() {
        val identity = identityProvider.currentIdentity()
        val pendingCount = syncQueueDao.getPendingCount()
        Log.d(DayZeroSyncConstants.LOG_PREFIX, "pending count $pendingCount")

        val pendingItems = syncQueueDao.getPending()
        Log.d(DayZeroSyncConstants.LOG_PREFIX, "sync runOnce start items=${pendingItems.size}")
        if (pendingItems.isEmpty()) {
            Log.d(DayZeroSyncConstants.LOG_PREFIX, "sync runOnce complete processed=0")
            return
        }

        remoteSyncGateway.canSync(identity)
        var processed = 0
        pendingItems.forEach { item ->
            try {
                processItem(item)
                processed += 1
            } catch (e: Exception) {
                Log.e(DayZeroSyncConstants.LOG_PREFIX, "sync item error id=${item.id}", e)
                syncQueueDao.markRetryableFailure(
                    id = item.id,
                    error = e.message ?: e::class.java.simpleName
                )
            }
        }

        Log.d(DayZeroSyncConstants.LOG_PREFIX, "pending count ${syncQueueDao.getPendingCount()}")
        Log.d(DayZeroSyncConstants.LOG_PREFIX, "sync runOnce complete processed=$processed")
    }

    private suspend fun processItem(item: SyncQueueEntity) {
        Log.d(
            DayZeroSyncConstants.LOG_PREFIX,
            "sync item start id=${item.id} operation=${item.operation} entityType=${item.entityType}"
        )
        syncQueueDao.markProcessing(item.id)

        val payload = payloadParser.parse(item).getOrElse { error ->
            Log.e(DayZeroSyncConstants.LOG_PREFIX, "payload parse fatal id=${item.id}", error)
            syncQueueDao.markFatalFailure(
                id = item.id,
                error = error.message ?: error::class.java.simpleName
            )
            return
        }

        val result = when (payload.operation) {
            DayZeroSyncConstants.OP_UPSERT_DAILY_RECORD -> remoteSyncGateway.upsertDailyRecord(payload)
            DayZeroSyncConstants.OP_UPSERT_MEAL -> remoteSyncGateway.upsertMeal(payload)
            DayZeroSyncConstants.OP_UPSERT_FOOD_ENTRY -> remoteSyncGateway.upsertFoodEntry(payload)
            DayZeroSyncConstants.OP_UPSERT_WEIGHT_RECORD -> remoteSyncGateway.upsertWeightRecord(payload)
            DayZeroSyncConstants.OP_SOFT_DELETE_RECORD -> remoteSyncGateway.softDeleteRecord(payload)
            else -> RemoteSyncResult.FatalFailure("unsupported_operation:${payload.operation}")
        }

        when (result) {
            RemoteSyncResult.Success -> {
                syncQueueDao.markDone(item.id)
                Log.d(DayZeroSyncConstants.LOG_PREFIX, "sync item done id=${item.id}")
            }

            is RemoteSyncResult.RetryableFailure -> {
                syncQueueDao.markRetryableFailure(item.id, result.message)
                Log.d(DayZeroSyncConstants.LOG_PREFIX, "sync item retryable id=${item.id} message=${result.message}")
            }

            is RemoteSyncResult.FatalFailure -> {
                syncQueueDao.markFatalFailure(item.id, result.message)
                Log.d(DayZeroSyncConstants.LOG_PREFIX, "sync item fatal id=${item.id} message=${result.message}")
            }

            is RemoteSyncResult.Skipped -> {
                if (result.reason == "waiting_for_auth") {
                    syncQueueDao.markWaitingForAuth(item.id, result.reason)
                    Log.d(DayZeroSyncConstants.LOG_PREFIX, "remote sync skipped waiting_for_auth id=${item.id}")
                } else {
                    syncQueueDao.markRetryableFailure(item.id, result.reason)
                    Log.d(DayZeroSyncConstants.LOG_PREFIX, "remote sync skipped ${result.reason} id=${item.id}")
                }
            }
        }
    }
}
