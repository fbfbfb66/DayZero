package com.example.data.sync

import android.util.Log
import com.example.data.local.dao.DailyRecordDao
import com.example.data.local.dao.SyncQueueDao
import com.example.data.local.entity.DailyRecordEntity
import com.example.data.local.entity.SyncQueueEntity
import com.example.data.local.mapper.DailyRecordMapper
import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider
import com.example.domain.model.DailyRecord
import com.example.domain.model.RecordStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BackfillCoordinator(
    private val dailyRecordDao: DailyRecordDao,
    private val syncQueueDao: SyncQueueDao,
    private val identityProvider: CurrentIdentityProvider,
    private val stateStore: BackfillStateStore,
    private val payloadBuilder: SyncPayloadBuilder = SyncPayloadBuilder(),
    private val taskBatchLimit: Int = DEFAULT_TASK_BATCH_LIMIT
) {
    private val mapper = DailyRecordMapper()
    private val mutex = Mutex()

    suspend fun runOnce(): BackfillStats = enqueueInitialBackfillIfNeeded()

    suspend fun enqueueInitialBackfillIfNeeded(): BackfillStats {
        if (mutex.isLocked) {
            Log.d(LOG_PREFIX, "run skipped already running")
            return getBackfillStats()
        }

        return mutex.withLock {
            runBackfill(initialOnly = true)
        }
    }

    suspend fun enqueueMissingRecords(): BackfillStats {
        if (mutex.isLocked) {
            Log.d(LOG_PREFIX, "run skipped already running")
            return getBackfillStats()
        }

        return mutex.withLock {
            runBackfill(initialOnly = false)
        }
    }

    suspend fun getBackfillStats(): BackfillStats {
        val records = dailyRecordDao.getConfirmedRecordsForBackfill()
        return records.toStats()
    }

    private suspend fun runBackfill(initialOnly: Boolean): BackfillStats {
        Log.d(LOG_PREFIX, "run start initialOnly=$initialOnly")
        val identity = identityProvider.currentIdentity()
        if (!identity.canRemoteSync || identity.remoteUserId.isNullOrBlank()) {
            Log.d(LOG_PREFIX, "identity waiting for auth")
            return getBackfillStats()
        }

        val state = stateStore.snapshot()
        val maxLocalUpdatedAt = dailyRecordDao.getMaxConfirmedUpdatedAt() ?: 0L
        val lastSuccessAt = state.lastSuccessAt ?: 0L
        val alreadyCompleted = state.status == BackfillStatus.COMPLETED &&
            state.schemaVersion >= BackfillStateStore.CURRENT_INITIAL_BACKFILL_VERSION

        if (initialOnly && alreadyCompleted && maxLocalUpdatedAt <= lastSuccessAt) {
            Log.d(LOG_PREFIX, "run skipped completed no new local changes")
            return getBackfillStats()
        }

        val scanUpdatedAfter = if (alreadyCompleted) lastSuccessAt else 0L
        val startedAt = System.currentTimeMillis()
        stateStore.markRunning(startedAt)
        Log.d(LOG_PREFIX, "scan start")

        return try {
            val records = dailyRecordDao.getConfirmedRecordsForBackfill()
                .filter { it.updatedAt > scanUpdatedAfter }
            var stats = records.toStats()
            Log.d(
                LOG_PREFIX,
                "scan result dailyRecords=${stats.scannedDailyRecordCount} meals=${stats.scannedMealCount} " +
                    "foodEntries=${stats.scannedFoodEntryCount} weightRecords=${stats.scannedWeightRecordCount}"
            )

            records.forEach { entity ->
                if (stats.enqueuedCount >= taskBatchLimit) return@forEach
                val record = mapper.toDomain(entity)
                if (record.status != RecordStatus.Confirmed) return@forEach
                val recordIdentity = identity.copy(
                    localOwnerId = entity.ownerLocalId.takeUnless { it == "local_uninitialized" || it.isBlank() }
                        ?: identity.localOwnerId
                )
                stats = enqueueRecord(
                    entity = entity,
                    record = record,
                    identity = recordIdentity,
                    currentStats = stats,
                    skipAlreadySyncedDailyRecord = alreadyCompleted
                )
            }

            val reachedBatchLimit = stats.enqueuedCount >= taskBatchLimit
            val completedAt = System.currentTimeMillis()
            if (!reachedBatchLimit) {
                stateStore.markCompleted(completedAt, stats)
            }
            Log.d(
                LOG_PREFIX,
                "run success enqueued=${stats.enqueuedCount} skipped=${stats.skippedAlreadyQueuedCount} " +
                    "errors=${stats.errorCount} reachedBatchLimit=$reachedBatchLimit"
            )
            stats
        } catch (e: IllegalArgumentException) {
            val reason = e.message ?: e::class.java.simpleName
            Log.e(LOG_PREFIX, "run fatal error reason=$reason", e)
            stateStore.markFatalFailure(reason)
            BackfillStats(errorCount = 1)
        } catch (e: Exception) {
            val reason = e.message ?: e::class.java.simpleName
            Log.e(LOG_PREFIX, "run retryable error reason=$reason", e)
            stateStore.markRetryableFailure(reason)
            BackfillStats(errorCount = 1)
        }
    }

    private suspend fun enqueueRecord(
        entity: DailyRecordEntity,
        record: DailyRecord,
        identity: AppIdentity,
        currentStats: BackfillStats,
        skipAlreadySyncedDailyRecord: Boolean
    ): BackfillStats {
        var stats = currentStats
        val now = System.currentTimeMillis()

        entity.deletedAt?.let { deletedAt ->
            return enqueueIfMissing(
                item = SyncQueueEntity(
                    entityType = ENTITY_DAILY_RECORD,
                    entityLocalId = record.id,
                    operation = DayZeroSyncConstants.OP_SOFT_DELETE_RECORD,
                    payloadJson = payloadBuilder.softDeletePayload(record.id, identity, deletedAt).toString(),
                    status = DayZeroSyncConstants.STATUS_PENDING,
                    createdAt = now,
                    updatedAt = now,
                    ownerLocalId = identity.localOwnerId
                ),
                stats = stats
            )
        }

        if (skipAlreadySyncedDailyRecord && isDailyRecordAlreadySynced(entity)) {
            Log.d(LOG_PREFIX, "enqueue skipped already synced entityType=daily_record clientId=${record.id}")
            stats = stats.copy(skippedAlreadySyncedCount = stats.skippedAlreadySyncedCount + 1)
        } else {
            stats = enqueueIfMissing(
                item = SyncQueueEntity(
                    entityType = ENTITY_DAILY_RECORD,
                    entityLocalId = record.id,
                    operation = DayZeroSyncConstants.OP_UPSERT_DAILY_RECORD,
                    payloadJson = payloadBuilder.dailyRecordPayload(record, identity).toString(),
                    status = DayZeroSyncConstants.STATUS_PENDING,
                    createdAt = now,
                    updatedAt = now,
                    ownerLocalId = identity.localOwnerId
                ),
                stats = stats
            )
        }

        record.meals.forEach { meal ->
            if (stats.enqueuedCount >= taskBatchLimit) return@forEach
            stats = enqueueIfMissing(
                item = SyncQueueEntity(
                    entityType = ENTITY_MEAL,
                    entityLocalId = meal.id,
                    operation = DayZeroSyncConstants.OP_UPSERT_MEAL,
                    payloadJson = payloadBuilder.mealPayload(record.id, meal, identity).toString(),
                    status = DayZeroSyncConstants.STATUS_PENDING,
                    createdAt = now,
                    updatedAt = now,
                    ownerLocalId = identity.localOwnerId
                ),
                stats = stats
            )

            meal.foods.forEach { food ->
                if (stats.enqueuedCount >= taskBatchLimit) return@forEach
                stats = enqueueIfMissing(
                    item = SyncQueueEntity(
                        entityType = ENTITY_FOOD_ENTRY,
                        entityLocalId = food.id,
                        operation = DayZeroSyncConstants.OP_UPSERT_FOOD_ENTRY,
                        payloadJson = payloadBuilder.foodPayload(record.id, meal.id, food, identity).toString(),
                        status = DayZeroSyncConstants.STATUS_PENDING,
                        createdAt = now,
                        updatedAt = now,
                        ownerLocalId = identity.localOwnerId
                    ),
                    stats = stats
                )
            }
        }

        record.weightKg?.let { weightKg ->
            if (stats.enqueuedCount < taskBatchLimit) {
                stats = enqueueIfMissing(
                    item = SyncQueueEntity(
                        entityType = ENTITY_WEIGHT_RECORD,
                        entityLocalId = "${record.id}:weight",
                        operation = DayZeroSyncConstants.OP_UPSERT_WEIGHT_RECORD,
                        payloadJson = payloadBuilder.weightPayload(record.id, record.date.toString(), weightKg, identity).toString(),
                        status = DayZeroSyncConstants.STATUS_PENDING,
                        createdAt = now,
                        updatedAt = now,
                        ownerLocalId = identity.localOwnerId
                    ),
                    stats = stats
                )
            }
        }

        return stats
    }

    private suspend fun enqueueIfMissing(
        item: SyncQueueEntity,
        stats: BackfillStats
    ): BackfillStats {
        Log.d(LOG_PREFIX, "enqueue start entityType=${item.entityType} clientId=${item.entityLocalId}")
        val existing = syncQueueDao.countBlockingDuplicate(
            ownerLocalId = item.ownerLocalId,
            entityType = item.entityType,
            entityLocalId = item.entityLocalId,
            operation = item.operation
        )
        if (existing > 0) {
            Log.d(LOG_PREFIX, "enqueue skipped duplicate entityType=${item.entityType} clientId=${item.entityLocalId}")
            return stats.copy(skippedAlreadyQueuedCount = stats.skippedAlreadyQueuedCount + 1)
        }

        return try {
            syncQueueDao.insert(item)
            Log.d(LOG_PREFIX, "enqueue success entityType=${item.entityType} clientId=${item.entityLocalId}")
            stats.copy(enqueuedCount = stats.enqueuedCount + 1)
        } catch (e: Exception) {
            val reason = e.message ?: e::class.java.simpleName
            Log.e(LOG_PREFIX, "enqueue error reason=$reason", e)
            stats.copy(errorCount = stats.errorCount + 1)
        }
    }

    private fun isDailyRecordAlreadySynced(entity: DailyRecordEntity): Boolean {
        val lastSyncedAt = entity.lastSyncedAt ?: return false
        return entity.syncStatus == DayZeroSyncConstants.STATUS_SYNCED &&
            !entity.remoteId.isNullOrBlank() &&
            lastSyncedAt >= entity.updatedAt
    }

    private fun List<DailyRecordEntity>.toStats(): BackfillStats {
        val records = map { mapper.toDomain(it) }
        return BackfillStats(
            scannedDailyRecordCount = size,
            scannedMealCount = records.sumOf { it.meals.size },
            scannedFoodEntryCount = records.sumOf { it.meals.sumOf { meal -> meal.foods.size } },
            scannedWeightRecordCount = count { it.weightKg != null }
        )
    }

    private companion object {
        private const val LOG_PREFIX = "DayZeroBackfill"
        private const val ENTITY_DAILY_RECORD = "daily_record"
        private const val ENTITY_MEAL = "meal"
        private const val ENTITY_FOOD_ENTRY = "food_entry"
        private const val ENTITY_WEIGHT_RECORD = "weight_record"
        private const val DEFAULT_TASK_BATCH_LIMIT = 200
    }
}
