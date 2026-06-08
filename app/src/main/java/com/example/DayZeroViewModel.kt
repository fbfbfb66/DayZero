package com.example

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.data.local.database.DayZeroDatabase
import com.example.data.remote.NetworkModule
import com.example.data.repository.FakeAiAssistantRepository
import com.example.data.repository.FakeAiDraftRepository
import com.example.data.repository.RemoteAiAssistantRepository
import com.example.data.repository.RemoteAiDraftRepository
import com.example.data.repository.RoomRecordRepository
import com.example.domain.model.AppState
import com.example.domain.model.RecordStatus
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.AiRecordConversationState
import com.example.domain.model.ai.ChatRole
import com.example.domain.model.ai.assistant.AiAssistantRequest
import com.example.domain.model.ai.assistant.ProtocolException
import com.example.domain.repository.AiAssistantRepository
import com.example.domain.repository.AiDraftRepository
import com.example.domain.repository.RecordRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed class UiEvent {
    object RecordConfirmed : UiEvent()
    data class Error(val message: String) : UiEvent()
}

class DayZeroViewModel(
    private val recordRepository: RecordRepository,
    private val aiDraftRepository: AiDraftRepository,
    private val aiAssistantRepository: AiAssistantRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppState(currentDate = LocalDate.now()))
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    init {
        observeRecords()
        observeChatMessages()
    }

    private fun observeRecords() {
        viewModelScope.launch {
            recordRepository.observeRecords().collect { records ->
                _uiState.update { it.copy(records = records) }
            }
        }
    }

    private fun observeChatMessages() {
        viewModelScope.launch {
            aiDraftRepository.observeChatMessages().collect { messages ->
                _uiState.update { it.copy(chatMessages = messages) }
            }
        }
    }

    fun sendAiMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        val userMessage = AiChatMessage(role = ChatRole.User, text = trimmed)
        Log.d("DayZeroAiV2", "send message: '$trimmed'")

        _uiState.update {
            it.copy(
                isAnalyzing = true,
                conversationState = AiRecordConversationState.Idle,
                chatMessages = it.chatMessages + userMessage
            )
        }

        viewModelScope.launch {
            try {
                aiDraftRepository.insertChatMessage(userMessage)
                requestAssistantTurnV2(trimmed)
            } catch (e: Exception) {
                handleAssistantTurnV2Error(e)
            }
        }
    }

    private suspend fun requestAssistantTurnV2(text: String) {
        Log.d("DayZeroAiV2", "assistant-turn-v2 start")

        val currentDate = _uiState.value.currentDate
        val todayRecord = recordRepository.getRecordByDateAndStatus(currentDate, RecordStatus.Confirmed)
        val request = AiAssistantRequest(
            date = currentDate,
            userText = text,
            todayRecord = todayRecord,
            pendingDraft = null,
            recentMessages = _uiState.value.chatMessages.takeLast(10),
            turnType = "user_message"
        )

        try {
            val turn = aiAssistantRepository.sendMessage(request)
            val reply = turn.replyText.trim()

            aiDraftRepository.insertChatMessage(
                AiChatMessage(
                    role = ChatRole.Assistant,
                    text = if (reply.isBlank()) "好的，已为你处理：" else reply,
                    assistantCards = turn.cards
                )
            )

            _uiState.update {
                it.copy(
                    isAnalyzing = false,
                    conversationState = AiRecordConversationState.Idle
                )
            }
            Log.d("DayZeroAiV2", "assistant-turn-v2 success")
        } catch (e: Exception) {
            handleAssistantTurnV2Error(e)
        }
    }

    fun sendInteractionResult(
        interactionId: String,
        actionType: String,
        optionId: String,
        optionLabel: String,
        field: String? = null,
        originalText: String? = null,
        confirmType: String? = null,
        payloadSummary: com.example.domain.model.ai.assistant.PayloadSummary? = null
    ) {
        if (actionType == "show_confirm_card" && confirmType == "food_record") {
            handleFoodRecordConfirm(interactionId, optionId, payloadSummary)
            return
        }

        Log.d("DayZeroAiV2", "interaction_result created")
        Log.d("DayZeroAiV2", "interaction_result actionType=$actionType")
        Log.d("DayZeroAiV2", "interaction_result selectedOptionId=$optionId")
        Log.d("DayZeroAiV2", "interaction_result send to assistant-turn-v2")

        _uiState.update {
            it.copy(
                isAnalyzing = true,
                conversationState = AiRecordConversationState.Idle
            )
        }

        viewModelScope.launch {
            try {
                val currentDate = _uiState.value.currentDate
                val todayRecord = recordRepository.getRecordByDateAndStatus(currentDate, RecordStatus.Confirmed)
                val request = AiAssistantRequest(
                    date = currentDate,
                    userText = "已选择：$optionLabel",
                    todayRecord = todayRecord,
                    pendingDraft = null,
                    recentMessages = _uiState.value.chatMessages.takeLast(10),
                    turnType = "interaction_result",
                    interactionResult = com.example.domain.model.ai.assistant.InteractionResult(
                        interactionId = interactionId,
                        actionType = actionType,
                        selectedOptionId = optionId,
                        selectedOptionLabel = optionLabel,
                        field = field,
                        originalText = originalText,
                        confirmType = confirmType,
                        payloadSummary = payloadSummary
                    )
                )

                val turn = aiAssistantRepository.sendMessage(request)
                val reply = turn.replyText.trim()
                
                aiDraftRepository.insertChatMessage(
                    AiChatMessage(
                        role = ChatRole.Assistant,
                        text = if (reply.isBlank()) "这是我为你生成的记录：" else reply,
                        assistantCards = turn.cards
                    )
                )

                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        conversationState = AiRecordConversationState.Idle
                    )
                }
                Log.d("DayZeroAiV2", "assistant-turn-v2 interaction_result success")
            } catch (e: Exception) {
                Log.e("DayZeroAiV2", "assistant-turn-v2 interaction_result error", e)
                handleAssistantTurnV2Error(e)
            }
        }
    }

    private suspend fun handleAssistantTurnV2Error(error: Throwable) {
        Log.e("DayZeroAiV2", "assistant-turn-v2 error", error)
        val errorMessage = if (error is ProtocolException) {
            "协议错误"
        } else {
            error.message ?: "assistant-turn-v2 failed"
        }
        _uiState.update {
            it.copy(
                isAnalyzing = false,
                conversationState = AiRecordConversationState.Error(errorMessage)
            )
        }
        _uiEvents.emit(UiEvent.Error(errorMessage))
    }

    fun clearAllData() {
        viewModelScope.launch {
            recordRepository.clearAllRecords()
            aiDraftRepository.clearChatMessages()
            _uiState.update {
                it.copy(
                    records = emptyList(),
                    chatMessages = emptyList(),
                    isAnalyzing = false,
                    conversationState = AiRecordConversationState.Idle
                )
            }
        }
    }

    private fun handleFoodRecordConfirm(
        interactionId: String,
        optionId: String,
        payloadSummary: com.example.domain.model.ai.assistant.PayloadSummary?
    ) {
        if (optionId == "cancel") {
            Log.d("DayZeroAiV2", "confirm food card clicked cancel")
            viewModelScope.launch {
                updateCardState(interactionId, "cancelled")
                addClientMessage("好，这次先不记录。")
            }
            return
        }

        if (optionId == "confirm") {
            Log.d("DayZeroAiV2", "confirm food card clicked confirm")
            Log.d("DayZeroAiV2", "food record save start")

            viewModelScope.launch {
                try {
                    val currentDate = _uiState.value.currentDate
                    val todayRecord = recordRepository.getRecordByDateAndStatus(currentDate, RecordStatus.Confirmed)
                        ?: com.example.domain.model.DailyRecord(date = currentDate, status = RecordStatus.Confirmed, meals = emptyList())

                    val updatedMeals = todayRecord.meals.toMutableList()
                    
                    val mealsToProcess = payloadSummary?.meals ?: if (payloadSummary?.mealType != null && payloadSummary.items != null) {
                        listOf(
                            com.example.domain.model.ai.assistant.ConfirmCardMeal(
                                mealType = payloadSummary.mealType,
                                mealLabel = payloadSummary.mealType,
                                subtotalCalories = payloadSummary.items.sumOf { it.calories },
                                items = payloadSummary.items
                            )
                        )
                    } else {
                        emptyList()
                    }

                    mealsToProcess.forEach { cardMeal ->
                        val mealTypeStr = cardMeal.mealType
                        val mealType = try {
                            when (mealTypeStr.lowercase()) {
                                "breakfast", "早餐" -> com.example.domain.model.MealType.Breakfast
                                "lunch", "午餐" -> com.example.domain.model.MealType.Lunch
                                "dinner", "晚餐" -> com.example.domain.model.MealType.Dinner
                                "snack", "加餐" -> com.example.domain.model.MealType.Snack
                                else -> com.example.domain.model.MealType.Snack
                            }
                        } catch (e: Exception) {
                            com.example.domain.model.MealType.Snack
                        }

                        val newFoods = cardMeal.items.map { item ->
                            com.example.domain.model.FoodEntry(
                                name = item.name,
                                quantity = item.amountText ?: "1份",
                                estimatedCalories = item.calories,
                                confidence = item.calorieConfidence
                            )
                        }

                        val existingMealIndex = updatedMeals.indexOfFirst { it.mealType == mealType }
                        if (existingMealIndex != -1) {
                            val existingMeal = updatedMeals[existingMealIndex]
                            updatedMeals[existingMealIndex] = existingMeal.copy(foods = existingMeal.foods + newFoods)
                        } else {
                            updatedMeals.add(com.example.domain.model.MealEntry(mealType = mealType, foods = newFoods))
                        }
                    }

                    val updatedRecord = todayRecord.copy(
                        meals = updatedMeals,
                        weightKg = payloadSummary?.weightKg?.toFloat() ?: todayRecord.weightKg
                    )
                    recordRepository.upsertRecord(updatedRecord)

                    Log.d("DayZeroAiV2", "food record save success")
                    updateCardState(interactionId, "confirmed")
                    addClientMessage("已记录到今天。")
                    _uiEvents.emit(UiEvent.RecordConfirmed)
                } catch (e: Exception) {
                    Log.e("DayZeroAiV2", "food record save error", e)
                    addClientMessage("记录失败，请重试。")
                }
            }
        }
    }

    private suspend fun updateCardState(interactionId: String, newState: String) {
        val messages = _uiState.value.chatMessages
        val targetMessage = messages.find { msg -> msg.assistantCards.any { it.id == interactionId } }
        if (targetMessage != null) {
            val updatedCards = targetMessage.assistantCards.map { card ->
                if (card.id == interactionId && card is com.example.domain.model.ai.assistant.ShowConfirmCardPayload) {
                    card.copy(state = newState, resolved = true)
                } else {
                    card
                }
            }
            aiDraftRepository.updateChatMessage(targetMessage.copy(assistantCards = updatedCards))
            Log.d("DayZeroAiV2", "confirm card state updated $newState")
        }
    }

    private suspend fun addClientMessage(text: String) {
        aiDraftRepository.insertChatMessage(
            AiChatMessage(
                role = ChatRole.Assistant,
                text = text
            )
        )
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

                val aiDraftRepository = if (USE_REMOTE_AI) {
                    RemoteAiDraftRepository(NetworkModule.aiDraftApiService, database.aiChatMessageDao())
                } else {
                    FakeAiDraftRepository()
                }

                val aiAssistantRepository = if (USE_REMOTE_AI) {
                    RemoteAiAssistantRepository(NetworkModule.aiDraftApiService)
                } else {
                    FakeAiAssistantRepository()
                }

                return DayZeroViewModel(
                    recordRepository = RoomRecordRepository(database.dailyRecordDao()),
                    aiDraftRepository = aiDraftRepository,
                    aiAssistantRepository = aiAssistantRepository
                ) as T
            }
        }
    }
}
