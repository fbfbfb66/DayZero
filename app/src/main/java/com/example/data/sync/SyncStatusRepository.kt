package com.example.data.sync

import android.util.Log

class SyncStatusRepository(
    private val syncCoordinator: SyncCoordinator?,
    private val backfillCoordinator: BackfillCoordinator?,
    private val syncHealthReporter: SyncHealthReporter?,
    private val syncScheduler: SyncScheduler? = null
) {
    suspend fun snapshot(): SyncHealthSnapshot? {
        return runCatching {
            syncHealthReporter?.snapshot()
        }.onFailure { error ->
            Log.e("DayZeroHealth", "snapshot error reason=${error::class.java.simpleName}", error)
        }.getOrNull()
    }

    suspend fun logSnapshot() {
        runCatching {
            syncHealthReporter?.logSnapshot()
        }.onFailure { error ->
            Log.e("DayZeroHealth", "log snapshot error reason=${error::class.java.simpleName}", error)
        }
    }

    suspend fun runManualSync(): SyncHealthSnapshot? {
        Log.d("DayZeroSync", "manual sync start")
        return try {
            val job = syncScheduler?.requestSyncAndBackfill(SyncTriggerReason.MANUAL)
            if (job != null) {
                job.join()
            } else {
                backfillCoordinator?.enqueueMissingRecords()
                syncCoordinator?.runOnce()
            }
            val snapshot = snapshot()
            Log.d("DayZeroSync", "manual sync finish")
            snapshot
        } catch (error: Exception) {
            Log.e("DayZeroSync", "manual sync error reason=${error::class.java.simpleName}", error)
            snapshot()
        }
    }
}
