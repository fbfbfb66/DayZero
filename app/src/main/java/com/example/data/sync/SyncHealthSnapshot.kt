package com.example.data.sync

data class SyncHealthSnapshot(
    val remoteSyncEnabled: Boolean,
    val hasRemoteIdentity: Boolean,
    val authProvider: String,
    val pendingCount: Int,
    val processingCount: Int,
    val doneCount: Int,
    val retryableFailureCount: Int,
    val fatalFailureCount: Int,
    val waitingForAuthCount: Int,
    val lastSyncAttemptAt: Long?,
    val lastSyncSuccessAt: Long?,
    val lastSyncFailureAt: Long?,
    val lastSyncError: String?,
    val backfillStatus: BackfillStatus,
    val backfillLastSuccessAt: Long?,
    val backfillPendingEstimatedCount: Int,
    val queueOldestPendingAt: Long?,
    val isHealthy: Boolean
)
