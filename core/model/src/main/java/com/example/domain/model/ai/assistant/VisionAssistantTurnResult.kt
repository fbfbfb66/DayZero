package com.example.domain.model.ai.assistant

/**
 * Result of a single vision assistant turn orchestration attempt.
 */
sealed class VisionAssistantTurnResult {
    object Success : VisionAssistantTurnResult()

    /**
     * The assistant placeholder already has final content; the turn was not started
     * to avoid silently overwriting a completed reply.
     */
    data class AlreadyCompleted(val assistantMessageId: String) : VisionAssistantTurnResult()

    /**
     * The input conversation or user message is not in a state that allows a vision turn,
     * e.g. the user message does not exist or does not belong to the conversation.
     */
    data class InvalidInput(val reason: String) : VisionAssistantTurnResult()

    /**
     * The turn failed after starting. [error] is safe to surface through the existing
     * text-path error handler; it will never be a [kotlinx.coroutines.CancellationException].
     */
    data class Failure(val error: Throwable) : VisionAssistantTurnResult()
}
