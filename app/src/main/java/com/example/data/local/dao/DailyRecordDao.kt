package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.DailyRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyRecordDao {
    @Query("SELECT * FROM daily_records WHERE deletedAt IS NULL ORDER BY date DESC")
    fun observeAllRecords(): Flow<List<DailyRecordEntity>>

    @Query("SELECT * FROM daily_records WHERE id = :id AND deletedAt IS NULL")
    suspend fun getRecordById(id: String): DailyRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecord(record: DailyRecordEntity)

    @Query(
        """
        UPDATE daily_records
        SET deletedAt = :deletedAt,
            updatedAt = :deletedAt,
            syncStatus = 'PENDING',
            syncVersion = :deletedAt
        WHERE id = :id
        """
    )
    suspend fun deleteRecordById(id: String, deletedAt: Long = System.currentTimeMillis())
    
    @Query("SELECT COUNT(*) FROM daily_records WHERE deletedAt IS NULL")
    suspend fun getRecordCount(): Int

    @Query("SELECT * FROM daily_records WHERE date = :date AND status = :status AND deletedAt IS NULL LIMIT 1")
    suspend fun getRecordByDateAndStatus(date: String, status: String): DailyRecordEntity?

    @Query("SELECT * FROM daily_records WHERE status = 'Confirmed' ORDER BY date ASC, createdAt ASC")
    suspend fun getConfirmedRecordsForBackfill(): List<DailyRecordEntity>

    @Query(
        """
        SELECT * FROM daily_records
        WHERE status = 'Confirmed'
          AND updatedAt > :updatedAfter
        ORDER BY date ASC, createdAt ASC
        LIMIT :limit
        """
    )
    suspend fun getConfirmedRecordsForBackfillBatch(
        updatedAfter: Long = 0L,
        limit: Int = 100
    ): List<DailyRecordEntity>

    @Query("SELECT MAX(updatedAt) FROM daily_records WHERE status = 'Confirmed'")
    suspend fun getMaxConfirmedUpdatedAt(): Long?

    @Query("SELECT COUNT(*) FROM daily_records WHERE status = 'Confirmed' AND updatedAt > :updatedAfter")
    suspend fun countConfirmedRecordsUpdatedAfter(updatedAfter: Long): Int

    @Query(
        """
        UPDATE daily_records
        SET syncStatus = :syncStatus,
            lastSyncedAt = :lastSyncedAt,
            remoteId = COALESCE(remoteId, :remoteId)
        WHERE id = :recordId
        """
    )
    suspend fun markRecordSyncMetadata(
        recordId: String,
        syncStatus: String,
        lastSyncedAt: Long,
        remoteId: String = recordId
    )

    @Query("DELETE FROM daily_records")
    suspend fun deleteAllRecords()
}
