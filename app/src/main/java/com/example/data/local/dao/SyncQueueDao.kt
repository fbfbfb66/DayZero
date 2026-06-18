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

    @Query(
        """
        SELECT * FROM sync_queue
        WHERE status IN ('PENDING', 'FAILED_RETRYABLE', 'WAITING_FOR_AUTH')
          AND nextAttemptAt <= :now
        ORDER BY
          CASE operation
            WHEN 'UPSERT_DAILY_RECORD' THEN 10
            WHEN 'UPSERT_MEAL' THEN 20
            WHEN 'UPSERT_FOOD_ENTRY' THEN 30
            WHEN 'UPSERT_WEIGHT_RECORD' THEN 40
            WHEN 'SOFT_DELETE_RECORD' THEN 50
            ELSE 99
          END ASC,
          createdAt ASC
        LIMIT :limit
        """
    )
    suspend fun getRunnableTasks(now: Long = System.currentTimeMillis(), limit: Int = 50): List<SyncQueueEntity>

    suspend fun getPending(now: Long = System.currentTimeMillis(), limit: Int = 50): List<SyncQueueEntity> {
        return getRunnableTasks(now, limit)
    }

    @Query(
        """
        UPDATE sync_queue
        SET status = 'PROCESSING',
            updatedAt = :now,
            lastAttemptAt = :now,
            lastStatusReason = :reason
        WHERE id = :id
          AND status IN ('PENDING', 'FAILED_RETRYABLE', 'WAITING_FOR_AUTH')
          AND nextAttemptAt <= :now
        """
    )
    suspend fun markProcessing(
        id: String,
        now: Long = System.currentTimeMillis(),
        reason: String? = null
    ): Int

    @Query("UPDATE sync_queue SET status = 'DONE', updatedAt = :updatedAt, nextAttemptAt = 0, lastStatusReason = NULL WHERE id = :id")
    suspend fun markDone(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query(
        """
        UPDATE sync_queue
        SET status = 'FAILED_RETRYABLE',
            retryCount = :retryCount,
            lastError = :error,
            lastStatusReason = :reason,
            updatedAt = :updatedAt,
            nextAttemptAt = :nextAttemptAt
        WHERE id = :id
        """
    )
    suspend fun markRetryableFailure(
        id: String,
        error: String?,
        retryCount: Int,
        updatedAt: Long = System.currentTimeMillis(),
        nextAttemptAt: Long,
        reason: String? = null
    )

    @Query("UPDATE sync_queue SET status = 'FAILED_FATAL', lastError = :error, lastStatusReason = :reason, updatedAt = :updatedAt, nextAttemptAt = 0 WHERE id = :id")
    suspend fun markFatalFailure(
        id: String,
        error: String?,
        updatedAt: Long = System.currentTimeMillis(),
        reason: String? = null
    )

    @Query("UPDATE sync_queue SET status = 'WAITING_FOR_AUTH', lastError = :reason, lastStatusReason = :reason, updatedAt = :updatedAt, nextAttemptAt = :nextAttemptAt WHERE id = :id")
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

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'PENDING'")
    suspend fun countPending(): Int

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'FAILED_RETRYABLE'")
    suspend fun countRetryable(): Int

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'FAILED_FATAL'")
    suspend fun countFatal(): Int

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'WAITING_FOR_AUTH'")
    suspend fun countWaitingForAuth(): Int

    @Query(
        """
        SELECT COUNT(*) FROM sync_queue
        WHERE ownerLocalId IN (:ownerLocalId, 'local_uninitialized')
          AND entityType = :entityType
          AND entityLocalId = :entityLocalId
          AND operation = :operation
          AND (
            status IN ('PENDING', 'PROCESSING', 'FAILED_RETRYABLE', 'WAITING_FOR_AUTH')
            OR (status = 'DONE' AND updatedAt >= :localUpdatedAt)
          )
        """
    )
    suspend fun countBlockingDuplicate(
        ownerLocalId: String,
        entityType: String,
        entityLocalId: String,
        operation: String,
        localUpdatedAt: Long
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM sync_queue
        WHERE ownerLocalId IN (:ownerLocalId, 'local_uninitialized')
          AND entityType = :entityType
          AND entityLocalId = :entityLocalId
          AND status IN ('PENDING', 'PROCESSING', 'FAILED_RETRYABLE', 'WAITING_FOR_AUTH')
        """
    )
    suspend fun countActiveTasksForEntity(
        ownerLocalId: String,
        entityType: String,
        entityLocalId: String
    ): Int

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    @Query("SELECT * FROM sync_queue WHERE status = :status ORDER BY createdAt ASC")
    suspend fun getTasksByStatus(status: String): List<SyncQueueEntity>

    @Query(
        """
        UPDATE sync_queue
        SET status = 'FAILED_RETRYABLE',
            retryCount = retryCount + 1,
            lastError = 'stuck_processing_reset',
            lastStatusReason = 'stuck_processing_reset',
            updatedAt = :now,
            nextAttemptAt = :now
        WHERE status = 'PROCESSING'
          AND updatedAt < :beforeTimestamp
        """
    )
    suspend fun resetStuckProcessingTasks(
        beforeTimestamp: Long,
        now: Long = System.currentTimeMillis()
    ): Int

    @Query("DELETE FROM sync_queue WHERE status = 'DONE' AND updatedAt < :beforeTimestamp")
    suspend fun deleteDoneOlderThan(beforeTimestamp: Long): Int

    @Query("SELECT MAX(updatedAt) FROM sync_queue")
    suspend fun getLastSyncAttemptAt(): Long?

    @Query("SELECT MAX(updatedAt) FROM sync_queue WHERE status = 'DONE'")
    suspend fun getLastSuccessfulSyncAt(): Long?

    @Query("SELECT MAX(updatedAt) FROM sync_queue WHERE status IN ('FAILED_RETRYABLE', 'FAILED_FATAL')")
    suspend fun getLastSyncFailureAt(): Long?

    @Query("SELECT MIN(createdAt) FROM sync_queue WHERE status IN ('PENDING', 'FAILED_RETRYABLE', 'WAITING_FOR_AUTH')")
    suspend fun getOldestPendingAt(): Long?

    @Query(
        """
        SELECT lastError FROM sync_queue
        WHERE status IN ('FAILED_RETRYABLE', 'FAILED_FATAL')
          AND lastError IS NOT NULL
        ORDER BY updatedAt DESC
        LIMIT 1
        """
    )
    suspend fun getLastSyncError(): String?
}
