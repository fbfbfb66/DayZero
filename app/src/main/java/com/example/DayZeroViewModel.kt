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
            if (reply.isBlank()) {
                throw IllegalStateException("assistant-turn-v2 returned blank reply")
            }

            aiDraftRepository.insertChatMessage(
                AiChatMessage(
                    role = ChatRole.Assistant,
                    text = reply,
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
        originalText: String? = null
    ) {
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
                        originalText = originalText
                    )
                )

                val turn = aiAssistantRepository.sendMessage(request)
                val reply = turn.replyText.trim()
                if (reply.isBlank()) {
                    throw IllegalStateException("assistant-turn-v2 returned blank reply")
                }

                aiDraftRepository.insertChatMessage(
                    AiChatMessage(
                        role = ChatRole.Assistant,
                        text = reply,
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
