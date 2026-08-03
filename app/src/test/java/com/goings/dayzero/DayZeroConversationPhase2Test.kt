package com.goings.dayzero

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.goings.dayzero.data.local.database.DayZeroDatabase
import com.goings.dayzero.data.remote.api.AiDraftApiService
import com.goings.dayzero.data.remote.dto.AiDraftRequestDto
import com.goings.dayzero.data.remote.dto.AiDraftResponseDto
import com.goings.dayzero.data.remote.dto.AiSummaryRequestDto
import com.goings.dayzero.data.remote.dto.AiSummaryResponseDto
import com.goings.dayzero.data.remote.dto.IntentClassificationResultDto
import com.goings.dayzero.data.remote.dto.IntentClassifierRequestDto
import com.goings.dayzero.data.repository.FakeAiDraftRepository
import com.goings.dayzero.data.repository.RemoteAiDraftRepository
import com.goings.dayzero.data.sync.DayZeroSyncConstants
import com.goings.dayzero.data.sync.chat.ChatBackfillCoordinator
import com.goings.dayzero.data.sync.chat.ChatBackfillStateStore
import com.goings.dayzero.data.sync.chat.ChatSyncQueueContract
import com.goings.dayzero.data.sync.chat.ChatSyncQueueWriter
import com.goings.dayzero.data.sync.title.ConversationTitleSyncContract
import com.goings.dayzero.data.telemetry.AiLatencyTraceLogger
import com.goings.dayzero.domain.identity.AppIdentity
import com.goings.dayzero.domain.identity.CurrentIdentityProvider
import com.goings.dayzero.domain.model.DailyRecord
import com.goings.dayzero.domain.model.MealType
import com.goings.dayzero.domain.model.RecordStatus
import com.goings.dayzero.domain.model.ai.AiChatMessage
import com.goings.dayzero.domain.model.ai.ChatRole
import com.goings.dayzero.domain.model.ai.Conversation
import com.goings.dayzero.domain.model.ai.assistant.AiAssistantRequest
import com.goings.dayzero.domain.model.ai.assistant.AiAssistantTurn
import com.goings.dayzero.domain.model.ai.assistant.AiIntent
import com.goings.dayzero.domain.model.ai.assistant.DebugChoiceCardPayload
import com.goings.dayzero.domain.model.ai.assistant.DebugChoiceOption
import com.goings.dayzero.domain.repository.AiAssistantRepository
import com.goings.dayzero.domain.repository.ConversationRepository
import com.goings.dayzero.domain.repository.RecordRepository
import com.goings.dayzero.domain.time.CurrentDateProvider
import com.goings.dayzero.domain.usecase.ClearLocalDataUseCase
import com.goings.dayzero.domain.usecase.ConfirmFoodRecordUseCase
import com.goings.dayzero.domain.usecase.CreateConversationWithFirstMessageUseCase
import com.goings.dayzero.domain.usecase.ObserveConversationMediaUseCase
import com.goings.dayzero.domain.usecase.ImportLocalMediaUseCase
import com.goings.dayzero.domain.usecase.RetryLocalMediaImportUseCase
import com.goings.dayzero.domain.usecase.DiscardStagedMediaUseCase
import com.goings.dayzero.domain.usecase.SendUserMessageWithMediaUseCase
import com.goings.dayzero.domain.repository.ChatMediaTransactionRepository
import com.goings.dayzero.ui.screens.AiRecordConversationEvent
import com.goings.dayzero.ui.screens.AiRecordViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DayZeroConversationPhase2Test {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private lateinit var context: Context
    private lateinit var database: DayZeroDatabase
    private lateinit var repository: RemoteAiDraftRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, DayZeroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        context.getSharedPreferences("dayzero_chat_backfill", Context.MODE_PRIVATE).edit().clear().commit()
        repository = RemoteAiDraftRepository(FakeAiDraftApiService(), database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun blankFirstMessageDoesNotCreateConversation() = runTest(mainDispatcherRule.testDispatcher) {
        val id = repository.createConversationWithFirstMessage("   ")

        assertNull(id)
        assertEquals(0, database.conversationDao().getConversationCountIncludingDeleted())
    }

    @Test
    fun createsConversationAndFirstMessageAtomically() = runTest(mainDispatcherRule.testDispatcher) {
        val now = Instant.parse("2026-06-18T02:00:00Z").toEpochMilli()
        val conversationId = repository.createConversationWithFirstMessage("  lunch egg rice  ", now)

        assertNotNull(conversationId)
        val conversation = database.conversationDao().getConversationById(conversationId!!)
        val messages = database.aiChatMessageDao().getMessagesByConversationId(conversationId)
        assertNotNull(conversation)
        assertEquals("lunch egg rice", conversation?.title)
        assertEquals("lunch egg rice", conversation?.lastMessagePreview)
        assertEquals(now, conversation?.createdAt)
        assertEquals(1, messages.size)
        assertEquals(conversationId, messages.single().conversationId)
        assertEquals("lunch egg rice", messages.single().text)
    }

    @Test
    fun createsConversationAndFirstMessageEnqueuesChatSyncTasks() = runTest(mainDispatcherRule.testDispatcher) {
        val syncingRepository = syncingRepository()
        val now = Instant.parse("2026-06-18T02:00:00Z").toEpochMilli()

        val conversationId = syncingRepository.createConversationWithFirstMessage("sync lunch", now)!!

        val tasks = database.syncQueueDao().getTasksByStatus(DayZeroSyncConstants.STATUS_PENDING)
        assertEquals(3, tasks.size)
        assertTrue(tasks.any {
            it.entityType == ChatSyncQueueContract.ENTITY_CONVERSATION &&
                it.entityLocalId == conversationId &&
                it.operation == ChatSyncQueueContract.OP_UPSERT_CONVERSATION
        })
        assertTrue(tasks.any {
            it.entityType == ChatSyncQueueContract.ENTITY_MESSAGE &&
                it.operation == ChatSyncQueueContract.OP_UPSERT_MESSAGE
        })
        assertTrue(tasks.any {
            it.entityType == ConversationTitleSyncContract.ENTITY_TITLE_JOB &&
                it.entityLocalId == conversationId &&
                it.operation == ConversationTitleSyncContract.OP_SUBMIT_TITLE_JOB
        })
    }

    @Test
    fun emptyAssistantPlaceholderDoesNotEnqueueMessage() = runTest(mainDispatcherRule.testDispatcher) {
        val syncingRepository = syncingRepository()
        val conversationId = syncingRepository.createConversationWithFirstMessage("sync lunch")!!
        val pendingBefore = database.syncQueueDao().getTasksByStatus(DayZeroSyncConstants.STATUS_PENDING).size

        syncingRepository.insertChatMessage(
            conversationId,
            AiChatMessage(
                id = "assistant-placeholder",
                conversationId = conversationId,
                role = ChatRole.Assistant,
                text = ""
            )
        )

        val pending = database.syncQueueDao().getTasksByStatus(DayZeroSyncConstants.STATUS_PENDING)
        assertEquals(pendingBefore, pending.size)
        assertTrue(pending.none { it.entityLocalId == "assistant-placeholder" })
    }

    @Test
    fun assistantFinalAndCardUpdatesCoalesceSameMessageTask() = runTest(mainDispatcherRule.testDispatcher) {
        val syncingRepository = syncingRepository()
        val conversationId = syncingRepository.createConversationWithFirstMessage("sync lunch")!!
        val messageId = "assistant-final"
        syncingRepository.insertChatMessage(
            conversationId,
            AiChatMessage(id = messageId, conversationId = conversationId, role = ChatRole.Assistant, text = "")
        )

        syncingRepository.updateChatMessage(
            AiChatMessage(id = messageId, conversationId = conversationId, role = ChatRole.Assistant, text = "final")
        )
        syncingRepository.updateChatMessage(
            AiChatMessage(
                id = messageId,
                conversationId = conversationId,
                role = ChatRole.Assistant,
                text = "final edited",
                assistantCards = listOf(
                    DebugChoiceCardPayload(
                        id = "choice-1",
                        title = "Pick",
                        message = "Pick",
                        options = listOf(DebugChoiceOption("confirm", "Confirm"))
                    )
                )
            )
        )

        val messageTasks = database.syncQueueDao().getTasksByStatus(DayZeroSyncConstants.STATUS_PENDING)
            .filter { it.entityType == ChatSyncQueueContract.ENTITY_MESSAGE && it.entityLocalId == messageId }
        assertEquals(1, messageTasks.size)
        assertTrue(messageTasks.single().payloadJson.contains("final edited"))
        assertTrue(messageTasks.single().payloadJson.contains("assistantCardsJson"))
    }

    @Test
    fun chatBackfillEnqueuesConversationsBeforeMessagesAndSkipsPlaceholders() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = "conversation-backfill"
        val now = Instant.parse("2026-06-18T02:00:00Z").toEpochMilli()
        database.conversationDao().insertConversation(
            com.goings.dayzero.data.local.entity.ConversationEntity(
                id = conversationId,
                conversationDate = "2026-06-18",
                title = "Backfill",
                lastMessagePreview = "Backfill",
                createdAt = now,
                updatedAt = now,
                lastActivityAt = now
            )
        )
        database.aiChatMessageDao().insertMessage(
            com.goings.dayzero.data.local.entity.AiChatMessageEntity(
                id = "placeholder",
                conversationId = conversationId,
                role = ChatRole.Assistant.name,
                text = "",
                createdAt = now + 1,
                relatedDraftId = null,
                messageType = "Text"
            )
        )
        database.aiChatMessageDao().insertMessage(
            com.goings.dayzero.data.local.entity.AiChatMessageEntity(
                id = "final-message",
                conversationId = conversationId,
                role = ChatRole.Assistant.name,
                text = "final",
                createdAt = now + 2,
                relatedDraftId = null,
                messageType = "Text"
            )
        )
        val coordinator = ChatBackfillCoordinator(
            conversationDao = database.conversationDao(),
            messageDao = database.aiChatMessageDao(),
            identityProvider = StaticChatIdentityProvider(),
            stateStore = ChatBackfillStateStore(context),
            queueWriter = ChatSyncQueueWriter(database.syncQueueDao())
        )

        val stats = coordinator.runOnce()

        assertEquals(1, stats.enqueuedConversationCount)
        assertEquals(1, stats.enqueuedMessageCount)
        assertEquals(1, stats.skippedPlaceholderCount)
        val runnable = database.syncQueueDao().getRunnableTasks(now = System.currentTimeMillis(), limit = 10)
        assertEquals(ChatSyncQueueContract.OP_UPSERT_CONVERSATION, runnable.first().operation)
        assertTrue(runnable.none { it.entityLocalId == "placeholder" })
    }

    @Test
    fun sameDayCanCreateMultipleConversationsWithDistinctIds() = runTest(mainDispatcherRule.testDispatcher) {
        val now = Instant.parse("2026-06-18T02:00:00Z").toEpochMilli()
        val first = repository.createConversationWithFirstMessage("first", now)
        val second = repository.createConversationWithFirstMessage("second", now + 1_000L)

        assertNotEquals(first, second)
        assertEquals(2, database.conversationDao().getConversationCountIncludingDeleted())
        assertEquals(1, database.aiChatMessageDao().getMessagesByConversationId(first!!).size)
        assertEquals(1, database.aiChatMessageDao().getMessagesByConversationId(second!!).size)
    }

    @Test
    fun continuingConversationKeepsDateAndTitleButUpdatesPreviewAndActivity() = runTest(mainDispatcherRule.testDispatcher) {
        val start = Instant.parse("2026-06-18T12:00:00Z").toEpochMilli()
        val later = Instant.parse("2026-06-20T12:00:00Z").toEpochMilli()
        val conversationId = repository.createConversationWithFirstMessage("first title", start)!!

        repository.insertChatMessage(
            conversationId,
            AiChatMessage(
                conversationId = conversationId,
                role = ChatRole.Assistant,
                text = "later preview",
                createdAt = later
            )
        )

        val conversation = database.conversationDao().getConversationById(conversationId)
        assertEquals(LocalDate.of(2026, 6, 18).toString(), conversation?.conversationDate)
        assertEquals("first title", conversation?.title)
        assertEquals("later preview", conversation?.lastMessagePreview)
        assertEquals(later, conversation?.lastActivityAt)
    }

    @Test
    fun recentContextIsIsolatedByConversation() = runTest(mainDispatcherRule.testDispatcher) {
        val a = repository.createConversationWithFirstMessage("A first")!!
        val b = repository.createConversationWithFirstMessage("B first")!!
        repository.insertChatMessage(a, AiChatMessage(conversationId = a, role = ChatRole.Assistant, text = "A reply"))
        repository.insertChatMessage(b, AiChatMessage(conversationId = b, role = ChatRole.Assistant, text = "B reply"))

        val aContext = repository.getRecentChatMessages(a, 10)
        val bContext = repository.getRecentChatMessages(b, 10)

        assertTrue(aContext.all { it.conversationId == a })
        assertTrue(bContext.all { it.conversationId == b })
        assertEquals(listOf("A first", "A reply"), aContext.map { it.text })
        assertEquals(listOf("B first", "B reply"), bContext.map { it.text })
    }

    @Test
    fun asyncReplyReturnsToOriginalConversationAfterAnotherSend() = runTest(mainDispatcherRule.testDispatcher) {
        val aiDraftRepository = FakeAiDraftRepository()
        val assistantRepository = ControlledAssistantRepository()
        val viewModel = createDayZeroViewModel(aiDraftRepository, assistantRepository)

        viewModel.sendAiMessage("A")
        runCurrent()
        val aConversationId = viewModel.uiState.value.activeConversationId!!
        viewModel.sendAiMessage("B")
        runCurrent()
        val bConversationId = viewModel.uiState.value.activeConversationId!!

        assistantRepository.completeNext("reply to A")
        assistantRepository.completeNext("reply to B")
        advanceUntilIdle()

        val aMessages = aiDraftRepository.getRecentChatMessages(aConversationId, 10)
        val bMessages = aiDraftRepository.getRecentChatMessages(bConversationId, 10)
        assertEquals(listOf("A", "reply to A"), aMessages.filter { it.text.isNotBlank() }.map { it.text })
        assertEquals(listOf("B", "reply to B"), bMessages.filter { it.text.isNotBlank() }.map { it.text })
        assertEquals(1, aMessages.count { it.role == ChatRole.Assistant && it.text == "reply to A" })
    }

    @Test
    fun interactionResultUsesOriginalCardConversationEvenWhenActiveChanges() = runTest(mainDispatcherRule.testDispatcher) {
        val aiDraftRepository = FakeAiDraftRepository()
        val assistantRepository = ImmediateAssistantRepository("interaction reply")
        val viewModel = createDayZeroViewModel(aiDraftRepository, assistantRepository)
        val conversationA = aiDraftRepository.createConversationWithFirstMessage("A")!!
        aiDraftRepository.insertChatMessage(
            conversationA,
            AiChatMessage(
                conversationId = conversationA,
                role = ChatRole.Assistant,
                text = "choose",
                assistantCards = listOf(
                    DebugChoiceCardPayload(
                        id = "card-a",
                        title = "Pick",
                        message = "Pick",
                        options = listOf(DebugChoiceOption("record", "Record"))
                    )
                )
            )
        )
        val conversationB = aiDraftRepository.createConversationWithFirstMessage("B")!!
        viewModel.sendAiMessage("active B")
        advanceUntilIdle()
        assertNotEquals(conversationA, conversationB)

        viewModel.sendInteractionResult(
            interactionId = "card-a",
            actionType = "ask_record_intent_card",
            optionId = "record",
            optionLabel = "Record"
        )
        advanceUntilIdle()

        val aMessages = aiDraftRepository.getRecentChatMessages(conversationA, 10)
        val bMessages = aiDraftRepository.getRecentChatMessages(conversationB, 10)
        assertTrue(aMessages.any { it.text == "interaction reply" })
        assertTrue(bMessages.none { it.text == "interaction reply" })
        assertEquals("card-a", assistantRepository.lastRequest?.interactionResult?.interactionId)
        assertTrue(assistantRepository.lastRequest?.recentMessages?.all { it.conversationId == conversationA } == true)
    }

    @Test
    fun existingFirstMessageAssistantStartDoesNotDuplicateUserMessage() = runTest(mainDispatcherRule.testDispatcher) {
        val aiDraftRepository = FakeAiDraftRepository()
        val assistantRepository = ImmediateAssistantRepository("first reply")
        val viewModel = createDayZeroViewModel(aiDraftRepository, assistantRepository)
        val conversationId = aiDraftRepository.createConversationWithFirstMessage("first message")!!

        viewModel.startAssistantTurnForExistingUserMessage(conversationId, "first message")
        advanceUntilIdle()

        val messages = aiDraftRepository.getRecentChatMessages(conversationId, 10)
        assertEquals(1, messages.count { it.role == ChatRole.User && it.text == "first message" })
        assertEquals(1, messages.count { it.role == ChatRole.Assistant && it.text == "first reply" })
        assertTrue(assistantRepository.lastRequest?.recentMessages?.all { it.conversationId == conversationId } == true)
    }

    @Test
    fun featureViewModelObservesSelectedConversationAndEmitsCreateEventOnce() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationRepository = InMemoryConversationRepository()
        val aiDraftRepository = FakeAiDraftRepository()
        val useCase = CreateConversationWithFirstMessageUseCase(aiDraftRepository)
        val fakeMediaRepository = object : com.goings.dayzero.domain.repository.MediaRepository {
            override fun observeConversationMedia(conversationId: String): kotlinx.coroutines.flow.Flow<List<com.goings.dayzero.domain.model.media.MediaAsset>> = kotlinx.coroutines.flow.flowOf(emptyList())
            override suspend fun getConversationMedia(conversationId: String): List<com.goings.dayzero.domain.model.media.MediaAsset> = emptyList()
            override suspend fun getMediaByIds(ids: List<String>): List<com.goings.dayzero.domain.model.media.MediaAsset> = emptyList()
            override suspend fun createStagedMedia(requests: List<com.goings.dayzero.domain.model.media.NewMediaAssetRequest>, now: Long): List<com.goings.dayzero.domain.model.media.MediaAsset> = emptyList()
            override suspend fun attachMediaToMessage(mediaIds: List<String>, conversationId: String, messageId: String, now: Long) {}
            override suspend fun markMediaReady(id: String, conversationId: String, masterRelativePath: String, thumbnailRelativePath: String, mimeType: String, width: Int, height: Int, byteSize: Long, sha256: String, now: Long): com.goings.dayzero.domain.model.media.MediaAsset = mockAsset()
            override suspend fun markMediaFailed(id: String, conversationId: String, failureCode: String?, now: Long): com.goings.dayzero.domain.model.media.MediaAsset = mockAsset()
            override suspend fun softDeleteMedia(id: String, conversationId: String, now: Long) {}
            override suspend fun findStaleStagedMedia(updatedBefore: Long): List<com.goings.dayzero.domain.model.media.MediaAsset> = emptyList()
            private fun mockAsset() = com.goings.dayzero.domain.model.media.MediaAsset(
                id = "1", ownerLocalId = "1", conversationId = "1", sourceMessageId = null,
                conversationOrder = 0, masterRelativePath = null, thumbnailRelativePath = null,
                mimeType = null, width = null, height = null, byteSize = null, sha256 = null,
                source = com.goings.dayzero.domain.model.media.MediaSource.CAMERA, lifecycleState = com.goings.dayzero.domain.model.media.MediaLifecycleState.STAGED,
                failureCode = null, createdAt = 0L, updatedAt = 0L, deletedAt = null
            )
        }
        val fakeImportRepository = object : com.goings.dayzero.domain.repository.LocalMediaImportRepository {
            override suspend fun importStagedMedia(mediaId: String, request: com.goings.dayzero.domain.model.media.ImportLocalMediaRequest): com.goings.dayzero.domain.model.media.LocalMediaImportItemResult = com.goings.dayzero.domain.model.media.LocalMediaImportItemResult.Failed(mediaId, com.goings.dayzero.domain.model.media.MediaImportFailureCode.UNKNOWN)
            override suspend fun retryImport(mediaId: String): com.goings.dayzero.domain.model.media.LocalMediaImportItemResult = com.goings.dayzero.domain.model.media.LocalMediaImportItemResult.Failed(mediaId, com.goings.dayzero.domain.model.media.MediaImportFailureCode.UNKNOWN)
            override suspend fun discardStagedMedia(mediaId: String): Boolean = true
            override suspend fun cleanupStaleMedia(updatedBefore: Long): List<String> = emptyList()
        }
        val fakeIdGenerator = com.goings.dayzero.domain.usecase.MediaIdGenerator { "id" }
        val fakeCurrentIdentityProvider = object : com.goings.dayzero.domain.identity.CurrentIdentityProvider {
            override suspend fun currentIdentity() = com.goings.dayzero.domain.identity.AppIdentity("owner-default", null, "local", false)
        }
        val fakeSendMediaUseCase = SendUserMessageWithMediaUseCase(
            object : ChatMediaTransactionRepository {
                override suspend fun sendUserMessageWithMedia(request: com.goings.dayzero.domain.model.ai.SendUserMessageWithMediaRequest) =
                    com.goings.dayzero.domain.model.ai.SendUserMessageWithMediaResult.Committed(
                        userMessageId = request.userMessageId,
                        assistantPlaceholderId = "placeholder-${request.userMessageId}"
                    )
            }
        )
        val viewModel = AiRecordViewModel(
            conversationRepository = conversationRepository,
            aiDraftRepository = aiDraftRepository,
            createConversationWithFirstMessageUseCase = useCase,
            observeConversationMediaUseCase = ObserveConversationMediaUseCase(fakeMediaRepository),
            importLocalMediaUseCase = ImportLocalMediaUseCase(fakeMediaRepository, fakeImportRepository, fakeIdGenerator),
            retryLocalMediaImportUseCase = RetryLocalMediaImportUseCase(fakeMediaRepository, fakeImportRepository),
            discardStagedMediaUseCase = DiscardStagedMediaUseCase(fakeMediaRepository, fakeImportRepository),
            sendUserMessageWithMediaUseCase = fakeSendMediaUseCase,
            currentIdentityProvider = fakeCurrentIdentityProvider,
            networkAvailabilityProvider = com.goings.dayzero.domain.network.NetworkAvailabilityProvider { true },
            updateFoodCardPhotoAssignmentsUseCase = com.goings.dayzero.domain.usecase.UpdateFoodCardPhotoAssignmentsUseCase(
                object : com.goings.dayzero.domain.repository.FoodCardPhotoAssignmentRepository {
                    override suspend fun updatePhotoAssignments(
                        cardId: String,
                        assignments: List<com.goings.dayzero.domain.repository.MealPhotoAssignment>
                    ) = com.goings.dayzero.domain.repository.UpdateFoodCardPhotoAssignmentsResult.Unchanged
                }
            ),
            savedStateHandle = SavedStateHandle()
        )

        val conversation = Conversation(
            id = "conversation-1",
            conversationDate = LocalDate.of(2026, 6, 18),
            title = "A",
            lastMessagePreview = "A"
        )
        conversationRepository.insertConversation(conversation)
        aiDraftRepository.insertChatMessage("conversation-1", AiChatMessage(conversationId = "conversation-1", role = ChatRole.User, text = "A"))
        viewModel.openConversation("conversation-1")
        advanceUntilIdle()

        val selectedState = viewModel.uiState.value
        assertEquals("conversation-1", selectedState.detail.currentConversation?.id)
        assertEquals(listOf("A"), selectedState.detail.messages.map { it.text })

        val eventDeferred = async { viewModel.events.first() }
        viewModel.createConversationWithFirstMessage("new")
        advanceUntilIdle()
        val event = eventDeferred.await()
        assertTrue(event is AiRecordConversationEvent.ConversationCreated)
        assertEquals("new", (event as AiRecordConversationEvent.ConversationCreated).firstMessageText)
    }

    private fun createDayZeroViewModel(
        aiDraftRepository: FakeAiDraftRepository,
        aiAssistantRepository: AiAssistantRepository
    ): DayZeroViewModel {
        val recordRepository = InMemoryPhase2RecordRepository()
        val conversationRepository = InMemoryConversationRepository()
        return DayZeroViewModel(
            recordRepository = recordRepository,
            aiDraftRepository = aiDraftRepository,
            aiAssistantRepository = aiAssistantRepository,
            latencyLogger = AiLatencyTraceLogger(context),
            clearLocalDataUseCase = ClearLocalDataUseCase(recordRepository, aiDraftRepository),
            confirmFoodCardUseCase = testConfirmFoodCardUseCase(aiDraftRepository, conversationRepository, recordRepository),
            createConversationWithFirstMessageUseCase = CreateConversationWithFirstMessageUseCase(aiDraftRepository),
            conversationRepository = conversationRepository,
            currentDateProvider = FixedCurrentDateProvider(LocalDate.of(2026, 6, 20)),
            syncScheduler = object : com.goings.dayzero.data.sync.SyncScheduler {
                override fun requestSync(reason: com.goings.dayzero.data.sync.SyncTriggerReason): kotlinx.coroutines.Job? = null
                override fun requestBackfill(reason: com.goings.dayzero.data.sync.SyncTriggerReason): kotlinx.coroutines.Job? = null
                override fun requestSyncAndBackfill(reason: com.goings.dayzero.data.sync.SyncTriggerReason): kotlinx.coroutines.Job? = null
                override fun requestPull(reason: com.goings.dayzero.data.sync.SyncTriggerReason): kotlinx.coroutines.Job? = null
                override fun requestInitialRestore(reason: com.goings.dayzero.data.sync.SyncTriggerReason): kotlinx.coroutines.Job? = null
                override fun requestSyncAndPull(reason: com.goings.dayzero.data.sync.SyncTriggerReason): kotlinx.coroutines.Job? = null
            },
            visionAssistantTurnOrchestrator = com.goings.dayzero.assistant.fakeVisionAssistantTurnOrchestrator(context),
            networkAvailabilityProvider = com.goings.dayzero.domain.network.NetworkAvailabilityProvider { true }
        )
    }

    private fun syncingRepository(): RemoteAiDraftRepository {
        return RemoteAiDraftRepository(
            apiService = FakeAiDraftApiService(),
            database = database,
            syncQueueDao = database.syncQueueDao(),
            identityProvider = StaticChatIdentityProvider()
        )
    }

    private class StaticChatIdentityProvider : CurrentIdentityProvider {
        override suspend fun currentIdentity(): AppIdentity {
            return AppIdentity(
                localOwnerId = "test-owner",
                remoteUserId = "00000000-0000-0000-0000-000000000001",
                authProvider = "supabase_anonymous",
                canRemoteSync = true
            )
        }
    }

    private class ControlledAssistantRepository : AiAssistantRepository {
        private val pending = ArrayDeque<CompletableDeferred<AiAssistantTurn>>()

        override suspend fun sendMessage(request: AiAssistantRequest): AiAssistantTurn {
            val deferred = CompletableDeferred<AiAssistantTurn>()
            pending.addLast(deferred)
            return deferred.await()
        }

        override suspend fun streamMessage(
            request: AiAssistantRequest,
            onDelta: suspend (String) -> Unit
        ): AiAssistantTurn {
            val turn = sendMessage(request)
            onDelta(turn.replyText)
            return turn
        }

        fun completeNext(reply: String) {
            pending.removeFirst().complete(turn(reply))
        }
    }

    private class ImmediateAssistantRepository(private val reply: String) : AiAssistantRepository {
        var lastRequest: AiAssistantRequest? = null
            private set

        override suspend fun sendMessage(request: AiAssistantRequest): AiAssistantTurn {
            lastRequest = request
            return turn(reply)
        }

        override suspend fun streamMessage(
            request: AiAssistantRequest,
            onDelta: suspend (String) -> Unit
        ): AiAssistantTurn {
            lastRequest = request
            onDelta(reply)
            return turn(reply)
        }
    }

    private class InMemoryConversationRepository : ConversationRepository {
        private val conversations = MutableStateFlow<List<Conversation>>(emptyList())

        override suspend fun insertConversation(conversation: Conversation) {
            conversations.update { current -> current.filterNot { it.id == conversation.id } + conversation }
        }

        override suspend fun getConversationById(id: String): Conversation? {
            return conversations.value.find { it.id == id }
        }

        override fun observeConversations(): Flow<List<Conversation>> = conversations.asStateFlow()

        override fun observeConversationsByLastActivity(): Flow<List<Conversation>> = conversations.asStateFlow()

        override suspend fun updateConversationSummary(
            id: String,
            title: String,
            lastMessagePreview: String,
            lastActivityAt: Long,
            updatedAt: Long
        ) {
            conversations.update { current ->
                current.map {
                    if (it.id == id) {
                        it.copy(
                            title = title,
                            lastMessagePreview = lastMessagePreview,
                            lastActivityAt = lastActivityAt,
                            updatedAt = updatedAt
                        )
                    } else {
                        it
                    }
                }
            }
        }

        override suspend fun softDeleteConversation(id: String, deletedAt: Long) {
            conversations.update { current -> current.map { if (it.id == id) it.copy(deletedAt = deletedAt) else it } }
        }
    }

    private class FixedCurrentDateProvider(private val date: LocalDate) : CurrentDateProvider {
        override fun currentDate(): LocalDate = date
    }

    private class InMemoryPhase2RecordRepository : RecordRepository {
        override fun observeRecords(): Flow<List<DailyRecord>> = MutableStateFlow(emptyList<DailyRecord>()).asStateFlow()
        override suspend fun upsertRecord(record: DailyRecord) = Unit
        override suspend fun deleteRecordById(recordId: String) = Unit
        override suspend fun getRecordById(recordId: String): DailyRecord? = null
        override suspend fun getRecordByDateAndStatus(date: LocalDate, status: RecordStatus): DailyRecord? = null
        override suspend fun updateRecordStatus(recordId: String, status: RecordStatus, weightKg: Float?) = Unit
        override suspend fun deleteFoodFromRecord(recordId: String, mealType: MealType, foodId: String) = Unit
        override suspend fun clearAllRecords() = Unit
    }

    private class FakeAiDraftApiService : AiDraftApiService {
        override suspend fun generateDraft(request: AiDraftRequestDto): AiDraftResponseDto = error("unused")
        override suspend fun generateDailySummary(request: AiSummaryRequestDto): AiSummaryResponseDto = error("unused")
        override suspend fun classifyUserIntent(request: IntentClassifierRequestDto): IntentClassificationResultDto = error("unused")
        override suspend fun sendAssistantTurnV2WithResponse(
            request: com.goings.dayzero.data.remote.dto.assistant.AiAssistantRequestDto
        ): Response<com.goings.dayzero.data.remote.dto.assistant.AssistantTurnV2ResponseDto> = error("unused")
    }

    private companion object {
        fun turn(reply: String): AiAssistantTurn {
            return AiAssistantTurn(
                id = "turn-${reply.hashCode()}",
                intent = AiIntent.GeneralChat,
                replyText = reply,
                cards = emptyList(),
                suggestedReplies = emptyList()
            )
        }
    }
}
