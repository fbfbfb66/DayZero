package com.example.data.sync

import android.util.Log
import com.example.data.sync.chat.ChatBackfillCoordinator
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
    private val chatBackfillCoordinator: ChatBackfillCoordinator? = null,
    private val pullCoordinator: PullCoordinator? = null,
    private val chatPullCoordinator: com.example.data.sync.chat.ChatPullCoordinator? = null,
    private val chatPullHealthStateStore: com.example.data.sync.chat.ChatPullHealthStateStore? = null,
    private val syncHealthReporter: SyncHealthReporter?,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val remoteIdentityBindingCoordinator: RemoteIdentityBindingCoordinator? = null
) : SyncScheduler {
    private val lock = Any()
    private var activeJob: Job? = null
    private var activeRequest: ScheduledRequest? = null
    private var pendingRequest: ScheduledRequest? = null

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
        val requested = ScheduledRequest(
            reason = reason,
            runBackfill = runBackfill,
            runSync = runSync,
            pullMode = pullMode
        )
        synchronized(lock) {
            activeJob?.takeIf { it.isActive }?.let { job ->
                val alreadyCovered = activeRequest?.covers(requested) == true ||
                    pendingRequest?.covers(requested) == true
                if (!alreadyCovered) {
                    pendingRequest = pendingRequest?.merge(requested) ?: requested
                    Log.d("DayZeroSync", "scheduler queued follow-up reason=$reason")
                } else {
                    Log.d("DayZeroSync", "scheduler skipped already covered reason=$reason")
                }
                return job
            }

            val job = scope.launch {
                try {
                    Log.d("DayZeroSync", "scheduler request reason=$reason backfill=$runBackfill sync=$runSync pull=$pullMode")
                    if (reason != SyncTriggerReason.MANUAL && debounceMs > 0L) {
                        delay(debounceMs)
                    }
                    remoteIdentityBindingCoordinator?.ensureRemoteUserBound()?.let { changed ->
                        if (changed) {
                            Log.w("DayZeroIdentityBind", "scheduler reset remote-scoped sync metadata reason=$reason")
                        }
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
                        Log.d("DayZeroChatBackfill", "scheduler step chat backfill reason=$reason")
                        val chatStats = chatBackfillCoordinator?.runOnce()
                        chatStats?.let {
                            Log.d(
                                "DayZeroChatBackfill",
                                "scheduler result reason=$reason conversations=${it.enqueuedConversationCount} " +
                                    "messages=${it.enqueuedMessageCount} skippedPlaceholders=${it.skippedPlaceholderCount}"
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
                    if (pullMode != null) {
                        Log.d("DayZeroChatPull", "scheduler step chat pull reason=$reason")
                        chatPullHealthStateStore?.markRunning()
                        val chatResult = chatPullCoordinator?.pullAll()
                        when (chatResult) {
                            is com.example.data.sync.chat.ChatPullResult.Success -> {
                                chatPullHealthStateStore?.markCompleted()
                            }
                            is com.example.data.sync.chat.ChatPullResult.ConversationRetryableFailure -> {
                                chatPullHealthStateStore?.markRetryableFailure(chatResult.reason)
                            }
                            is com.example.data.sync.chat.ChatPullResult.ConversationFatalFailure -> {
                                chatPullHealthStateStore?.markFatalFailure(chatResult.reason)
                            }
                            is com.example.data.sync.chat.ChatPullResult.MessageRetryableFailure -> {
                                chatPullHealthStateStore?.markRetryableFailure(chatResult.reason)
                            }
                            is com.example.data.sync.chat.ChatPullResult.MessageFatalFailure -> {
                                chatPullHealthStateStore?.markFatalFailure(chatResult.reason)
                            }
                            is com.example.data.sync.chat.ChatPullResult.Skipped,
                            com.example.data.sync.chat.ChatPullResult.SkippedAlreadyRunning,
                            null -> Unit
                        }
                    }
                    Log.d("DayZeroHealth", "scheduler step health refresh reason=$reason")
                    syncHealthReporter?.logSnapshot()
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    Log.e("DayZeroSync", "scheduler error reason=$reason type=${error::class.java.simpleName}", error)
                } finally {
                    var followUp: ScheduledRequest? = null
                    synchronized(lock) {
                        if (activeJob === this.coroutineContext[Job]) {
                            activeJob = null
                            activeRequest = null
                            followUp = pendingRequest
                            pendingRequest = null
                        }
                    }
                    followUp?.let {
                        Log.d("DayZeroSync", "scheduler starting queued follow-up reason=${it.reason}")
                        request(
                            reason = it.reason,
                            runBackfill = it.runBackfill,
                            runSync = it.runSync,
                            pullMode = it.pullMode
                        )
                    }
                }
            }
            activeJob = job
            activeRequest = requested
            return job
        }
    }

    private data class ScheduledRequest(
        val reason: SyncTriggerReason,
        val runBackfill: Boolean,
        val runSync: Boolean,
        val pullMode: PullMode?
    ) {
        fun covers(other: ScheduledRequest): Boolean {
            return (!other.runBackfill || runBackfill) &&
                (!other.runSync || runSync) &&
                (other.pullMode == null || pullMode == other.pullMode)
        }

        fun merge(other: ScheduledRequest): ScheduledRequest {
            return ScheduledRequest(
                reason = other.reason,
                runBackfill = runBackfill || other.runBackfill,
                runSync = runSync || other.runSync,
                pullMode = mergePullMode(pullMode, other.pullMode)
            )
        }

        private fun mergePullMode(current: PullMode?, next: PullMode?): PullMode? {
            return when {
                current == PullMode.MANUAL_RESTORE_CHECK || next == PullMode.MANUAL_RESTORE_CHECK ->
                    PullMode.MANUAL_RESTORE_CHECK
                current == PullMode.INITIAL_RESTORE || next == PullMode.INITIAL_RESTORE ->
                    PullMode.INITIAL_RESTORE
                current == PullMode.INCREMENTAL || next == PullMode.INCREMENTAL ->
                    PullMode.INCREMENTAL
                else -> null
            }
        }
    }

    private companion object {
        private const val DEFAULT_DEBOUNCE_MS = 750L
    }
}
