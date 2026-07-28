package com.goings.dayzero.assistant

import android.util.Log
import com.goings.dayzero.data.repository.RoomChatMediaTransactionRepository
import com.goings.dayzero.data.telemetry.AiLatencyTraceLogger
import com.goings.dayzero.domain.model.RecordStatus
import com.goings.dayzero.domain.model.ai.AiChatMessage
import com.goings.dayzero.domain.model.ai.ChatRole
import com.goings.dayzero.domain.model.ai.assistant.AiAssistantRequest
import com.goings.dayzero.domain.model.ai.assistant.AiAssistantTurn
import com.goings.dayzero.domain.model.ai.assistant.AiChatCard
import com.goings.dayzero.domain.model.ai.assistant.DateMismatchGuardCardPayload
import com.goings.dayzero.domain.model.ai.assistant.normalizeCardPhotoAssignments
import com.goings.dayzero.domain.model.ai.assistant.PreparedVisionRequest
import com.goings.dayzero.domain.model.ai.assistant.ProtocolException
import com.goings.dayzero.domain.model.ai.assistant.ShowConfirmCardPayload
import com.goings.dayzero.domain.model.ai.assistant.assistantPlaceholderId
import com.goings.dayzero.domain.model.ai.assistant.sanitizeFinalAssistantCards
import com.goings.dayzero.domain.model.ai.assistant.VisionAssistantTurnResult
import com.goings.dayzero.domain.model.ai.assistant.VisionPreparationFailure
import com.goings.dayzero.domain.repository.AiAssistantRepository
import com.goings.dayzero.domain.repository.AiDraftRepository
import com.goings.dayzero.domain.repository.ConversationRepository
import com.goings.dayzero.domain.repository.RecordRepository
import com.goings.dayzero.domain.time.CurrentDateProvider
import com.goings.dayzero.domain.usecase.PrepareVisionAttachmentsForMessageUseCase
import com.goings.dayzero.domain.usecase.ReleasePreparedVisionAttachmentsUseCase
import com.squareup.moshi.JsonDataException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID

/**
 * Production orchestrator for a single persisted-image assistant turn.
 *
 * The authoritative input is the persisted user message identified by
 * [conversationId] and [userMessageId]. All downstream writes (streaming state,
 * final reply, fallback reply, errors) are pinned to those immutable IDs.
 *
 * This class does not depend on Compose and is intended to be called from
 * [DayZeroViewModel] (and ultimately from [AiRecordActionHandler]) in a
 * ViewModel-scoped coroutine.
 */
