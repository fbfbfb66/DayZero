package com.example.assistant

import android.content.Context
import com.example.data.telemetry.AiLatencyTraceLogger
import com.example.domain.model.DailyRecord
import com.example.domain.model.MealType
import com.example.domain.model.RecordStatus
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.AiDraftRequest
import com.example.domain.model.ai.CheckinDraft
import com.example.domain.model.ai.Conversation
import com.example.domain.model.ai.assistant.AiAssistantRequest
import com.example.domain.model.ai.assistant.AiAssistantTurn
import com.example.domain.model.ai.assistant.AiIntent
import com.example.domain.model.ai.assistant.PrepareVisionAttachmentsRequest
import com.example.domain.model.ai.assistant.PreparedVisionRequest
import com.example.domain.model.ai.assistant.VisionAssistantTurnResult
import com.example.domain.model.ai.assistant.VisionPreparationFailure
import com.example.domain.repository.AiAssistantRepository
import com.example.domain.repository.AiDraftRepository
import com.example.domain.repository.ConversationRepository
import com.example.domain.repository.RecordRepository
import com.example.domain.repository.VisionAttachmentPreparationRepository
import com.example.domain.time.CurrentDateProvider
import com.example.domain.usecase.PrepareVisionAttachmentsForMessageUseCase
import com.example.domain.usecase.ReleasePreparedVisionAttachmentsUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate

/**
 * Test fixture that builds a real [VisionAssistantTurnOrchestrator] backed by no-op fakes.
 *
 * This is intended for ViewModel unit tests that do not exercise the vision send path but
 * require a non-null orchestrator dependency after Phase 2B-3C1-F1.
 */
fun fakeVisionAssistantTurnOrchestrator(context: Context): VisionAssistantTurnOrchestrator {
    val preparationRepository = noOpPreparationRepository()
    return VisionAssistantTurnOrchestrator(
        prepareUseCase = PrepareVisionAttachmentsForMessageUseCase(preparationRepository),
        releaseUseCase = ReleasePreparedVisionAttachmentsUseCase(preparationRepository),
        aiAssistantRepository = noOpAssistantRepository(),
        aiDraftRepository = noOpDraftRepository(),
        recordRepository = noOpRecordRepository(),
        conversationRepository = noOpConversationRepository(),
        currentDateProvider = fixedCurrentDateProvider(),
        latencyLogger = AiLatencyTraceLogger(context)
    )
}

/**
 * Test fixture providing a [VisionAssistantTurnOrchestrator] whose [runVisionTurn] can be
 * suspended, failed, or completed on demand. Useful for testing ViewModel-level attempt
 * ownership without depending on real preparation/streaming/fallback logic.
 */
fun controllableFakeVisionAssistantTurnOrchestrator(context: Context): ControllableFakeVisionAssistantTurnOrchestrator {
    return ControllableFakeVisionAssistantTurnOrchestrator(context)
}

/**
 * Controllable fake for ViewModel attempt-ownership tests.
 *
 * Calls to [runVisionTurn] block until [complete] or [fail] is invoked, or until a pre-configured
 * result is available. The callback invocation sequence is recorded so tests can verify ownership.
 */
class ControllableFakeVisionAssistantTurnOrchestrator(context: Context) : VisionAssistantTurnOrchestrator(
    prepareUseCase = PrepareVisionAttachmentsForMessageUseCase(noOpPreparationRepository()),
    releaseUseCase = ReleasePreparedVisionAttachmentsUseCase(noOpPreparationRepository()),
    aiAssistantRepository = noOpAssistantRepository(),
    aiDraftRepository = noOpDraftRepository(),
    recordRepository = noOpRecordRepository(),
    conversationRepository = noOpConversationRepository(),
    currentDateProvider = fixedCurrentDateProvider(),
    latencyLogger = AiLatencyTraceLogger(context)
) {
    private val lock = Object()
    private var pendingResult: VisionAssistantTurnResult = VisionAssistantTurnResult.Success
    private var pendingError: Throwable? = null
    private var unblockDeferred: CompletableDeferred<Unit>? = null
    private var autoComplete: Boolean = true

    private val _callbackEvents = mutableListOf<Pair<String, Boolean>>()
    val callbackEvents: List<Pair<String, Boolean>>
        get() = synchronized(lock) { _callbackEvents.toList() }

    private val _capturedCallbacks = mutableListOf<(Boolean) -> Unit>()
    val capturedCallbacks: List<(Boolean) -> Unit>
        get() = synchronized(lock) { _capturedCallbacks.toList() }

    fun configureResult(result: VisionAssistantTurnResult) {
        synchronized(lock) {
            pendingResult = result
            pendingError = null
        }
    }

    fun configureError(error: Throwable) {
        synchronized(lock) {
            pendingError = error
        }
    }

    fun suspendUntilUnblocked() {
        synchronized(lock) {
            unblockDeferred = CompletableDeferred()
        }
    }

    fun unblock() {
        synchronized(lock) {
            unblockDeferred?.complete(Unit)
            unblockDeferred = null
        }
    }

    fun setAutoComplete(enabled: Boolean) {
        synchronized(lock) {
            autoComplete = enabled
        }
    }

    fun completeLastAttempt() {
        synchronized(lock) {
            _capturedCallbacks.lastOrNull()?.invoke(false)
        }
    }

    override suspend fun runVisionTurn(
        conversationId: String,
        userMessageId: String,
        onAnalyzingChanged: (Boolean) -> Unit
    ): VisionAssistantTurnResult {
        synchronized(lock) { _capturedCallbacks.add(onAnalyzingChanged) }
        onAnalyzingChanged(true)
        synchronized(lock) { _callbackEvents.add("true" to true) }

        try {
            val deferred = synchronized(lock) { unblockDeferred }
            if (deferred != null) {
                deferred.await()
            }

            val error = synchronized(lock) { pendingError }
            if (error != null) {
                throw error
            }

            return synchronized(lock) { pendingResult }
        } finally {
            if (synchronized(lock) { autoComplete }) {
                onAnalyzingChanged(false)
                synchronized(lock) { _callbackEvents.add("false" to false) }
            }
        }
    }
}

