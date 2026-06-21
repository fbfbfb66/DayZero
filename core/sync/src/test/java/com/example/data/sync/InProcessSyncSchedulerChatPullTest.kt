package com.example.data.sync

import com.example.data.sync.chat.ChatConversationMergeStats
import com.example.data.sync.chat.ChatMessageMergeStats
import com.example.data.sync.chat.ChatPullCoordinator
import com.example.data.sync.chat.ChatPullHealthStateStore
import com.example.data.sync.chat.ChatPullResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InProcessSyncSchedulerChatPullTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    private fun createScheduler(
        testScope: TestScope,
        syncCoordinator: SyncCoordinator = mockk(relaxed = true),
        backfillCoordinator: BackfillCoordinator = mockk(relaxed = true),
        chatBackfillCoordinator: com.example.data.sync.chat.ChatBackfillCoordinator = mockk(relaxed = true),
        pullCoordinator: PullCoordinator = mockk(relaxed = true),
        chatPullCoordinator: ChatPullCoordinator = mockk(relaxed = true),
        chatPullHealthStateStore: ChatPullHealthStateStore = mockk(relaxed = true),
        syncHealthReporter: SyncHealthReporter = mockk(relaxed = true),
        debounceMs: Long = 0L
    ) = InProcessSyncScheduler(
        scope = testScope,
        syncCoordinator = syncCoordinator,
        backfillCoordinator = backfillCoordinator,
        chatBackfillCoordinator = chatBackfillCoordinator,
        pullCoordinator = pullCoordinator,
        chatPullCoordinator = chatPullCoordinator,
        chatPullHealthStateStore = chatPullHealthStateStore,
        syncHealthReporter = syncHealthReporter,
        debounceMs = debounceMs
    )

    @Test
    fun `requestSync does not call pullMode and therefore no ChatPull`() = runTest {
        val testScope = this
        val chatPull = mockk<ChatPullCoordinator>(relaxed = true)
        val sync = mockk<SyncCoordinator>(relaxed = true)
        val scheduler = createScheduler(testScope, syncCoordinator = sync, chatPullCoordinator = chatPull)

        scheduler.requestSync(SyncTriggerReason.MANUAL)
        testScope.advanceUntilIdle()

        coVerify(exactly = 1) { sync.runOnce() }
        coVerify(exactly = 0) { chatPull.pullAll() }
    }

    @Test
    fun `requestBackfill does not call pullMode and therefore no ChatPull`() = runTest {
        val testScope = this
        val chatPull = mockk<ChatPullCoordinator>(relaxed = true)
        val backfill = mockk<BackfillCoordinator>(relaxed = true)
        val scheduler = createScheduler(testScope, backfillCoordinator = backfill, chatPullCoordinator = chatPull)

        scheduler.requestBackfill(SyncTriggerReason.MANUAL)
        testScope.advanceUntilIdle()

        coVerify(exactly = 1) { backfill.enqueueMissingRecords() }
        coVerify(exactly = 0) { chatPull.pullAll() }
    }

    @Test
    fun `requestSyncAndBackfill does not call pullMode and therefore no ChatPull`() = runTest {
        val testScope = this
        val chatPull = mockk<ChatPullCoordinator>(relaxed = true)
        val sync = mockk<SyncCoordinator>(relaxed = true)
        val scheduler = createScheduler(testScope, syncCoordinator = sync, chatPullCoordinator = chatPull)

        scheduler.requestSyncAndBackfill(SyncTriggerReason.MANUAL)
        testScope.advanceUntilIdle()

        coVerify(exactly = 2) { sync.runOnce() }
        coVerify(exactly = 0) { chatPull.pullAll() }
    }

    @Test
    fun `requestPull calls Incremental Pull and then ChatPull`() = runTest {
        val testScope = this
        val chatPull = mockk<ChatPullCoordinator>(relaxed = true)
        val pull = mockk<PullCoordinator>(relaxed = true)
        val scheduler = createScheduler(testScope, pullCoordinator = pull, chatPullCoordinator = chatPull)

        scheduler.requestPull(SyncTriggerReason.MANUAL)
        testScope.advanceUntilIdle()

        coVerifyOrder {
            pull.runIncrementalPull()
            chatPull.pullAll()
        }
    }

    @Test
    fun `requestInitialRestore calls Initial Restore and then ChatPull`() = runTest {
        val testScope = this
        val chatPull = mockk<ChatPullCoordinator>(relaxed = true)
        val pull = mockk<PullCoordinator>(relaxed = true)
        val scheduler = createScheduler(testScope, pullCoordinator = pull, chatPullCoordinator = chatPull)

        scheduler.requestInitialRestore(SyncTriggerReason.MANUAL)
        testScope.advanceUntilIdle()

        coVerifyOrder {
            pull.runInitialRestoreIfLocalEmpty()
            chatPull.pullAll()
        }
    }

    @Test
    fun `requestSyncAndPull calls Manual Restore Check and then ChatPull`() = runTest {
        val testScope = this
        val chatPull = mockk<ChatPullCoordinator>(relaxed = true)
        val pull = mockk<PullCoordinator>(relaxed = true)
        val scheduler = createScheduler(testScope, pullCoordinator = pull, chatPullCoordinator = chatPull)

        scheduler.requestSyncAndPull(SyncTriggerReason.MANUAL)
        testScope.advanceUntilIdle()

        coVerifyOrder {
            pull.runOnce(PullMode.MANUAL_RESTORE_CHECK)
            chatPull.pullAll()
        }
    }

    @Test
    fun `Chat Success updates health state to completed`() = runTest {
        val testScope = this
        val healthStore = mockk<ChatPullHealthStateStore>(relaxed = true)
        val chatPull = mockk<ChatPullCoordinator> {
            coEvery { pullAll() } returns ChatPullResult.Success(
                ChatConversationMergeStats(),
                ChatMessageMergeStats()
            )
        }
        val reporter = mockk<SyncHealthReporter>(relaxed = true)
        val scheduler = createScheduler(testScope, chatPullHealthStateStore = healthStore, chatPullCoordinator = chatPull, syncHealthReporter = reporter)

        scheduler.requestPull(SyncTriggerReason.MANUAL)
        testScope.advanceUntilIdle()

        coVerifyOrder {
            healthStore.markRunning(any())
            chatPull.pullAll()
            healthStore.markCompleted(any())
            reporter.logSnapshot()
        }
    }

    @Test
    fun `Ordinary Skipped does not update health error state but logs snapshot`() = runTest {
        val testScope = this
        val healthStore = mockk<ChatPullHealthStateStore>(relaxed = true)
        val chatPull = mockk<ChatPullCoordinator> {
            coEvery { pullAll() } returns ChatPullResult.Skipped("no_remote_user")
        }
        val reporter = mockk<SyncHealthReporter>(relaxed = true)
        val scheduler = createScheduler(testScope, chatPullHealthStateStore = healthStore, chatPullCoordinator = chatPull, syncHealthReporter = reporter)

        scheduler.requestPull(SyncTriggerReason.MANUAL)
        testScope.advanceUntilIdle()

        coVerify(exactly = 1) { healthStore.markRunning(any()) }
        coVerify(exactly = 0) { healthStore.markCompleted(any()) }
        coVerify(exactly = 0) { healthStore.markRetryableFailure(any(), any()) }
        coVerify(exactly = 0) { healthStore.markFatalFailure(any(), any()) }
        coVerify(exactly = 1) { reporter.logSnapshot() }
    }

    @Test
    fun `SkippedAlreadyRunning does not update health error state`() = runTest {
        val testScope = this
        val healthStore = mockk<ChatPullHealthStateStore>(relaxed = true)
        val chatPull = mockk<ChatPullCoordinator> {
            coEvery { pullAll() } returns ChatPullResult.SkippedAlreadyRunning
        }
        val scheduler = createScheduler(testScope, chatPullHealthStateStore = healthStore, chatPullCoordinator = chatPull)

        scheduler.requestPull(SyncTriggerReason.MANUAL)
        testScope.advanceUntilIdle()

        coVerify(exactly = 1) { healthStore.markRunning(any()) }
        coVerify(exactly = 0) { healthStore.markCompleted(any()) }
        coVerify(exactly = 0) { healthStore.markRetryableFailure(any(), any()) }
    }

    @Test
    fun `Conversation Retryable sets retryable failure`() = runTest {
        val testScope = this
        val healthStore = mockk<ChatPullHealthStateStore>(relaxed = true)
        val chatPull = mockk<ChatPullCoordinator> {
            coEvery { pullAll() } returns ChatPullResult.ConversationRetryableFailure("timeout")
        }
        val scheduler = createScheduler(testScope, chatPullHealthStateStore = healthStore, chatPullCoordinator = chatPull)

        scheduler.requestPull(SyncTriggerReason.MANUAL)
        testScope.advanceUntilIdle()

        coVerify(exactly = 1) { healthStore.markRetryableFailure(eq("timeout"), any()) }
    }

    @Test
    fun `Message Fatal sets fatal failure`() = runTest {
        val testScope = this
        val healthStore = mockk<ChatPullHealthStateStore>(relaxed = true)
        val chatPull = mockk<ChatPullCoordinator> {
            coEvery { pullAll() } returns ChatPullResult.MessageFatalFailure("401", ChatConversationMergeStats())
        }
        val scheduler = createScheduler(testScope, chatPullHealthStateStore = healthStore, chatPullCoordinator = chatPull)

        scheduler.requestPull(SyncTriggerReason.MANUAL)
        testScope.advanceUntilIdle()

        coVerify(exactly = 1) { healthStore.markFatalFailure(eq("401"), any()) }
    }

    @Test
    fun `CancellationException propagates and clears activeJob`() = runTest {
        val testScope = this
        val chatPull = mockk<ChatPullCoordinator> {
            coEvery { pullAll() } throws CancellationException("Cancelled")
        }
        val scheduler = createScheduler(testScope, chatPullCoordinator = chatPull)

        val job = scheduler.requestPull(SyncTriggerReason.MANUAL)
        assertNotNull(job)
        job?.join()

        assertTrue(job?.isCancelled == true)

        // Ensure active job is cleared by doing a second pull and verifying it runs
        coEvery { chatPull.pullAll() } returns ChatPullResult.Success(
            ChatConversationMergeStats(),
            ChatMessageMergeStats()
        )
        scheduler.requestPull(SyncTriggerReason.MANUAL)
        testScope.advanceUntilIdle()
        coVerify(exactly = 2) { chatPull.pullAll() }
    }

    @Test
    fun `Concurrent requests drop the second one`() = runTest {
        val testScope = this
        val chatPull = mockk<ChatPullCoordinator> {
            coEvery { pullAll() } coAnswers {
                delay(1000)
                ChatPullResult.Success(
                    ChatConversationMergeStats(),
                    ChatMessageMergeStats()
                )
            }
        }
        val scheduler = createScheduler(testScope, chatPullCoordinator = chatPull)

        scheduler.requestPull(SyncTriggerReason.MANUAL)
        scheduler.requestPull(SyncTriggerReason.MANUAL) // should be dropped

        testScope.advanceUntilIdle()

        coVerify(exactly = 1) { chatPull.pullAll() }
    }

    @Test
    fun `Failure allows next request to run`() = runTest {
        val testScope = this
        val chatPull = mockk<ChatPullCoordinator> {
            coEvery { pullAll() } returns ChatPullResult.ConversationRetryableFailure("err")
        }
        val scheduler = createScheduler(testScope, chatPullCoordinator = chatPull)

        scheduler.requestPull(SyncTriggerReason.MANUAL)
        testScope.advanceUntilIdle()

        coEvery { chatPull.pullAll() } returns ChatPullResult.Success(
            ChatConversationMergeStats(),
            ChatMessageMergeStats()
        )
        scheduler.requestPull(SyncTriggerReason.MANUAL)
        testScope.advanceUntilIdle()

        coVerify(exactly = 2) { chatPull.pullAll() }
    }
}
