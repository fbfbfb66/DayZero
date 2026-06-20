package com.example.domain.repository

import com.example.domain.model.DailyRecord
import com.example.domain.model.MealType
import com.example.domain.model.RecordStatus
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface RecordRepository {
    fun observeRecords(): Flow<List<DailyRecord>>

    suspend fun upsertRecord(record: DailyRecord)

    suspend fun deleteRecordById(recordId: String)

    suspend fun getRecordById(recordId: String): DailyRecord?

    suspend fun getRecordByDateAndStatus(date: LocalDate, status: RecordStatus): DailyRecord?

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

    suspend fun clearAllRecords()
}
