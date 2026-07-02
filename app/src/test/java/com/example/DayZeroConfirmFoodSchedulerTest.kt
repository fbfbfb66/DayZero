package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.FakeAiDraftRepository
import com.example.data.telemetry.AiLatencyTraceLogger
import com.example.domain.model.DailyRecord
import com.example.domain.model.RecordStatus
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.ChatRole
import com.example.domain.model.ai.Conversation
import com.example.domain.model.ai.assistant.AiAssistantRequest
import com.example.domain.model.ai.assistant.AiAssistantTurn
import com.example.domain.model.ai.assistant.AiIntent
import com.example.domain.model.ai.assistant.ConfirmCardOption
import com.example.domain.model.ai.assistant.PayloadSummary
import com.example.domain.model.ai.assistant.ShowConfirmCardPayload
import com.example.domain.repository.AiAssistantRepository
import com.example.domain.repository.AiDraftRepository
import com.example.domain.repository.ConfirmFoodCardResult
import com.example.domain.repository.ConversationRepository
import com.example.domain.repository.FoodCardConfirmationRepository
import com.example.domain.repository.RecordRepository
import com.example.domain.time.CurrentDateProvider
import com.example.domain.usecase.ClearLocalDataUseCase
import com.example.domain.usecase.ConfirmFoodCardUseCase
import com.example.domain.usecase.CreateConversationWithFirstMessageUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DayZeroConfirmFoodSchedulerTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun successfulAtomicConfirmTriggersSchedulerOnce() = runTest(mainDispatcherRule.testDispatcher) {
        val fixture = Fixture(ConfirmFoodCardResult.Confirmed(sampleRecord(), "conversation", "message", "card"))

        fixture.viewModel.sendInteractionResult(
            interactionId = "card",
            actionType = "show_confirm_card",
            optionId = "confirm",
            optionLabel = "Confirm",
            confirmType = "food_record",
            payloadSummary = PayloadSummary(originalText = "rice")
        )
        advanceUntilIdle()

        assertEquals(1, fixture.scheduler.syncRequests)
    }

    @Test
    fun failedAtomicConfirmDoesNotTriggerScheduler() = runTest(mainDispatcherRule.testDispatcher) {
        val fixture = Fixture(ConfirmFoodCardResult.Failed(IllegalStateException("boom")))

        fixture.viewModel.sendInteractionResult(
            interactionId = "card",
            actionType = "show_confirm_card",
            optionId = "confirm",
            optionLabel = "Confirm",
            confirmType = "food_record",
            payloadSummary = PayloadSummary(originalText = "rice")
        )
        advanceUntilIdle()

        assertEquals(0, fixture.scheduler.syncRequests)
    }

    @Test
    fun alreadyConfirmedNoOpDoesNotTriggerScheduler() = runTest(mainDispatcherRule.testDispatcher) {
        val fixture = Fixture(ConfirmFoodCardResult.AlreadyConfirmed)

        fixture.viewModel.sendInteractionResult(
            interactionId = "card",
            actionType = "show_confirm_card",
            optionId = "confirm",
            optionLabel = "Confirm",
            confirmType = "food_record",
            payloadSummary = PayloadSummary(originalText = "rice")
        )
        advanceUntilIdle()

        assertEquals(0, fixture.scheduler.syncRequests)
    }

    private class Fixture(result: ConfirmFoodCardResult) {
        val aiDraftRepository = FakeAiDraftRepository()
        val conversationRepository = InMemoryConversationRepository()
        val recordRepository = EmptyRecordRepository()
        val scheduler = CountingScheduler()
        val viewModel: DayZeroViewModel

        init {
            kotlinx.coroutines.runBlocking {
                conversationRepository.insertConversation(
                    Conversation(
                        id = "conversation",
                        conversationDate = LocalDate.of(2026, 6, 18),
                        title = "conversation",
                        lastMessagePreview = "preview"
                    )
                )
                aiDraftRepository.insertChatMessage(
                    "conversation",
                    AiChatMessage(
                        id = "message",
                        conversationId = "conversation",
                        role = ChatRole.Assistant,
                        text = "",
                        assistantCards = listOf(
                            ShowConfirmCardPayload(
                                id = "card",
                                confirmType = "food_record",
                                title = "Confirm",
                                message = "Confirm",
                                originalText = "rice",
                                mealType = null,
                                items = emptyList(),
                                buttons = listOf(ConfirmCardOption("confirm", "Confirm"))
                            )
                        )
                    )
                )
            }
            viewModel = DayZeroViewModel(
                recordRepository = recordRepository,
                aiDraftRepository = aiDraftRepository,
                aiAssistantRepository = NoopAssistantRepository(),
                latencyLogger = AiLatencyTraceLogger(ApplicationProvider.getApplicationContext()),
                clearLocalDataUseCase = ClearLocalDataUseCase(recordRepository, aiDraftRepository),
                confirmFoodCardUseCase = ConfirmFoodCardUseCase(StaticFoodCardConfirmationRepository(result)),
                createConversationWithFirstMessageUseCase = CreateConversationWithFirstMessageUseCase(aiDraftRepository),
                conversationRepository = conversationRepository,
                currentDateProvider = object : CurrentDateProvider {
                    override fun currentDate(): LocalDate = LocalDate.of(2026, 6, 20)
                },
                syncScheduler = scheduler,
                visionAssistantTurnOrchestrator = com.example.assistant.fakeVisionAssistantTurnOrchestrator(
                    ApplicationProvider.getApplicationContext()
                ),
                networkAvailabilityProvider = com.example.domain.network.NetworkAvailabilityProvider { true }
            )
            scheduler.reset()
        }
    }

    private class StaticFoodCardConfirmationRepository(
        private val result: ConfirmFoodCardResult
    ) : FoodCardConfirmationRepository {
        override suspend fun confirmFoodCard(
            cardId: String,
            payloadSummary: PayloadSummary?
        ): ConfirmFoodCardResult = result

        override suspend fun cancelFoodCard(cardId: String): ConfirmFoodCardResult = ConfirmFoodCardResult.Cancelled
    }

    private class CountingScheduler : com.example.data.sync.SyncScheduler {
        var syncRequests = 0
            private set

        fun reset() {
            syncRequests = 0
        }

        override fun requestSync(reason: com.example.data.sync.SyncTriggerReason): Job? {
            syncRequests += 1
            return null
        }

        override fun requestBackfill(reason: com.example.data.sync.SyncTriggerReason): Job? = null
        override fun requestSyncAndBackfill(reason: com.example.data.sync.SyncTriggerReason): Job? = null
        override fun requestPull(reason: com.example.data.sync.SyncTriggerReason): Job? = null
        override fun requestInitialRestore(reason: com.example.data.sync.SyncTriggerReason): Job? = null
        override fun requestSyncAndPull(reason: com.example.data.sync.SyncTriggerReason): Job? = null
    }

    private class NoopAssistantRepository : AiAssistantRepository {
        override suspend fun sendMessage(request: AiAssistantRequest): AiAssistantTurn = turn()
        override suspend fun streamMessage(
            request: AiAssistantRequest,
            onDelta: suspend (String) -> Unit
        ): AiAssistantTurn = turn()

        private fun turn(): AiAssistantTurn {
            return AiAssistantTurn(
                id = "turn",
                intent = AiIntent.GeneralChat,
                replyText = "",
                cards = emptyList(),
                suggestedReplies = emptyList()
            )
        }
    }

    private class InMemoryConversationRepository : ConversationRepository {
        private val conversations = MutableStateFlow<List<Conversation>>(emptyList())
        override suspend fun insertConversation(conversation: Conversation) {
            conversations.update { it.filterNot { item -> item.id == conversation.id } + conversation }
        }
        override suspend fun getConversationById(id: String): Conversation? = conversations.value.find { it.id == id }
        override fun observeConversations(): Flow<List<Conversation>> = conversations.asStateFlow()
        override fun observeConversationsByLastActivity(): Flow<List<Conversation>> = conversations.asStateFlow()
        override suspend fun updateConversationSummary(id: String, title: String, lastMessagePreview: String, lastActivityAt: Long, updatedAt: Long) = Unit
        override suspend fun softDeleteConversation(id: String, deletedAt: Long) = Unit
    }

    private class EmptyRecordRepository : RecordRepository {
        override fun observeRecords(): Flow<List<DailyRecord>> = MutableStateFlow(emptyList<DailyRecord>()).asStateFlow()
        override suspend fun upsertRecord(record: DailyRecord) = Unit
        override suspend fun deleteRecordById(recordId: String) = Unit
        override suspend fun getRecordById(recordId: String): DailyRecord? = null
        override suspend fun getRecordByDateAndStatus(date: LocalDate, status: RecordStatus): DailyRecord? = null
        override suspend fun updateRecordStatus(recordId: String, status: RecordStatus, weightKg: Float?) = Unit
        override suspend fun deleteFoodFromRecord(recordId: String, mealType: com.example.domain.model.MealType, foodId: String) = Unit
        override suspend fun clearAllRecords() = Unit
    }

    private companion object {
        fun sampleRecord(): DailyRecord {
            return DailyRecord(
                id = "record",
                date = LocalDate.of(2026, 6, 18),
                status = RecordStatus.Confirmed,
                meals = emptyList()
            )
        }
    }
}
