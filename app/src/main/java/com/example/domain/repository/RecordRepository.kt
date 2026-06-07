package com.example.domain.repository

import com.example.domain.model.DailyRecord
import com.example.domain.model.MealType
import com.example.domain.model.RecordStatus
import kotlinx.coroutines.flow.Flow

interface RecordRepository {
    fun observeRecords(): Flow<List<DailyRecord>>

    suspend fun upsertRecord(record: DailyRecord)

    suspend fun updateRecordStatus(
        recordId: String,
        status: RecordStatus,
        weightKg: Float? = null
    )

    suspend fun deleteFoodFromRecord(
        recordId: String,
        mealType: MealType,
        foodId: String
    )
}
