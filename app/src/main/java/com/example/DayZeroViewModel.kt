package com.example

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.sync.BackfillCoordinator
import com.example.data.sync.InProcessSyncScheduler
import com.example.data.sync.PullCoordinator
import com.example.data.sync.SyncCoordinator
import com.example.data.sync.SyncHealthReporter
import com.example.data.sync.SyncScheduler
import com.example.data.sync.SyncStatusRepository
import com.example.data.sync.SyncTriggerReason
import com.example.data.sync.SupabaseCloudBackupCleaner
import com.example.data.sync.chat.ChatBackfillCoordinator
import com.example.data.telemetry.AiLatencyTraceLogger
import com.example.domain.model.AppState
import com.example.domain.model.RecordStatus
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.AiRecordConversationState
import com.example.domain.model.ai.ChatRole
import com.example.domain.model.ai.assistant.AiAssistantRequest
import com.example.domain.model.ai.assistant.AiChatCard
import com.example.domain.model.ai.assistant.DateMismatchGuardCardPayload
import com.example.domain.model.ai.assistant.ShowConfirmCardPayload
import com.example.domain.model.ai.assistant.ProtocolException
import com.example.domain.repository.AiAssistantRepository
import com.example.domain.repository.AiDraftRepository
import com.example.domain.repository.ConversationRepository
import com.example.domain.repository.RecordRepository
import com.example.domain.time.CurrentDateProvider
import com.example.domain.usecase.ClearLocalDataAction
import com.example.domain.usecase.ClearLocalDataUseCase
import com.example.domain.usecase.ConfirmFoodRecordUseCase
import com.example.domain.usecase.CreateConversationWithFirstMessageUseCase
import com.example.ui.sync.SyncStatusUiState
import com.example.ui.sync.SyncStatusUiStateMapper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

sealed class UiEvent {
    object RecordConfirmed : UiEvent()
    data class Error(val message: String) : UiEvent()
}

