package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.FakeAiDraftRepository
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
import com.example.domain.model.ai.assistant.ConfirmCardItem
import com.example.domain.model.ai.assistant.ConfirmCardMeal
import com.example.domain.model.ai.assistant.ConfirmCardOption
import com.example.domain.model.ai.assistant.DateMismatchGuardCardPayload
import com.example.domain.model.ai.assistant.PayloadSummary
import com.example.domain.model.ai.assistant.ShowConfirmCardPayload
import com.example.domain.repository.AiAssistantRepository
import com.example.domain.repository.ConversationRepository
import com.example.domain.repository.RecordRepository
import com.example.domain.time.CurrentDateProvider
import com.example.domain.usecase.ClearLocalDataUseCase
import com.example.domain.usecase.ConfirmFoodRecordUseCase
import com.example.domain.usecase.CreateConversationWithFirstMessageUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DayZeroDateMismatchGuardTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun sameDateConversationShowsOriginalConfirmCardDirectly() = runTest(mainDispatcherRule.testDispatcher) {
        val fixture = Fixture(currentDate = LocalDate.of(2026, 6, 20))
        fixture.insertConversation("conversation", LocalDate.of(2026, 6, 20))

        fixture.viewModel.startAssistantTurnForExistingUserMessage("conversation", "record lunch")
        advanceUntilIdle()

        val cards = fixture.assistantCards("conversation")
        assertEquals(1, cards.size)
        assertTrue(cards.single() is ShowConfirmCardPayload)
        assertEquals(0, cards.filterIsInstance<DateMismatchGuardCardPayload>().size)
    }

    @Test
    fun earlierDateConversationWrapsConfirmCardBeforeItIsVisible() = runTest(mainDispatcherRule.testDispatcher) {
        val fixture = Fixture(currentDate = LocalDate.of(2026, 6, 20))
        fixture.insertConversation("conversation", LocalDate.of(2026, 6, 18))

        fixture.viewModel.startAssistantTurnForExistingUserMessage("conversation", "record lunch")
        advanceUntilIdle()

        val guard = fixture.assistantCards("conversation").single() as DateMismatchGuardCardPayload
        assertEquals("pending", guard.state)
        assertEquals(LocalDate.of(2026, 6, 18), guard.conversationDate)
        assertEquals(LocalDate.of(2026, 6, 20), guard.detectedCurrentDate)
        assertEquals(fixture.confirmCard.id, guard.pendingOriginalCard.id)
        assertEquals(fixture.confirmCard, guard.pendingOriginalCard)
    }

    @Test
    fun futureDateConversationAlsoShowsGuard() = runTest(mainDispatcherRule.testDispatcher) {
        val fixture = Fixture(currentDate = LocalDate.of(2026, 6, 20))
        fixture.insertConversation("conversation", LocalDate.of(2026, 6, 22))

        fixture.viewModel.startAssistantTurnForExistingUserMessage("conversation", "record lunch")
        advanceUntilIdle()

        val guard = fixture.assistantCards("conversation").single() as DateMismatchGuardCardPayload
        assertEquals(LocalDate.of(2026, 6, 22), guard.conversationDate)
        assertEquals("pending", guard.state)
    }

    @Test
    fun approvingGuardReleasesOriginalCardOnceWithoutNetwork() = runTest(mainDispatcherRule.testDispatcher) {
        val fixture = Fixture(currentDate = LocalDate.of(2026, 6, 20))
        fixture.insertConversation("conversation", LocalDate.of(2026, 6, 18))
        fixture.viewModel.startAssistantTurnForExistingUserMessage("conversation", "record lunch")
        advanceUntilIdle()
        val guardId = (fixture.assistantCards("conversation").single() as DateMismatchGuardCardPayload).id
        val callsBeforeApprove = fixture.assistantRepository.streamRequests

        fixture.viewModel.handleDateMismatchGuardResult(guardId, approved = true)
        fixture.viewModel.handleDateMismatchGuardResult(guardId, approved = true)
        advanceUntilIdle()

        val guard = fixture.assistantCards("conversation").single() as DateMismatchGuardCardPayload
        assertEquals("approved", guard.state)
        assertEquals(fixture.confirmCard.id, guard.pendingOriginalCard.id)
        assertEquals(callsBeforeApprove, fixture.assistantRepository.streamRequests)
    }

    @Test
    fun cancellingGuardPreventsRecordWriteAndDoesNotBlockLaterChat() = runTest(mainDispatcherRule.testDispatcher) {
        val fixture = Fixture(currentDate = LocalDate.of(2026, 6, 20))
        fixture.insertConversation("conversation", LocalDate.of(2026, 6, 18))
        fixture.viewModel.startAssistantTurnForExistingUserMessage("conversation", "record lunch")
        advanceUntilIdle()
        val guardId = (fixture.assistantCards("conversation").single() as DateMismatchGuardCardPayload).id

        fixture.viewModel.handleDateMismatchGuardResult(guardId, approved = false)
        fixture.viewModel.handleDateMismatchGuardResult(guardId, approved = false)
        advanceUntilIdle()
        fixture.viewModel.sendInteractionResult(
            interactionId = fixture.confirmCard.id,
            actionType = "show_confirm_card",
            optionId = "confirm",
            optionLabel = "confirm",
            confirmType = "food_record",
            payloadSummary = fixture.payloadSummary
        )
        advanceUntilIdle()

        val guard = fixture.assistantCards("conversation").single() as DateMismatchGuardCardPayload
        assertEquals("cancelled", guard.state)
        assertTrue(fixture.recordRepository.records.value.isEmpty())

        fixture.viewModel.sendAiMessage("conversation", "keep chatting")
        advanceUntilIdle()
        assertTrue(fixture.aiDraftRepository.getRecentChatMessages("conversation", 20).any { it.text == "keep chatting" })
    }

    @Test
    fun approvedOldConversationWritesFoodAndWeightToConversationDate() = runTest(mainDispatcherRule.testDispatcher) {
        val fixture = Fixture(currentDate = LocalDate.of(2026, 6, 20))
        fixture.insertConversation("conversation", LocalDate.of(2026, 6, 18))
        fixture.insertConversation("other", LocalDate.of(2026, 6, 20))
        fixture.viewModel.startAssistantTurnForExistingUserMessage("conversation", "record lunch")
        advanceUntilIdle()
        val guardId = (fixture.assistantCards("conversation").single() as DateMismatchGuardCardPayload).id
        fixture.viewModel.handleDateMismatchGuardResult(guardId, approved = true)
        advanceUntilIdle()
        fixture.viewModel.setActiveConversationId("other")

        fixture.viewModel.sendInteractionResult(
            interactionId = fixture.confirmCard.id,
            actionType = "show_confirm_card",
            optionId = "confirm",
            optionLabel = "confirm",
            confirmType = "food_record",
            payloadSummary = fixture.payloadSummary
        )
        advanceUntilIdle()

        val record = fixture.recordRepository.records.value.single()
        assertEquals(LocalDate.of(2026, 6, 18), record.date)
        assertEquals(72.5f, record.weightKg)
        assertEquals(MealType.Lunch, record.meals.single().mealType)
        assertEquals("rice", record.meals.single().foods.single().name)
        assertEquals("confirmed", ((fixture.assistantCards("conversation").single() as DateMismatchGuardCardPayload).pendingOriginalCard.state))
    }

    @Test
    fun oldUnwrappedConfirmCardAlsoWritesToOwningConversationDate() = runTest(mainDispatcherRule.testDispatcher) {
        val fixture = Fixture(currentDate = LocalDate.of(2026, 6, 20))
        fixture.insertConversation("conversation", LocalDate.of(2026, 6, 18))
        fixture.aiDraftRepository.insertChatMessage(
            "conversation",
            AiChatMessage(
                conversationId = "conversation",
                role = ChatRole.Assistant,
                text = "",
                assistantCards = listOf(fixture.confirmCard)
            )
        )

        fixture.viewModel.sendInteractionResult(
            interactionId = fixture.confirmCard.id,
            actionType = "show_confirm_card",
            optionId = "confirm",
            optionLabel = "confirm",
            confirmType = "food_record",
            payloadSummary = fixture.payloadSummary
        )
        advanceUntilIdle()

        assertEquals(LocalDate.of(2026, 6, 18), fixture.recordRepository.records.value.single().date)
    }

    @Test
    fun draftUpdatePersistsEditedNutritionNullsBeforeConfirm() = runTest(mainDispatcherRule.testDispatcher) {
        val fixture = Fixture(currentDate = LocalDate.of(2026, 6, 20))
        fixture.insertConversation("conversation", LocalDate.of(2026, 6, 20))
        fixture.viewModel.startAssistantTurnForExistingUserMessage("conversation", "record lunch")
        advanceUntilIdle()

        val editedMeals = fixture.confirmCard.meals!!.map { meal ->
            meal.copy(
                items = meal.items.map { item ->
                    item.copy(
                        name = "brown rice",
                        carbohydratesG = null,
                        proteinG = null,
                        fatG = null,
                        fiberG = null
                    )
                }
            )
        }
        fixture.viewModel.updateFoodDraftCard(
            interactionId = fixture.confirmCard.id,
            weightKg = null,
            meals = editedMeals
        )
        advanceUntilIdle()

        val card = fixture.assistantCards("conversation").single() as ShowConfirmCardPayload
        val storedItem = card.meals!!.single().items.single()
        assertEquals("brown rice", storedItem.name)
        assertNull(storedItem.carbohydratesG)
        assertNull(storedItem.proteinG)
        assertNull(storedItem.fatG)
        assertNull(storedItem.fiberG)

        fixture.viewModel.sendInteractionResult(
            interactionId = fixture.confirmCard.id,
            actionType = "show_confirm_card",
            optionId = "confirm",
            optionLabel = "confirm",
            confirmType = "food_record",
            payloadSummary = PayloadSummary(
                originalText = "rice",
                weightKg = null,
                meals = editedMeals
            )
        )
        advanceUntilIdle()

        val savedFood = fixture.recordRepository.records.value.single().meals.single().foods.single()
        assertEquals("brown rice", savedFood.name)
        assertNull(savedFood.carbohydratesG)
        assertNull(savedFood.proteinG)
        assertNull(savedFood.fatG)
        assertNull(savedFood.fiberG)
        val confirmedCard = fixture.assistantCards("conversation").first() as ShowConfirmCardPayload
        assertEquals("confirmed", confirmedCard.state)
        assertNull(confirmedCard.meals!!.single().items.single().fiberG)
    }

    @Test
    fun approvedGuardDraftUpdatePersistsInsidePendingOriginalCard() = runTest(mainDispatcherRule.testDispatcher) {
        val fixture = Fixture(currentDate = LocalDate.of(2026, 6, 20))
        fixture.insertConversation("conversation", LocalDate.of(2026, 6, 18))
        fixture.viewModel.startAssistantTurnForExistingUserMessage("conversation", "record lunch")
        advanceUntilIdle()
        val guardId = (fixture.assistantCards("conversation").single() as DateMismatchGuardCardPayload).id
        fixture.viewModel.handleDateMismatchGuardResult(guardId, approved = true)
        advanceUntilIdle()

        val editedMeals = fixture.confirmCard.meals!!.map { meal ->
            meal.copy(items = meal.items.map { it.copy(amountText = "2 bowls", carbohydratesG = null, proteinG = null, fatG = null, fiberG = null) })
        }
        fixture.viewModel.updateFoodDraftCard(
            interactionId = fixture.confirmCard.id,
            weightKg = 70.0,
            meals = editedMeals
        )
        advanceUntilIdle()

        val guard = fixture.assistantCards("conversation").single() as DateMismatchGuardCardPayload
        val nestedItem = guard.pendingOriginalCard.meals!!.single().items.single()
        assertEquals("2 bowls", nestedItem.amountText)
        assertNull(nestedItem.carbohydratesG)
        assertNull(nestedItem.proteinG)
        assertNull(nestedItem.fatG)
        assertNull(nestedItem.fiberG)

        fixture.viewModel.sendInteractionResult(
            interactionId = fixture.confirmCard.id,
            actionType = "show_confirm_card",
            optionId = "cancel",
            optionLabel = "cancel",
            confirmType = "food_record",
            payloadSummary = PayloadSummary(originalText = "rice", weightKg = 70.0, meals = editedMeals)
        )
        advanceUntilIdle()

        val cancelledGuard = fixture.assistantCards("conversation").single() as DateMismatchGuardCardPayload
        assertEquals("cancelled", cancelledGuard.pendingOriginalCard.state)
        assertNull(cancelledGuard.pendingOriginalCard.meals!!.single().items.single().fiberG)
    }

    private class Fixture(currentDate: LocalDate) {
        val aiDraftRepository = FakeAiDraftRepository()
        val conversationRepository = InMemoryConversationRepository()
        val recordRepository = InMemoryRecordRepository()
        val confirmCard = showConfirmCard()
        val payloadSummary = PayloadSummary(
            originalText = "rice",
            weightKg = 72.5,
            meals = confirmCard.meals
        )
        val assistantRepository = CardAssistantRepository(confirmCard)
        val viewModel = DayZeroViewModel(
            recordRepository = recordRepository,
            aiDraftRepository = aiDraftRepository,
            aiAssistantRepository = assistantRepository,
            latencyLogger = AiLatencyTraceLogger(ApplicationProvider.getApplicationContext()),
            clearLocalDataUseCase = ClearLocalDataUseCase(recordRepository, aiDraftRepository),
            confirmFoodRecordUseCase = ConfirmFoodRecordUseCase(recordRepository),
            createConversationWithFirstMessageUseCase = CreateConversationWithFirstMessageUseCase(aiDraftRepository),
            conversationRepository = conversationRepository,
            currentDateProvider = FixedCurrentDateProvider(currentDate),
            syncScheduler = object : com.example.data.sync.SyncScheduler {
                override fun requestSync(reason: com.example.data.sync.SyncTriggerReason): kotlinx.coroutines.Job? = null
                override fun requestBackfill(reason: com.example.data.sync.SyncTriggerReason): kotlinx.coroutines.Job? = null
                override fun requestSyncAndBackfill(reason: com.example.data.sync.SyncTriggerReason): kotlinx.coroutines.Job? = null
                override fun requestPull(reason: com.example.data.sync.SyncTriggerReason): kotlinx.coroutines.Job? = null
                override fun requestInitialRestore(reason: com.example.data.sync.SyncTriggerReason): kotlinx.coroutines.Job? = null
                override fun requestSyncAndPull(reason: com.example.data.sync.SyncTriggerReason): kotlinx.coroutines.Job? = null
            }
        )

        suspend fun insertConversation(id: String, date: LocalDate) {
            conversationRepository.insertConversation(
                Conversation(
                    id = id,
                    conversationDate = date,
                    title = id,
                    lastMessagePreview = id,
                    createdAt = 1L,
                    updatedAt = 1L,
                    lastActivityAt = 1L
                )
            )
            aiDraftRepository.insertChatMessage(
                id,
                AiChatMessage(conversationId = id, role = ChatRole.User, text = "first")
            )
        }

        suspend fun assistantCards(conversationId: String) =
            aiDraftRepository.getRecentChatMessages(conversationId, 20)
                .last { it.role == ChatRole.Assistant && it.assistantCards.isNotEmpty() }
                .assistantCards
    }

    private class CardAssistantRepository(private val card: ShowConfirmCardPayload) : AiAssistantRepository {
        var streamRequests = 0
            private set

        override suspend fun sendMessage(request: AiAssistantRequest): AiAssistantTurn {
            return turn(card)
        }

        override suspend fun streamMessage(
            request: AiAssistantRequest,
            onDelta: suspend (String) -> Unit
        ): AiAssistantTurn {
            streamRequests += 1
            onDelta("Here is the card.")
            return turn(card)
        }
    }

    private class InMemoryConversationRepository : ConversationRepository {
        private val conversations = MutableStateFlow<List<Conversation>>(emptyList())

        override suspend fun insertConversation(conversation: Conversation) {
            conversations.update { current -> current.filterNot { it.id == conversation.id } + conversation }
        }

        override suspend fun getConversationById(id: String): Conversation? = conversations.value.find { it.id == id }

        override fun observeConversations(): Flow<List<Conversation>> = conversations.asStateFlow()

        override fun observeConversationsByLastActivity(): Flow<List<Conversation>> = conversations.asStateFlow()

        override suspend fun updateConversationSummary(
            id: String,
            title: String,
            lastMessagePreview: String,
            lastActivityAt: Long,
            updatedAt: Long
        ) = Unit

        override suspend fun softDeleteConversation(id: String, deletedAt: Long) = Unit
    }

    private class InMemoryRecordRepository : RecordRepository {
        private val _records = MutableStateFlow<List<DailyRecord>>(emptyList())
        val records = _records.asStateFlow()

        override fun observeRecords(): Flow<List<DailyRecord>> = records

        override suspend fun upsertRecord(record: DailyRecord) {
            _records.update { current -> current.filterNot { it.id == record.id } + record }
        }

        override suspend fun deleteRecordById(recordId: String) = Unit

        override suspend fun getRecordById(recordId: String): DailyRecord? = records.value.find { it.id == recordId }

        override suspend fun getRecordByDateAndStatus(date: LocalDate, status: RecordStatus): DailyRecord? {
            return records.value.find { it.date == date && it.status == status }
        }

        override suspend fun updateRecordStatus(recordId: String, status: RecordStatus, weightKg: Float?) = Unit

        override suspend fun deleteFoodFromRecord(recordId: String, mealType: MealType, foodId: String) = Unit

        override suspend fun clearAllRecords() {
            _records.value = emptyList()
        }
    }

    private class FixedCurrentDateProvider(private val date: LocalDate) : CurrentDateProvider {
        override fun currentDate(): LocalDate = date
    }

    private companion object {
        fun showConfirmCard(): ShowConfirmCardPayload {
            val meal = ConfirmCardMeal(
                mealType = "lunch",
                mealLabel = "Lunch",
                subtotalCalories = 300,
                items = listOf(
                    ConfirmCardItem(
                        id = "item-1",
                        name = "rice",
                        amountText = "1 bowl",
                        calories = 300,
                        calorieConfidence = "medium",
                        carbohydratesG = 85f,
                        proteinG = 15f,
                        fatG = 22f,
                        fiberG = 6f
                    )
                )
            )
            return ShowConfirmCardPayload(
                id = "confirm-card",
                confirmType = "food_record",
                title = "Confirm",
                message = "Confirm food",
                originalText = "rice",
                mealType = null,
                items = emptyList(),
                date = null,
                weightKg = 72.5,
                totalCalories = 300,
                meals = listOf(meal),
                buttons = listOf(
                    ConfirmCardOption("cancel", "Cancel"),
                    ConfirmCardOption("confirm", "Confirm")
                )
            )
        }

        fun turn(card: ShowConfirmCardPayload): AiAssistantTurn {
            return AiAssistantTurn(
                id = "turn-1",
                intent = AiIntent.GeneralChat,
                replyText = "Here is the card.",
                cards = listOf(card),
                suggestedReplies = emptyList()
            )
        }
    }
}
