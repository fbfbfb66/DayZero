package com.example.data.repository

import com.example.data.mock.createMockRecords
import com.example.domain.model.DailyRecord
import com.example.domain.model.MealType
import com.example.domain.model.RecordStatus
import com.example.domain.repository.RecordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate

class MockRecordRepository : RecordRepository {
    private val _records = MutableStateFlow(createMockRecords())

    override fun observeRecords(): Flow<List<DailyRecord>> = _records.asStateFlow()

    override suspend fun upsertRecord(record: DailyRecord) {
        _records.update { currentRecords ->
            val index = currentRecords.indexOfFirst { it.id == record.id }
            if (index >= 0) {
                currentRecords.toMutableList().apply { set(index, record) }
            } else {
                currentRecords + record
            }
        }
    }

    override suspend fun deleteRecordById(recordId: String) {
        _records.update { currentRecords ->
            currentRecords.filterNot { it.id == recordId }
        }
    }

    override suspend fun getRecordById(recordId: String): DailyRecord? {
        return _records.value.find { it.id == recordId }
    }

    override suspend fun getRecordByDateAndStatus(date: LocalDate, status: RecordStatus): DailyRecord? {
        return _records.value.find { it.date == date && it.status == status }
    }

    override suspend fun updateRecordStatus(recordId: String, status: RecordStatus, weightKg: Float?) {
        _records.update { currentRecords ->
            currentRecords.map { record ->
                if (record.id == recordId) {
                    record.copy(status = status, weightKg = weightKg ?: record.weightKg)
                } else {
                    record
                }
            }
        }
    }

    override suspend fun deleteFoodFromRecord(recordId: String, mealType: MealType, foodId: String) {
        _records.update { currentRecords ->
            currentRecords.map { record ->
                if (record.id == recordId && record.status == RecordStatus.Draft) {
                    val updatedMeals = record.meals.map { meal ->
                        if (meal.mealType == mealType) {
                            meal.copy(foods = meal.foods.filter { it.id != foodId })
                        } else {
                            meal
                        }
                    }
                    record.copy(meals = updatedMeals)
                } else {
                    record
                }
            }
        }
    }
}
