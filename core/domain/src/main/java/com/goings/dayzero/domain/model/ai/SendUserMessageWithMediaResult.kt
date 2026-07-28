package com.goings.dayzero.domain.model.ai

sealed class SendUserMessageWithMediaResult {
    data class Committed(
        val userMessageId: String,
        val assistantPlaceholderId: String
    ) : SendUserMessageWithMediaResult()

    data object AlreadyCommitted : SendUserMessageWithMediaResult()

    data class InvalidConversation(val reason: String) : SendUserMessageWithMediaResult()

    data class InvalidMedia(val reason: String) : SendUserMessageWithMediaResult()

    data class MediaAlreadyAttached(val mediaIds: List<String>) : SendUserMessageWithMediaResult()

    data class Conflict(val reason: String) : SendUserMessageWithMediaResult()

    data class Failed(val error: Throwable) : SendUserMessageWithMediaResult()
}
