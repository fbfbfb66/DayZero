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
import com.example.domain.model.ai.ChatActionType
import com.example.domain.model.ai.ChatRole
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

sealed interface UiEvent {
    data object RecordConfirmed : UiEvent
    data class Error(val message: String) : UiEvent
}

class DayZeroViewModel(
    private val recordRepository: RecordRepository,
    private val aiDraftRepository: AiDraftRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppState())
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
                val request = AiDraftRequest(
                    date = _uiState.value.currentDate,
                    text = text
                )
                val draft = aiDraftRepository.generateDraft(request)
                val dailyRecord = draftMapper.toDailyRecord(draft)
                
                recordRepository.upsertRecord(dailyRecord)
                
                aiDraftRepository.insertChatMessage(AiChatMessage(
                    role = ChatRole.Assistant, 
                    text = "我先帮你估算了一版，你可以修改后再确认。",
                    relatedDraftId = dailyRecord.id
                ))
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
                val draftMealTypes = draft.meals.map { it.mealType }.toSet()
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
                        actionType = ChatActionType.MealConflict,
                        relatedDraftId = draftId
                    ))
                }
            }
        }
    }

    fun handleConflictResolution(action: ConflictAction) {
        val state = _uiState.value.conflictState ?: return
        viewModelScope.launch {
            val draft = recordRepository.getRecordById(state.draftId) ?: return@launch
            val existingConfirmed = recordRepository.getRecordByDateAndStatus(draft.date, RecordStatus.Confirmed) ?: return@launch

            when (action) {
                ConflictAction.Cancel -> {
                    _uiState.update { it.copy(conflictState = null) }
                }
                ConflictAction.AddNonConflicting -> {
                    val draftMealTypes = draft.meals.map { it.mealType }.toSet()
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
                ConflictAction.Overwrite -> {
                    mergeAndSave(existingConfirmed, draft, state.weightKg, draft.meals.map { it.mealType }.toSet())
                    recordRepository.deleteRecordById(state.draftId)
                    completeConfirmation()
                }
            }
        }
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

enum class ConflictAction {
    Cancel, AddNonConflicting, Overwrite
}
