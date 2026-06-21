package com.example.data.sync

import com.example.data.local.dao.DailyRecordDao
import com.example.data.local.dao.SyncQueueDao
import com.example.data.sync.chat.ChatPullHealthStateStore
import com.example.data.sync.chat.ChatPullHealthState
import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider
import com.example.data.sync.BackfillStateSnapshot
import com.example.data.sync.BackfillStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncHealthReporterChatPullTest {

    @Test
    fun `snapshot includes chat pull retryable failure count`() = runTest {
        val identityProvider = mockk<CurrentIdentityProvider>()
        coEvery { identityProvider.currentIdentity() } returns AppIdentity("local", "remote", "supabase", true)
        val syncQueueDao = mockk<SyncQueueDao>(relaxed = true)
        coEvery { syncQueueDao.countByStatus(DayZeroSyncConstants.STATUS_FAILED_RETRYABLE) } returns 2
        coEvery { syncQueueDao.countByStatus(DayZeroSyncConstants.STATUS_FAILED_FATAL) } returns 0
        val backfillStore = mockk<BackfillStateStore>()
        every { backfillStore.snapshot() } returns BackfillStateSnapshot(BackfillStatus.NOT_STARTED, null, null, null, null, null, 0, 0, 0, 0, 0, 0, 0)
        val dailyRecordDao = mockk<DailyRecordDao>()
        coEvery { dailyRecordDao.countConfirmedRecordsUpdatedAfter(0L) } returns 0
        coEvery { dailyRecordDao.countBusinessRecordsIncludingDeleted() } returns 10
        val chatPullStateStore = mockk<ChatPullHealthStateStore>()
        every { chatPullStateStore.snapshot() } returns ChatPullHealthState(
            status = PullStatus.FAILED_RETRYABLE,
            lastAttemptAt = null,
            lastSuccessAt = null,
            lastFailureAt = null,
            lastError = "network_timeout"
        )

        val reporter = SyncHealthReporter(
            syncQueueDao = syncQueueDao,
            identityProvider = identityProvider,
            backfillStateStore = backfillStore,
            pullStateStore = null,
            chatPullHealthStateStore = chatPullStateStore,
            dailyRecordDao = dailyRecordDao,
            remoteSyncEnabledProvider = { true }
        )

        val snapshot = reporter.snapshot()

        // 2 from SyncQueue + 1 from ChatPull
        assertEquals(3, snapshot.retryableFailureCount)
        assertEquals(PullStatus.FAILED_RETRYABLE, snapshot.chatPullStatus)
        assertEquals("network_timeout", snapshot.chatPullLastError)
    }

    @Test
    fun `snapshot includes chat pull fatal failure count`() = runTest {
        val identityProvider = mockk<CurrentIdentityProvider>()
        coEvery { identityProvider.currentIdentity() } returns AppIdentity("local", "remote", "supabase", true)
        val syncQueueDao = mockk<SyncQueueDao>(relaxed = true)
        coEvery { syncQueueDao.countByStatus(DayZeroSyncConstants.STATUS_FAILED_RETRYABLE) } returns 0
        coEvery { syncQueueDao.countByStatus(DayZeroSyncConstants.STATUS_FAILED_FATAL) } returns 1
        val backfillStore = mockk<BackfillStateStore>()
        every { backfillStore.snapshot() } returns BackfillStateSnapshot(BackfillStatus.NOT_STARTED, null, null, null, null, null, 0, 0, 0, 0, 0, 0, 0)
        val dailyRecordDao = mockk<DailyRecordDao>()
        coEvery { dailyRecordDao.countConfirmedRecordsUpdatedAfter(0L) } returns 0
        coEvery { dailyRecordDao.countBusinessRecordsIncludingDeleted() } returns 10
        val chatPullStateStore = mockk<ChatPullHealthStateStore>()
        every { chatPullStateStore.snapshot() } returns ChatPullHealthState(
            status = PullStatus.FAILED_FATAL,
            lastAttemptAt = null,
            lastSuccessAt = null,
            lastFailureAt = null,
            lastError = "auth_error"
        )

        val reporter = SyncHealthReporter(
            syncQueueDao = syncQueueDao,
            identityProvider = identityProvider,
            backfillStateStore = backfillStore,
            pullStateStore = null,
            chatPullHealthStateStore = chatPullStateStore,
            dailyRecordDao = dailyRecordDao,
            remoteSyncEnabledProvider = { true }
        )

        val snapshot = reporter.snapshot()

        // 1 from SyncQueue + 1 from ChatPull
        assertEquals(2, snapshot.fatalFailureCount)
        assertEquals(PullStatus.FAILED_FATAL, snapshot.chatPullStatus)
        assertEquals("auth_error", snapshot.chatPullLastError)
    }

    @Test
    fun `snapshot recovery clears fatal count after successful completed pull`() = runTest {
        val identityProvider = mockk<CurrentIdentityProvider>()
        coEvery { identityProvider.currentIdentity() } returns AppIdentity("local", "remote", "supabase", true)
        val syncQueueDao = mockk<SyncQueueDao>(relaxed = true)
        coEvery { syncQueueDao.countByStatus(DayZeroSyncConstants.STATUS_FAILED_RETRYABLE) } returns 0
        coEvery { syncQueueDao.countByStatus(DayZeroSyncConstants.STATUS_FAILED_FATAL) } returns 0
        val backfillStore = mockk<BackfillStateStore>()
        every { backfillStore.snapshot() } returns BackfillStateSnapshot(BackfillStatus.NOT_STARTED, null, null, null, null, null, 0, 0, 0, 0, 0, 0, 0)
        val dailyRecordDao = mockk<DailyRecordDao>()
        coEvery { dailyRecordDao.countConfirmedRecordsUpdatedAfter(0L) } returns 0
        coEvery { dailyRecordDao.countBusinessRecordsIncludingDeleted() } returns 10
        val chatPullStateStore = mockk<ChatPullHealthStateStore>()
        every { chatPullStateStore.snapshot() } returns ChatPullHealthState(
            status = PullStatus.COMPLETED,
            lastAttemptAt = null,
            lastSuccessAt = 12345L,
            lastFailureAt = null,
            lastError = null
        )

        val reporter = SyncHealthReporter(
            syncQueueDao = syncQueueDao,
            identityProvider = identityProvider,
            backfillStateStore = backfillStore,
            pullStateStore = null,
            chatPullHealthStateStore = chatPullStateStore,
            dailyRecordDao = dailyRecordDao,
            remoteSyncEnabledProvider = { true }
        )

        val snapshot = reporter.snapshot()

        assertEquals(0, snapshot.fatalFailureCount)
        assertEquals(0, snapshot.retryableFailureCount)
        assertEquals(PullStatus.COMPLETED, snapshot.chatPullStatus)
        assertNull(snapshot.chatPullLastError)
    }
}
