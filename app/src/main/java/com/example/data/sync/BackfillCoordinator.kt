package com.example.data.sync

import android.util.Log
import com.example.data.local.dao.DailyRecordDao
import com.example.data.local.dao.SyncQueueDao
import com.example.data.local.entity.SyncQueueEntity
import com.example.data.local.mapper.DailyRecordMapper
import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider
import com.example.domain.model.DailyRecord
import com.example.domain.model.RecordStatus

class BackfillCoordinator(
    private val dailyRecordDao: DailyRecordDao,
    private val syncQueueDao: SyncQueueDao,
    private val identityProvider: CurrentIdentityProvider,
    private val stateStore: BackfillStateStore,
    private val payloadBuilder: SyncPayloadBuilder = SyncPayloadBuilder()
) {
    private val mapper = DailyRecordMapper()

    suspend fun enqueueInitialBackfillIfNeeded(): BackfillStats {
        val version = BackfillStateStore.CURRENT_INITIAL_BACKFILL_VERSION
        if (stateStore.getInitialBackfillVersion() >= version) {
            Log.d(LOG_PREFIX, "initial backfill already enqueued version=$version")
            return getBackfillStats()
        }

        val identity = identityProvider.currentIdentity()
        if (!identity.canRemoteSync || identity.remoteUserId.isNullOrBlank()) {
            Log.d(LOG_PREFIX, "scan skipped waiting_for_auth")
            return getBackfillStats()
        }

        val stats = enqueueMissingRecords(identity)
        val now = System.currentTimeMillis()
        stateStore.markInitialBackfillEnqueued(version, now)
        stateStore.markInitialBackfillCompleted(now)
        return stats
    }

    suspend fun enqueueMissingRecords(): BackfillStats {
        val identity = identityProvider.currentIdentity()
        if (!identity.canRemoteSync || identity.remoteUserId.isNullOrBlank()) {
            Log.d(LOG_PREFIX, "scan skipped waiting_for_auth")
            return getBackfillStats()
        }

        return enqueueMissingRecords(identity)
    }

    private suspend fun enqueueMissingRecords(identity: AppIdentity): BackfillStats {
        Log.d(LOG_PREFIX, "scan start")
        return try {
            val records = dailyRecordDao.getConfirmedRecordsForBackfill()
            var stats = BackfillStats(
                scannedDailyRecordCount = records.size,
                scannedMealCount = records.sumOf { mapper.toDomain(it).meals.size },
                scannedFoodEntryCount = records.sumOf { mapper.toDomain(it).meals.sumOf { meal -> meal.foods.size } },
                scannedWeightRecordCount = records.count { it.weightKg != null }
            )
            Log.d(
                LOG_PREFIX,
                "scan result dailyRecords=${stats.scannedDailyRecordCount} meals=${stats.scannedMealCount} " +
                    "foodEntries=${stats.scannedFoodEntryCount} weights=${stats.scannedWeightRecordCount}"
            )

            records.forEach { entity ->
                val recordIdentity = identity.copy(
                    localOwnerId = entity.ownerLocalId.takeUnless { it == "local_uninitialized" || it.isBlank() }
                        ?: identity.localOwnerId
                )
                val record = mapper.toDomain(entity)
                if (record.status != RecordStatus.Confirmed) return@forEach

                if (isAlreadySynced(entity.syncStatus, entity.remoteId, entity.lastSyncedAt)) {
                    Log.d(LOG_PREFIX, "enqueue skipped already synced entityType=daily_record clientId=${record.id}")
                    stats = stats.copy(skippedAlreadySyncedCount = stats.skippedAlreadySyncedCount + 1)
                    return@forEach
                }

                stats = enqueueRecord(record, recordIdentity, stats)
            }

            Log.d(LOG_PREFIX, "enqueue success count=${stats.enqueuedCount}")
            stats
        } catch (e: Exception) {
            val reason = e.message ?: e::class.java.simpleName
            Log.e(LOG_PREFIX, "enqueue error reason=$reason", e)
            stateStore.recordError(reason)
            BackfillStats(errorCount = 1)
        }
    }

    suspend fun getBackfillStats(): BackfillStats {
        val records = dailyRecordDao.getConfirmedRecordsForBackfill()
        return BackfillStats(
            scannedDailyRecordCount = records.size,
            scannedMealCount = records.sumOf { mapper.toDomain(it).meals.size },
            scannedFoodEntryCount = records.sumOf { mapper.toDomain(it).meals.sumOf { meal -> meal.foods.size } },
            scannedWeightRecordCount = records.count { it.weightKg != null }
        )
    }

    private suspend fun enqueueRecord(
        record: DailyRecord,
        identity: AppIdentity,
        currentStats: BackfillStats
    ): BackfillStats {
        var stats = currentStats
        val now = System.currentTimeMillis()

        stats = enqueueIfMissing(
            item = SyncQueueEntity(
                entityType = "daily_record",
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

        record.meals.forEach { meal ->
            stats = enqueueIfMissing(
                item = SyncQueueEntity(
                    entityType = "meal",
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
                stats = enqueueIfMissing(
                    item = SyncQueueEntity(
                        entityType = "food_entry",
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
            stats = enqueueIfMissing(
                item = SyncQueueEntity(
                    entityType = "weight_record",
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

        return stats
    }

    private suspend fun enqueueIfMissing(item: SyncQueueEntity, stats: BackfillStats): BackfillStats {
        Log.d(LOG_PREFIX, "enqueue start entityType=${item.entityType} clientId=${item.entityLocalId}")
        val existing = syncQueueDao.countActiveOrCompleted(
            ownerLocalId = item.ownerLocalId,
            entityType = item.entityType,
            entityLocalId = item.entityLocalId,
            operation = item.operation
        )
        if (existing > 0) {
            Log.d(LOG_PREFIX, "enqueue skipped already queued entityType=${item.entityType} clientId=${item.entityLocalId}")
            return stats.copy(skippedAlreadyQueuedCount = stats.skippedAlreadyQueuedCount + 1)
        }

        return try {
            syncQueueDao.insert(item)
            stats.copy(enqueuedCount = stats.enqueuedCount + 1)
        } catch (e: Exception) {
            val reason = e.message ?: e::class.java.simpleName
            Log.e(LOG_PREFIX, "enqueue error reason=$reason", e)
            stats.copy(errorCount = stats.errorCount + 1)
        }
    }

    private fun isAlreadySynced(syncStatus: String, remoteId: String?, lastSyncedAt: Long?): Boolean {
        return syncStatus == "SYNCED" && !remoteId.isNullOrBlank() && lastSyncedAt != null && lastSyncedAt > 0L
    }

    private companion object {
        private const val LOG_PREFIX = "DayZeroBackfill"
    }
}
