package com.example.data.sync

import android.util.Log
import com.example.data.local.dao.SyncQueueDao

class SyncHealthReporter(
    private val syncQueueDao: SyncQueueDao
) {
    suspend fun snapshot(): SyncHealthSnapshot {
        return SyncHealthSnapshot(
            pendingCount = syncQueueDao.countByStatus(DayZeroSyncConstants.STATUS_PENDING),
            processingCount = syncQueueDao.countByStatus(DayZeroSyncConstants.STATUS_PROCESSING),
            doneCount = syncQueueDao.countByStatus(DayZeroSyncConstants.STATUS_DONE),
            retryableFailureCount = syncQueueDao.countByStatus(DayZeroSyncConstants.STATUS_FAILED_RETRYABLE),
            fatalFailureCount = syncQueueDao.countByStatus(DayZeroSyncConstants.STATUS_FAILED_FATAL),
            waitingForAuthCount = syncQueueDao.countByStatus(DayZeroSyncConstants.STATUS_WAITING_FOR_AUTH),
            lastSuccessfulSyncAt = syncQueueDao.getLastSuccessfulSyncAt(),
            lastSyncError = syncQueueDao.getLastSyncError()
        )
    }

    suspend fun logSnapshot() {
        val health = snapshot()
        Log.d(
            "DayZeroSyncHealth",
            "pending=${health.pendingCount} processing=${health.processingCount} done=${health.doneCount} " +
                "retryable=${health.retryableFailureCount} fatal=${health.fatalFailureCount} " +
                "waitingForAuth=${health.waitingForAuthCount} lastSuccessfulSyncAt=${health.lastSuccessfulSyncAt} " +
                "lastSyncError=${health.lastSyncError}"
        )
    }
}
