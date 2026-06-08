package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.DailyRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyRecordDao {
    @Query("SELECT * FROM daily_records ORDER BY date DESC")
    fun observeAllRecords(): Flow<List<DailyRecordEntity>>

    @Query("SELECT * FROM daily_records WHERE id = :id")
    suspend fun getRecordById(id: String): DailyRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecord(record: DailyRecordEntity)

    @Query("DELETE FROM daily_records WHERE id = :id")
    suspend fun deleteRecordById(id: String)
    
    @Query("SELECT COUNT(*) FROM daily_records")
    suspend fun getRecordCount(): Int

    @Query("SELECT * FROM daily_records WHERE date = :date AND status = :status LIMIT 1")
    suspend fun getRecordByDateAndStatus(date: String, status: String): DailyRecordEntity?

    @Query("DELETE FROM daily_records")
    suspend fun deleteAllRecords()
}
