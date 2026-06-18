package com.example.data.sync

import android.util.Log
import com.example.data.local.dao.DailyRecordDao
import com.example.data.local.dao.SyncQueueDao
import com.example.domain.identity.CurrentIdentityProvider

class SyncHealthReporter(
    private val syncQueueDao: SyncQueueDao,
    private val identityProvider: CurrentIdentityProvider,
    private val backfillStateStore: BackfillStateStore,
    private val dailyRecordDao: DailyRecordDao,
    private val remoteSyncEnabledProvider: () -> Boolean
) {
    suspend fun snapshot(): SyncHealthSnapshot {
        val identity = identityProvider.currentIdentity()
        val backfillState = backfillStateStore.snapshot()
        val pendingCount = syncQueueDao.countByStatus(DayZeroSyncConstants.STATUS_PENDING)
        val retryableFailureCount = syncQueueDao.countByStatus(DayZeroSyncConstants.STATUS_FAILED_RETRYABLE)
        val fatalFailureCount = syncQueueDao.countByStatus(DayZeroSyncConstants.STATUS_FAILED_FATAL)
        val waitingForAuthCount = syncQueueDao.countByStatus(DayZeroSyncConstants.STATUS_WAITING_FOR_AUTH)
        val remoteSyncEnabled = remoteSyncEnabledProvider()
        val hasRemoteIdentity = identity.canRemoteSync && !identity.remoteUserId.isNullOrBlank()
        val lastBackfillSuccess = backfillState.lastSuccessAt ?: 0L
        val backfillPendingEstimatedCount = dailyRecordDao.countConfirmedRecordsUpdatedAfter(lastBackfillSuccess)
        val lastSyncSuccessAt = syncQueueDao.getLastSuccessfulSyncAt()

        return SyncHealthSnapshot(
            remoteSyncEnabled = remoteSyncEnabled,
            hasRemoteIdentity = hasRemoteIdentity,
            authProvider = identity.authProvider,
            pendingCount = pendingCount,
            processingCount = syncQueueDao.countByStatus(DayZeroSyncConstants.STATUS_PROCESSING),
            doneCount = syncQueueDao.countByStatus(DayZeroSyncConstants.STATUS_DONE),
            retryableFailureCount = retryableFailureCount,
            fatalFailureCount = fatalFailureCount,
            waitingForAuthCount = waitingForAuthCount,
            lastSyncAttemptAt = syncQueueDao.getLastSyncAttemptAt(),
            lastSyncSuccessAt = lastSyncSuccessAt,
            lastSyncFailureAt = syncQueueDao.getLastSyncFailureAt(),
            lastSyncError = syncQueueDao.getLastSyncError(),
            backfillStatus = backfillState.status,
            backfillLastSuccessAt = backfillState.lastSuccessAt,
            backfillPendingEstimatedCount = backfillPendingEstimatedCount,
            queueOldestPendingAt = syncQueueDao.getOldestPendingAt(),
            isHealthy = isHealthy(
                remoteSyncEnabled = remoteSyncEnabled,
                hasRemoteIdentity = hasRemoteIdentity,
                pendingCount = pendingCount,
                retryableFailureCount = retryableFailureCount,
                fatalFailureCount = fatalFailureCount,
                lastSyncSuccessAt = lastSyncSuccessAt,
                waitingForAuthCount = waitingForAuthCount
            )
        )
    }

    suspend fun logSnapshot() {
        val health = snapshot()
        Log.d(
            "DayZeroHealth",
            "remoteSyncEnabled=${health.remoteSyncEnabled} hasRemoteIdentity=${health.hasRemoteIdentity} " +
                "authProvider=${health.authProvider} pending=${health.pendingCount} processing=${health.processingCount} " +
                "done=${health.doneCount} retryable=${health.retryableFailureCount} fatal=${health.fatalFailureCount} " +
                "waitingForAuth=${health.waitingForAuthCount} backfillStatus=${health.backfillStatus.value} " +
                "backfillPendingEstimated=${health.backfillPendingEstimatedCount} isHealthy=${health.isHealthy}"
        )
    }

    private fun isHealthy(
        remoteSyncEnabled: Boolean,
        hasRemoteIdentity: Boolean,
        pendingCount: Int,
        retryableFailureCount: Int,
        fatalFailureCount: Int,
        lastSyncSuccessAt: Long?,
        waitingForAuthCount: Int
    ): Boolean {
        if (!remoteSyncEnabled) return true
        if (fatalFailureCount > 0) return false
        if (!hasRemoteIdentity) return waitingForAuthCount >= 0
        if (retryableFailureCount > 0 && lastSyncSuccessAt == null) return false
        if (pendingCount > PENDING_WARNING_THRESHOLD && lastSyncSuccessAt == null) return false
        return true
    }

    private companion object {
        private const val PENDING_WARNING_THRESHOLD = 20
    }
}
