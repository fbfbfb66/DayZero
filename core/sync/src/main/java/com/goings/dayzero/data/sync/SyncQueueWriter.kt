package com.goings.dayzero.data.sync

import android.util.Log
import com.goings.dayzero.data.local.dao.SyncQueueDao
import com.goings.dayzero.data.local.entity.SyncQueueEntity
import com.goings.dayzero.domain.identity.AppIdentity
import com.goings.dayzero.domain.model.DailyRecord

class SyncQueueWriter(
    private val syncQueueDao: SyncQueueDao,
    private val payloadBuilder: SyncPayloadBuilder = SyncPayloadBuilder()
) {
    suspend fun enqueueRecordUpsert(record: DailyRecord, identity: AppIdentity): Int {
        Log.d(DayZeroSyncConstants.LOG_PREFIX, "enqueue start dailyRecord=${record.id} ownerLocalId=${identity.localOwnerId}")
        var enqueued = 0
        val now = System.currentTimeMillis()

        enqueued += enqueueLatest(
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
            reason = "record_latest"
        )

        record.meals.forEach { meal ->
            enqueued += enqueueLatest(
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
                reason = "meal_latest"
            )
            meal.foods.forEach { food ->
                enqueued += enqueueLatest(
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
                    reason = "food_entry_latest"
                )
            }
        }

        record.weightKg?.let { weightKg ->
            enqueued += enqueueLatest(
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
                reason = "weight_latest"
            )
        }

        Log.d(DayZeroSyncConstants.LOG_PREFIX, "enqueue success dailyRecord=${record.id}")
        return enqueued
    }

    private suspend fun enqueueLatest(item: SyncQueueEntity, reason: String): Int {
        val coalesced = syncQueueDao.coalescePendingTask(
            ownerLocalId = item.ownerLocalId,
            entityType = item.entityType,
            entityLocalId = item.entityLocalId,
            operation = item.operation,
            payloadJson = item.payloadJson,
            updatedAt = item.updatedAt,
            reason = reason
        )
        if (coalesced > 0) {
            Log.d(DayZeroSyncConstants.LOG_PREFIX, "enqueue coalesced entityType=${item.entityType} clientId=${item.entityLocalId}")
            return 0
        }

        syncQueueDao.insert(item)
        Log.d(DayZeroSyncConstants.LOG_PREFIX, "enqueue success entityType=${item.entityType} clientId=${item.entityLocalId}")
        return 1
    }

    private companion object {
        private const val ENTITY_DAILY_RECORD = "daily_record"
        private const val ENTITY_MEAL = "meal"
        private const val ENTITY_FOOD_ENTRY = "food_entry"
        private const val ENTITY_WEIGHT_RECORD = "weight_record"
    }
}
