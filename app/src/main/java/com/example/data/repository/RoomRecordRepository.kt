package com.example.data.repository

import android.util.Log
import com.example.data.identity.StaticLocalIdentityProvider
import com.example.data.local.dao.DailyRecordDao
import com.example.data.local.dao.SyncQueueDao
import com.example.data.local.entity.SyncQueueEntity
import com.example.data.local.mapper.DailyRecordMapper
import com.example.data.mock.createMockRecords
import com.example.data.sync.DayZeroSyncConstants
import com.example.data.sync.SyncPayloadBuilder
import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider
import com.example.domain.model.DailyRecord
import com.example.domain.model.MealType
import com.example.domain.model.RecordStatus
import com.example.domain.repository.RecordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.time.LocalDate

class RoomRecordRepository(
    private val dao: DailyRecordDao,
    private val syncQueueDao: SyncQueueDao? = null,
    private val identityProvider: CurrentIdentityProvider = StaticLocalIdentityProvider()
) : RecordRepository {
    private val mapper = DailyRecordMapper()
    private val payloadBuilder = SyncPayloadBuilder()

    companion object {
        private const val ENABLE_DEMO_SEEDING = false
    }

    override fun observeRecords(): Flow<List<DailyRecord>> {
        return dao.observeAllRecords()
            .onStart {
                if (ENABLE_DEMO_SEEDING && dao.getRecordCount() == 0) {
                    createMockRecords().forEach { 
                        dao.upsertRecord(mapper.toEntity(it))
                    }
                }
            }
            .map { entities ->
                entities.map { mapper.toDomain(it) }
            }
    }

    override suspend fun upsertRecord(record: DailyRecord) {
        val identity = identityProvider.currentIdentity()
        dao.upsertRecord(mapper.toEntity(record, identity.localOwnerId))
        enqueueRecordUpsert(record, identity)
    }

    override suspend fun deleteRecordById(recordId: String) {
        dao.deleteRecordById(recordId)
        enqueueSoftDelete(recordId, identityProvider.currentIdentity())
    }

    override suspend fun getRecordById(recordId: String): DailyRecord? {
        return dao.getRecordById(recordId)?.let { mapper.toDomain(it) }
    }

    override suspend fun getRecordByDateAndStatus(date: LocalDate, status: RecordStatus): DailyRecord? {
        return dao.getRecordByDateAndStatus(date.toString(), status.name)?.let { mapper.toDomain(it) }
    }

    override suspend fun updateRecordStatus(recordId: String, status: RecordStatus, weightKg: Float?) {
        val entity = dao.getRecordById(recordId)
        if (entity != null) {
            val identity = identityProvider.currentIdentity()
            val updatedEntity = entity.copy(
                status = status.name,
                weightKg = weightKg ?: entity.weightKg,
                updatedAt = System.currentTimeMillis(),
                ownerLocalId = identity.localOwnerId
            )
            dao.upsertRecord(updatedEntity)
            enqueueRecordUpsert(mapper.toDomain(updatedEntity), identity)
        }
    }

    override suspend fun deleteFoodFromRecord(recordId: String, mealType: MealType, foodId: String) {
        val entity = dao.getRecordById(recordId)
        if (entity != null) {
            val domain = mapper.toDomain(entity)
            if (domain.status == RecordStatus.Draft) {
                val updatedMeals = domain.meals.map { meal ->
                    if (meal.mealType == mealType) {
                        meal.copy(foods = meal.foods.filter { it.id != foodId })
                    } else {
                        meal
                    }
                }
                val updatedDomain = domain.copy(meals = updatedMeals)
                val identity = identityProvider.currentIdentity()
                dao.upsertRecord(mapper.toEntity(updatedDomain, identity.localOwnerId))
                enqueueRecordUpsert(updatedDomain, identity)
            }
        }
    }

    override suspend fun clearAllRecords() {
        dao.deleteAllRecords()
    }

    private suspend fun enqueueRecordUpsert(record: DailyRecord, identity: AppIdentity) {
        val queueDao = syncQueueDao ?: return
        Log.d(DayZeroSyncConstants.LOG_PREFIX, "enqueue start dailyRecord=${record.id} ownerLocalId=${identity.localOwnerId}")
        try {
            val now = System.currentTimeMillis()
            queueDao.insert(
                SyncQueueEntity(
                    entityType = "daily_record",
                    entityLocalId = record.id,
                    operation = DayZeroSyncConstants.OP_UPSERT_DAILY_RECORD,
                    payloadJson = payloadBuilder.dailyRecordPayload(record, identity).toString(),
                    status = DayZeroSyncConstants.STATUS_PENDING,
                    createdAt = now,
                    updatedAt = now,
                    ownerLocalId = identity.localOwnerId
                )
            )

            record.meals.forEach { meal ->
                queueDao.insert(
                    SyncQueueEntity(
                        entityType = "meal",
                        entityLocalId = meal.id,
                        operation = DayZeroSyncConstants.OP_UPSERT_MEAL,
                        payloadJson = payloadBuilder.mealPayload(record.id, meal, identity).toString(),
                        status = DayZeroSyncConstants.STATUS_PENDING,
                        createdAt = now,
                        updatedAt = now,
                        ownerLocalId = identity.localOwnerId
                    )
                )
                meal.foods.forEach { food ->
                    queueDao.insert(
                        SyncQueueEntity(
                            entityType = "food_entry",
                            entityLocalId = food.id,
                            operation = DayZeroSyncConstants.OP_UPSERT_FOOD_ENTRY,
                            payloadJson = payloadBuilder.foodPayload(record.id, meal.id, food, identity).toString(),
                            status = DayZeroSyncConstants.STATUS_PENDING,
                            createdAt = now,
                            updatedAt = now,
                            ownerLocalId = identity.localOwnerId
                        )
                    )
                }
            }

            record.weightKg?.let { weightKg ->
                queueDao.insert(
                    SyncQueueEntity(
                        entityType = "weight_record",
                        entityLocalId = "${record.id}:weight",
                        operation = DayZeroSyncConstants.OP_UPSERT_WEIGHT_RECORD,
                        payloadJson = payloadBuilder.weightPayload(record.id, record.date.toString(), weightKg, identity).toString(),
                        status = DayZeroSyncConstants.STATUS_PENDING,
                        createdAt = now,
                        updatedAt = now,
                        ownerLocalId = identity.localOwnerId
                    )
                )
            }
            Log.d(DayZeroSyncConstants.LOG_PREFIX, "enqueue success dailyRecord=${record.id}")
            Log.d(DayZeroSyncConstants.LOG_PREFIX, "pending count ${queueDao.getPendingCount()}")
            if (!identity.canRemoteSync) {
                Log.d(DayZeroSyncConstants.LOG_PREFIX, "remote sync skipped: waiting for auth")
            }
        } catch (e: Exception) {
            Log.e(DayZeroSyncConstants.LOG_PREFIX, "enqueue error dailyRecord=${record.id}", e)
        }
    }

    private suspend fun enqueueSoftDelete(recordId: String, identity: AppIdentity) {
        val queueDao = syncQueueDao ?: return
        Log.d(DayZeroSyncConstants.LOG_PREFIX, "enqueue start softDelete=$recordId ownerLocalId=${identity.localOwnerId}")
        try {
            val now = System.currentTimeMillis()
            queueDao.insert(
                SyncQueueEntity(
                    entityType = "daily_record",
                    entityLocalId = recordId,
                    operation = DayZeroSyncConstants.OP_SOFT_DELETE_RECORD,
                    payloadJson = payloadBuilder.softDeletePayload(recordId, identity, now).toString(),
                    status = DayZeroSyncConstants.STATUS_PENDING,
                    createdAt = now,
                    updatedAt = now,
                    ownerLocalId = identity.localOwnerId
                )
            )
            Log.d(DayZeroSyncConstants.LOG_PREFIX, "enqueue success softDelete=$recordId")
            Log.d(DayZeroSyncConstants.LOG_PREFIX, "pending count ${queueDao.getPendingCount()}")
            if (!identity.canRemoteSync) {
                Log.d(DayZeroSyncConstants.LOG_PREFIX, "remote sync skipped: waiting for auth")
            }
        } catch (e: Exception) {
            Log.e(DayZeroSyncConstants.LOG_PREFIX, "enqueue error softDelete=$recordId", e)
        }
    }
}
