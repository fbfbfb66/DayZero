package com.example.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import com.example.domain.model.AppState
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.AiDraftRequest
import com.example.domain.model.ai.ChatRole
import com.example.domain.model.ai.CheckinDraft
import com.example.domain.model.ai.Conversation
import com.example.domain.model.ai.assistant.DebugChoiceCardPayload
import com.example.domain.model.ai.assistant.DebugChoiceOption
import com.example.domain.model.ai.assistant.ConfirmCardItem
import com.example.domain.model.ai.assistant.ConfirmCardMeal
import com.example.domain.model.ai.assistant.ConfirmCardOption
import com.example.domain.model.ai.assistant.DateMismatchGuardCardPayload
import com.example.domain.model.ai.assistant.PayloadSummary
import com.example.domain.model.ai.assistant.ShowConfirmCardPayload
import com.example.ui.components.ai.FoodDraftConfirmCardTestTags
import com.example.domain.repository.AiDraftRepository
import com.example.domain.repository.ConversationRepository
import com.example.domain.usecase.CreateConversationWithFirstMessageUseCase
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AiRecordPhase3Test {
    @get:Rule
    val mainDispatcherRule = FeatureMainDispatcherRule()

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var conversationRepository: InMemoryConversationRepository
    private lateinit var aiDraftRepository: InMemoryAiDraftRepository

    @Before
    fun setUp() {
        conversationRepository = InMemoryConversationRepository()
        aiDraftRepository = InMemoryAiDraftRepository(conversationRepository)
    }

    @Test
    fun homeObservesHistoryAndCreatesConversationOnce() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = createViewModel()
        conversationRepository.insertConversation(conversation("history-a", "Breakfast", 20L))
        advanceUntilIdle()

        assertEquals(listOf("Breakfast"), viewModel.uiState.value.history.conversations.map { it.title })

        val eventDeferred = this.async { viewModel.events.first() }
        viewModel.updateHomeInput(" first message ")
        viewModel.submitHomeInput()
        viewModel.submitHomeInput()
        advanceUntilIdle()

        val event = eventDeferred.await()
        assertTrue(event is AiRecordConversationEvent.ConversationCreated)
        assertEquals(1, aiDraftRepository.createCount)
        assertEquals("", viewModel.uiState.value.history.homeInputText)
    }

    @Test
    fun blankHomeMessageDoesNotCreateConversation() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = createViewModel()

        viewModel.updateHomeInput("   ")
        viewModel.submitHomeInput()
        advanceUntilIdle()

        assertEquals(0, aiDraftRepository.createCount)
        assertTrue(viewModel.uiState.value.history.errorMessage?.contains("blank", ignoreCase = true) == true)
    }

    @Test
    fun detailOnlyObservesSelectedConversationAndRestoresSavedId() = runTest(mainDispatcherRule.dispatcher) {
        conversationRepository.insertConversation(conversation("a", "A", 30L))
        conversationRepository.insertConversation(conversation("b", "B", 40L))
        aiDraftRepository.insertChatMessage("a", AiChatMessage(conversationId = "a", role = ChatRole.User, text = "A only"))
        aiDraftRepository.insertChatMessage("b", AiChatMessage(conversationId = "b", role = ChatRole.User, text = "B only"))
        val viewModel = createViewModel(SavedStateHandle(mapOf("conversationId" to "a")))
        advanceUntilIdle()

        assertEquals(listOf("A only"), viewModel.uiState.value.detail.messages.map { it.text })

        viewModel.openConversation("b")
        advanceUntilIdle()

        assertEquals("b", viewModel.uiState.value.detail.currentConversation?.id)
        assertEquals(listOf("B only"), viewModel.uiState.value.detail.messages.map { it.text })
    }

    @Test
    fun homeComposeShowsPromptHistoryAndEmptyState() {
        val state = AiConversationHistoryState(
            conversations = listOf(conversation("a", "Lunch chat", 100L).copy(lastMessagePreview = "noodles")),
            isLoading = false,
            homeInputText = ""
        )

        composeRule.setContent {
            MyApplicationTheme {
                AiRecordHomeScreen(
                    state = state,
                    isAnalyzing = false,
                    onInputChange = {},
                    onSubmit = {},
                    onOpenConversation = {}
                )
            }
        }

        composeRule.onNodeWithTag(AiRecordTestTags.HomeInput).assertIsDisplayed()
        composeRule.onNodeWithText("Lunch chat").assertIsDisplayed()
        composeRule.onNodeWithText("noodles").assertIsDisplayed()
        composeRule.onAllNodesWithTag(AiRecordTestTags.EmptyHistory).assertCountEquals(0)
    }

    @Test
    fun homeComposeEmptyStateAndSendCallbackOnce() {
        var submitted = 0
        composeRule.setContent {
            MyApplicationTheme {
                AiRecordHomeScreen(
                    state = AiConversationHistoryState(isLoading = false, homeInputText = "hello"),
                    isAnalyzing = false,
                    onInputChange = {},
                    onSubmit = { submitted += 1 },
                    onOpenConversation = {}
                )
            }
        }

        composeRule.onNodeWithTag(AiRecordTestTags.EmptyHistory).assertIsDisplayed()
        composeRule.onNodeWithTag(AiRecordTestTags.HomeSend).performClick()
        assertEquals(1, submitted)
    }

    @Test
    fun conversationComposeShowsOnlyProvidedMessagesAndExistingCardRenderer() {
        val messages = listOf(
            AiChatMessage(conversationId = "a", role = ChatRole.User, text = "A visible"),
            AiChatMessage(
                conversationId = "a",
                role = ChatRole.Assistant,
                text = "choose",
                assistantCards = listOf(
                    DebugChoiceCardPayload(
                        id = "card-a",
                        title = "Pick",
                        message = "Pick one",
                        options = listOf(DebugChoiceOption("one", "One"))
                    )
                )
            )
        )

        composeRule.setContent {
            MyApplicationTheme {
                AiConversationScreen(
                    conversationId = "a",
                    detailState = AiConversationDetailState(
                        currentConversation = conversation("a", "A", 1L),
                        messages = messages
                    ),
                    appState = AppState(activeConversationId = "a"),
                    actionHandler = NoOpActionHandler,
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithTag(AiRecordTestTags.ConversationBack).assertIsDisplayed()
        composeRule.onNodeWithText("A visible").assertIsDisplayed()
        composeRule.onNodeWithText("Pick").assertIsDisplayed()
        composeRule.onAllNodesWithText("B hidden").assertCountEquals(0)
    }

    @Test
    fun conversationSendUsesExplicitConversationAndDisablesDuringGeneration() {
        val sent = mutableListOf<Pair<String, String>>()
        composeRule.setContent {
            MyApplicationTheme {
                AiConversationScreen(
                    conversationId = "a",
                    detailState = AiConversationDetailState(currentConversation = conversation("a", "A", 1L)),
                    appState = AppState(activeConversationId = "a", isAnalyzing = true),
                    actionHandler = object : AiRecordActionHandler by NoOpActionHandler {
                        override fun sendAiMessage(conversationId: String, text: String) {
                            sent += conversationId to text
                        }
                    },
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithTag(AiRecordTestTags.ConversationInput).assertIsNotEnabled()
        composeRule.onNodeWithTag(AiRecordTestTags.ConversationSend).assertIsDisplayed()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun dateMismatchGuardRendererShowsPendingApprovedAndCancelledStates() {
        val originalCard = showConfirmCard()
        val pendingGuard = DateMismatchGuardCardPayload(
            id = "guard-1",
            conversationId = "a",
            conversationDate = LocalDate.of(2026, 6, 18),
            detectedCurrentDate = LocalDate.of(2026, 6, 20),
            state = "pending",
            pendingOriginalCard = originalCard
        )
        val guardEvents = mutableListOf<Pair<String, Boolean>>()
        val visibleCard = mutableStateOf(pendingGuard)

        composeRule.setContent {
            MyApplicationTheme {
                AssistantCardRenderer(
                    card = visibleCard.value,
                    actionHandler = object : AiRecordActionHandler by NoOpActionHandler {
                        override fun handleDateMismatchGuardResult(guardId: String, approved: Boolean) {
                            guardEvents += guardId to approved
                        }
                    }
                )
            }
        }

        composeRule.onNodeWithText("正在记录到 6月18日").assertIsDisplayed()
        composeRule.onNodeWithText("继续记录").performClick()
        assertEquals(listOf("guard-1" to true), guardEvents)

        composeRule.runOnIdle { visibleCard.value = pendingGuard.copy(state = "approved") }
        composeRule.onAllNodesWithTag(FoodDraftConfirmCardTestTags.ConfirmButton).assertCountEquals(1)

        composeRule.runOnIdle { visibleCard.value = pendingGuard.copy(state = "cancelled") }
        composeRule.onNodeWithText("已取消，本次内容未记录").assertIsDisplayed()
    }

    @Test
    fun foodConfirmCardShowsNutritionCapsuleWhenAllItemsAreComplete() {
        composeRule.setContent {
            MyApplicationTheme {
                AssistantCardRenderer(
                    card = showConfirmCardWithNutrition(),
                    actionHandler = NoOpActionHandler
                )
            }
        }

        composeRule.onNodeWithTag(FoodDraftConfirmCardTestTags.NutritionCapsule).assertIsDisplayed()
        composeRule.onAllNodesWithTag(FoodDraftConfirmCardTestTags.WeightSection).assertCountEquals(1)
        composeRule.onAllNodesWithTag(FoodDraftConfirmCardTestTags.ConfirmButton).assertCountEquals(1)
        composeRule.onAllNodesWithTag(FoodDraftConfirmCardTestTags.EditFoodButton).assertCountEquals(1)

        // 1. Verify title and label exist
        composeRule.onNodeWithText("营养构成").assertIsDisplayed()
        composeRule.onNodeWithText("按克数占比").assertIsDisplayed()

        // 2. Verify all item labels exist
        composeRule.onNodeWithText("净碳水").assertIsDisplayed()
        composeRule.onNodeWithText("蛋白质").assertIsDisplayed()
        composeRule.onNodeWithText("脂肪").assertIsDisplayed()
        composeRule.onNodeWithText("膳食纤维").assertIsDisplayed()

        // 3. Verify percentages and grams are calculated and formatted correctly
        // total = (30 - 5) + 20 + 10 + 5 = 60
        // net carbs = 25g (42%), protein = 20g (33%), fat = 10g (17%), fiber = 5g (8%)
        composeRule.onNodeWithText("25g").assertIsDisplayed()
        composeRule.onNodeWithText("42%").assertIsDisplayed()
        composeRule.onNodeWithText("20g").assertIsDisplayed()
        composeRule.onNodeWithText("33%").assertIsDisplayed()
        composeRule.onNodeWithText("10g").assertIsDisplayed()
        composeRule.onNodeWithText("17%").assertIsDisplayed()
        composeRule.onNodeWithText("5g").assertIsDisplayed()
        composeRule.onNodeWithText("8%").assertIsDisplayed()

        // 4. Verify contentDescription contains four items semantics
        composeRule.onNodeWithContentDescription("营养构成：净碳水 25克，占比 42%；蛋白质 20克，占比 33%；脂肪 10克，占比 17%；膳食纤维 5克，占比 8%。").assertIsDisplayed()
    }

    @Test
    fun foodConfirmCardHidesNutritionCapsuleWhenAnyItemIsMissingNutrition() {
        val card = showConfirmCardWithNutrition(
            item = nutritionItem().copy(carbohydratesG = null)
        )

        composeRule.setContent {
            MyApplicationTheme {
                AssistantCardRenderer(card = card, actionHandler = NoOpActionHandler)
            }
        }

        composeRule.onAllNodesWithTag(FoodDraftConfirmCardTestTags.NutritionCapsule).assertCountEquals(0)
        composeRule.onAllNodesWithTag(FoodDraftConfirmCardTestTags.ConfirmButton).assertCountEquals(1)
    }

    @Test
    fun foodConfirmCardHandlesZeroRatioSegmentsWithoutCrashing() {
        val card = showConfirmCardWithNutrition(
            item = nutritionItem(
                carbs = 0f,
                protein = 10f,
                fat = 0f,
                fiber = 0f
            )
        )

        composeRule.setContent {
            MyApplicationTheme {
                AssistantCardRenderer(card = card, actionHandler = NoOpActionHandler)
            }
        }

        composeRule.onNodeWithTag(FoodDraftConfirmCardTestTags.NutritionCapsule).assertIsDisplayed()
        composeRule.onAllNodesWithTag(FoodDraftConfirmCardTestTags.ConfirmButton).assertCountEquals(1)
    }

    @Test
    fun foodConfirmCardHandlesFiberGreaterThanCarbsNetCarbsClamp() {
        val card = showConfirmCardWithNutrition(
            item = nutritionItem(
                carbs = 2f,
                protein = 10f,
                fat = 5f,
                fiber = 6f
            )
        )

        composeRule.setContent {
            MyApplicationTheme {
                AssistantCardRenderer(card = card, actionHandler = NoOpActionHandler)
            }
        }

        composeRule.onNodeWithTag(FoodDraftConfirmCardTestTags.NutritionCapsule).assertIsDisplayed()
        // Net carbs should display 0g and 0% because 2 - 6 is clamped to 0.
        composeRule.onNodeWithText("0g").assertIsDisplayed()
        composeRule.onNodeWithText("0%").assertIsDisplayed()
    }

    @Test
    fun foodConfirmCardHidesOnEditInvalidate() {
        val card = showConfirmCardWithNutrition()

        composeRule.setContent {
            MyApplicationTheme {
                AssistantCardRenderer(card = card, actionHandler = NoOpActionHandler)
            }
        }

        composeRule.onNodeWithTag(FoodDraftConfirmCardTestTags.NutritionCapsule).assertIsDisplayed()

        // Open edit dialog
        composeRule.onAllNodesWithTag(FoodDraftConfirmCardTestTags.EditFoodButton)[0].performClick()
        composeRule.waitForIdle()

        // Modify the food name to trigger invalidation
        composeRule.onNode(hasText("rice") and hasSetTextAction()).performTextInput(" edited")
        composeRule.onNodeWithText("确定").performClick()
        composeRule.waitForIdle()

        // Nutrition capsule should eventually be hidden
        composeRule.onAllNodesWithTag(FoodDraftConfirmCardTestTags.NutritionCapsule).assertCountEquals(0)
    }

    @Test
    fun foodConfirmCardShowsOnDeleteIncompleteItem() {
        val complete = nutritionItem().copy(id = "item-complete")
        val incomplete = nutritionItem().copy(id = "item-incomplete", carbohydratesG = null)
        val card = showConfirmCardWithNutrition().copy(
            meals = listOf(
                ConfirmCardMeal(
                    mealType = "lunch",
                    mealLabel = "Lunch",
                    subtotalCalories = 600,
                    items = listOf(complete, incomplete)
                )
            )
        )

        composeRule.setContent {
            MyApplicationTheme {
                AssistantCardRenderer(card = card, actionHandler = NoOpActionHandler)
            }
        }

        // Initially capsule is hidden
        composeRule.onAllNodesWithTag(FoodDraftConfirmCardTestTags.NutritionCapsule).assertCountEquals(0)

        // Delete the incomplete item
        composeRule.onAllNodesWithTag(FoodDraftConfirmCardTestTags.DeleteFoodButton)[1].performClick()
        composeRule.waitForIdle()

        // Capsule should now be displayed
        composeRule.onNodeWithTag(FoodDraftConfirmCardTestTags.NutritionCapsule).assertIsDisplayed()
    }

    @Test
    fun foodConfirmCardAnimatesNutritionGramsAndRatios() {
        composeRule.mainClock.autoAdvance = false

        composeRule.setContent {
            MyApplicationTheme {
                AssistantCardRenderer(
                    card = showConfirmCardWithNutrition(),
                    actionHandler = NoOpActionHandler
                )
            }
        }

        // Trigger LaunchedEffect for AnimatedVisibility wrapper
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame() // allow side-effects to run
        
        // Advance time to allow expandVertically to give the container some height (>0)
        // so assertIsDisplayed() doesn't fail due to 0-size bounds
        composeRule.mainClock.advanceTimeBy(100)

        // At start of the numbers animation (delay is 160ms+), ring percentage should still be 0
        // Grams should be immediately displayed
        composeRule.onNodeWithText("25g").assertIsDisplayed()
        composeRule.onNodeWithText("0%").assertIsDisplayed()

        // Advance to end
        composeRule.mainClock.advanceTimeBy(5000)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        // Verify final state
        composeRule.onNodeWithText("25g").assertIsDisplayed()
        composeRule.onNodeWithText("42%").assertIsDisplayed()
    }

    private fun createViewModel(savedStateHandle: SavedStateHandle = SavedStateHandle()): AiRecordViewModel {
        return AiRecordViewModel(
            conversationRepository = conversationRepository,
            aiDraftRepository = aiDraftRepository,
            createConversationWithFirstMessageUseCase = CreateConversationWithFirstMessageUseCase(aiDraftRepository),
            savedStateHandle = savedStateHandle
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FeatureMainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

private class InMemoryConversationRepository : ConversationRepository {
    private val conversations = MutableStateFlow<List<Conversation>>(emptyList())

    override suspend fun insertConversation(conversation: Conversation) {
        conversations.update { current ->
            (current.filterNot { it.id == conversation.id } + conversation)
                .sortedWith(compareByDescending<Conversation> { it.lastActivityAt }.thenByDescending { it.createdAt })
        }
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

private class InMemoryAiDraftRepository(
    private val conversationRepository: ConversationRepository
) : AiDraftRepository {
    private val messages = MutableStateFlow<List<AiChatMessage>>(emptyList())
    var createCount = 0
        private set

    private val streamingStates = MutableStateFlow<Map<String, StreamingState>>(emptyMap())

    data class StreamingState(
        val conversationId: String,
        val messageId: String,
        val text: String,
        val isStreaming: Boolean
    )

    override fun updateStreamingState(conversationId: String, messageId: String, text: String, isStreaming: Boolean) {
        streamingStates.value = streamingStates.value + (conversationId to StreamingState(conversationId, messageId, text, isStreaming))
    }

    override fun clearStreamingState(conversationId: String) {
        streamingStates.value = streamingStates.value - conversationId
    }

    override suspend fun generateDraft(request: AiDraftRequest): CheckinDraft = error("unused")

    override fun observeChatMessages(): Flow<List<AiChatMessage>> {
        return kotlinx.coroutines.flow.combine(messages, streamingStates) { msgs, states ->
            msgs.map { msg ->
                val state = states[msg.conversationId]
                if (state != null && msg.id == state.messageId) {
                    msg.copy(text = state.text)
                } else {
                    msg
                }
            }
        }
    }

    override fun observeChatMessages(conversationId: String): Flow<List<AiChatMessage>> {
        return kotlinx.coroutines.flow.combine(messages, streamingStates) { msgs, states ->
            val state = states[conversationId]
            msgs.filter { it.conversationId == conversationId }.map { msg ->
                if (state != null && msg.id == state.messageId) {
                    msg.copy(text = state.text)
                } else {
                    msg
                }
            }
        }
    }

    override suspend fun createConversationWithFirstMessage(text: String, now: Long): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null
        createCount += 1
        val conversationId = UUID.randomUUID().toString()
        conversationRepository.insertConversation(
            Conversation(
                id = conversationId,
                conversationDate = LocalDate.now(),
                title = trimmed,
                lastMessagePreview = trimmed,
                createdAt = now,
                updatedAt = now,
                lastActivityAt = now
            )
        )
        insertChatMessage(
            conversationId,
            AiChatMessage(
                conversationId = conversationId,
                role = ChatRole.User,
                text = trimmed,
                createdAt = now
            )
        )
        return conversationId
    }

    override suspend fun getRecentChatMessages(conversationId: String, limit: Int): List<AiChatMessage> {
        return messages.value.filter { it.conversationId == conversationId }.takeLast(limit)
    }

    override suspend fun findMessageByAssistantCardId(cardId: String): AiChatMessage? {
        return messages.value.find { message ->
            message.assistantCards.any {
                it.id == cardId ||
                    (it is DateMismatchGuardCardPayload && it.pendingOriginalCard.id == cardId)
            }
        }
    }

    override suspend fun insertChatMessage(message: AiChatMessage) {
        messages.update { it + message }
    }

    override suspend fun insertChatMessage(conversationId: String, message: AiChatMessage) {
        messages.update { it + message.copy(conversationId = conversationId) }
    }

    override suspend fun updateChatMessage(message: AiChatMessage) {
        messages.update { current -> current.map { if (it.id == message.id) message else it } }
    }

    override suspend fun clearChatMessages() {
        messages.value = emptyList()
    }
}

private fun conversation(id: String, title: String, activity: Long): Conversation {
    return Conversation(
        id = id,
        conversationDate = LocalDate.of(2026, 6, 20),
        title = title,
        lastMessagePreview = title,
        createdAt = activity,
        updatedAt = activity,
        lastActivityAt = activity
    )
}

private object NoOpActionHandler : AiRecordActionHandler {
    override fun sendAiMessage(text: String) = Unit
    override fun sendAiMessage(conversationId: String, text: String) = Unit
    override fun startAssistantTurnForExistingUserMessage(conversationId: String, text: String) = Unit
    override fun setActiveConversationId(conversationId: String?) = Unit
    override fun sendInteractionResult(
        interactionId: String,
        actionType: String,
        optionId: String,
        optionLabel: String,
        field: String?,
        originalText: String?,
        confirmType: String?,
        payloadSummary: PayloadSummary?
    ) = Unit

    override fun handleDateMismatchGuardResult(guardId: String, approved: Boolean) = Unit

    override fun updateFoodDraftCard(
        interactionId: String,
        weightKg: Double?,
        meals: List<ConfirmCardMeal>
    ) = Unit

    override fun clearChatMessages() = Unit
    override fun clearLocalRecords() = Unit
    override fun clearAllData() = Unit
    override fun clearCloudBackupForDebug() = Unit
    override fun markAssistantMessageRendered(message: AiChatMessage) = Unit
}

private fun showConfirmCard(): ShowConfirmCardPayload {
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
                calorieConfidence = "medium"
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
        weightKg = 72.5,
        totalCalories = 300,
        meals = listOf(meal),
        buttons = listOf(
            ConfirmCardOption("cancel", "Cancel"),
            ConfirmCardOption("confirm", "Confirm")
        )
    )
}

private fun showConfirmCardWithNutrition(
    item: ConfirmCardItem = nutritionItem()
): ShowConfirmCardPayload {
    val meal = ConfirmCardMeal(
        mealType = "lunch",
        mealLabel = "Lunch",
        subtotalCalories = item.calories,
        items = listOf(item)
    )
    return ShowConfirmCardPayload(
        id = "confirm-card",
        confirmType = "food_record",
        title = "Confirm",
        message = "Confirm food",
        originalText = "rice",
        mealType = null,
        items = emptyList(),
        weightKg = 72.5,
        totalCalories = item.calories,
        meals = listOf(meal),
        buttons = listOf(
            ConfirmCardOption("cancel", "Cancel"),
            ConfirmCardOption("confirm", "Confirm")
        )
    )
}

private fun nutritionItem(
    carbs: Float? = 30f,
    protein: Float? = 20f,
    fat: Float? = 10f,
    fiber: Float? = 5f
): ConfirmCardItem {
    return ConfirmCardItem(
        id = "item-1",
        name = "rice",
        amountText = "1 bowl",
        calories = 300,
        calorieConfidence = "estimated",
        carbohydratesG = carbs,
        proteinG = protein,
        fatG = fat,
        fiberG = fiber
    )
}
