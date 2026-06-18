package com.example.data.sync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SyncTriggerReason {
    APP_START,
    RECORD_CONFIRMED,
    MANUAL,
    NETWORK_AVAILABLE,
    BACKFILL_COMPLETED,
    AUTH_READY,
    RETRY
}

interface SyncScheduler {
    fun requestSync(reason: SyncTriggerReason): Job?
    fun requestBackfill(reason: SyncTriggerReason): Job?
    fun requestSyncAndBackfill(reason: SyncTriggerReason): Job?
    fun requestPull(reason: SyncTriggerReason): Job?
    fun requestInitialRestore(reason: SyncTriggerReason): Job?
    fun requestSyncAndPull(reason: SyncTriggerReason): Job?
}

class InProcessSyncScheduler(
    private val scope: CoroutineScope,
    private val syncCoordinator: SyncCoordinator?,
    private val backfillCoordinator: BackfillCoordinator?,
    private val pullCoordinator: PullCoordinator? = null,
    private val syncHealthReporter: SyncHealthReporter?,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS
) : SyncScheduler {
    private val lock = Any()
    private var activeJob: Job? = null

    override fun requestSync(reason: SyncTriggerReason): Job? {
        return request(reason = reason, runBackfill = false, runSync = true)
    }

    override fun requestBackfill(reason: SyncTriggerReason): Job? {
        return request(reason = reason, runBackfill = true, runSync = false)
    }

    override fun requestSyncAndBackfill(reason: SyncTriggerReason): Job? {
        return request(reason = reason, runBackfill = true, runSync = true, pullMode = null)
    }

    override fun requestPull(reason: SyncTriggerReason): Job? {
        return request(reason = reason, runBackfill = false, runSync = false, pullMode = PullMode.INCREMENTAL)
    }

    override fun requestInitialRestore(reason: SyncTriggerReason): Job? {
        return request(reason = reason, runBackfill = false, runSync = false, pullMode = PullMode.INITIAL_RESTORE)
    }

    override fun requestSyncAndPull(reason: SyncTriggerReason): Job? {
        return request(reason = reason, runBackfill = true, runSync = true, pullMode = PullMode.MANUAL_RESTORE_CHECK)
    }

    private fun request(
        reason: SyncTriggerReason,
        runBackfill: Boolean,
        runSync: Boolean,
        pullMode: PullMode? = null
    ): Job? {
        synchronized(lock) {
            activeJob?.takeIf { it.isActive }?.let { job ->
                Log.d("DayZeroSync", "scheduler skipped already running reason=$reason")
                return job
            }

            val job = scope.launch {
                try {
                    Log.d("DayZeroSync", "scheduler request reason=$reason backfill=$runBackfill sync=$runSync pull=$pullMode")
                    if (reason != SyncTriggerReason.MANUAL && debounceMs > 0L) {
                        delay(debounceMs)
                    }
                    if (runSync) {
                        Log.d("DayZeroSync", "scheduler step push pending reason=$reason")
                        syncCoordinator?.runOnce()
                    }
                    if (runBackfill) {
                        Log.d("DayZeroBackfill", "scheduler step backfill reason=$reason")
                        val stats = if (reason == SyncTriggerReason.APP_START) {
                            backfillCoordinator?.enqueueInitialBackfillIfNeeded()
                        } else {
                            backfillCoordinator?.enqueueMissingRecords()
                        }
                        stats?.let {
                            Log.d(
                                "DayZeroBackfill",
                                "scheduler result reason=$reason enqueued=${it.enqueuedCount} skipped=${it.skippedAlreadyQueuedCount}"
                            )
                        }
                    }
                    if (runSync && runBackfill) {
                        Log.d("DayZeroSync", "scheduler step push pending after backfill reason=$reason")
                        syncCoordinator?.runOnce()
                    }
                    when (pullMode) {
                        PullMode.INITIAL_RESTORE -> {
                            Log.d("DayZeroPull", "scheduler step initial restore reason=$reason")
                            pullCoordinator?.runInitialRestoreIfLocalEmpty()
                        }
                        PullMode.INCREMENTAL -> {
                            Log.d("DayZeroPull", "scheduler step pull incremental reason=$reason")
                            pullCoordinator?.runIncrementalPull()
                        }
                        PullMode.MANUAL_RESTORE_CHECK -> {
                            Log.d("DayZeroPull", "scheduler step pull manual check reason=$reason")
                            pullCoordinator?.runOnce(PullMode.MANUAL_RESTORE_CHECK)
                        }
                        null -> Unit
                    }
                    Log.d("DayZeroHealth", "scheduler step health refresh reason=$reason")
                    syncHealthReporter?.logSnapshot()
                } catch (error: Exception) {
                    Log.e("DayZeroSync", "scheduler error reason=$reason type=${error::class.java.simpleName}", error)
                } finally {
                    synchronized(lock) {
                        if (activeJob === this.coroutineContext[Job]) {
                            activeJob = null
                        }
                    }
                }
            }
            activeJob = job
            return job
        }
    }

    private companion object {
        private const val DEFAULT_DEBOUNCE_MS = 750L
    }
}