@HiltViewModel
class DayZeroViewModel @Inject constructor(
    private val recordRepository: RecordRepository,
    private val aiDraftRepository: AiDraftRepository,
    private val aiAssistantRepository: AiAssistantRepository,
    private val latencyLogger: AiLatencyTraceLogger,
    private val clearLocalDataUseCase: ClearLocalDataUseCase,
    private val confirmFoodRecordUseCase: ConfirmFoodRecordUseCase,
    private val createConversationWithFirstMessageUseCase: CreateConversationWithFirstMessageUseCase,
    private val conversationRepository: ConversationRepository,
    private val currentDateProvider: CurrentDateProvider,
    private val syncScheduler: SyncScheduler,
    private val syncStatusRepository: SyncStatusRepository? = null,
    private val cloudBackupCleaner: SupabaseCloudBackupCleaner? = null
) : ViewModel() {
    private val effectiveSyncScheduler: SyncScheduler = syncScheduler
    private val effectiveSyncStatusRepository: SyncStatusRepository? = syncStatusRepository

    private val _uiState = MutableStateFlow(AppState(currentDate = currentDateProvider.currentDate()))
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()

    private val _syncStatusUiState = MutableStateFlow(SyncStatusUiState())
    val syncStatusUiState: StateFlow<SyncStatusUiState> = _syncStatusUiState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    init {
        observeRecords()
        observeChatMessages()
        refreshSyncHealth("app_start")
        triggerInitialBackfill("app_start", delayMs = 1_500L)
        triggerInitialRestore("app_start", delayMs = 2_500L)
        triggerBackgroundSync("app_start")
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

        val traceId = latencyLogger.start(turnType = "user_message", userText = trimmed)
        Log.d("DayZeroAiV2", "send message: '$trimmed'")

        _uiState.update {
            it.copy(
                isAnalyzing = true,
                conversationState = AiRecordConversationState.Idle
            )
        }
        latencyLogger.mark(
            traceId,
            "ui_optimistic_state_updated",
            mapOf("chatMessagesCount" to _uiState.value.chatMessages.size)
        )

        viewModelScope.launch {
            try {
                latencyLogger.mark(traceId, "room_user_message_insert_start")
                val targetConversationId = createConversationWithFirstMessageUseCase(trimmed)
                    ?: error("Blank first message cannot create conversation")
                _uiState.update { it.copy(activeConversationId = targetConversationId) }
                latencyLogger.mark(traceId, "room_user_message_insert_complete")
                requestAssistantTurnV2(trimmed, traceId, targetConversationId)
            } catch (e: Exception) {
                handleAssistantTurnV2Error(e, traceId)
            }
        }
    }

    fun sendAiMessage(conversationId: String, text: String) {
        val trimmed = text.trim()
        if (conversationId.isBlank() || trimmed.isBlank()) return

        val traceId = latencyLogger.start(turnType = "user_message", userText = trimmed)
        Log.d("DayZeroAiV2", "send message to conversation=$conversationId: '$trimmed'")

        _uiState.update {
            it.copy(
                activeConversationId = conversationId,
                isAnalyzing = true,
                conversationState = AiRecordConversationState.Idle
            )
        }
        latencyLogger.mark(traceId, "ui_optimistic_state_updated")

        viewModelScope.launch {
            try {
                latencyLogger.mark(traceId, "room_user_message_insert_start")
                aiDraftRepository.insertChatMessage(
                    conversationId,
                    AiChatMessage(
                        conversationId = conversationId,
                        role = ChatRole.User,
                        text = trimmed
                    )
                )
                latencyLogger.mark(traceId, "room_user_message_insert_complete")
                requestAssistantTurnV2(trimmed, traceId, conversationId)
            } catch (e: Exception) {
                handleAssistantTurnV2Error(e, traceId)
            }
        }
    }

    fun startAssistantTurnForExistingUserMessage(conversationId: String, text: String) {
        val trimmed = text.trim()
        if (conversationId.isBlank() || trimmed.isBlank()) return

        val traceId = latencyLogger.start(turnType = "user_message", userText = trimmed)
        Log.d("DayZeroAiV2", "start assistant for existing first message conversation=$conversationId")
        _uiState.update {
            it.copy(
                activeConversationId = conversationId,
                isAnalyzing = true,
                conversationState = AiRecordConversationState.Idle
            )
        }

        viewModelScope.launch {
            try {
                requestAssistantTurnV2(trimmed, traceId, conversationId)
            } catch (e: Exception) {
                handleAssistantTurnV2Error(e, traceId)
            }
        }
    }

    fun setActiveConversationId(conversationId: String?) {
        _uiState.update { it.copy(activeConversationId = conversationId) }
    }

    private suspend fun requestAssistantTurnV2(text: String, traceId: String, targetConversationId: String) {
        Log.d("DayZeroAiV2", "assistant-turn-v2 start")

        latencyLogger.mark(traceId, "assistant_request_build_start")
        val currentDate = _uiState.value.currentDate
        latencyLogger.mark(traceId, "room_today_record_read_start")
        val todayRecord = recordRepository.getRecordByDateAndStatus(currentDate, RecordStatus.Confirmed)
        latencyLogger.mark(traceId, "room_today_record_read_complete", mapOf("hasTodayRecord" to (todayRecord != null)))
        val request = AiAssistantRequest(
            traceId = traceId,
            date = currentDate,
            userText = text,
            todayRecord = todayRecord,
            pendingDraft = null,
            recentMessages = aiDraftRepository.getRecentChatMessages(targetConversationId, 10),
            turnType = "user_message"
        )
        latencyLogger.mark(
            traceId,
            "assistant_request_build_complete",
            mapOf("recentMessagesCount" to request.recentMessages.size)
        )

        completeAssistantTurnWithStreamingFallback(
            request = request,
            traceId = traceId,
            targetConversationId = targetConversationId,
            fallbackReply = "好的，已为你处理。"
        )
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
        val traceId = latencyLogger.start(
            turnType = if (actionType == "show_confirm_card" && confirmType == "food_record") {
                "local_card_interaction"
            } else {
                "interaction_result"
            },
            userText = "已选择：$optionLabel",
            actionType = actionType,
            selectedOptionId = optionId
        )
        latencyLogger.mark(
            traceId,
            "card_click_received",
            mapOf(
                "interactionId" to interactionId,
                "actionType" to actionType,
                "optionId" to optionId,
                "confirmType" to confirmType
            )
        )

        if (actionType == "show_confirm_card" && confirmType == "food_record") {
            handleFoodRecordConfirm(traceId, interactionId, optionId, payloadSummary)
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
        latencyLogger.mark(traceId, "ui_interaction_analyzing_state_updated")

        viewModelScope.launch {
            try {
                val targetConversationId = aiDraftRepository.findMessageByAssistantCardId(interactionId)?.conversationId
                    ?: _uiState.value.activeConversationId
                    ?: error("Cannot resolve conversation for interaction $interactionId")
                latencyLogger.mark(traceId, "room_card_resolve_start")
                markCardAsResolved(interactionId)
                latencyLogger.mark(traceId, "room_card_resolve_complete")
                latencyLogger.mark(traceId, "assistant_request_build_start")
                val currentDate = _uiState.value.currentDate
                latencyLogger.mark(traceId, "room_today_record_read_start")
                val todayRecord = recordRepository.getRecordByDateAndStatus(currentDate, RecordStatus.Confirmed)
                latencyLogger.mark(traceId, "room_today_record_read_complete", mapOf("hasTodayRecord" to (todayRecord != null)))
                val request = AiAssistantRequest(
                    traceId = traceId,
                    date = currentDate,
                    userText = "已选择：$optionLabel",
                    todayRecord = todayRecord,
                    pendingDraft = null,
                    recentMessages = aiDraftRepository.getRecentChatMessages(targetConversationId, 10),
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
                latencyLogger.mark(
                    traceId,
                    "assistant_request_build_complete",
                    mapOf("recentMessagesCount" to request.recentMessages.size)
                )

                completeAssistantTurnWithStreamingFallback(
                    request = request,
                    traceId = traceId,
                    targetConversationId = targetConversationId,
                    fallbackReply = "这是我为你生成的记录。"
                )
            } catch (e: Exception) {
                Log.e("DayZeroAiV2", "assistant-turn-v2 interaction_result error", e)
                handleAssistantTurnV2Error(e, traceId)
            }
        }
    }

    private suspend fun completeAssistantTurnWithStreamingFallback(
        request: AiAssistantRequest,
        traceId: String,
        targetConversationId: String,
        fallbackReply: String
    ) {
        val assistantMessage = AiChatMessage(
            conversationId = targetConversationId,
            role = ChatRole.Assistant,
            text = ""
        )
        val targetText = StringBuilder()
        val displayLock = Any()
        var latestMessage = assistantMessage
        var firstDeltaReceived = false
        var displayedLength = 0
        var streamFinished = false
        var typedEventCount = 0

        latencyLogger.mark(traceId, "room_streaming_assistant_placeholder_insert_start")
        aiDraftRepository.insertChatMessage(targetConversationId, assistantMessage)
        latencyLogger.mark(traceId, "room_streaming_assistant_placeholder_insert_complete")

        _uiState.update { state ->
            if (state.chatMessages.any { it.id == assistantMessage.id }) {
                state
            } else {
                state.copy(chatMessages = state.chatMessages + assistantMessage)
            }
        }

        fun updateStreamingMessageText(text: String) {
            latestMessage = latestMessage.copy(text = text)
            aiDraftRepository.updateStreamingState(targetConversationId, latestMessage.id, text, true)
            _uiState.update { state ->
                state.copy(
                    chatMessages = state.chatMessages.map { message ->
                        if (message.id == latestMessage.id) latestMessage else message
                    }
                )
            }
        }

        val typewriterJob: Job = viewModelScope.launch {
            while (true) {
                val nextText: String?
                val shouldFinish: Boolean
                synchronized(displayLock) {
                    if (displayedLength < targetText.length) {
                        val remaining = targetText.length - displayedLength
                        displayedLength = (displayedLength + streamingTypewriterStep(remaining))
                            .coerceAtMost(targetText.length)
                        nextText = targetText.substring(0, displayedLength)
                        shouldFinish = false
                    } else {
                        nextText = null
                        shouldFinish = streamFinished
                    }
                }

                if (nextText != null) {
                    updateStreamingMessageText(nextText)
                    typedEventCount += 1
                    if (typedEventCount % 12 == 0) {
                        latencyLogger.mark(
                            traceId,
                            "ui_streaming_text_typed",
                            mapOf("textLength" to nextText.length)
                        )
                    }
                }

                if (shouldFinish) break
                delay(30L)
            }
        }

        var firstDeltaTime = 0L
        var deltaCount = 0
        val isFirstTurn = request.recentMessages.isEmpty()
        val turnSource = if (isFirstTurn) "首页首轮" else "已有会话发送"

        Log.i("DayZeroAiStream", "stream request started | conversationId=$targetConversationId | messageId=${assistantMessage.id} | source=$turnSource")

        try {
            latencyLogger.mark(traceId, "remote_repository_stream_start")
            Log.i("DayZeroAiStream", "stream connected | conversationId=$targetConversationId | messageId=${assistantMessage.id} | source=$turnSource")
            val turn = aiAssistantRepository.streamMessage(request) { delta ->
                if (!firstDeltaReceived) {
                    firstDeltaReceived = true
                    firstDeltaTime = System.currentTimeMillis()
                    latencyLogger.mark(traceId, "time_to_first_token")
                    Log.i("DayZeroAiStream", "first delta received | conversationId=$targetConversationId | messageId=${assistantMessage.id} | source=$turnSource")
                }
                deltaCount++
                synchronized(displayLock) {
                    targetText.append(delta)
                }
            }
            latencyLogger.mark(
                traceId,
                "remote_repository_stream_complete",
                mapOf("cardsCount" to turn.cards.size, "cardTypes" to turn.cards.joinToString(",") { it.type.name })
            )
            val finalReply = turn.replyText.trim()
            synchronized(displayLock) {
                if (finalReply.isNotBlank() && targetText.toString() != finalReply) {
                    targetText.clear()
                    targetText.append(finalReply)
                }
                streamFinished = true
            }
            typewriterJob.join()
            
            val endTime = System.currentTimeMillis()
            val duration = if (firstDeltaTime > 0) endTime - firstDeltaTime else 0
            Log.i("DayZeroAiStream", "delta count / accumulated length | count=$deltaCount length=${targetText.length} | conversationId=$targetConversationId | messageId=${assistantMessage.id} | source=$turnSource")
            Log.i("DayZeroAiStream", "stream final received | conversationId=$targetConversationId | messageId=${assistantMessage.id} | source=$turnSource | fallback=false | duration=$duration ms")
            
            latencyLogger.mark(
                traceId,
                "ui_streaming_text_animation_complete",
                mapOf("textLength" to latestMessage.text.length)
            )
            completeAssistantMessage(
                traceId = traceId,
                baseMessage = latestMessage,
                reply = turn.replyText,
                cards = turn.cards,
                fallbackReply = fallbackReply,
                metadata = mapOf("fallbackUsed" to false)
            )
            Log.d("DayZeroAiV2", "assistant-turn-v2-stream success")
        } catch (streamError: Exception) {
            val fallbackReason = streamError.message ?: streamError::class.java.simpleName
            Log.e("DayZeroAiStream", "stream failed | conversationId=$targetConversationId | messageId=${assistantMessage.id} | source=$turnSource | error=$fallbackReason")
            
            synchronized(displayLock) {
                streamFinished = true
            }
            typewriterJob.cancelAndJoin()
            Log.w("DayZeroAiV2", "assistant-turn-v2-stream fallback to assistant-turn-v2", streamError)
            
            Log.i("DayZeroAiStream", "fallback started + exact reason | reason=$fallbackReason | conversationId=$targetConversationId | messageId=${assistantMessage.id} | source=$turnSource")
            
            latencyLogger.mark(
                traceId,
                "remote_repository_stream_failed_fallback_start",
                mapOf("error" to (streamError.message ?: streamError::class.java.simpleName))
            )
            val turn = aiAssistantRepository.sendMessage(request)
            
            val endTime = System.currentTimeMillis()
            val duration = if (firstDeltaTime > 0) endTime - firstDeltaTime else 0
            Log.i("DayZeroAiStream", "fallback completed | conversationId=$targetConversationId | messageId=${assistantMessage.id} | source=$turnSource | fallback=true | duration=$duration ms")
            
            latencyLogger.mark(
                traceId,
                "remote_repository_send_complete",
                mapOf(
                    "cardsCount" to turn.cards.size,
                    "cardTypes" to turn.cards.joinToString(",") { it.type.name },
                    "fallbackUsed" to true
                )
            )
            completeAssistantMessage(
                traceId = traceId,
                baseMessage = latestMessage,
                reply = turn.replyText,
                cards = turn.cards,
                fallbackReply = fallbackReply,
                metadata = mapOf("fallbackUsed" to true)
            )
            Log.d("DayZeroAiV2", "assistant-turn-v2 fallback success")
        }
    }

    private suspend fun completeAssistantMessage(
        traceId: String,
        baseMessage: AiChatMessage,
        reply: String,
        cards: List<com.example.domain.model.ai.assistant.AiChatCard>,
        fallbackReply: String,
        metadata: Map<String, Any?>
    ) {
        val finalReply = reply.trim().ifBlank { fallbackReply }
        val finalCards = cards.withDateMismatchGuardIfNeeded(baseMessage.conversationId)
        val finalMessage = baseMessage.copy(
            text = finalReply,
            assistantCards = finalCards
        )
        latencyLogger.bindAssistantMessage(traceId, finalMessage.id, conversationTypeForCards(finalCards))
        latencyLogger.mark(traceId, "actions_received", mapOf("cardsCount" to finalCards.size) + metadata)
        latencyLogger.mark(traceId, "room_assistant_message_update_final_start")
        aiDraftRepository.updateChatMessage(finalMessage)
        finalMessage.conversationId?.let { aiDraftRepository.clearStreamingState(it) }
        latencyLogger.mark(traceId, "room_assistant_message_update_final_complete")

        _uiState.update {
            it.copy(
                isAnalyzing = false,
                conversationState = AiRecordConversationState.Idle
            )
        }
        latencyLogger.mark(traceId, "ui_state_assistant_complete")
        
        Log.i("DayZeroAiStream", "final persisted | conversationId=${finalMessage.conversationId} | messageId=${finalMessage.id}")
    }

    private suspend fun List<AiChatCard>.withDateMismatchGuardIfNeeded(
        conversationId: String?
    ): List<AiChatCard> {
        if (conversationId.isNullOrBlank() || none { it is ShowConfirmCardPayload }) return this
        val conversationDate = conversationRepository.getConversationById(conversationId)?.conversationDate ?: return this
        val detectedCurrentDate = currentDateProvider.currentDate()
        if (conversationDate == detectedCurrentDate) return this

        return map { card ->
            when {
                card is DateMismatchGuardCardPayload -> card
                card is ShowConfirmCardPayload && card.confirmType == "food_record" -> {
                    DateMismatchGuardCardPayload(
                        id = dateMismatchGuardId(card.id),
                        conversationId = conversationId,
                        conversationDate = conversationDate,
                        detectedCurrentDate = detectedCurrentDate,
                        state = "pending",
                        pendingOriginalCard = card,
                        createdAt = System.currentTimeMillis()
                    )
                }
                else -> card
            }
        }
    }

    fun handleDateMismatchGuardResult(guardId: String, approved: Boolean) {
        if (guardId.isBlank()) return
        viewModelScope.launch {
            updateDateMismatchGuardState(
                guardId = guardId,
                newState = if (approved) "approved" else "cancelled"
            )
        }
    }

    private suspend fun updateDateMismatchGuardState(guardId: String, newState: String) {
        val targetMessage = aiDraftRepository.findMessageByAssistantCardId(guardId) ?: return
        var changed = false
        val updatedCards = targetMessage.assistantCards.map { card ->
            if (card is DateMismatchGuardCardPayload && card.id == guardId && card.state == "pending") {
                changed = true
                card.copy(state = newState)
            } else {
                card
            }
        }
        if (changed) {
            aiDraftRepository.updateChatMessage(targetMessage.copy(assistantCards = updatedCards))
            Log.d("DayZeroAiV2", "date mismatch guard $guardId updated $newState")
        }
    }

    private fun dateMismatchGuardId(originalCardId: String): String {
        return "date-mismatch-guard-$originalCardId"
    }

    private fun streamingTypewriterStep(remainingChars: Int): Int {
        return when {
            remainingChars > 140 -> 3
            remainingChars > 56 -> 2
            else -> 1
        }
    }

    private suspend fun handleAssistantTurnV2Error(error: Throwable, traceId: String? = null) {
        Log.e("DayZeroAiV2", "assistant-turn-v2 error", error)
        val errorMessage = if (error is ProtocolException) {
            error.message ?: "协议错误"
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
        latencyLogger.fail(traceId, error)
    }

    fun clearAllData() {
        viewModelScope.launch {
            clearLocalDataUseCase(ClearLocalDataAction.AllLocal)
            _uiState.update {
                it.copy(
                    records = emptyList(),
                    activeConversationId = null,
                    chatMessages = emptyList(),
                    isAnalyzing = false,
                    conversationState = AiRecordConversationState.Idle
                )
            }
        }
    }

    fun clearChatMessages() {
        viewModelScope.launch {
            clearLocalDataUseCase(ClearLocalDataAction.ChatOnly)
            _uiState.update {
                it.copy(
                    activeConversationId = null,
                    chatMessages = emptyList(),
                    isAnalyzing = false,
                    conversationState = AiRecordConversationState.Idle
                )
            }
        }
    }

    fun clearLocalRecords() {
        viewModelScope.launch {
            clearLocalDataUseCase(ClearLocalDataAction.LocalRecordsOnly)
            _uiState.update { it.copy(records = emptyList()) }
            refreshSyncHealthState("clear_local_records")
        }
    }

    fun clearCloudBackupForDebug() {
        if (!BuildConfig.DEBUG) {
            Log.w("DayZeroRemote", "debug cloud clear ignored in release")
            return
        }
        viewModelScope.launch {
            Log.w("DayZeroRemote", "debug cloud clear requested")
            val success = cloudBackupCleaner?.clearCurrentUserCloudBackup() == true
            refreshSyncHealthState("debug_clear_cloud_backup")
            _syncStatusUiState.update {
                it.copy(
                    actionText = if (success) {
                        "Cloud backup cleared"
                    } else {
                        "Cloud backup clear failed"
                    }
                )
            }
        }
    }

    fun clearLocalBusinessRecordsForDebug() {
        if (!BuildConfig.DEBUG) {
            Log.w("DayZeroSync", "debug clear local business records ignored in release")
            return
        }
        viewModelScope.launch {
            Log.w("DayZeroSync", "debug clear local business records start")
            clearLocalDataUseCase(ClearLocalDataAction.LocalRecordsOnly)
            _uiState.update { it.copy(records = emptyList()) }
            refreshSyncHealthState("debug_clear_local_business_records")
            Log.w("DayZeroSync", "debug clear local business records success")
        }
    }

    fun refreshSyncHealth(reason: String = "manual") {
        viewModelScope.launch {
            refreshSyncHealthState(reason = reason)
        }
    }

    fun runManualSync() {
        val repository = effectiveSyncStatusRepository ?: return
        viewModelScope.launch {
            _syncStatusUiState.update { it.copy(isRefreshing = true, actionText = null) }
            val snapshot = repository.runManualSync()
            if (snapshot != null) {
                val actionText = if (snapshot.retryableFailureCount > 0 || snapshot.fatalFailureCount > 0) {
                    "部分记录稍后自动重试"
                } else {
                    "已检查同步状态"
                }
                _syncStatusUiState.value = SyncStatusUiStateMapper.from(snapshot)
                    .copy(isRefreshing = false, actionText = actionText)
                repository.logSnapshot()
            } else {
                _syncStatusUiState.update {
                    it.copy(
                        isRefreshing = false,
                        actionText = "同步状态暂不可用"
                    )
                }
            }
        }
    }

    fun runManualRestoreCheck() {
        val repository = effectiveSyncStatusRepository ?: return
        viewModelScope.launch {
            _syncStatusUiState.update { it.copy(isRefreshing = true, actionText = null) }
            val snapshot = repository.runManualRestoreCheck()
            if (snapshot != null) {
                _syncStatusUiState.value = SyncStatusUiStateMapper.from(snapshot)
                    .copy(isRefreshing = false, actionText = "已检查云端记录")
                repository.logSnapshot()
            } else {
                _syncStatusUiState.update {
                    it.copy(isRefreshing = false, actionText = "云端记录暂不可用")
                }
            }
        }
    }

    private fun handleFoodRecordConfirm(
        traceId: String,
        interactionId: String,
        optionId: String,
        payloadSummary: com.example.domain.model.ai.assistant.PayloadSummary?
    ) {
        latencyLogger.mark(traceId, "local_food_confirm_flow_start", mapOf("optionId" to optionId))
        if (optionId == "cancel") {
            Log.d("DayZeroAiV2", "confirm food card clicked cancel")
            viewModelScope.launch {
                if (!isShowConfirmCardActionAllowed(interactionId)) return@launch
                val targetConversationId = conversationIdForInteraction(interactionId)
                _uiState.update { it.copy(activeConversationId = targetConversationId) }
                latencyLogger.mark(traceId, "room_confirm_card_state_update_start")
                updateCardState(interactionId, "cancelled")
                latencyLogger.mark(traceId, "room_confirm_card_state_update_complete")
                addClientMessage("好，这次先不记录。", traceId, "local_food_cancel")
            }
            return
        }

        if (optionId == "confirm") {
            Log.d("DayZeroAiV2", "confirm food card clicked confirm")
            Log.d("DayZeroAiV2", "food record save start")

            viewModelScope.launch {
                try {
                    if (!isShowConfirmCardActionAllowed(interactionId)) return@launch
                    val targetConversationId = conversationIdForInteraction(interactionId)
                    _uiState.update { it.copy(activeConversationId = targetConversationId) }
                    val targetRecordDate = recordDateForInteraction(interactionId)
                    latencyLogger.mark(traceId, "food_record_payload_map_start")
                    val updatedRecord = confirmFoodRecordUseCase(targetRecordDate, payloadSummary)
                    latencyLogger.mark(
                        traceId,
                        "food_record_payload_map_complete",
                        mapOf("mealsCount" to updatedRecord.meals.size)
                    )
                    triggerBackgroundSync("food_confirm_enqueue")
                    latencyLogger.mark(
                        traceId,
                        "room_daily_record_upsert_complete",
                        mapOf("totalCalories" to updatedRecord.totalCalories)
                    )

                    Log.d("DayZeroAiV2", "food record save success")
                    latencyLogger.mark(traceId, "room_confirm_card_state_update_start")
                    updateCardState(
                        interactionId = interactionId,
                        newState = "confirmed",
                        updatedWeightKg = payloadSummary?.weightKg,
                        updatedMeals = payloadSummary?.meals
                    )
                    latencyLogger.mark(traceId, "room_confirm_card_state_update_complete")
                    addClientMessage("已记录到今天。", traceId, "local_food_confirm")
                    _uiEvents.emit(UiEvent.RecordConfirmed)
                } catch (e: Exception) {
                    Log.e("DayZeroAiV2", "food record save error", e)
                    addClientMessage("记录失败，请重试。", traceId, "local_food_confirm_error")
                }
            }
        }
    }

    private suspend fun conversationIdForInteraction(interactionId: String): String {
        return aiDraftRepository.findMessageByAssistantCardId(interactionId)?.conversationId
            ?: _uiState.value.activeConversationId
            ?: error("Cannot resolve conversation for interaction $interactionId")
    }

    private suspend fun recordDateForInteraction(interactionId: String): LocalDate {
        val conversationId = aiDraftRepository.findMessageByAssistantCardId(interactionId)?.conversationId
            ?: error("Cannot resolve message for record card $interactionId")
        return conversationRepository.getConversationById(conversationId)?.conversationDate
            ?: error("Cannot resolve conversation date for record card $interactionId")
    }

    private suspend fun isShowConfirmCardActionAllowed(interactionId: String): Boolean {
        val targetMessage = aiDraftRepository.findMessageByAssistantCardId(interactionId) ?: return false
        val originalState = targetMessage.assistantCards.firstNotNullOfOrNull { card ->
            when {
                card is ShowConfirmCardPayload && card.id == interactionId -> card.state
                card is DateMismatchGuardCardPayload && card.pendingOriginalCard.id == interactionId -> {
                    if (card.state == "approved") card.pendingOriginalCard.state else "guard_${card.state}"
                }
                else -> null
            }
        }
        return originalState == null || originalState == "pending"
    }

    private suspend fun updateCardState(
        interactionId: String,
        newState: String,
        updatedWeightKg: Double? = null,
        updatedMeals: List<com.example.domain.model.ai.assistant.ConfirmCardMeal>? = null
    ) {
        val targetMessage = aiDraftRepository.findMessageByAssistantCardId(interactionId)
        if (targetMessage != null) {
            val updatedCards = targetMessage.assistantCards.map { card ->
                if (card.id == interactionId && card is ShowConfirmCardPayload) {
                    card.copy(
                        state = newState,
                        resolved = true,
                        weightKg = updatedWeightKg ?: card.weightKg,
                        meals = updatedMeals ?: card.meals
                    )
                } else if (card is DateMismatchGuardCardPayload && card.pendingOriginalCard.id == interactionId) {
                    card.copy(
                        pendingOriginalCard = card.pendingOriginalCard.copy(
                            state = newState,
                            resolved = true,
                            weightKg = updatedWeightKg ?: card.pendingOriginalCard.weightKg,
                            meals = updatedMeals ?: card.pendingOriginalCard.meals
                        )
                    )
                } else {
                    card
                }
            }
            aiDraftRepository.updateChatMessage(targetMessage.copy(assistantCards = updatedCards))
            Log.d("DayZeroAiV2", "confirm card state updated $newState")
        }
    }

    private suspend fun markCardAsResolved(interactionId: String) {
        val targetMessage = aiDraftRepository.findMessageByAssistantCardId(interactionId)
        if (targetMessage != null) {
            val updatedCards = targetMessage.assistantCards.map { card ->
                if (card.id == interactionId) {
                    when (card) {
                        is com.example.domain.model.ai.assistant.AskRecordIntentCardPayload -> card.copy(resolved = true)
                        is com.example.domain.model.ai.assistant.AskMissingInfoCardPayload -> card.copy(resolved = true)
                        is com.example.domain.model.ai.assistant.DebugChoiceCardPayload -> card.copy(resolved = true)
                        is com.example.domain.model.ai.assistant.ShowConfirmCardPayload -> card.copy(resolved = true)
                        is com.example.domain.model.ai.assistant.ChoiceCardPayload -> card.copy(resolved = true)
                        is com.example.domain.model.ai.assistant.WeightCardPayload -> card.copy(resolved = true)
                        is com.example.domain.model.ai.assistant.EditConfirmCardPayload -> card.copy(resolved = true)
                        is com.example.domain.model.ai.assistant.DeleteConfirmCardPayload -> card.copy(resolved = true)
                        else -> card
                    }
                } else {
                    card
                }
            }
            aiDraftRepository.updateChatMessage(targetMessage.copy(assistantCards = updatedCards))
            Log.d("DayZeroAiV2", "card resolved: $interactionId")
        }
    }

    private suspend fun addClientMessage(
        text: String,
        traceId: String? = null,
        conversationType: String = "client_message"
    ) {
        val conversationId = _uiState.value.activeConversationId
            ?: error("Cannot add client message without an active conversation")
        val message = AiChatMessage(
            conversationId = conversationId,
            role = ChatRole.Assistant,
            text = text
        )
        latencyLogger.bindAssistantMessage(traceId, message.id, conversationType)
        latencyLogger.mark(traceId, "room_client_message_insert_start")
        aiDraftRepository.insertChatMessage(conversationId, message)
        latencyLogger.mark(traceId, "room_client_message_insert_complete")
    }

    fun markAssistantMessageRendered(message: AiChatMessage) {
        latencyLogger.completeByRenderedMessage(
            messageId = message.id,
            fallbackConversationType = conversationTypeForCards(message.assistantCards)
        )
    }

    private fun conversationTypeForCards(cards: List<com.example.domain.model.ai.assistant.AiChatCard>): String {
        if (cards.isEmpty()) return "ai_reply_chat_only"
        return "ai_reply_with_card:${cards.joinToString(",") { it.type.name }}"
    }

    private fun triggerBackgroundSync(reason: String) {
        val triggerReason = syncTriggerReason(reason)
        val job = effectiveSyncScheduler.requestSync(triggerReason) ?: return
        viewModelScope.launch {
            try {
                Log.d("DayZeroSync", "runOnce trigger reason=$reason")
                job.join()
                refreshSyncHealthState(reason = "sync_$reason")
            } catch (e: Exception) {
                Log.e("DayZeroSync", "runOnce trigger error reason=$reason", e)
            }
        }
    }

    private fun triggerInitialBackfill(reason: String, delayMs: Long = 0L) {
        viewModelScope.launch {
            try {
                if (delayMs > 0L) delay(delayMs)
                Log.d("DayZeroBackfill", "initial trigger reason=$reason")
                val job = effectiveSyncScheduler.requestSyncAndBackfill(syncTriggerReason(reason))
                job?.join()
                refreshSyncHealthState(reason = "backfill_$reason")
            } catch (e: Exception) {
                Log.e("DayZeroBackfill", "initial trigger error reason=$reason", e)
            }
        }
    }

    private fun triggerInitialRestore(reason: String, delayMs: Long = 0L) {
        viewModelScope.launch {
            try {
                if (delayMs > 0L) delay(delayMs)
                Log.d("DayZeroPull", "initial restore trigger reason=$reason")
                val job = effectiveSyncScheduler.requestInitialRestore(syncTriggerReason(reason))
                job?.join()
                refreshSyncHealthState(reason = "pull_$reason")
            } catch (e: Exception) {
                Log.e("DayZeroPull", "initial restore trigger error reason=$reason", e)
            }
        }
    }

    private suspend fun refreshSyncHealthState(reason: String) {
        val snapshot = effectiveSyncStatusRepository?.snapshot()
        if (snapshot == null) {
            Log.d("DayZeroHealth", "snapshot skipped reason=$reason")
            return
        }

        val current = _syncStatusUiState.value
        _syncStatusUiState.value = SyncStatusUiStateMapper.from(snapshot)
            .copy(
                isRefreshing = current.isRefreshing,
                actionText = current.actionText
            )
        Log.d(
            "DayZeroHealth",
            "ui state refreshed reason=$reason pending=${snapshot.pendingCount} " +
                "retryable=${snapshot.retryableFailureCount} fatal=${snapshot.fatalFailureCount} " +
                "hasRemoteIdentity=${snapshot.hasRemoteIdentity} isHealthy=${snapshot.isHealthy}"
        )
    }

    private fun syncTriggerReason(reason: String): SyncTriggerReason {
        return when (reason) {
            "app_start" -> SyncTriggerReason.APP_START
            "food_confirm_enqueue" -> SyncTriggerReason.RECORD_CONFIRMED
            "backfill_enqueued" -> SyncTriggerReason.BACKFILL_COMPLETED
            else -> SyncTriggerReason.RETRY
        }
    }

}
