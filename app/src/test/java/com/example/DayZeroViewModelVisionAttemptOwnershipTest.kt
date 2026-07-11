package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.assistant.ControllableFakeVisionAssistantTurnOrchestrator
import com.example.assistant.controllableFakeVisionAssistantTurnOrchestrator
import com.example.data.repository.FakeAiDraftRepository
import com.example.data.telemetry.AiLatencyTraceLogger
import com.example.domain.model.ai.AiRecordConversationState
import com.example.domain.model.ai.assistant.AiAssistantRequest
import com.example.domain.model.ai.assistant.AiAssistantTurn
import com.example.domain.model.ai.assistant.AiIntent
import com.example.domain.model.ai.assistant.VisionAssistantTurnResult
import com.example.domain.model.ai.assistant.assistantPlaceholderId
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
class DayZeroViewModelVisionAttemptOwnershipTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private lateinit var context: android.content.Context
    private lateinit var aiDraftRepository: FakeAiDraftRepository
    private lateinit var fakeOrchestrator: ControllableFakeVisionAssistantTurnOrchestrator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        aiDraftRepository = FakeAiDraftRepository()
        fakeOrchestrator = controllableFakeVisionAssistantTurnOrchestrator(context)
    }

    @Test
    fun attemptACompletes_thenAnalyzingIsCleared() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        fakeOrchestrator.configureResult(VisionAssistantTurnResult.Success)

        viewModel.startVisionAssistantTurnForExistingUserMessage("conv-1", "user-1")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isAnalyzing)
        assertEquals(
            listOf("true" to true, "false" to false),
            fakeOrchestrator.callbackEvents
        )
    }

    @Test
    fun attemptAFails_thenAnalyzingIsCleared() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        fakeOrchestrator.configureResult(
            VisionAssistantTurnResult.Failure(RuntimeException("vision failed"))
        )

        viewModel.startVisionAssistantTurnForExistingUserMessage("conv-1", "user-1")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isAnalyzing)
        val failure = viewModel.uiState.value.conversationState as AiRecordConversationState.Error
        assertEquals("conv-1", failure.conversationId)
        assertEquals("user-1", failure.userMessageId)
        assertEquals(assistantPlaceholderId("user-1"), failure.assistantMessageId)
        assertTrue(failure.retryable)
        assertEquals(
            listOf("true" to true, "false" to false),
            fakeOrchestrator.callbackEvents
        )
    }

    @Test
    fun attemptACancelled_thenAnalyzingIsCleared() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        fakeOrchestrator.suspendUntilUnblocked()

        viewModel.startVisionAssistantTurnForExistingUserMessage("conv-1", "user-1")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isAnalyzing)

        // Simulate cancellation by making the orchestrator throw CancellationException.
        fakeOrchestrator.configureError(kotlinx.coroutines.CancellationException("user cancelled"))
        fakeOrchestrator.unblock()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isAnalyzing)
        assertEquals(listOf("true" to true, "false" to false), fakeOrchestrator.callbackEvents)
    }

    @Test
    fun attemptBRejectedWhileAIsActive() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        fakeOrchestrator.suspendUntilUnblocked()

        viewModel.startVisionAssistantTurnForExistingUserMessage("conv-1", "user-1")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isAnalyzing)

        viewModel.startVisionAssistantTurnForExistingUserMessage("conv-2", "user-2")
        advanceUntilIdle()

        // Only one attempt should have been launched.
        assertEquals(1, fakeOrchestrator.callbackEvents.size)
        assertTrue(viewModel.uiState.value.isAnalyzing)

        fakeOrchestrator.unblock()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isAnalyzing)
    }

    @Test
    fun staleCallbackFromCompletedAttemptIsIgnored() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        fakeOrchestrator.configureResult(VisionAssistantTurnResult.Success)

        // Start A and capture its callback without auto-completing.
        fakeOrchestrator.setAutoComplete(false)
        fakeOrchestrator.suspendUntilUnblocked()
        viewModel.startVisionAssistantTurnForExistingUserMessage("conv-1", "user-1")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isAnalyzing)
        val staleCallback = fakeOrchestrator.capturedCallbacks.single()

        // Complete A manually; owner is released.
        fakeOrchestrator.unblock()
        fakeOrchestrator.completeLastAttempt()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isAnalyzing)

        // Start B while suspending it so we can inspect ownership before completion.
        fakeOrchestrator.suspendUntilUnblocked()
        viewModel.startVisionAssistantTurnForExistingUserMessage("conv-2", "user-2")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isAnalyzing)

        // A's stale callback must not clear B's analyzing state.
        staleCallback.invoke(false)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isAnalyzing)

        // Complete B.
        fakeOrchestrator.unblock()
        fakeOrchestrator.completeLastAttempt()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isAnalyzing)
    }

    @Test
    fun switchingActiveConversationDoesNotChangeOwnership() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        fakeOrchestrator.suspendUntilUnblocked()

        viewModel.startVisionAssistantTurnForExistingUserMessage("conv-1", "user-1")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isAnalyzing)

        viewModel.setActiveConversationId("conv-other")
        advanceUntilIdle()

        // Ownership is still tied to attempt A; analyzing remains true.
        assertTrue(viewModel.uiState.value.isAnalyzing)

        fakeOrchestrator.unblock()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isAnalyzing)
    }

    @Test
    fun ordinaryTextFlowAnalyzingBehaviorDoesNotRegress() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = createViewModel()

        viewModel.sendAiMessage("hello")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isAnalyzing)
        assertEquals(2, viewModel.uiState.value.chatMessages.size)
    }

    private fun createViewModel(): DayZeroViewModel {
        val recordRepository = object : RecordRepository {
            override fun observeRecords() =
                kotlinx.coroutines.flow.flowOf(emptyList<com.example.domain.model.DailyRecord>())

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
        val conversationRepository = InMemoryConversationRepository()
        return DayZeroViewModel(
            recordRepository = recordRepository,
            aiDraftRepository = aiDraftRepository,
            aiAssistantRepository = object : AiAssistantRepository {
                override suspend fun sendMessage(request: AiAssistantRequest): AiAssistantTurn {
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
            createConversationWithFirstMessageUseCase = CreateConversationWithFirstMessageUseCase(
                aiDraftRepository
            ),
            conversationRepository = conversationRepository,
            currentDateProvider = object : CurrentDateProvider {
                override fun currentDate(): LocalDate = LocalDate.of(2026, 6, 20)
            },
            syncScheduler = object : com.example.data.sync.SyncScheduler {
                override fun requestSync(reason: com.example.data.sync.SyncTriggerReason) = null
                override fun requestBackfill(reason: com.example.data.sync.SyncTriggerReason) = null
                override fun requestSyncAndBackfill(reason: com.example.data.sync.SyncTriggerReason) =
                    null

                override fun requestPull(reason: com.example.data.sync.SyncTriggerReason) = null
                override fun requestInitialRestore(reason: com.example.data.sync.SyncTriggerReason) =
                    null

                override fun requestSyncAndPull(reason: com.example.data.sync.SyncTriggerReason) = null
            },
            visionAssistantTurnOrchestrator = fakeOrchestrator,
            networkAvailabilityProvider = com.example.domain.network.NetworkAvailabilityProvider { true }
        )
    }

    private class NoopFoodCardConfirmationRepository : FoodCardConfirmationRepository {
        override suspend fun confirmFoodCard(
            cardId: String,
            payloadSummary: com.example.domain.model.ai.assistant.PayloadSummary?
        ) = ConfirmFoodCardResult.CardNotFound

        override suspend fun cancelFoodCard(cardId: String) = ConfirmFoodCardResult.CardNotFound
    }

    private class InMemoryConversationRepository : ConversationRepository {
        private val conversations = mutableMapOf<String, com.example.domain.model.ai.Conversation>()

        override suspend fun insertConversation(conversation: com.example.domain.model.ai.Conversation) {
            conversations[conversation.id] = conversation
        }

        override suspend fun getConversationById(id: String): com.example.domain.model.ai.Conversation? =
            conversations[id]

        override fun observeConversations() =
            kotlinx.coroutines.flow.flowOf(conversations.values.toList())

        override fun observeConversationsByLastActivity() =
            kotlinx.coroutines.flow.flowOf(conversations.values.toList())

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
}
