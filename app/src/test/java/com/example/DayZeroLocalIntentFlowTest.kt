package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.FakeAiDraftRepository
import com.example.data.telemetry.AiLatencyTraceLogger
import com.example.domain.model.DailyRecord
import com.example.domain.model.MealType
import com.example.domain.model.RecordStatus
import com.example.domain.model.ai.AiRecordConversationState
import com.example.domain.model.ai.ChatRole
import com.example.domain.model.ai.assistant.AiAssistantRequest
import com.example.domain.model.ai.assistant.AiAssistantTurn
import com.example.domain.model.ai.assistant.AiIntent
import com.example.domain.repository.AiAssistantRepository
import com.example.domain.repository.RecordRepository
import com.example.domain.usecase.ClearLocalDataUseCase
import com.example.domain.usecase.ConfirmFoodRecordUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DayZeroLocalIntentFlowTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var recordRepository: InMemoryRecordRepository
    private lateinit var aiDraftRepository: FakeAiDraftRepository

    @Before
    fun setUp() {
        recordRepository = InMemoryRecordRepository()
        aiDraftRepository = FakeAiDraftRepository()
    }

    @Test
    fun foodInputOnlyReturnsChatReply() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = createViewModel("Pure chat reply for a food message.")
        sendAndAssertPureChat(viewModel, "Food input: pork rice noodle roll")
    }

    @Test
    fun weightInputOnlyReturnsChatReply() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = createViewModel("Pure chat reply for a weight message.")
        sendAndAssertPureChat(viewModel, "Weight input: 94kg today")
    }

    @Test
    fun summaryInputOnlyReturnsChatReply() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = createViewModel("Pure chat reply for a summary question.")
        sendAndAssertPureChat(viewModel, "How did I eat today?")
    }

    @Test
    fun cravingInputOnlyReturnsChatReply() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = createViewModel("Pure chat reply for a craving message.")
        sendAndAssertPureChat(viewModel, "I cannot stop craving fried chicken")
    }

    @Test
    fun assistantTurnFailureShowsErrorWithoutFallback() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = DayZeroViewModel(
            recordRepository = recordRepository,
            aiDraftRepository = aiDraftRepository,
            aiAssistantRepository = object : AiAssistantRepository {
                override suspend fun sendMessage(request: AiAssistantRequest): AiAssistantTurn {
                    error("network down")
                }
            },
            latencyLogger = createLatencyLogger(),
            clearLocalDataUseCase = ClearLocalDataUseCase(recordRepository, aiDraftRepository),
            confirmFoodRecordUseCase = ConfirmFoodRecordUseCase(recordRepository)
        )

        viewModel.sendAiMessage("Weight input: 94kg today")
        advanceUntilIdle()

        val messages = viewModel.uiState.value.chatMessages
        assertEquals(2, messages.size)
        assertEquals(ChatRole.User, messages[0].role)
        assertEquals(ChatRole.Assistant, messages[1].role)
        assertTrue(messages[1].text.isBlank())
        assertTrue(messages[1].assistantCards.isEmpty())
        assertTrue(recordRepository.records.value.isEmpty())
        assertEquals(false, viewModel.uiState.value.isAnalyzing)
        assertTrue(viewModel.uiState.value.conversationState is AiRecordConversationState.Error)
    }

    private fun createViewModel(assistantReply: String): DayZeroViewModel {
        return DayZeroViewModel(
            recordRepository = recordRepository,
            aiDraftRepository = aiDraftRepository,
            aiAssistantRepository = object : AiAssistantRepository {
                override suspend fun sendMessage(request: AiAssistantRequest): AiAssistantTurn {
                    return AiAssistantTurn(
                        id = "turn-1",
                        intent = AiIntent.GeneralChat,
                        replyText = assistantReply,
                        cards = emptyList(),
                        suggestedReplies = emptyList()
                    )
                }
            },
            latencyLogger = createLatencyLogger(),
            clearLocalDataUseCase = ClearLocalDataUseCase(recordRepository, aiDraftRepository),
            confirmFoodRecordUseCase = ConfirmFoodRecordUseCase(recordRepository)
        )
    }

    private fun createLatencyLogger(): AiLatencyTraceLogger {
        return AiLatencyTraceLogger(ApplicationProvider.getApplicationContext())
    }

    private suspend fun TestScope.sendAndAssertPureChat(viewModel: DayZeroViewModel, text: String) {
        viewModel.sendAiMessage(text)
        advanceUntilIdle()

        val messages = viewModel.uiState.value.chatMessages
        assertEquals(2, messages.size)
        assertEquals(ChatRole.User, messages[0].role)
        assertEquals(text, messages[0].text)
        assertEquals(ChatRole.Assistant, messages[1].role)
        assertTrue(messages[1].text.isNotBlank())
        assertTrue(messages[1].assistantCards.isEmpty())
        assertTrue(messages[1].suggestedReplies.isEmpty())
        assertTrue(recordRepository.records.value.isEmpty())
        assertEquals(AiRecordConversationState.Idle, viewModel.uiState.value.conversationState)
        assertEquals(false, viewModel.uiState.value.isAnalyzing)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

private class InMemoryRecordRepository : RecordRepository {
    private val _records = MutableStateFlow<List<DailyRecord>>(emptyList())
    val records = _records.asStateFlow()

    override fun observeRecords(): Flow<List<DailyRecord>> = records

    override suspend fun upsertRecord(record: DailyRecord) {
        _records.update { current ->
            val index = current.indexOfFirst { it.id == record.id }
            if (index >= 0) {
                current.toMutableList().apply { set(index, record) }
            } else {
                current + record
            }
        }
    }

    override suspend fun deleteRecordById(recordId: String) {
        _records.update { records -> records.filterNot { it.id == recordId } }
    }

    override suspend fun getRecordById(recordId: String): DailyRecord? {
        return records.value.find { it.id == recordId }
    }

    override suspend fun getRecordByDateAndStatus(date: LocalDate, status: RecordStatus): DailyRecord? {
        return records.value.find { it.date == date && it.status == status }
    }

    override suspend fun updateRecordStatus(recordId: String, status: RecordStatus, weightKg: Float?) {
        _records.update { current ->
            current.map { record ->
                if (record.id == recordId) {
                    record.copy(status = status, weightKg = weightKg ?: record.weightKg)
                } else {
                    record
                }
            }
        }
    }

    override suspend fun deleteFoodFromRecord(recordId: String, mealType: MealType, foodId: String) {
        _records.update { current ->
            current.map { record ->
                if (record.id == recordId) {
                    record.copy(
                        meals = record.meals.map { meal ->
                            if (meal.mealType == mealType) {
                                meal.copy(foods = meal.foods.filterNot { it.id == foodId })
                            } else {
                                meal
                            }
                        }
                    )
                } else {
                    record
                }
            }
        }
    }

    override suspend fun clearAllRecords() {
        _records.value = emptyList()
    }
}