open class VisionAssistantTurnOrchestrator(
    private val prepareUseCase: PrepareVisionAttachmentsForMessageUseCase,
    private val releaseUseCase: ReleasePreparedVisionAttachmentsUseCase,
    private val aiAssistantRepository: AiAssistantRepository,
    private val aiDraftRepository: AiDraftRepository,
    private val recordRepository: RecordRepository,
    private val conversationRepository: ConversationRepository,
    private val currentDateProvider: CurrentDateProvider,
    private val latencyLogger: AiLatencyTraceLogger
) {

    /**
     * Runs a complete vision assistant turn for an already-persisted user message.
     *
     * @param conversationId immutable target conversation
     * @param userMessageId immutable target user message
     * @param onAnalyzingChanged callback to set/clear the analyzing UI state; always
     *   invoked with `false` before returning, even on cancellation (after cleanup)
     */
    open suspend fun runVisionTurn(
        conversationId: String,
        userMessageId: String,
        onAnalyzingChanged: (Boolean) -> Unit = {}
    ): VisionAssistantTurnResult {
        if (conversationId.isBlank() || userMessageId.isBlank()) {
            return VisionAssistantTurnResult.InvalidInput(
                "conversationId and userMessageId must not be blank"
            )
        }

        val targetConversationId = conversationId
        val targetUserMessageId = userMessageId
        val targetAssistantMessageId = assistantPlaceholderId(targetUserMessageId)
        val requestId = UUID.randomUUID().toString()
        val traceId = latencyLogger.start(
            turnType = "vision_user_message",
            userText = "[image]"
        )

        latencyLogger.mark(
            traceId,
            "vision_turn_start",
            mapOf(
                "requestId" to requestId,
                "conversationId" to targetConversationId,
                "userMessageId" to targetUserMessageId,
                "assistantMessageId" to targetAssistantMessageId
            )
        )

        logStart(
            requestId = requestId,
            conversationId = targetConversationId,
            userMessageId = targetUserMessageId,
            assistantMessageId = targetAssistantMessageId
        )

        onAnalyzingChanged(true)

        var prepared: PreparedVisionRequest? = null

        try {
            val placeholderStatus = ensureAssistantPlaceholder(
                targetConversationId,
                targetAssistantMessageId
            )
            if (placeholderStatus != PlaceholderStatus.Ready) {
                return when (placeholderStatus) {
                    is PlaceholderStatus.AlreadyCompleted -> {
                        logSkip(
                            targetConversationId,
                            targetAssistantMessageId,
                            "placeholder already completed"
                        )
                        VisionAssistantTurnResult.AlreadyCompleted(targetAssistantMessageId)
                    }

                    is PlaceholderStatus.Invalid -> {
                        logSkip(
                            targetConversationId,
                            targetAssistantMessageId,
                            placeholderStatus.reason
                        )
                        VisionAssistantTurnResult.InvalidInput(placeholderStatus.reason)
                    }

                    else -> {
                        // Should not happen; defensive.
                        VisionAssistantTurnResult.InvalidInput("unexpected placeholder status")
                    }
                }
            }

            latencyLogger.mark(traceId, "vision_prepare_start")
            prepared = prepareUseCase(
                requestId = requestId,
                conversationId = targetConversationId,
                userMessageId = targetUserMessageId
            ).getOrElse { error ->
                latencyLogger.mark(
                    traceId,
                    "vision_prepare_failure",
                    mapOf(
                        "error" to (error.message ?: error::class.java.simpleName),
                        "errorCode" to error.errorCode()
                    )
                )
                logFailure(
                    stage = "prepare",
                    requestId = requestId,
                    conversationId = targetConversationId,
                    userMessageId = targetUserMessageId,
                    assistantMessageId = targetAssistantMessageId,
                    error = error
                )
                return VisionAssistantTurnResult.Failure(
                    mapPreparationError(error, targetUserMessageId)
                )
            }
            latencyLogger.mark(
                traceId,
                "vision_prepare_complete",
                mapOf(
                    "attachmentCount" to prepared.attachments.size,
                    "totalByteSize" to prepared.totalByteSize
                )
            )

            val request = buildAssistantRequest(
                prepared = prepared,
                traceId = traceId
            )

            var streamStartAt: Long? = null
            val (turn, fallbackUsed) = try {
                latencyLogger.mark(
                    traceId,
                    "vision_stream_start",
                    mapOf(
                        "attachmentCount" to prepared.attachments.size,
                        "totalByteSize" to prepared.totalByteSize
                    )
                )
                logStreamStarted(
                    requestId = requestId,
                    conversationId = targetConversationId,
                    userMessageId = targetUserMessageId,
                    assistantMessageId = targetAssistantMessageId,
                    attachmentCount = prepared.attachments.size
                )

                var streamedText = ""
                var presentedLength = 0
                var firstDeltaReceived = false
                var deltaCount = 0
                streamStartAt = System.currentTimeMillis()
                var firstDeltaAt = 0L
                val streamTurn = aiAssistantRepository.streamMessage(request) { delta ->
                    streamedText += delta
                    deltaCount++
                    if (!firstDeltaReceived) {
                        firstDeltaReceived = true
                        firstDeltaAt = System.currentTimeMillis()
                        latencyLogger.mark(
                            traceId,
                            "vision_first_delta_received",
                            mapOf("timeToFirstDeltaMs" to (firstDeltaAt - streamStartAt))
                        )
                        logFirstDelta(
                            requestId = requestId,
                            conversationId = targetConversationId,
                            assistantMessageId = targetAssistantMessageId,
                            timeToFirstDeltaMs = firstDeltaAt - streamStartAt
                        )
                    }
                    // Present only successful SSE delta text. Fallback replies never enter
                    // this branch and are persisted once, without simulated streaming.
                    while (presentedLength < streamedText.length) {
                        val remaining = streamedText.length - presentedLength
                        presentedLength = (presentedLength + streamingReplyStep(remaining))
                            .coerceAtMost(streamedText.length)
                        aiDraftRepository.updateStreamingState(
                            targetConversationId,
                            targetAssistantMessageId,
                            streamedText.substring(0, presentedLength),
                            true
                        )
                        if (presentedLength < streamedText.length) {
                            delay(STREAMING_REPLY_FRAME_DELAY_MS)
                        }
                    }
                }
                val streamCompleteAt = System.currentTimeMillis()
                latencyLogger.mark(
                    traceId,
                    "vision_stream_complete",
                    mapOf(
                        "fallbackUsed" to false,
                        "cardsCount" to streamTurn.cards.size,
                        "deltaCount" to deltaCount,
                        "streamDurationMs" to (streamCompleteAt - streamStartAt),
                        "timeToFirstDeltaMs" to if (firstDeltaAt > 0) firstDeltaAt - streamStartAt else 0
                    )
                )
                logStreamSuccess(
                    requestId = requestId,
                    conversationId = targetConversationId,
                    userMessageId = targetUserMessageId,
                    assistantMessageId = targetAssistantMessageId,
                    deltaCount = deltaCount,
                    timeToFirstDeltaMs = if (firstDeltaAt > 0) firstDeltaAt - streamStartAt else 0,
                    streamDurationMs = streamCompleteAt - streamStartAt
                )
                streamTurn to false
            } catch (streamError: CancellationException) {
                throw streamError
            } catch (streamError: Throwable) {
                val streamFailedAt = System.currentTimeMillis()
                val streamDurationMs = streamStartAt?.let { streamFailedAt - it } ?: -1L
                if (!streamError.isEligibleForFallback()) {
                    latencyLogger.mark(
                        traceId,
                        "vision_stream_ineligible_error",
                        mapOf(
                            "error" to (streamError.message ?: streamError::class.java.simpleName),
                            "errorClass" to streamError::class.java.simpleName,
                            "streamDurationMs" to streamDurationMs
                        )
                    )
                    throw streamError
                }

                val fallbackReason = FallbackReason.from(streamError)
                latencyLogger.mark(
                    traceId,
                    "vision_stream_fallback_start",
                    mapOf(
                        "error" to (streamError.message ?: streamError::class.java.simpleName),
                        "errorClass" to streamError::class.java.simpleName,
                        "fallbackReason" to fallbackReason.name,
                        "streamDurationMs" to streamDurationMs
                    )
                )
                logStreamFailureAndFallback(
                    requestId = requestId,
                    conversationId = targetConversationId,
                    userMessageId = targetUserMessageId,
                    assistantMessageId = targetAssistantMessageId,
                    fallbackReason = fallbackReason,
                    streamDurationMs = streamDurationMs,
                    streamError = streamError
                )

                // Edge now owns the bounded, parallel candidate race. Starting another
                // full client-side request here would turn one failed turn into a 25s + 50s
                // serial wait and duplicate image uploads.
                aiDraftRepository.clearStreamingState(targetConversationId)
                throw streamError
            }

            finalizeAssistantMessage(
                traceId = traceId,
                conversationId = targetConversationId,
                assistantMessageId = targetAssistantMessageId,
                turn = turn,
                allowedSourceMediaIds = prepared.attachments.map { it.mediaId },
                fallbackUsed = fallbackUsed
            )
            logSuccess(
                requestId = requestId,
                conversationId = targetConversationId,
                assistantMessageId = targetAssistantMessageId
            )
            latencyLogger.complete(
                traceId = traceId,
                status = "success",
                conversationType = conversationTypeForCards(turn.cards)
            )
            return VisionAssistantTurnResult.Success
        } catch (e: CancellationException) {
            latencyLogger.mark(
                traceId,
                "vision_turn_cancelled",
                mapOf("stage" to cancellationStage(prepared))
            )
            logFailure(
                stage = "cancel",
                requestId = requestId,
                conversationId = targetConversationId,
                userMessageId = targetUserMessageId,
                assistantMessageId = targetAssistantMessageId,
                error = e
            )
            throw e
        } catch (e: Throwable) {
            logFailure(
                stage = if (prepared == null) "prepare_or_placeholder" else "stream_or_fallback",
                requestId = requestId,
                conversationId = targetConversationId,
                userMessageId = targetUserMessageId,
                assistantMessageId = targetAssistantMessageId,
                error = e
            )
            latencyLogger.fail(traceId, e)
            return VisionAssistantTurnResult.Failure(e)
        } finally {
            prepared?.let {
                try {
                    releaseUseCase(it.requestId)
                    logRelease(requestId = it.requestId)
                } catch (releaseError: Throwable) {
                    Log.w(
                        TAG,
                        "vision release failed | requestId=${it.requestId} " +
                            "error=${releaseError.message ?: releaseError::class.java.simpleName}",
                        releaseError
                    )
                }
            }
            try {
                aiDraftRepository.clearStreamingState(targetConversationId)
            } catch (clearError: Throwable) {
                Log.w(
                    TAG,
                    "vision clear streaming state failed | conversationId=$targetConversationId " +
                        "error=${clearError.message ?: clearError::class.java.simpleName}",
                    clearError
                )
            }
            onAnalyzingChanged(false)
        }
    }

    private suspend fun ensureAssistantPlaceholder(
        conversationId: String,
        assistantMessageId: String
    ): PlaceholderStatus {
        val existing = aiDraftRepository.getChatMessageById(assistantMessageId)
        return when {
            existing == null -> {
                val placeholder = AiChatMessage(
                    id = assistantMessageId,
                    conversationId = conversationId,
                    role = ChatRole.Assistant,
                    text = ""
                )
                aiDraftRepository.insertChatMessage(conversationId, placeholder)
                PlaceholderStatus.Ready
            }

            existing.conversationId != conversationId -> {
                PlaceholderStatus.Invalid(
                    "Existing placeholder belongs to a different conversation"
                )
            }

            existing.role != ChatRole.Assistant -> {
                PlaceholderStatus.Invalid(
                    "Existing message is not an assistant placeholder"
                )
            }

            existing.text.isNotBlank() || existing.assistantCards.isNotEmpty() -> {
                PlaceholderStatus.AlreadyCompleted(assistantMessageId)
            }

            else -> PlaceholderStatus.Ready
        }
    }

    private suspend fun buildAssistantRequest(
        prepared: PreparedVisionRequest,
        traceId: String
    ): AiAssistantRequest {
        val currentDate = currentDateProvider.currentDate()
        val todayRecord = recordRepository.getRecordByDateAndStatus(currentDate, RecordStatus.Confirmed)
        return AiAssistantRequest(
            traceId = traceId,
            date = currentDate,
            userText = prepared.effectiveAiText,
            todayRecord = todayRecord,
            pendingDraft = null,
            recentMessages = aiDraftRepository.getRecentChatMessages(prepared.conversationId, 10),
            turnType = "user_message",
            attachments = prepared.attachments
        )
    }

    private suspend fun finalizeAssistantMessage(
        traceId: String,
        conversationId: String,
        assistantMessageId: String,
        turn: AiAssistantTurn,
        allowedSourceMediaIds: List<String>,
        fallbackUsed: Boolean
    ) {
        val finalReply = turn.replyText.trim()
        val normalizedCards = turn.cards.normalizeCardPhotoAssignments(allowedSourceMediaIds)
        val sanitizedCards = normalizedCards.sanitizeFinalAssistantCards()
        logFinalCardSanitizer(
            assistantMessageId = assistantMessageId,
            before = turn.cards,
            after = sanitizedCards,
            fallbackUsed = fallbackUsed
        )
        val finalCards = sanitizedCards.withDateMismatchGuardIfNeeded(conversationId)
        val finalMessage = AiChatMessage(
            id = assistantMessageId,
            conversationId = conversationId,
            role = ChatRole.Assistant,
            text = finalReply,
            assistantCards = finalCards
        )
        latencyLogger.bindAssistantMessage(
            traceId,
            assistantMessageId,
            conversationTypeForCards(finalCards)
        )
        latencyLogger.mark(
            traceId,
            "vision_final_persist_start",
            mapOf(
                "cardsCount" to finalCards.size,
                "fallbackUsed" to fallbackUsed
            )
        )
        aiDraftRepository.updateChatMessage(finalMessage)
        aiDraftRepository.clearStreamingState(conversationId)
        latencyLogger.mark(traceId, "vision_final_persist_complete")
    }

    private suspend fun List<AiChatCard>.withDateMismatchGuardIfNeeded(
        conversationId: String
    ): List<AiChatCard> {
        if (none { it is ShowConfirmCardPayload }) return this
        val conversationDate = conversationRepository.getConversationById(conversationId)?.conversationDate
            ?: return this
        val detectedCurrentDate = currentDateProvider.currentDate()
        if (conversationDate == detectedCurrentDate) return this

        return map { card ->
            when {
                card is DateMismatchGuardCardPayload -> card
                card is ShowConfirmCardPayload && card.confirmType == "food_record" -> {
                    DateMismatchGuardCardPayload(
                        id = "date-mismatch-guard-${card.id}",
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

    /**
     * Determines whether a streaming failure should trigger the non-streaming fallback.
     *
     * Only remote streaming transport, SSE protocol, and response-parsing failures are
     * recoverable. Cancellation, local validation/file errors, persistence errors,
     * business-input errors, and clear programming errors are not.
     *
     * The cause chain is inspected with a bounded depth and cycle protection so a
     * recoverable transport error wrapped by a local exception is still recognized,
     * but inspection cannot loop forever.
     */
    private fun Throwable.isEligibleForFallback(): Boolean {
        if (this is CancellationException) return false
        return causes(maxDepth = 4).any { cause ->
            cause is IOException ||
                cause is ProtocolException ||
                cause is JsonDataException ||
                (cause is HttpException && cause.isTemporaryHttpFailure())
        }
    }

    private fun HttpException.isTemporaryHttpFailure(): Boolean {
        val code = code()
        return code == 408 || code == 429 || code >= 500
    }

    /**
     * Categorizes a streaming failure into a safe, coarse-grained reason enum.
     * This is used for diagnostic logging only and must never include user data,
     * Base64, file paths, or full payloads.
     */
    private enum class FallbackReason {
        UPSTREAM_HEADER_TIMEOUT,
        TIMEOUT,
        PROTOCOL_ERROR,
        HTTP_ERROR,
        NETWORK_IO_ERROR,
        PARSE_ERROR,
        UNKNOWN;

        companion object {
            fun from(error: Throwable): FallbackReason {
                return when {
                    error.isUpstreamHeaderTimeout() -> UPSTREAM_HEADER_TIMEOUT
                    error.isTimeoutLike() -> TIMEOUT
                    error is HttpException -> HTTP_ERROR
                    error is ProtocolException -> PROTOCOL_ERROR
                    error is java.net.ProtocolException -> PROTOCOL_ERROR
                    error is JsonDataException -> PARSE_ERROR
                    error.isIoLike() -> NETWORK_IO_ERROR
                    else -> UNKNOWN
                }
            }

            private fun Throwable.isTimeoutLike(): Boolean {
                return causes(maxDepth = 4).any { cause ->
                    val name = cause::class.java.simpleName.lowercase()
                    name.contains("timeout") || name.contains("abort") ||
                        (cause is HttpException && (cause.code() == 408 || cause.code() == 504))
                }
            }

            private fun Throwable.isUpstreamHeaderTimeout(): Boolean {
                return causes(maxDepth = 4).any { cause ->
                    val message = cause.message.orEmpty().lowercase()
                    message.contains("upstream_header_timeout") ||
                        message.contains("upstream vision request timed out") ||
                        message.contains("signal has been aborted")
                }
            }

            private fun Throwable.isIoLike(): Boolean {
                return causes(maxDepth = 4).any { cause ->
                    cause is IOException && cause !is java.net.ProtocolException
                }
            }
        }
    }

    private fun mapPreparationError(error: Throwable, userMessageId: String): Throwable {
        return if (error is VisionPreparationFailure) {
            error
        } else {
            VisionPreparationFailure.Unknown(
                "Prepare failed for $userMessageId: ${error.message}",
                error
            )
        }
    }

    private fun Throwable.errorCode(): String {
        return if (this is VisionPreparationFailure) errorCode else "UNKNOWN"
    }

    private fun cancellationStage(prepared: PreparedVisionRequest?): String {
        return if (prepared == null) "before_prepare" else "after_prepare"
    }

    private fun conversationTypeForCards(cards: List<AiChatCard>): String {
        if (cards.isEmpty()) return "ai_reply_chat_only"
        return "ai_reply_with_card:${cards.joinToString(",") { it.type.name }}"
    }

    private sealed class PlaceholderStatus {
        object Ready : PlaceholderStatus()
        data class AlreadyCompleted(val assistantMessageId: String) : PlaceholderStatus()
        data class Invalid(val reason: String) : PlaceholderStatus()
    }

    // ------------------------------------------------------------------
    // Safe logging: never logs Base64, data URL, absolute paths, or full DTO.
    // ------------------------------------------------------------------

    private fun logStart(
        requestId: String,
        conversationId: String,
        userMessageId: String,
        assistantMessageId: String
    ) {
        Log.i(
            TAG,
            "vision turn start | requestId=$requestId " +
                "conversationId=$conversationId " +
                "userMessageId=$userMessageId " +
                "assistantMessageId=$assistantMessageId"
        )
    }

    private fun logSkip(
        conversationId: String,
        assistantMessageId: String,
        reason: String
    ) {
        Log.w(
            TAG,
            "vision turn skipped | conversationId=$conversationId " +
                "assistantMessageId=$assistantMessageId " +
                "reason=$reason"
        )
    }

    private fun logStreamStarted(
        requestId: String,
        conversationId: String,
        userMessageId: String,
        assistantMessageId: String,
        attachmentCount: Int
    ) {
        Log.i(
            TAG,
            "VISION_STREAM_START | requestId=$requestId " +
                "conversationId=$conversationId " +
                "userMessageId=$userMessageId " +
                "assistantMessageId=$assistantMessageId " +
                "attachmentCount=$attachmentCount"
        )
    }

    private fun logFirstDelta(
        requestId: String,
        conversationId: String,
        assistantMessageId: String,
        timeToFirstDeltaMs: Long
    ) {
        Log.i(
            TAG,
            "VISION_STREAM_FIRST_DELTA | requestId=$requestId " +
                "conversationId=$conversationId " +
                "assistantMessageId=$assistantMessageId " +
                "timeToFirstDeltaMs=$timeToFirstDeltaMs"
        )
    }

    private fun logStreamSuccess(
        requestId: String,
        conversationId: String,
        userMessageId: String,
        assistantMessageId: String,
        deltaCount: Int,
        timeToFirstDeltaMs: Long,
        streamDurationMs: Long
    ) {
        Log.i(
            TAG,
            "VISION_STREAM_SUCCESS | requestId=$requestId " +
                "conversationId=$conversationId " +
                "userMessageId=$userMessageId " +
                "assistantMessageId=$assistantMessageId " +
                "deltaCount=$deltaCount " +
                "timeToFirstDeltaMs=$timeToFirstDeltaMs " +
                "streamDurationMs=$streamDurationMs"
        )
    }

    private fun logStreamFailureAndFallback(
        requestId: String,
        conversationId: String,
        userMessageId: String,
        assistantMessageId: String,
        fallbackReason: FallbackReason,
        streamDurationMs: Long,
        streamError: Throwable
    ) {
        val httpStatus = (streamError as? HttpException)?.code()
            ?: (streamError.causes(maxDepth = 4).firstOrNull { it is HttpException } as? HttpException)?.code()
            ?: -1
        val exceptionClass = streamError::class.java.simpleName
        val errorMessage = streamError.message?.take(80)?.replace('\n', ' ') ?: "null"
        Log.w(
            TAG,
            "VISION_STREAM_FALLBACK | requestId=$requestId " +
                "conversationId=$conversationId " +
                "userMessageId=$userMessageId " +
                "assistantMessageId=$assistantMessageId " +
                "fallbackReason=$fallbackReason " +
                "exceptionClass=$exceptionClass " +
                "httpStatus=$httpStatus " +
                "streamDurationMs=$streamDurationMs " +
                "error=$errorMessage"
        )
    }

    private fun logFallbackCompleted(
        requestId: String,
        conversationId: String,
        userMessageId: String,
        assistantMessageId: String
    ) {
        Log.i(
            TAG,
            "VISION_STREAM_FALLBACK_COMPLETED | requestId=$requestId " +
                "conversationId=$conversationId " +
                "userMessageId=$userMessageId " +
                "assistantMessageId=$assistantMessageId"
        )
    }

    private fun logFailure(
        stage: String,
        requestId: String,
        conversationId: String,
        userMessageId: String,
        assistantMessageId: String,
        error: Throwable
    ) {
        Log.e(
            TAG,
            "vision turn failure | stage=$stage " +
                "requestId=$requestId " +
                "conversationId=$conversationId " +
                "userMessageId=$userMessageId " +
                "assistantMessageId=$assistantMessageId " +
                "error=${error.message ?: error::class.java.simpleName}",
            error
        )
    }

    private fun logSuccess(
        requestId: String,
        conversationId: String,
        assistantMessageId: String
    ) {
        Log.i(
            TAG,
            "vision turn success | requestId=$requestId " +
                "conversationId=$conversationId " +
                "assistantMessageId=$assistantMessageId"
        )
    }

    private fun logRelease(requestId: String) {
        Log.d(TAG, "vision attachments released | requestId=$requestId")
    }

    private fun logFinalCardSanitizer(
        assistantMessageId: String,
        before: List<AiChatCard>,
        after: List<AiChatCard>,
        fallbackUsed: Boolean
    ) {
        Log.i(
            TAG,
            "VISION_FINAL_CARD_SANITIZER | assistantMessageId=${assistantMessageId.shortId()} " +
                "fallbackUsed=$fallbackUsed " +
                "beforeCount=${before.size} beforeTypes=${before.typeOrder()} " +
                "afterCount=${after.size} afterTypes=${after.typeOrder()}"
        )
    }

    companion object {
        private const val TAG = "VisionAssistantTurn"
    }
}

private fun String.shortId(): String = take(8)

private fun List<AiChatCard>.typeOrder(): String =
    joinToString(">") { it.type.name }.ifBlank { "none" }

private fun Throwable.causes(maxDepth: Int): Sequence<Throwable> = sequence {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = this@causes
    var depth = 0
    while (current != null && depth < maxDepth && seen.add(current)) {
        yield(current)
        current = current.cause
        depth++
    }
}
