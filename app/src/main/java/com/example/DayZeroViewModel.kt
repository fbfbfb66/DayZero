package com.example

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.data.local.database.DayZeroDatabase
import com.example.data.repository.RoomRecordRepository
import com.example.domain.model.AppState
import com.example.domain.model.MealType
import com.example.domain.model.RecordStatus
import com.example.domain.repository.RecordRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DayZeroViewModel(
    private val recordRepository: RecordRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppState())
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()

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

    fun confirmDraft(recordId: String, newWeight: Float?) {
        viewModelScope.launch {
            recordRepository.updateRecordStatus(
                recordId = recordId,
                status = RecordStatus.Confirmed,
                weightKg = newWeight
            )
            _uiState.update { 
                it.copy(aiMessage = "成功确认！今日记录已更新。")
            }
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
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras
            ): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                val database = DayZeroDatabase.getDatabase(application)
                return DayZeroViewModel(
                    recordRepository = RoomRecordRepository(database.dailyRecordDao())
                ) as T
            }
        }
    }
}
