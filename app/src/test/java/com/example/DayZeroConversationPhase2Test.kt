package com.example

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.DayZeroDatabase
import com.example.data.remote.api.AiDraftApiService
import com.example.data.remote.dto.AiDraftRequestDto
import com.example.data.remote.dto.AiDraftResponseDto
import com.example.data.remote.dto.AiSummaryRequestDto
import com.example.data.remote.dto.AiSummaryResponseDto
import com.example.data.remote.dto.IntentClassificationResultDto
import com.example.data.remote.dto.IntentClassifierRequestDto
import com.example.data.repository.FakeAiDraftRepository
import com.example.data.repository.RemoteAiDraftRepository
import com.example.data.telemetry.AiLatencyTraceLogger
import com.example.domain.model.DailyRecord
import com.example.domain.model.MealType
import com.example.domain.model.RecordStatus
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.ChatRole
import com.example.domain.model.ai.Conversation
import com.example.domain.model.ai.assistant.AiAssistantRequest
import com.example.domain.model.ai.assistant.AiAssistantTurn
import com.example.domain.model.ai.assistant.AiIntent
import com.example.domain.model.ai.assistant.DebugChoiceCardPayload
import com.example.domain.model.ai.assistant.DebugChoiceOption
import com.example.domain.repository.AiAssistantRepository
import com.example.domain.repository.ConversationRepository
import com.example.domain.repository.RecordRepository
import com.example.domain.usecase.ClearLocalDataUseCase
import com.example.domain.usecase.ConfirmFoodRecordUseCase
import com.example.domain.usecase.CreateConversationWithFirstMessageUseCase
import com.example.ui.screens.AiRecordConversationEvent
import com.example.ui.screens.AiRecordViewModel
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
        val start = Instant.parse("2026-06-18T02:00:00Z").toEpochMilli()
        val later = Instant.parse("2026-06-20T02:00:00Z").toEpochMilli()
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
    fun featureViewModelObservesSelectedConversationAndEmitsCreateEventOnce() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationRepository = InMemoryConversationRepository()
        val aiDraftRepository = FakeAiDraftRepository()
        val useCase = CreateConversationWithFirstMessageUseCase(aiDraftRepository)
        val viewModel = AiRecordViewModel(
            conversationRepository = conversationRepository,
            aiDraftRepository = aiDraftRepository,
            createConversationWithFirstMessageUseCase = useCase,
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
    }

    private fun createDayZeroViewModel(
        aiDraftRepository: FakeAiDraftRepository,
        aiAssistantRepository: AiAssistantRepository
    ): DayZeroViewModel {
        val recordRepository = InMemoryPhase2RecordRepository()
        return DayZeroViewModel(
            recordRepository = recordRepository,
            aiDraftRepository = aiDraftRepository,
            aiAssistantRepository = aiAssistantRepository,
            latencyLogger = AiLatencyTraceLogger(context),
            clearLocalDataUseCase = ClearLocalDataUseCase(recordRepository, aiDraftRepository),
            confirmFoodRecordUseCase = ConfirmFoodRecordUseCase(recordRepository),
            createConversationWithFirstMessageUseCase = CreateConversationWithFirstMessageUseCase(aiDraftRepository)
        )
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
            request: com.example.data.remote.dto.assistant.AiAssistantRequestDto
        ): Response<com.example.data.remote.dto.assistant.AssistantTurnV2ResponseDto> = error("unused")
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
