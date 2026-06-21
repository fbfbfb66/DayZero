package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.DayZeroDatabase
import com.example.data.local.mapper.DailyRecordMapper
import com.example.data.local.entity.SyncQueueEntity
import com.example.data.identity.SupabaseAuthSession
import com.example.data.identity.SupabaseAuthSessionProvider
import com.example.data.repository.RoomRecordRepository
import com.example.data.sync.BackfillCoordinator
import com.example.data.sync.BackfillStateStore
import com.example.data.sync.DayZeroSyncConstants
import com.example.data.sync.LocalFirstSyncCoordinator
import com.example.data.sync.RemoteSyncGateway
import com.example.data.sync.RemoteSyncResult
import com.example.data.sync.SupabaseRemoteSyncGateway
import com.example.data.sync.InProcessSyncScheduler
import com.example.data.sync.PullCoordinator
import com.example.data.sync.PullStateStore
import com.example.data.sync.PullStatus
import com.example.data.sync.RemotePullGateway
import com.example.data.sync.RemotePullResult
import com.example.data.sync.SyncCoordinator
import com.example.data.sync.SyncHealthReporter
import com.example.data.sync.SyncPayload
import com.example.data.sync.SyncTriggerReason
import com.example.data.sync.remote.DailyRecordRemoteDto
import com.example.data.sync.remote.FoodEntryRemoteDto
import com.example.data.sync.remote.MealRemoteDto
import com.example.data.sync.remote.WeightRecordRemoteDto
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
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
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
        context.getSharedPreferences("dayzero_pull", Context.MODE_PRIVATE)
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
    fun supabasePushUsesCanonicalRemoteFields() = runTest {
        val interceptor = CapturingInterceptor()
        val gateway = SupabaseRemoteSyncGateway(
            okHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
            sessionProvider = StaticSupabaseSessionProvider(),
            supabaseUrl = "https://example.supabase.co",
            anonKey = "anon-key",
            isConfigured = true
        )

        gateway.upsertFoodEntry(
            SyncPayload(
                queueId = "queue-1",
                entityType = "food_entry",
                entityLocalId = "food-1",
                operation = DayZeroSyncConstants.OP_UPSERT_FOOD_ENTRY,
                ownerLocalId = LOCAL_OWNER_ID,
                body = JSONObject()
                    .put("clientId", "food-1")
                    .put("mealClientId", "meal-1")
                    .put("dailyRecordClientId", "record-1")
                    .put("name", "egg rice roll")
                    .put("quantity", "1 serving")
                    .put("estimatedCalories", 320)
                    .put("confidence", "high")
                    .put("schemaVersion", 1)
            )
        )

        val body = JSONObject(interceptor.lastBody)
        assertEquals("food-1", body.getString("client_id"))
        assertEquals("meal-1", body.getString("meal_client_id"))
        assertEquals("1 serving", body.getString("amount_text"))
        assertEquals(320, body.getInt("calories"))
        assertTrue(body.isNull("confidence"))
        assertFalse(body.has("quantity"))
        assertFalse(body.has("estimated_calories"))
        assertFalse(body.has("daily_record_client_id"))
        assertFalse(body.has("meal_id"))
        assertFalse(body.has("daily_record_id"))
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

    @Test
    fun initialRestoreWritesRemoteRecordsWithoutCreatingPushQueue() = runTest {
        val pullCoordinator = createPullCoordinator(
            gateway = FakePullGateway(
                dailyRecords = listOf(remoteDailyRecord("remote-record-1")),
                meals = listOf(remoteMeal("remote-meal-1", "remote-record-1")),
                foods = listOf(remoteFood("remote-food-1", "remote-meal-1", "remote-record-1")),
                weights = listOf(remoteWeight("remote-weight-1", "remote-record-1"))
            )
        )

        val stats = pullCoordinator.runInitialRestoreIfLocalEmpty()

        assertEquals(1, stats.appliedCount)
        assertEquals(0, database.syncQueueDao().getPendingCount())
        val restored = database.dailyRecordDao().getRecordByClientIdIncludingDeleted("remote-record-1")
        require(restored != null)
        assertEquals(DayZeroSyncConstants.STATUS_SYNCED, restored.syncStatus)
        assertEquals(71.2f, restored.weightKg)
        assertEquals(1, mapper.toDomain(restored).meals.size)
        assertEquals(1, mapper.toDomain(restored).meals.first().foods.size)
    }

    @Test
    fun pullDoesNotOverwriteDirtyLocalRecord() = runTest {
        val dirty = mapper.toEntity(sampleConfirmedRecord().copy(id = "remote-record-1"), LOCAL_OWNER_ID)
            .copy(syncStatus = DayZeroSyncConstants.STATUS_PENDING, lastSyncedAt = null)
        database.dailyRecordDao().upsertRecord(dirty)
        val pullCoordinator = createPullCoordinator(
            gateway = FakePullGateway(
                dailyRecords = listOf(remoteDailyRecord("remote-record-1", updatedAt = dirty.updatedAt + 60_000L))
            )
        )

        val stats = pullCoordinator.runOnce(com.example.data.sync.PullMode.INCREMENTAL)

        assertEquals(1, stats.conflictCount)
        val after = database.dailyRecordDao().getRecordByClientIdIncludingDeleted("remote-record-1")
        require(after != null)
        assertEquals(DayZeroSyncConstants.STATUS_PENDING, after.syncStatus)
    }

    @Test
    fun pullDoesNotOverwriteSyncedLocalRecordWithActiveQueueTask() = runTest {
        val local = mapper.toEntity(sampleConfirmedRecord().copy(id = "remote-record-1"), LOCAL_OWNER_ID)
            .copy(
                syncStatus = DayZeroSyncConstants.STATUS_SYNCED,
                lastSyncedAt = 1_000L,
                updatedAt = 1_000L,
                aiSummary = "local queued summary"
            )
        database.dailyRecordDao().upsertRecord(local)
        database.syncQueueDao().insert(goodQueueTask("remote-record-1", 1_500L))
        val pullCoordinator = createPullCoordinator(
            gateway = FakePullGateway(
                dailyRecords = listOf(remoteDailyRecord("remote-record-1", updatedAt = 2_000L))
            )
        )

        val stats = pullCoordinator.runOnce(com.example.data.sync.PullMode.INCREMENTAL)

        assertEquals(1, stats.conflictCount)
        val after = database.dailyRecordDao().getRecordByClientIdIncludingDeleted("remote-record-1")
        require(after != null)
        assertEquals("local queued summary", after.aiSummary)
        assertEquals(1, database.syncQueueDao().countByStatus(DayZeroSyncConstants.STATUS_PENDING))
    }

    @Test
    fun pullAppliesRemoteSoftDeleteToSyncedLocalRecord() = runTest {
        val local = mapper.toEntity(sampleConfirmedRecord().copy(id = "remote-record-1"), LOCAL_OWNER_ID)
            .copy(
                syncStatus = DayZeroSyncConstants.STATUS_SYNCED,
                lastSyncedAt = 1_000L,
                updatedAt = 1_000L
            )
        database.dailyRecordDao().upsertRecord(local)
        val pullCoordinator = createPullCoordinator(
            gateway = FakePullGateway(
                dailyRecords = listOf(remoteDailyRecord("remote-record-1", updatedAt = 2_000L, deletedAt = 2_000L))
            )
        )

        val stats = pullCoordinator.runOnce(com.example.data.sync.PullMode.INCREMENTAL)

        assertEquals(1, stats.appliedCount)
        assertEquals(null, database.dailyRecordDao().getRecordById("remote-record-1"))
        val deleted = database.dailyRecordDao().getRecordByClientIdIncludingDeleted("remote-record-1")
        require(deleted != null)
        assertEquals(2_000L, deleted.deletedAt)
        assertEquals(DayZeroSyncConstants.STATUS_SYNCED, deleted.syncStatus)
    }

    @Test
    fun pullPartialFailureDoesNotAdvanceFailedTableCursorOrWritePartialAggregate() = runTest {
        val stateStore = PullStateStore(context)
        val pullCoordinator = createPullCoordinator(
            gateway = FakePullGateway(
                dailyRecords = listOf(remoteDailyRecord("remote-record-1")),
                mealsFailure = RemotePullResult.RetryableFailure("network_timeout")
            ),
            stateStore = stateStore
        )

        val stats = pullCoordinator.runOnce(com.example.data.sync.PullMode.INCREMENTAL)
        val state = stateStore.snapshot()

        assertEquals(1, stats.partialFailureCount)
        assertEquals(0, stats.appliedCount)
        assertEquals(PullStatus.FAILED_RETRYABLE, state.status)
        assertEquals(1, state.partialFailureCount)
        assertEquals(null, state.dailyRecordsCursorUpdatedAt)
        assertEquals(null, state.mealsCursorUpdatedAt)
        assertEquals(null, database.dailyRecordDao().getRecordByClientIdIncludingDeleted("remote-record-1"))
    }

    @Test
    fun pullDetectsSameUpdatedAtCursorRiskWithoutAdvancingCursor() = runTest {
        val stateStore = PullStateStore(context)
        val pullCoordinator = createPullCoordinator(
            gateway = FakePullGateway(
                dailyRecords = listOf(
                    remoteDailyRecord("remote-record-1", updatedAt = 2_000L),
                    remoteDailyRecord("remote-record-2", updatedAt = 2_000L),
                    remoteDailyRecord("remote-record-3", updatedAt = 2_000L)
                )
            ),
            stateStore = stateStore,
            batchLimit = 2
        )

        val stats = pullCoordinator.runOnce(com.example.data.sync.PullMode.INCREMENTAL)
        val state = stateStore.snapshot()

        assertEquals(1, stats.partialFailureCount)
        assertEquals(2, stats.pulledDailyRecordCount)
        assertEquals(PullStatus.FAILED_RETRYABLE, state.status)
        assertTrue(state.lastError.orEmpty().contains("same_updated_at_cursor_risk"))
        assertEquals(null, state.dailyRecordsCursorUpdatedAt)
    }

    @Test
    fun pullCountsMissingParentRowsWithoutCrashing() = runTest {
        val stateStore = PullStateStore(context)
        val pullCoordinator = createPullCoordinator(
            gateway = FakePullGateway(
                meals = listOf(remoteMeal("remote-meal-1", "missing-record")),
                foods = listOf(remoteFood("remote-food-1", "missing-meal", "missing-record"))
            ),
            stateStore = stateStore
        )

        val stats = pullCoordinator.runOnce(com.example.data.sync.PullMode.INCREMENTAL)
        val state = stateStore.snapshot()

        assertEquals(2, stats.skippedMissingParentCount)
        assertEquals(PullStatus.COMPLETED, state.status)
        assertEquals(2, state.skippedMissingParentCount)
        assertEquals(0, database.dailyRecordDao().countBusinessRecordsIncludingDeleted())
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

    private fun createPullCoordinator(
        gateway: RemotePullGateway,
        stateStore: PullStateStore = PullStateStore(context),
        batchLimit: Int = 100
    ): PullCoordinator {
        return PullCoordinator(
            database = database,
            remotePullGateway = gateway,
            identityProvider = StaticIdentityProvider(canRemoteSync = true),
            stateStore = stateStore,
            batchLimit = batchLimit
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

    private fun remoteDailyRecord(
        clientId: String,
        updatedAt: Long = 2_000L,
        deletedAt: Long? = null
    ): DailyRecordRemoteDto {
        return DailyRecordRemoteDto(
            userId = "remote-user-id",
            clientId = clientId,
            localDate = "2026-06-19",
            timezone = null,
            note = null,
            createdAt = 1_000L,
            updatedAt = updatedAt,
            deletedAt = deletedAt,
            schemaVersion = 1
        )
    }

    private fun remoteMeal(clientId: String, recordClientId: String): MealRemoteDto {
        return MealRemoteDto(
            userId = "remote-user-id",
            clientId = clientId,
            dailyRecordClientId = recordClientId,
            mealType = MealType.Lunch.name,
            loggedAt = null,
            displayOrder = null,
            createdAt = 1_100L,
            updatedAt = 2_100L,
            deletedAt = null,
            schemaVersion = 1
        )
    }

    private fun remoteFood(clientId: String, mealClientId: String, recordClientId: String): FoodEntryRemoteDto {
        return FoodEntryRemoteDto(
            userId = "remote-user-id",
            clientId = clientId,
            mealClientId = mealClientId,
            name = "egg rice roll",
            amountText = "1 serving",
            grams = null,
            calories = 320f,
            proteinG = null,
            carbsG = null,
            fatG = null,
            confidence = null,
            source = "confirm_card",
            createdAt = 1_200L,
            updatedAt = 2_200L,
            deletedAt = null,
            schemaVersion = 1
        )
    }

    private fun remoteWeight(clientId: String, recordClientId: String): WeightRecordRemoteDto {
        return WeightRecordRemoteDto(
            userId = "remote-user-id",
            clientId = clientId,
            localDate = "2026-06-19",
            measuredAt = null,
            weightKg = 71.2f,
            source = "confirm_card",
            createdAt = 1_300L,
            updatedAt = 2_300L,
            deletedAt = null,
            schemaVersion = 1
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
        override suspend fun upsertChatConversation(payload: SyncPayload): RemoteSyncResult = RemoteSyncResult.Success
        override suspend fun upsertChatMessage(payload: SyncPayload): RemoteSyncResult = RemoteSyncResult.Success
    }

    private class AlwaysRetryableGateway : RemoteSyncGateway {
        override suspend fun canSync(identity: AppIdentity): Boolean = true
        override suspend fun upsertDailyRecord(payload: SyncPayload): RemoteSyncResult = RemoteSyncResult.RetryableFailure("network_timeout")
        override suspend fun upsertMeal(payload: SyncPayload): RemoteSyncResult = RemoteSyncResult.RetryableFailure("network_timeout")
        override suspend fun upsertFoodEntry(payload: SyncPayload): RemoteSyncResult = RemoteSyncResult.RetryableFailure("network_timeout")
        override suspend fun upsertWeightRecord(payload: SyncPayload): RemoteSyncResult = RemoteSyncResult.RetryableFailure("network_timeout")
        override suspend fun softDeleteRecord(payload: SyncPayload): RemoteSyncResult = RemoteSyncResult.RetryableFailure("network_timeout")
        override suspend fun upsertChatConversation(payload: SyncPayload): RemoteSyncResult = RemoteSyncResult.RetryableFailure("network_timeout")
        override suspend fun upsertChatMessage(payload: SyncPayload): RemoteSyncResult = RemoteSyncResult.RetryableFailure("network_timeout")
    }

    private class StaticSupabaseSessionProvider : SupabaseAuthSessionProvider {
        override suspend fun currentSessionOrNull(): SupabaseAuthSession {
            return SupabaseAuthSession(
                userId = "00000000-0000-0000-0000-000000000001",
                accessToken = "access-token",
                refreshToken = null,
                expiresAtEpochSeconds = Long.MAX_VALUE
            )
        }
        override suspend fun forceRefreshSession(): SupabaseAuthSession? {
            return currentSessionOrNull()
        }
    }

    private class CapturingInterceptor : Interceptor {
        var lastBody: String = ""
            private set

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            lastBody = buffer.readUtf8()
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(201)
                .message("Created")
                .body("".toResponseBody())
                .build()
        }
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

    private class FakePullGateway(
        private val dailyRecords: List<DailyRecordRemoteDto> = emptyList(),
        private val meals: List<MealRemoteDto> = emptyList(),
        private val foods: List<FoodEntryRemoteDto> = emptyList(),
        private val weights: List<WeightRecordRemoteDto> = emptyList(),
        private val dailyRecordsFailure: RemotePullResult<DailyRecordRemoteDto>? = null,
        private val mealsFailure: RemotePullResult<MealRemoteDto>? = null,
        private val foodsFailure: RemotePullResult<FoodEntryRemoteDto>? = null,
        private val weightsFailure: RemotePullResult<WeightRecordRemoteDto>? = null
    ) : RemotePullGateway {
        override suspend fun canPull(identity: AppIdentity): Boolean = identity.canRemoteSync

        override suspend fun pullDailyRecords(
            sinceUpdatedAt: Long?,
            limit: Int
        ): RemotePullResult<DailyRecordRemoteDto> {
            dailyRecordsFailure?.let { return it }
            val filtered = dailyRecords.filterSince(sinceUpdatedAt)
            return RemotePullResult.Success(filtered.take(limit), filtered.size > limit)
        }

        override suspend fun pullMeals(
            sinceUpdatedAt: Long?,
            limit: Int
        ): RemotePullResult<MealRemoteDto> {
            mealsFailure?.let { return it }
            val filtered = meals.filterSince(sinceUpdatedAt)
            return RemotePullResult.Success(filtered.take(limit), filtered.size > limit)
        }

        override suspend fun pullFoodEntries(
            sinceUpdatedAt: Long?,
            limit: Int
        ): RemotePullResult<FoodEntryRemoteDto> {
            foodsFailure?.let { return it }
            val filtered = foods.filterSince(sinceUpdatedAt)
            return RemotePullResult.Success(filtered.take(limit), filtered.size > limit)
        }

        override suspend fun pullWeightRecords(
            sinceUpdatedAt: Long?,
            limit: Int
        ): RemotePullResult<WeightRecordRemoteDto> {
            weightsFailure?.let { return it }
            val filtered = weights.filterSince(sinceUpdatedAt)
            return RemotePullResult.Success(filtered.take(limit), filtered.size > limit)
        }

        private fun <T> List<T>.filterSince(sinceUpdatedAt: Long?): List<T> {
            if (sinceUpdatedAt == null) return this
            return filter {
                when (it) {
                    is DailyRecordRemoteDto -> it.updatedAt > sinceUpdatedAt
                    is MealRemoteDto -> it.updatedAt > sinceUpdatedAt
                    is FoodEntryRemoteDto -> it.updatedAt > sinceUpdatedAt
                    is WeightRecordRemoteDto -> it.updatedAt > sinceUpdatedAt
                    else -> false
                }
            }
        }
    }

    private companion object {
        private const val LOCAL_OWNER_ID = "local-owner-id"
    }
}