private fun noOpPreparationRepository(): VisionAttachmentPreparationRepository {
    return object : VisionAttachmentPreparationRepository {
        override suspend fun prepare(request: PrepareVisionAttachmentsRequest): Result<PreparedVisionRequest> {
            return Result.failure(VisionPreparationFailure.MessageNotFound("fake: vision path not configured"))
        }

        override suspend fun release(requestId: String): Result<Unit> = Result.success(Unit)
    }
}

private fun noOpDraftRepository(): AiDraftRepository {
    return object : AiDraftRepository {
        override suspend fun generateDraft(request: AiDraftRequest): CheckinDraft = error("unused")
        override fun observeChatMessages(): Flow<List<AiChatMessage>> = flowOf(emptyList())
        override fun observeChatMessages(conversationId: String): Flow<List<AiChatMessage>> =
            flowOf(emptyList())

        override suspend fun createConversationWithFirstMessage(text: String, now: Long): String? = null
        override suspend fun getRecentChatMessages(conversationId: String, limit: Int): List<AiChatMessage> =
            emptyList()

        override suspend fun findMessageByAssistantCardId(cardId: String): AiChatMessage? = null
        override suspend fun getChatMessageById(messageId: String): AiChatMessage? = null
        override suspend fun insertChatMessage(message: AiChatMessage) {}
        override suspend fun insertChatMessage(conversationId: String, message: AiChatMessage) {}
        override suspend fun updateChatMessage(message: AiChatMessage) {}
        override suspend fun clearChatMessages() {}
        override fun updateStreamingState(
            conversationId: String,
            messageId: String,
            text: String,
            isStreaming: Boolean
        ) {
        }

        override fun clearStreamingState(conversationId: String) {}
    }
}

private fun noOpAssistantRepository(): AiAssistantRepository {
    return object : AiAssistantRepository {
        override suspend fun sendMessage(request: AiAssistantRequest): AiAssistantTurn {
            return AiAssistantTurn(
                id = "fake-turn",
                intent = AiIntent.GeneralChat,
                replyText = "",
                cards = emptyList(),
                suggestedReplies = emptyList()
            )
        }

        override suspend fun streamMessage(
            request: AiAssistantRequest,
            onDelta: suspend (String) -> Unit
        ): AiAssistantTurn = sendMessage(request)
    }
}

private fun noOpRecordRepository(): RecordRepository {
    return object : RecordRepository {
        override fun observeRecords(): Flow<List<DailyRecord>> = flowOf(emptyList())
        override suspend fun upsertRecord(record: DailyRecord) {}
        override suspend fun deleteRecordById(recordId: String) {}
        override suspend fun getRecordById(recordId: String): DailyRecord? = null
        override suspend fun getRecordByDateAndStatus(date: LocalDate, status: RecordStatus): DailyRecord? =
            null

        override suspend fun updateRecordStatus(recordId: String, status: RecordStatus, weightKg: Float?) {}
        override suspend fun deleteFoodFromRecord(recordId: String, mealType: MealType, foodId: String) {}
        override suspend fun clearAllRecords() {}
    }
}

private fun noOpConversationRepository(): ConversationRepository {
    return object : ConversationRepository {
        override suspend fun insertConversation(conversation: Conversation) {}
        override suspend fun getConversationById(id: String): Conversation? = null
        override fun observeConversations(): Flow<List<Conversation>> = flowOf(emptyList())
        override fun observeConversationsByLastActivity(): Flow<List<Conversation>> = flowOf(emptyList())
        override suspend fun updateConversationSummary(
            id: String,
            title: String,
            lastMessagePreview: String,
            lastActivityAt: Long,
            updatedAt: Long
        ) {
        }

        override suspend fun softDeleteConversation(id: String, deletedAt: Long) {}
    }
}

private fun fixedCurrentDateProvider(): CurrentDateProvider {
    return object : CurrentDateProvider {
        override fun currentDate(): LocalDate = LocalDate.of(2026, 6, 20)
    }
}
