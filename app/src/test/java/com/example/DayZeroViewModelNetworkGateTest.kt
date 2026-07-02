package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.assistant.controllableFakeVisionAssistantTurnOrchestrator
import com.example.data.repository.FakeAiDraftRepository
import com.example.data.telemetry.AiLatencyTraceLogger
import com.example.domain.model.ai.AiRecordConversationState
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.ChatRole
import com.example.domain.model.ai.assistant.AiAssistantRequest
import com.example.domain.model.ai.assistant.AiAssistantTurn
import com.example.domain.model.ai.assistant.AskMissingInfoCardPayload
import com.example.domain.model.ai.assistant.AskMissingInfoOption
import com.example.domain.model.ai.assistant.AiIntent
import com.example.domain.model.ai.assistant.VisionAssistantTurnResult
import com.example.domain.network.NetworkAvailabilityProvider
import com.example.domain.repository.AiAssistantRepository
import com.example.domain.repository.ConfirmFoodCardResult
import com.example.domain.repository.ConversationRepository
import com.example.domain.repository.FoodCardConfirmationRepository
import com.example.domain.repository.RecordRepository
import com.example.domain.time.CurrentDateProvider
import com.example.domain.usecase.ClearLocalDataUseCase
import com.example.domain.usecase.ConfirmFoodCardUseCase
import com.example.domain.usecase.CreateConversationWithFirstMessageUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DayZeroViewModelNetworkGateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private lateinit var context: android.content.Context
    private lateinit var aiDraftRepository: FakeAiDraftRepository
    private lateinit var networkAvailabilityProvider: FakeNetworkAvailabilityProvider
    private val assistantRequests = mutableListOf<AiAssistantRequest>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        aiDraftRepository = FakeAiDraftRepository()
        networkAvailabilityProvider = FakeNetworkAvailabilityProvider()
        assistantRequests.clear()
    }

    @Test
    fun `text message blocked when no network`() = runTest(mainDispatcherRule.testDispatcher) {
        networkAvailabilityProvider.hasInternet = false
        val viewModel = createViewModel()

        val accepted = viewModel.sendAiMessage("hello")
        advanceUntilIdle()

        assertFalse(accepted)
        assertEquals(0, viewModel.uiState.value.chatMessages.size)
        assertTrue(viewModel.uiState.value.conversationState is AiRecordConversationState.Error)
        assertFalse(viewModel.uiState.value.isAnalyzing)
    }

    @Test
    fun `interaction result blocked before card state changes when no network`() = runTest(mainDispatcherRule.testDispatcher) {
        networkAvailabilityProvider.hasInternet = false
        val card = AskMissingInfoCardPayload(
            id = "meal-card",
            title = "Meal",
            message = "Which meal?",
            field = "mealType",
            originalText = "food",
            options = listOf(AskMissingInfoOption("lunch", "Lunch"))
        )
        aiDraftRepository.insertChatMessage(
            AiChatMessage(
                id = "assistant-card",
                conversationId = "conv-card",
                role = ChatRole.Assistant,
                text = "choose",
                assistantCards = listOf(card)
            )
        )
        val viewModel = createViewModel()

        viewModel.sendInteractionResult(
            interactionId = card.id,
            actionType = "ask_missing_info_card",
            optionId = "lunch",
            optionLabel = "Lunch",
            field = "mealType",
            originalText = "food"
        )
        advanceUntilIdle()

        val stored = aiDraftRepository.findMessageByAssistantCardId(card.id)
        assertEquals(false, (stored?.assistantCards?.single() as AskMissingInfoCardPayload).resolved)
        assertFalse(viewModel.uiState.value.isAnalyzing)
        assertEquals(1, viewModel.uiState.value.chatMessages.size)
    }

    @Test
    fun `vision card interaction carries structured context without attachments and stays in source conversation`() = runTest(mainDispatcherRule.testDispatcher) {
        networkAvailabilityProvider.hasInternet = true
        val context = mapOf(
            "schemaVersion" to 1,
            "recognizedFoods" to listOf(
                mapOf("name" to "apple", "amountText" to "1 item", "calories" to 95, "proteinG" to 0.5)
            ),
            "mediaIds" to listOf("11111111-1111-4111-8111-111111111111")
        )
        val card = AskMissingInfoCardPayload(
            id = "vision-meal-card",
            title = "Meal",
            message = "Which meal?",
            field = "mealType",
            originalText = "",
            options = listOf(AskMissingInfoOption("lunch", "Lunch")),
            continuationContext = context
        )
        aiDraftRepository.insertChatMessage(
            AiChatMessage(
                id = "vision-assistant-card",
                conversationId = "vision-source-conversation",
                role = ChatRole.Assistant,
                text = "choose",
                assistantCards = listOf(card)
            )
        )
        val viewModel = createViewModel()

        viewModel.sendInteractionResult(
            interactionId = card.id,
            actionType = "ask_missing_info_card",
            optionId = "lunch",
            optionLabel = "Lunch",
            field = "mealType",
            originalText = ""
        )
        advanceUntilIdle()

        val request = assistantRequests.single { it.turnType == "interaction_result" }
        assertEquals(context, request.interactionResult?.continuationContext)
        assertTrue(request.attachments.orEmpty().isEmpty())
        assertTrue(request.recentMessages.all { it.conversationId == "vision-source-conversation" })
        assertEquals(2, aiDraftRepository.getRecentChatMessages("vision-source-conversation", 10).size)
    }

    @Test
    fun `text message allowed when network available`() = runTest(mainDispatcherRule.testDispatcher) {
        networkAvailabilityProvider.hasInternet = true
        val viewModel = createViewModel()

        viewModel.sendAiMessage("hello")
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.chatMessages.size)
        assertFalse(viewModel.uiState.value.conversationState is AiRecordConversationState.Error)
    }

    @Test
    fun `vision retry blocked when no network`() = runTest(mainDispatcherRule.testDispatcher) {
        networkAvailabilityProvider.hasInternet = false
        val viewModel = createViewModel()

        viewModel.startVisionAssistantTurnForExistingUserMessage("conv-1", "user-1")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.conversationState is AiRecordConversationState.Error)
    }

    @Test
    fun `vision retry allowed when network available`() = runTest(mainDispatcherRule.testDispatcher) {
        networkAvailabilityProvider.hasInternet = true
        val viewModel = createViewModel()

        viewModel.startVisionAssistantTurnForExistingUserMessage("conv-1", "user-1")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.conversationState is AiRecordConversationState.Error)
    }

    private fun createViewModel(): DayZeroViewModel {
        val recordRepository = object : RecordRepository {
            override fun observeRecords() = flowOf(emptyList<com.example.domain.model.DailyRecord>())
            override suspend fun upsertRecord(record: com.example.domain.model.DailyRecord) {}
            override suspend fun deleteRecordById(recordId: String) {}
            override suspend fun getRecordById(recordId: String) = null
            override suspend fun getRecordByDateAndStatus(
                date: LocalDate,
                status: com.example.domain.model.RecordStatus
            ) = null
            override suspend fun updateRecordStatus(
                recordId: String,
                status: com.example.domain.model.RecordStatus,
                weightKg: Float?
            ) {
            }
            override suspend fun deleteFoodFromRecord(
                recordId: String,
                mealType: com.example.domain.model.MealType,
                foodId: String
            ) {
            }
            override suspend fun clearAllRecords() {}
        }
        val conversationRepository = object : ConversationRepository {
            override suspend fun insertConversation(conversation: com.example.domain.model.ai.Conversation) {}
            override suspend fun getConversationById(id: String) = null
            override fun observeConversations() = flowOf(emptyList<com.example.domain.model.ai.Conversation>())
            override fun observeConversationsByLastActivity() =
                flowOf(emptyList<com.example.domain.model.ai.Conversation>())
            override suspend fun updateConversationSummary(
                id: String,
                title: String,
                lastMessagePreview: String,
                lastActivityAt: Long,
                updatedAt: Long
            ) {
            }
            override suspend fun softDeleteConversation(id: String, deletedAt: Long) {}
        }
        return DayZeroViewModel(
            recordRepository = recordRepository,
            aiDraftRepository = aiDraftRepository,
            aiAssistantRepository = object : AiAssistantRepository {
                override suspend fun sendMessage(request: AiAssistantRequest): AiAssistantTurn {
                    assistantRequests.add(request)
                    return AiAssistantTurn(
                        id = "turn-text",
                        intent = AiIntent.GeneralChat,
                        replyText = "text reply",
                        cards = emptyList(),
                        suggestedReplies = emptyList()
                    )
                }
            },
            latencyLogger = AiLatencyTraceLogger(context),
            clearLocalDataUseCase = ClearLocalDataUseCase(recordRepository, aiDraftRepository),
            confirmFoodCardUseCase = ConfirmFoodCardUseCase(NoopFoodCardConfirmationRepository()),
            createConversationWithFirstMessageUseCase = CreateConversationWithFirstMessageUseCase(aiDraftRepository),
            conversationRepository = conversationRepository,
            currentDateProvider = object : CurrentDateProvider {
                override fun currentDate(): LocalDate = LocalDate.of(2026, 6, 20)
            },
            syncScheduler = object : com.example.data.sync.SyncScheduler {
                override fun requestSync(reason: com.example.data.sync.SyncTriggerReason) = null
                override fun requestBackfill(reason: com.example.data.sync.SyncTriggerReason) = null
                override fun requestSyncAndBackfill(reason: com.example.data.sync.SyncTriggerReason) = null
                override fun requestPull(reason: com.example.data.sync.SyncTriggerReason) = null
                override fun requestInitialRestore(reason: com.example.data.sync.SyncTriggerReason) = null
                override fun requestSyncAndPull(reason: com.example.data.sync.SyncTriggerReason) = null
            },
            visionAssistantTurnOrchestrator = controllableFakeVisionAssistantTurnOrchestrator(context),
            networkAvailabilityProvider = networkAvailabilityProvider
        )
    }

    private class NoopFoodCardConfirmationRepository : FoodCardConfirmationRepository {
        override suspend fun confirmFoodCard(
            cardId: String,
            payloadSummary: com.example.domain.model.ai.assistant.PayloadSummary?
        ) = ConfirmFoodCardResult.CardNotFound
        override suspend fun cancelFoodCard(cardId: String) = ConfirmFoodCardResult.CardNotFound
    }

    private class FakeNetworkAvailabilityProvider : NetworkAvailabilityProvider {
        var hasInternet: Boolean = true
        override fun hasValidatedInternet(): Boolean = hasInternet
    }
}
