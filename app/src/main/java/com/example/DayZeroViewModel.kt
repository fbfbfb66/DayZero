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
import com.example.domain.model.MealType
import com.example.domain.model.RecordStatus
import com.example.domain.model.ai.AiDraftRequest
import com.example.domain.repository.AiDraftRepository
import com.example.domain.repository.RecordRepository
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
    }

    private fun observeRecords() {
        recordRepository.observeRecords()
            .onEach { allRecords ->
                _uiState.update { it.copy(records = allRecords) }
            }
            .launchIn(viewModelScope)
    }

    fun generateDraftFromText(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzing = true) }
            try {
                val request = AiDraftRequest(
                    date = _uiState.value.currentDate,
                    text = text
                )
                val draft = aiDraftRepository.generateDraft(request)
                val dailyRecord = draftMapper.toDailyRecord(draft)
                
                // Save as Draft to Room
                recordRepository.upsertRecord(dailyRecord)
            } catch (e: Exception) {
                _uiEvents.emit(UiEvent.Error("AI 分析暂时失败了，可以稍后再试。"))
            } finally {
                _uiState.update { it.copy(isAnalyzing = false) }
            }
        }
    }

    fun confirmDraft(recordId: String, newWeight: Float?) {
        viewModelScope.launch {
            recordRepository.updateRecordStatus(
                recordId = recordId,
                status = RecordStatus.Confirmed,
                weightKg = newWeight
            )
            _uiEvents.emit(UiEvent.RecordConfirmed)
        }
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
                    RemoteAiDraftRepository(NetworkModule.aiDraftApiService)
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
