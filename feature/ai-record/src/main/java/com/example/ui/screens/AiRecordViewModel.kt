package com.example.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.Conversation
import com.example.domain.repository.AiDraftRepository
import com.example.domain.repository.ConversationRepository
import com.example.domain.usecase.CreateConversationWithFirstMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AiConversationHistoryState(
    val conversations: List<Conversation> = emptyList(),
    val isLoading: Boolean = true,
    val isCreating: Boolean = false,
    val homeInputText: String = "",
    val lastCreatedConversationId: String? = null,
    val errorMessage: String? = null
)

data class AiConversationDetailState(
    val currentConversation: Conversation? = null,
    val messages: List<AiChatMessage> = emptyList(),
    val isSending: Boolean = false,
    val isStreaming: Boolean = false,
    val errorMessage: String? = null
)

data class AiRecordConversationUiState(
    val history: AiConversationHistoryState = AiConversationHistoryState(),
    val detail: AiConversationDetailState = AiConversationDetailState()
)

sealed interface AiRecordConversationEvent {
    data class ConversationCreated(
        val conversationId: String,
        val firstMessageText: String
    ) : AiRecordConversationEvent
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class AiRecordViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val aiDraftRepository: AiDraftRepository,
    private val createConversationWithFirstMessageUseCase: CreateConversationWithFirstMessageUseCase,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val selectedConversationId = MutableStateFlow<String?>(savedStateHandle[KEY_CONVERSATION_ID] as? String)
    private val historyTransient = MutableStateFlow(HistoryTransientState())
    private val detailTransient = MutableStateFlow(DetailTransientState())
    private val _events = MutableSharedFlow<AiRecordConversationEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    private val historyState: Flow<AiConversationHistoryState> = combine(
        observeHistory(),
        historyTransient
    ) { history: AiConversationHistoryState, overlay: HistoryTransientState ->
        history.copy(
            isCreating = overlay.isCreating,
            homeInputText = overlay.homeInputText,
            lastCreatedConversationId = overlay.lastCreatedConversationId,
            errorMessage = overlay.errorMessage ?: history.errorMessage
        )
    }

    private val detailState: Flow<AiConversationDetailState> = combine(
        observeDetail(),
        detailTransient
    ) { detail: AiConversationDetailState, overlay: DetailTransientState ->
        detail.copy(
            isSending = overlay.isSending,
            isStreaming = overlay.isStreaming,
            errorMessage = overlay.errorMessage ?: detail.errorMessage
        )
    }

    val uiState: StateFlow<AiRecordConversationUiState> = combine(
        historyState,
        detailState
    ) { history: AiConversationHistoryState, detail: AiConversationDetailState ->
        AiRecordConversationUiState(history = history, detail = detail)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AiRecordConversationUiState()
    )

    fun openConversation(conversationId: String) {
        savedStateHandle[KEY_CONVERSATION_ID] = conversationId
        selectedConversationId.value = conversationId
        detailTransient.value = DetailTransientState()
    }

    fun updateHomeInput(text: String) {
        historyTransient.update { it.copy(homeInputText = text, errorMessage = null) }
    }

    fun submitHomeInput() {
        createConversationWithFirstMessage(historyTransient.value.homeInputText)
    }

    fun createConversationWithFirstMessage(text: String) {
        if (historyTransient.value.isCreating) return
        historyTransient.update { it.copy(isCreating = true, errorMessage = null) }

        viewModelScope.launch {
            val trimmed = text.trim()
            if (trimmed.isBlank()) {
                historyTransient.update { it.copy(isCreating = false, errorMessage = "Message cannot be blank") }
                return@launch
            }

            runCatching { createConversationWithFirstMessageUseCase(trimmed) }
                .onSuccess { conversationId ->
                    if (conversationId == null) {
                        historyTransient.update { it.copy(isCreating = false, errorMessage = "Message cannot be blank") }
                    } else {
                        savedStateHandle[KEY_CONVERSATION_ID] = conversationId
                        selectedConversationId.value = conversationId
                        historyTransient.value = HistoryTransientState(
                            isCreating = false,
                            homeInputText = "",
                            lastCreatedConversationId = conversationId
                        )
                        _events.tryEmit(
                            AiRecordConversationEvent.ConversationCreated(
                                conversationId = conversationId,
                                firstMessageText = trimmed
                            )
                        )
                    }
                }
                .onFailure { error ->
                    historyTransient.update {
                        it.copy(isCreating = false, errorMessage = error.message ?: "Failed to create conversation")
                    }
                }
        }
    }

    fun setSendingState(isSending: Boolean, isStreaming: Boolean = false) {
        detailTransient.update { it.copy(isSending = isSending, isStreaming = isStreaming) }
    }

    fun setDetailError(message: String?) {
        detailTransient.update { it.copy(errorMessage = message) }
    }

    private fun observeHistory(): Flow<AiConversationHistoryState> {
        return conversationRepository.observeConversationsByLastActivity()
            .map { conversations ->
                AiConversationHistoryState(conversations = conversations, isLoading = false)
            }
            .onStart { emit(AiConversationHistoryState(isLoading = true)) }
            .catch { error ->
                emit(
                    AiConversationHistoryState(
                        isLoading = false,
                        errorMessage = error.message ?: "Failed to load conversations"
                    )
                )
            }
    }

    private fun observeDetail(): Flow<AiConversationDetailState> {
        return selectedConversationId
            .flatMapLatest { conversationId: String? ->
                if (conversationId.isNullOrBlank()) {
                    emptyFlow<AiConversationDetailState>()
                } else {
                    combine(
                        conversationRepository.observeConversationsByLastActivity()
                            .map { conversations -> conversations.firstOrNull { it.id == conversationId } },
                        aiDraftRepository.observeChatMessages(conversationId)
                    ) { conversation, messages ->
                        AiConversationDetailState(
                            currentConversation = conversation,
                            messages = messages
                        )
                    }
                }
            }
            .onStart { emit(AiConversationDetailState()) }
            .catch { error ->
                emit(AiConversationDetailState(errorMessage = error.message ?: "Failed to load conversation"))
            }
    }

    private data class HistoryTransientState(
        val isCreating: Boolean = false,
        val homeInputText: String = "",
        val lastCreatedConversationId: String? = null,
        val errorMessage: String? = null
    )

    private data class DetailTransientState(
        val isSending: Boolean = false,
        val isStreaming: Boolean = false,
        val errorMessage: String? = null
    )

    private companion object {
        private const val KEY_CONVERSATION_ID = "conversationId"
    }
}
