package com.example

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.data.local.database.DayZeroDatabase
import com.example.data.remote.NetworkModule
import com.example.data.repository.FakeAiDraftRepository
import com.example.data.repository.RemoteAiDraftRepository
import com.example.data.repository.RoomRecordRepository
import com.example.domain.mapper.CheckinDraftMapper
import com.example.domain.model.AppState
import com.example.domain.model.ConflictState
import com.example.domain.model.DailyRecord
import com.example.domain.model.MealType
import com.example.domain.model.RecordStatus
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.AiDraftRequest
import com.example.domain.model.ai.ChatAction
import com.example.domain.model.ai.ChatMessageType
import com.example.domain.model.ai.ChatOption
import com.example.domain.model.ai.ChatRole
import com.example.domain.model.ai.ChoiceCard
import com.example.domain.repository.AiDraftRepository
import com.example.domain.repository.RecordRepository
import com.example.domain.summary.DailySummaryBuilder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed interface UiEvent {
    data object RecordConfirmed : UiEvent
    data class Error(val message: String) : UiEvent
}

class DayZeroViewModel(
    private val recordRepository: RecordRepository,
    private val aiDraftRepository: AiDraftRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppState(currentDate = LocalDate.now()))
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()
    
    private val draftMapper = CheckinDraftMapper()

    init {
        observeRecords()
        observeChatMessages()
    }

    private fun observeRecords() {
        recordRepository.observeRecords()
            .onEach { allRecords ->
                _uiState.update { it.copy(records = allRecords) }
            }
            .launchIn(viewModelScope)
    }

    private fun observeChatMessages() {
        aiDraftRepository.observeChatMessages()
            .onEach { messages ->
                _uiState.update { it.copy(chatMessages = messages) }
            }
            .launchIn(viewModelScope)
    }

    fun generateDraftFromText(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            val userMessage = AiChatMessage(role = ChatRole.User, text = text)
            aiDraftRepository.insertChatMessage(userMessage)

            _uiState.update { it.copy(isAnalyzing = true) }
            try {
                // Build context
                val date = _uiState.value.currentDate
                val confirmedToday = _uiState.value.records.find { it.date == date && it.status == RecordStatus.Confirmed }
                val contextText = confirmedToday?.let { 
                    val meals = it.meals.filter { m -> m.foods.isNotEmpty() }.joinToString { m -> "${m.mealType.displayName}:${m.foods.joinToString { f -> f.name }}" }
                    "【当天已记录：$meals，总热量约 ${it.totalCalories} kcal】"
                } ?: ""
                
                val recentChat = _uiState.value.chatMessages.takeLast(5).joinToString("\n") { "${it.role}: ${it.text}" }
                val fullText = if (contextText.isNotEmpty()) "$contextText\n$recentChat\n用户新输入: $text" else "$recentChat\n用户新输入: $text"

                val request = AiDraftRequest(
                    date = date,
                    text = fullText
                )
                val draft = aiDraftRepository.generateDraft(request)
                val dailyRecord = draftMapper.toDailyRecord(draft).copy(date = date)
                
                recordRepository.upsertRecord(dailyRecord)
                
                // Rule: If AI returns only Snack and user didn't specify time, ask
                val isOnlySnack = dailyRecord.meals.all { it.mealType == MealType.Snack || it.foods.isEmpty() }
                val hasTimeWord = listOf("早", "午", "中", "晚", "下午", "夜", "零食").any { text.contains(it) }
                
                if (isOnlySnack && !hasTimeWord && dailyRecord.meals.any { it.foods.isNotEmpty() }) {
                    aiDraftRepository.insertChatMessage(AiChatMessage(
                        role = ChatRole.Assistant, 
                        text = "这个是在哪一餐吃的呀？",
                        messageType = ChatMessageType.ChoiceCard,
                        choiceCard = ChoiceCard(
                            title = "选择餐次",
                            options = listOf(
                                ChatOption("1", "早餐", ChatAction.SetMealTypeBreakfast),
                                ChatOption("2", "午餐", ChatAction.SetMealTypeLunch),
                                ChatOption("3", "晚餐", ChatAction.SetMealTypeDinner),
                                ChatOption("4", "加餐", ChatAction.SetMealTypeSnack)
                            )
                        ),
                        relatedDraftId = dailyRecord.id
                    ))
                } else {
                    aiDraftRepository.insertChatMessage(AiChatMessage(
                        role = ChatRole.Assistant, 
                        text = "我先帮你估算了一版，你可以修改后再确认。",
                        relatedDraftId = dailyRecord.id
                    ))
                }
            } catch (e: Exception) {
                aiDraftRepository.insertChatMessage(AiChatMessage(role = ChatRole.Assistant, text = "这次分析失败了，可以稍后再试。"))
                _uiEvents.emit(UiEvent.Error("AI 分析暂时失败了，可以稍后再试。"))
            } finally {
                _uiState.update { it.copy(isAnalyzing = false) }
            }
        }
    }

    fun confirmDraftWithMerge(draftId: String, weightKg: Float?) {
        viewModelScope.launch {
            val draft = recordRepository.getRecordById(draftId) ?: return@launch
            val existingConfirmed = recordRepository.getRecordByDateAndStatus(draft.date, RecordStatus.Confirmed)

            if (existingConfirmed == null) {
                val confirmedRecord = draft.copy(
                    status = RecordStatus.Confirmed,
                    weightKg = weightKg ?: draft.weightKg
                )
                val finalRecord = confirmedRecord.copy(aiSummary = DailySummaryBuilder.buildSummary(confirmedRecord))
                recordRepository.upsertRecord(finalRecord)
                recordRepository.deleteRecordById(draftId)
                completeConfirmation()
            } else {
                val draftMealTypes = draft.meals.filter { it.foods.isNotEmpty() }.map { it.mealType }.toSet()
                val existingMealTypes = existingConfirmed.meals.filter { it.foods.isNotEmpty() }.map { it.mealType }.toSet()
                val conflicts = draftMealTypes.intersect(existingMealTypes)

                if (conflicts.isEmpty()) {
                    mergeAndSave(existingConfirmed, draft, weightKg, draftMealTypes)
                    recordRepository.deleteRecordById(draftId)
                    completeConfirmation()
                } else {
                    _uiState.update { 
                        it.copy(conflictState = ConflictState(
                            draftId = draftId,
                            existingMealTypes = conflicts.toList(),
                            weightKg = weightKg
                        ))
                    }
                    val conflictNames = conflicts.joinToString("、") { it.displayName }
                    aiDraftRepository.insertChatMessage(AiChatMessage(
                        role = ChatRole.Assistant, 
                        text = "我发现今天已经有 $conflictNames 的记录，需要你确认如何处理。",
                        messageType = ChatMessageType.ChoiceCard,
                        choiceCard = ChoiceCard(
                            title = "已有餐次记录",
                            options = listOf(
                                ChatOption("c1", "取消", ChatAction.Cancel),
                                ChatOption("c2", "仅添加未冲突餐次", ChatAction.AddNonConflictingMeals),
                                ChatOption("c3", "覆盖并录入", ChatAction.OverrideConflictingMeals)
                            )
                        ),
                        relatedDraftId = draftId
                    ))
                }
            }
        }
    }

    fun handleChatAction(messageId: String, option: ChatOption) {
        viewModelScope.launch {
            // Mark as resolved
            val message = _uiState.value.chatMessages.find { it.id == messageId } ?: return@launch
            val resolvedMessage = message.copy(choiceCard = message.choiceCard?.copy(resolved = true))
            aiDraftRepository.updateChatMessage(resolvedMessage)
            
            // Add user response message
            aiDraftRepository.insertChatMessage(AiChatMessage(role = ChatRole.User, text = option.label))

            when (option.action) {
                ChatAction.Cancel -> {
                    _uiState.update { it.copy(conflictState = null) }
                }
                ChatAction.AddNonConflictingMeals -> {
                    val state = _uiState.value.conflictState ?: return@launch
                    val draft = recordRepository.getRecordById(state.draftId) ?: return@launch
                    val existingConfirmed = recordRepository.getRecordByDateAndStatus(draft.date, RecordStatus.Confirmed) ?: return@launch
                    
                    val draftMealTypes = draft.meals.filter { it.foods.isNotEmpty() }.map { it.mealType }.toSet()
                    val existingMealTypes = existingConfirmed.meals.filter { it.foods.isNotEmpty() }.map { it.mealType }.toSet()
                    val nonConflicting = draftMealTypes.subtract(existingMealTypes)
                    
                    if (nonConflicting.isEmpty()) {
                        _uiEvents.emit(UiEvent.Error("没有新的餐次可添加"))
                    } else {
                        mergeAndSave(existingConfirmed, draft, state.weightKg, nonConflicting)
                        recordRepository.deleteRecordById(state.draftId)
                        completeConfirmation()
                    }
                }
                ChatAction.OverrideConflictingMeals -> {
                    val state = _uiState.value.conflictState ?: return@launch
                    val draft = recordRepository.getRecordById(state.draftId) ?: return@launch
                    val existingConfirmed = recordRepository.getRecordByDateAndStatus(draft.date, RecordStatus.Confirmed) ?: return@launch
                    
                    mergeAndSave(existingConfirmed, draft, state.weightKg, draft.meals.filter { it.foods.isNotEmpty() }.map { it.mealType }.toSet())
                    recordRepository.deleteRecordById(state.draftId)
                    completeConfirmation()
                }
                ChatAction.SetMealTypeBreakfast -> updateDraftMealType(message.relatedDraftId, MealType.Breakfast)
                ChatAction.SetMealTypeLunch -> updateDraftMealType(message.relatedDraftId, MealType.Lunch)
                ChatAction.SetMealTypeDinner -> updateDraftMealType(message.relatedDraftId, MealType.Dinner)
                ChatAction.SetMealTypeSnack -> updateDraftMealType(message.relatedDraftId, MealType.Snack)
            }
        }
    }

    private suspend fun updateDraftMealType(draftId: String?, newType: MealType) {
        if (draftId == null) return
        val draft = recordRepository.getRecordById(draftId) ?: return
        val updatedMeals = draft.meals.map { it.copy(mealType = newType) }
        recordRepository.upsertRecord(draft.copy(meals = updatedMeals))
        aiDraftRepository.insertChatMessage(AiChatMessage(role = ChatRole.Assistant, text = "好的，已更新为${newType.displayName}。你可以检查草稿卡片后确认。"))
    }

    private suspend fun mergeAndSave(
        existing: DailyRecord, 
        draft: DailyRecord, 
        newWeight: Float?, 
        mealsToTakeFromDraft: Set<MealType>
    ) {
        val mergedMeals = existing.meals.toMutableList()
        
        draft.meals.forEach { draftMeal ->
            if (mealsToTakeFromDraft.contains(draftMeal.mealType)) {
                val index = mergedMeals.indexOfFirst { it.mealType == draftMeal.mealType }
                if (index >= 0) {
                    mergedMeals[index] = draftMeal
                } else {
                    mergedMeals.add(draftMeal)
                }
            }
        }

        val updatedRecord = existing.copy(
            meals = mergedMeals,
            weightKg = newWeight ?: existing.weightKg
        )
        val finalRecord = updatedRecord.copy(aiSummary = DailySummaryBuilder.buildSummary(updatedRecord))
        recordRepository.upsertRecord(finalRecord)
    }

    private suspend fun completeConfirmation() {
        _uiState.update { it.copy(conflictState = null) }
        _uiEvents.emit(UiEvent.RecordConfirmed)
        aiDraftRepository.insertChatMessage(AiChatMessage(role = ChatRole.Assistant, text = "已更新今天的记录。"))
    }

    fun removeFood(recordId: String, mealType: MealType, foodId: String) {
        viewModelScope.launch {
            recordRepository.deleteFoodFromRecord(
                recordId = recordId,
                mealType = mealType,
                foodId = foodId
            )
        }
    }

    companion object {
        private const val USE_REMOTE_AI = true

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras
            ): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                val database = DayZeroDatabase.getDatabase(application)
                
                val aiRepository = if (USE_REMOTE_AI) {
                    RemoteAiDraftRepository(
                        NetworkModule.aiDraftApiService,
                        database.aiChatMessageDao()
                    )
                } else {
                    FakeAiDraftRepository()
                }

                return DayZeroViewModel(
                    recordRepository = RoomRecordRepository(database.dailyRecordDao()),
                    aiDraftRepository = aiRepository
                ) as T
            }
        }
    }
}
