package com.example.data.repository

import com.example.data.local.dao.DailyRecordDao
import com.example.data.local.mapper.DailyRecordMapper
import com.example.data.mock.createMockRecords
import com.example.domain.model.DailyRecord
import com.example.domain.model.MealType
import com.example.domain.model.RecordStatus
import com.example.domain.repository.RecordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

class RoomRecordRepository(
    private val dao: DailyRecordDao
) : RecordRepository {
    private val mapper = DailyRecordMapper()

    override fun observeRecords(): Flow<List<DailyRecord>> {
        return dao.observeAllRecords()
            .onStart {
                if (dao.getRecordCount() == 0) {
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
        dao.upsertRecord(mapper.toEntity(record))
    }

    override suspend fun updateRecordStatus(recordId: String, status: RecordStatus, weightKg: Float?) {
        val entity = dao.getRecordById(recordId)
        if (entity != null) {
            val updatedEntity = entity.copy(
                status = status.name,
                weightKg = weightKg ?: entity.weightKg,
                updatedAt = System.currentTimeMillis()
            )
            dao.upsertRecord(updatedEntity)
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
                dao.upsertRecord(mapper.toEntity(updatedDomain))
            }
        }
    }
}
