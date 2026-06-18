package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.SyncQueueEntity
import com.example.data.sync.DayZeroSyncConstants
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SyncQueueEntity)

    @Query("SELECT * FROM sync_queue WHERE status IN ('PENDING', 'FAILED_RETRYABLE', 'WAITING_FOR_AUTH') AND nextAttemptAt <= :now ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getPending(now: Long = System.currentTimeMillis(), limit: Int = 50): List<SyncQueueEntity>

    @Query("UPDATE sync_queue SET status = 'PROCESSING', updatedAt = :updatedAt WHERE id = :id")
    suspend fun markProcessing(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE sync_queue SET status = 'DONE', updatedAt = :updatedAt, nextAttemptAt = 0 WHERE id = :id")
    suspend fun markDone(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE sync_queue SET status = 'FAILED_RETRYABLE', retryCount = retryCount + 1, lastError = :error, updatedAt = :updatedAt, nextAttemptAt = :nextAttemptAt WHERE id = :id")
    suspend fun markRetryableFailure(
        id: String,
        error: String?,
        updatedAt: Long = System.currentTimeMillis(),
        nextAttemptAt: Long = updatedAt + 60_000L
    )

    @Query("UPDATE sync_queue SET status = 'FAILED_FATAL', lastError = :error, updatedAt = :updatedAt, nextAttemptAt = 0 WHERE id = :id")
    suspend fun markFatalFailure(id: String, error: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE sync_queue SET status = 'WAITING_FOR_AUTH', lastError = :reason, updatedAt = :updatedAt, nextAttemptAt = :nextAttemptAt WHERE id = :id")
    suspend fun markWaitingForAuth(
        id: String,
        reason: String?,
        updatedAt: Long = System.currentTimeMillis(),
        nextAttemptAt: Long = updatedAt + DayZeroSyncConstants.WAITING_FOR_AUTH_RETRY_DELAY_MS
    )

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status IN ('PENDING', 'FAILED_RETRYABLE', 'WAITING_FOR_AUTH')")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status IN ('PENDING', 'FAILED_RETRYABLE', 'WAITING_FOR_AUTH')")
    suspend fun getPendingCount(): Int
}
