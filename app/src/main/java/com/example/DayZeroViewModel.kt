package com.example

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.data.identity.CompositeIdentityProvider
import com.example.data.identity.LocalIdentityProvider
import com.example.data.identity.SupabaseAnonymousIdentityProvider
import com.example.data.local.database.DayZeroDatabase
import com.example.data.remote.NetworkModule
import com.example.data.remote.PromptCacheKeyProvider
import com.example.data.remote.SupabaseConfig
import com.example.data.remote.stream.AssistantTurnStreamClient
import com.example.data.sync.BackfillCoordinator
import com.example.data.sync.BackfillStateStore
import com.example.data.sync.LocalFirstSyncCoordinator
import com.example.data.sync.NoopRemoteSyncGateway
import com.example.data.sync.SupabaseRemoteSyncGateway
import com.example.data.sync.SyncCoordinator
import com.example.data.sync.SyncHealthReporter
import com.example.data.sync.SyncStatusRepository
import com.example.data.telemetry.AiLatencyTraceLogger
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

sealed class UiEvent {
    object RecordConfirmed : UiEvent()
    data class Error(val message: String) : UiEvent()
}

class DayZeroViewModel(
    private val recordRepository: RecordRepository,
    private val aiDraftRepository: AiDraftRepository,
    private val aiAssistantRepository: AiAssistantRepository,
    private val latencyLogger: AiLatencyTraceLogger,
    private val syncCoordinator: SyncCoordinator? = null,
    private val backfillCoordinator: BackfillCoordinator? = null,
    private val syncHealthReporter: SyncHealthReporter? = null,
    private val syncStatusRepository: SyncStatusRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppState(currentDate = LocalDate.now()))
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
        val userMessage = AiChatMessage(role = ChatRole.User, text = trimmed)
        Log.d("DayZeroAiV2", "send message: '$trimmed'")

        _uiState.update {
            it.copy(
                isAnalyzing = true,
                conversationState = AiRecordConversationState.Idle,
                chatMessages = it.chatMessages + userMessage
            )
        }
        latencyLogger.mark(
            traceId,
            "ui_optimistic_state_updated",
            mapOf("messageId" to userMessage.id, "chatMessagesCount" to _uiState.value.chatMessages.size)
        )

        viewModelScope.launch {
            try {
                latencyLogger.mark(traceId, "room_user_message_insert_start")
                aiDraftRepository.insertChatMessage(userMessage)
                latencyLogger.mark(traceId, "room_user_message_insert_complete")
                requestAssistantTurnV2(trimmed, traceId)
            } catch (e: Exception) {
                handleAssistantTurnV2Error(e, traceId)
            }
        }
    }

    private suspend fun requestAssistantTurnV2(text: String, traceId: String) {
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
            recentMessages = _uiState.value.chatMessages.takeLast(10),
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
            fallbackReply = "好的，已为你处理。"
        )
        return

        try {
            latencyLogger.mark(traceId, "remote_repository_send_start")
            val turn = aiAssistantRepository.sendMessage(request)
            latencyLogger.mark(
                traceId,
                "remote_repository_send_complete",
                mapOf("cardsCount" to turn.cards.size, "cardTypes" to turn.cards.joinToString(",") { it.type.name })
            )
            val reply = turn.replyText.trim()

            val assistantMessage = AiChatMessage(
                role = ChatRole.Assistant,
                text = if (reply.isBlank()) "好的，已为你处理：" else reply,
                assistantCards = turn.cards
            )
            latencyLogger.bindAssistantMessage(traceId, assistantMessage.id, conversationTypeForCards(turn.cards))
            latencyLogger.mark(traceId, "room_assistant_message_insert_start")
            aiDraftRepository.insertChatMessage(assistantMessage)
            latencyLogger.mark(traceId, "room_assistant_message_insert_complete")

            _uiState.update {
                it.copy(
                    isAnalyzing = false,
                    conversationState = AiRecordConversationState.Idle
                )
            }
            latencyLogger.mark(traceId, "ui_state_assistant_complete")
            Log.d("DayZeroAiV2", "assistant-turn-v2 success")
        } catch (e: Exception) {
            handleAssistantTurnV2Error(e, traceId)
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
                latencyLogger.mark(
                    traceId,
                    "assistant_request_build_complete",
                    mapOf("recentMessagesCount" to request.recentMessages.size)
                )

                completeAssistantTurnWithStreamingFallback(
                    request = request,
                    traceId = traceId,
                    fallbackReply = "这是我为你生成的记录。"
                )
                return@launch

                latencyLogger.mark(traceId, "remote_repository_send_start")
                val turn = aiAssistantRepository.sendMessage(request)
                latencyLogger.mark(
                    traceId,
                    "remote_repository_send_complete",
                    mapOf("cardsCount" to turn.cards.size, "cardTypes" to turn.cards.joinToString(",") { it.type.name })
                )
                val reply = turn.replyText.trim()
                
                val assistantMessage = AiChatMessage(
                    role = ChatRole.Assistant,
                    text = if (reply.isBlank()) "这是我为你生成的记录：" else reply,
                    assistantCards = turn.cards
                )
                latencyLogger.bindAssistantMessage(traceId, assistantMessage.id, conversationTypeForCards(turn.cards))
                latencyLogger.mark(traceId, "room_assistant_message_insert_start")
                aiDraftRepository.insertChatMessage(assistantMessage)
                latencyLogger.mark(traceId, "room_assistant_message_insert_complete")

                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        conversationState = AiRecordConversationState.Idle
                    )
                }
                latencyLogger.mark(traceId, "ui_state_assistant_complete")
                Log.d("DayZeroAiV2", "assistant-turn-v2 interaction_result success")
            } catch (e: Exception) {
                Log.e("DayZeroAiV2", "assistant-turn-v2 interaction_result error", e)
                handleAssistantTurnV2Error(e, traceId)
            }
        }
    }

    private suspend fun completeAssistantTurnWithStreamingFallback(
        request: AiAssistantRequest,
        traceId: String,
        fallbackReply: String
    ) {
        val assistantMessage = AiChatMessage(role = ChatRole.Assistant, text = "")
        val targetText = StringBuilder()
        val displayLock = Any()
        var latestMessage = assistantMessage
        var firstDeltaReceived = false
        var displayedLength = 0
        var streamFinished = false
        var typedEventCount = 0

        latencyLogger.mark(traceId, "room_streaming_assistant_placeholder_insert_start")
        aiDraftRepository.insertChatMessage(assistantMessage)
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

        try {
            latencyLogger.mark(traceId, "remote_repository_stream_start")
            val turn = aiAssistantRepository.streamMessage(request) { delta ->
                if (!firstDeltaReceived) {
                    firstDeltaReceived = true
                    latencyLogger.mark(traceId, "time_to_first_token")
                }
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
            synchronized(displayLock) {
                streamFinished = true
            }
            typewriterJob.cancelAndJoin()
            Log.w("DayZeroAiV2", "assistant-turn-v2-stream fallback to assistant-turn-v2", streamError)
            latencyLogger.mark(
                traceId,
                "remote_repository_stream_failed_fallback_start",
                mapOf("error" to (streamError.message ?: streamError::class.java.simpleName))
            )
            val turn = aiAssistantRepository.sendMessage(request)
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
        val finalMessage = baseMessage.copy(
            text = finalReply,
            assistantCards = cards
        )
        latencyLogger.bindAssistantMessage(traceId, finalMessage.id, conversationTypeForCards(cards))
        latencyLogger.mark(traceId, "actions_received", mapOf("cardsCount" to cards.size) + metadata)
        latencyLogger.mark(traceId, "room_assistant_message_update_final_start")
        aiDraftRepository.updateChatMessage(finalMessage)
        latencyLogger.mark(traceId, "room_assistant_message_update_final_complete")

        _uiState.update {
            it.copy(
                isAnalyzing = false,
                conversationState = AiRecordConversationState.Idle
            )
        }
        latencyLogger.mark(traceId, "ui_state_assistant_complete")
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

    fun refreshSyncHealth(reason: String = "manual") {
        viewModelScope.launch {
            refreshSyncHealthState(reason = reason)
        }
    }

    fun runManualSync() {
        val repository = syncStatusRepository ?: return
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
                    val currentDate = _uiState.value.currentDate
                    latencyLogger.mark(traceId, "room_today_record_read_start")
                    val todayRecord = recordRepository.getRecordByDateAndStatus(currentDate, RecordStatus.Confirmed)
                        ?: com.example.domain.model.DailyRecord(date = currentDate, status = RecordStatus.Confirmed, meals = emptyList())
                    latencyLogger.mark(traceId, "room_today_record_read_complete", mapOf("hasTodayRecord" to (todayRecord.id.isNotBlank())))

                    latencyLogger.mark(traceId, "food_record_payload_map_start")
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
                    latencyLogger.mark(
                        traceId,
                        "food_record_payload_map_complete",
                        mapOf("mealsCount" to mealsToProcess.size)
                    )

                    val updatedRecord = todayRecord.copy(
                        meals = updatedMeals,
                        weightKg = payloadSummary?.weightKg?.toFloat() ?: todayRecord.weightKg
                    )
                    latencyLogger.mark(traceId, "room_daily_record_upsert_start")
                    recordRepository.upsertRecord(updatedRecord)
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

    private suspend fun updateCardState(
        interactionId: String,
        newState: String,
        updatedWeightKg: Double? = null,
        updatedMeals: List<com.example.domain.model.ai.assistant.ConfirmCardMeal>? = null
    ) {
        val messages = _uiState.value.chatMessages
        val targetMessage = messages.find { msg -> msg.assistantCards.any { it.id == interactionId } }
        if (targetMessage != null) {
            val updatedCards = targetMessage.assistantCards.map { card ->
                if (card.id == interactionId && card is com.example.domain.model.ai.assistant.ShowConfirmCardPayload) {
                    card.copy(
                        state = newState,
                        resolved = true,
                        weightKg = updatedWeightKg ?: card.weightKg,
                        meals = updatedMeals ?: card.meals
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
        val messages = _uiState.value.chatMessages
        val targetMessage = messages.find { msg -> msg.assistantCards.any { it.id == interactionId } }
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
        val message = AiChatMessage(
            role = ChatRole.Assistant,
            text = text
        )
        latencyLogger.bindAssistantMessage(traceId, message.id, conversationType)
        latencyLogger.mark(traceId, "room_client_message_insert_start")
        aiDraftRepository.insertChatMessage(message)
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
        val coordinator = syncCoordinator ?: return
        viewModelScope.launch {
            try {
                Log.d("DayZeroSync", "runOnce trigger reason=$reason")
                coordinator.runOnce()
                syncStatusRepository?.logSnapshot() ?: syncHealthReporter?.logSnapshot()
                refreshSyncHealthState(reason = "sync_$reason")
            } catch (e: Exception) {
                Log.e("DayZeroSync", "runOnce trigger error reason=$reason", e)
            }
        }
    }

    private fun triggerInitialBackfill(reason: String, delayMs: Long = 0L) {
        val coordinator = backfillCoordinator ?: return
        viewModelScope.launch {
            try {
                if (delayMs > 0L) delay(delayMs)
                Log.d("DayZeroBackfill", "initial trigger reason=$reason")
                val stats = coordinator.enqueueInitialBackfillIfNeeded()
                Log.d(
                    "DayZeroBackfill",
                    "initial result scannedDailyRecords=${stats.scannedDailyRecordCount} " +
                        "enqueued=${stats.enqueuedCount} skippedQueued=${stats.skippedAlreadyQueuedCount} " +
                        "skippedSynced=${stats.skippedAlreadySyncedCount} errors=${stats.errorCount}"
                )
                syncStatusRepository?.logSnapshot() ?: syncHealthReporter?.logSnapshot()
                refreshSyncHealthState(reason = "backfill_$reason")
                if (stats.enqueuedCount > 0) {
                    triggerBackgroundSync("backfill_enqueued")
                }
            } catch (e: Exception) {
                Log.e("DayZeroBackfill", "initial trigger error reason=$reason", e)
            }
        }
    }

    private suspend fun refreshSyncHealthState(reason: String) {
        val snapshot = syncStatusRepository?.snapshot()
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
                val latencyLogger = AiLatencyTraceLogger(application)
                val localIdentityProvider = LocalIdentityProvider(application)
                val supabaseIdentityProvider = if (SupabaseConfig.isConfigured()) {
                    SupabaseAnonymousIdentityProvider(
                        context = application,
                        localIdentityProvider = localIdentityProvider,
                        okHttpClient = NetworkModule.syncOkHttpClient
                    )
                } else {
                    null
                }
                val identityProvider = CompositeIdentityProvider(
                    localIdentityProvider = localIdentityProvider,
                    remoteIdentityProvider = supabaseIdentityProvider
                )
                val remoteSyncGateway = if (SupabaseConfig.isConfigured() && supabaseIdentityProvider != null) {
                    SupabaseRemoteSyncGateway(
                        okHttpClient = NetworkModule.syncOkHttpClient,
                        sessionProvider = supabaseIdentityProvider
                    )
                } else {
                    NoopRemoteSyncGateway()
                }
                val syncCoordinator = LocalFirstSyncCoordinator(
                    syncQueueDao = database.syncQueueDao(),
                    identityProvider = identityProvider,
                    remoteSyncGateway = remoteSyncGateway,
                    dailyRecordDao = database.dailyRecordDao()
                )
                val backfillStateStore = BackfillStateStore(application)
                val backfillCoordinator = BackfillCoordinator(
                    dailyRecordDao = database.dailyRecordDao(),
                    syncQueueDao = database.syncQueueDao(),
                    identityProvider = identityProvider,
                    stateStore = backfillStateStore
                )
                val syncHealthReporter = SyncHealthReporter(
                    syncQueueDao = database.syncQueueDao(),
                    identityProvider = identityProvider,
                    backfillStateStore = backfillStateStore,
                    dailyRecordDao = database.dailyRecordDao(),
                    remoteSyncEnabledProvider = { SupabaseConfig.isConfigured() }
                )
                val syncStatusRepository = SyncStatusRepository(
                    syncCoordinator = syncCoordinator,
                    backfillCoordinator = backfillCoordinator,
                    syncHealthReporter = syncHealthReporter
                )

                val aiDraftRepository = if (USE_REMOTE_AI) {
                    RemoteAiDraftRepository(NetworkModule.aiDraftApiService, database.aiChatMessageDao())
                } else {
                    FakeAiDraftRepository()
                }

                val aiAssistantRepository = if (USE_REMOTE_AI) {
                    val promptCacheKeyProvider = PromptCacheKeyProvider(application)
                    val streamClient = AssistantTurnStreamClient(
                        okHttpClient = NetworkModule.streamingOkHttpClient,
                        moshi = NetworkModule.moshi
                    )
                    RemoteAiAssistantRepository(
                        apiService = NetworkModule.aiDraftApiService,
                        latencyLogger = latencyLogger,
                        streamClient = streamClient,
                        promptCacheKeyProvider = { promptCacheKeyProvider.getPromptCacheKey() }
                    )
                } else {
                    FakeAiAssistantRepository()
                }

                return DayZeroViewModel(
                    recordRepository = RoomRecordRepository(
                        dao = database.dailyRecordDao(),
                        syncQueueDao = database.syncQueueDao(),
                        identityProvider = identityProvider
                    ),
                    aiDraftRepository = aiDraftRepository,
                    aiAssistantRepository = aiAssistantRepository,
                    latencyLogger = latencyLogger,
                    syncCoordinator = syncCoordinator,
                    backfillCoordinator = backfillCoordinator,
                    syncHealthReporter = syncHealthReporter,
                    syncStatusRepository = syncStatusRepository
                ) as T
            }
        }
    }
}
