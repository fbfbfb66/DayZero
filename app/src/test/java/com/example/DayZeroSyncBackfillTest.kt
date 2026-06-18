package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.DayZeroDatabase
import com.example.data.local.mapper.DailyRecordMapper
import com.example.data.local.entity.SyncQueueEntity
import com.example.data.repository.RoomRecordRepository
import com.example.data.sync.BackfillCoordinator
import com.example.data.sync.BackfillStateStore
import com.example.data.sync.DayZeroSyncConstants
import com.example.data.sync.LocalFirstSyncCoordinator
import com.example.data.sync.RemoteSyncGateway
import com.example.data.sync.RemoteSyncResult
import com.example.data.sync.InProcessSyncScheduler
import com.example.data.sync.SyncCoordinator
import com.example.data.sync.SyncHealthReporter
import com.example.data.sync.SyncPayload
import com.example.data.sync.SyncTriggerReason
import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider
import com.example.domain.model.DailyRecord
import com.example.domain.model.FoodEntry
import com.example.domain.model.MealEntry
import com.example.domain.model.MealType
import com.example.domain.model.RecordStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DayZeroSyncBackfillTest {
    private lateinit var context: Context
    private lateinit var database: DayZeroDatabase
    private val mapper = DailyRecordMapper()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("dayzero_backfill", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        database = Room.inMemoryDatabaseBuilder(context, DayZeroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun backfillEnqueuesHistoryRecordsAndDoesNotDuplicateOnSecondRun() = runTest {
        database.dailyRecordDao().upsertRecord(
            mapper.toEntity(
                domain = sampleConfirmedRecord(),
                ownerLocalId = LOCAL_OWNER_ID
            )
        )
        val coordinator = createBackfillCoordinator(canRemoteSync = true)

        val first = coordinator.enqueueInitialBackfillIfNeeded()
        assertEquals(4, first.enqueuedCount)
        assertEquals(4, database.syncQueueDao().getPendingCount())

        val second = coordinator.enqueueInitialBackfillIfNeeded()
        assertEquals(0, second.enqueuedCount)
        assertEquals(4, database.syncQueueDao().getPendingCount())
    }

    @Test
    fun backfillWaitsForAuthWithoutEnqueueingOrFailing() = runTest {
        database.dailyRecordDao().upsertRecord(
            mapper.toEntity(
                domain = sampleConfirmedRecord(),
                ownerLocalId = LOCAL_OWNER_ID
            )
        )
        val coordinator = createBackfillCoordinator(canRemoteSync = false)

        val stats = coordinator.enqueueInitialBackfillIfNeeded()
        assertEquals(1, stats.scannedDailyRecordCount)
        assertEquals(0, stats.enqueuedCount)
        assertEquals(0, database.syncQueueDao().getPendingCount())
    }

    @Test
    fun syncCoordinatorMarksBadPayloadFatalAndContinuesBatch() = runTest {
        val now = System.currentTimeMillis()
        database.syncQueueDao().insert(
            SyncQueueEntity(
                entityType = "daily_record",
                entityLocalId = "bad-record",
                operation = DayZeroSyncConstants.OP_UPSERT_DAILY_RECORD,
                payloadJson = "{}",
                status = DayZeroSyncConstants.STATUS_PENDING,
                createdAt = now,
                updatedAt = now,
                ownerLocalId = LOCAL_OWNER_ID
            )
        )
        database.syncQueueDao().insert(
            SyncQueueEntity(
                entityType = "daily_record",
                entityLocalId = "good-record",
                operation = DayZeroSyncConstants.OP_UPSERT_DAILY_RECORD,
                payloadJson = """{"clientId":"good-record","schemaVersion":1}""",
                status = DayZeroSyncConstants.STATUS_PENDING,
                createdAt = now + 1,
                updatedAt = now + 1,
                ownerLocalId = LOCAL_OWNER_ID
            )
        )
        val coordinator = LocalFirstSyncCoordinator(
            syncQueueDao = database.syncQueueDao(),
            identityProvider = StaticIdentityProvider(canRemoteSync = true),
            remoteSyncGateway = AlwaysSuccessGateway(),
            dailyRecordDao = database.dailyRecordDao()
        )

        coordinator.runOnce()

        assertEquals(1, database.syncQueueDao().countByStatus(DayZeroSyncConstants.STATUS_FAILED_FATAL))
        assertEquals(1, database.syncQueueDao().countByStatus(DayZeroSyncConstants.STATUS_DONE))
    }

    @Test
    fun syncHealthReporterIncludesBackfillAndQueueState() = runTest {
        database.dailyRecordDao().upsertRecord(
            mapper.toEntity(
                domain = sampleConfirmedRecord(),
                ownerLocalId = LOCAL_OWNER_ID
            )
        )
        val backfillStateStore = BackfillStateStore(context)
        createBackfillCoordinator(canRemoteSync = false, stateStore = backfillStateStore)
            .enqueueInitialBackfillIfNeeded()
        val reporter = SyncHealthReporter(
            syncQueueDao = database.syncQueueDao(),
            identityProvider = StaticIdentityProvider(canRemoteSync = false),
            backfillStateStore = backfillStateStore,
            dailyRecordDao = database.dailyRecordDao(),
            remoteSyncEnabledProvider = { false }
        )

        val snapshot = reporter.snapshot()
        assertFalse(snapshot.remoteSyncEnabled)
        assertFalse(snapshot.hasRemoteIdentity)
        assertEquals(0, snapshot.pendingCount)
        assertTrue(snapshot.isHealthy)
        assertTrue(snapshot.backfillPendingEstimatedCount >= 1)
    }

    @Test
    fun repositorySoftDeleteHidesRecordAndEnqueuesDeleteTask() = runTest {
        database.dailyRecordDao().upsertRecord(
            mapper.toEntity(
                domain = sampleConfirmedRecord(),
                ownerLocalId = LOCAL_OWNER_ID
            )
        )
        val repository = RoomRecordRepository(
            dao = database.dailyRecordDao(),
            syncQueueDao = database.syncQueueDao(),
            identityProvider = StaticIdentityProvider(canRemoteSync = true)
        )

        repository.deleteRecordById("record-1")

        assertEquals(null, database.dailyRecordDao().getRecordById("record-1"))
        val tasks = database.syncQueueDao().getPending()
        assertEquals(1, tasks.size)
        assertEquals(DayZeroSyncConstants.OP_SOFT_DELETE_RECORD, tasks.first().operation)
        assertEquals("daily_record", tasks.first().entityType)
        assertEquals("record-1", tasks.first().entityLocalId)
    }

    @Test
    fun retryableFailureSetsRetryCountAndNextAttemptAt() = runTest {
        val now = System.currentTimeMillis()
        database.syncQueueDao().insert(goodQueueTask("retry-record", now))
        val coordinator = LocalFirstSyncCoordinator(
            syncQueueDao = database.syncQueueDao(),
            identityProvider = StaticIdentityProvider(canRemoteSync = true),
            remoteSyncGateway = AlwaysRetryableGateway(),
            dailyRecordDao = database.dailyRecordDao()
        )

        coordinator.runOnce()

        val failed = database.syncQueueDao()
            .getTasksByStatus(DayZeroSyncConstants.STATUS_FAILED_RETRYABLE)
            .single()
        assertEquals(1, failed.retryCount)
        assertTrue(failed.nextAttemptAt > now)
        assertTrue(database.syncQueueDao().getRunnableTasks(now = now + 1_000L).isEmpty())
        assertEquals(1, database.syncQueueDao().getRunnableTasks(now = now + 31_000L).size)
    }

    @Test
    fun stuckProcessingTaskCanBeResetForRetry() = runTest {
        val now = System.currentTimeMillis()
        database.syncQueueDao().insert(
            goodQueueTask("stuck-record", now).copy(
                status = DayZeroSyncConstants.STATUS_PROCESSING,
                updatedAt = now - 60 * 60 * 1000L
            )
        )

        val reset = database.syncQueueDao().resetStuckProcessingTasks(now - 15 * 60 * 1000L, now)

        assertEquals(1, reset)
        assertEquals(1, database.syncQueueDao().countByStatus(DayZeroSyncConstants.STATUS_FAILED_RETRYABLE))
        assertEquals(1, database.syncQueueDao().getRunnableTasks(now = now).size)
    }

    @Test
    fun syncCoordinatorHonorsBatchLimit() = runTest {
        val now = System.currentTimeMillis()
        repeat(55) { index ->
            database.syncQueueDao().insert(goodQueueTask("batch-record-$index", now + index))
        }
        val coordinator = LocalFirstSyncCoordinator(
            syncQueueDao = database.syncQueueDao(),
            identityProvider = StaticIdentityProvider(canRemoteSync = true),
            remoteSyncGateway = AlwaysSuccessGateway(),
            dailyRecordDao = database.dailyRecordDao(),
            batchLimit = 20
        )

        coordinator.runOnce()

        assertEquals(20, database.syncQueueDao().countByStatus(DayZeroSyncConstants.STATUS_DONE))
        assertEquals(35, database.syncQueueDao().countByStatus(DayZeroSyncConstants.STATUS_PENDING))
    }

    @Test
    fun schedulerDebouncesDuplicateRequestsWhileRunning() = runTest {
        val coordinator = CountingCoordinator()
        val scheduler = InProcessSyncScheduler(
            scope = this,
            syncCoordinator = coordinator,
            backfillCoordinator = null,
            syncHealthReporter = null,
            debounceMs = 0L
        )

        val first = scheduler.requestSync(SyncTriggerReason.MANUAL)
        val second = scheduler.requestSync(SyncTriggerReason.MANUAL)
        first?.join()
        second?.join()

        assertEquals(1, coordinator.runCount)
    }

    @Test
    fun doneMaintenanceDeletesOnlyOldDoneTasks() = runTest {
        val now = System.currentTimeMillis()
        database.syncQueueDao().insert(goodQueueTask("old-done", now - 10_000L).copy(status = DayZeroSyncConstants.STATUS_DONE))
        database.syncQueueDao().insert(goodQueueTask("pending", now).copy(status = DayZeroSyncConstants.STATUS_PENDING))
        database.syncQueueDao().insert(goodQueueTask("fatal", now).copy(status = DayZeroSyncConstants.STATUS_FAILED_FATAL))

        val deleted = database.syncQueueDao().deleteDoneOlderThan(now - 1_000L)

        assertEquals(1, deleted)
        assertEquals(0, database.syncQueueDao().countByStatus(DayZeroSyncConstants.STATUS_DONE))
        assertEquals(1, database.syncQueueDao().countByStatus(DayZeroSyncConstants.STATUS_PENDING))
        assertEquals(1, database.syncQueueDao().countByStatus(DayZeroSyncConstants.STATUS_FAILED_FATAL))
    }

    private fun createBackfillCoordinator(
        canRemoteSync: Boolean,
        stateStore: BackfillStateStore = BackfillStateStore(context)
    ): BackfillCoordinator {
        return BackfillCoordinator(
            dailyRecordDao = database.dailyRecordDao(),
            syncQueueDao = database.syncQueueDao(),
            identityProvider = StaticIdentityProvider(canRemoteSync = canRemoteSync),
            stateStore = stateStore
        )
    }

    private fun sampleConfirmedRecord(): DailyRecord {
        return DailyRecord(
            id = "record-1",
            date = LocalDate.of(2026, 6, 19),
            status = RecordStatus.Confirmed,
            meals = listOf(
                MealEntry(
                    id = "meal-1",
                    mealType = MealType.Lunch,
                    foods = listOf(
                        FoodEntry(
                            id = "food-1",
                            name = "egg rice roll",
                            quantity = "1 serving",
                            estimatedCalories = 320
                        )
                    )
                )
            ),
            weightKg = 72.5f
        )
    }

    private fun goodQueueTask(recordId: String, now: Long): SyncQueueEntity {
        return SyncQueueEntity(
            entityType = "daily_record",
            entityLocalId = recordId,
            operation = DayZeroSyncConstants.OP_UPSERT_DAILY_RECORD,
            payloadJson = """{"clientId":"$recordId","schemaVersion":1}""",
            status = DayZeroSyncConstants.STATUS_PENDING,
            createdAt = now,
            updatedAt = now,
            ownerLocalId = LOCAL_OWNER_ID
        )
    }

    private class StaticIdentityProvider(
        private val canRemoteSync: Boolean
    ) : CurrentIdentityProvider {
        override suspend fun currentIdentity(): AppIdentity {
            return AppIdentity(
                localOwnerId = LOCAL_OWNER_ID,
                remoteUserId = if (canRemoteSync) "remote-user-id" else null,
                authProvider = if (canRemoteSync) "supabase_anonymous" else "local",
                canRemoteSync = canRemoteSync
            )
        }
    }

    private class AlwaysSuccessGateway : RemoteSyncGateway {
        override suspend fun canSync(identity: AppIdentity): Boolean = true
        override suspend fun upsertDailyRecord(payload: SyncPayload): RemoteSyncResult = RemoteSyncResult.Success
        override suspend fun upsertMeal(payload: SyncPayload): RemoteSyncResult = RemoteSyncResult.Success
        override suspend fun upsertFoodEntry(payload: SyncPayload): RemoteSyncResult = RemoteSyncResult.Success
        override suspend fun upsertWeightRecord(payload: SyncPayload): RemoteSyncResult = RemoteSyncResult.Success
        override suspend fun softDeleteRecord(payload: SyncPayload): RemoteSyncResult = RemoteSyncResult.Success
    }

    private class AlwaysRetryableGateway : RemoteSyncGateway {
        override suspend fun canSync(identity: AppIdentity): Boolean = true
        override suspend fun upsertDailyRecord(payload: SyncPayload): RemoteSyncResult = RemoteSyncResult.RetryableFailure("network_timeout")
        override suspend fun upsertMeal(payload: SyncPayload): RemoteSyncResult = RemoteSyncResult.RetryableFailure("network_timeout")
        override suspend fun upsertFoodEntry(payload: SyncPayload): RemoteSyncResult = RemoteSyncResult.RetryableFailure("network_timeout")
        override suspend fun upsertWeightRecord(payload: SyncPayload): RemoteSyncResult = RemoteSyncResult.RetryableFailure("network_timeout")
        override suspend fun softDeleteRecord(payload: SyncPayload): RemoteSyncResult = RemoteSyncResult.RetryableFailure("network_timeout")
    }

    private class CountingCoordinator : SyncCoordinator {
        var runCount: Int = 0
            private set

        override suspend fun syncPending() = runOnce()

        override suspend fun runOnce() {
            runCount += 1
            delay(50L)
        }
    }

    private companion object {
        private const val LOCAL_OWNER_ID = "local-owner-id"
    }
}
