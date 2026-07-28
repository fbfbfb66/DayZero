package com.goings.dayzero.domain.model.ai.assistant

/**
 * Input to [com.goings.dayzero.domain.usecase.PrepareVisionAttachmentsForMessageUseCase].
 *
 * The repository must treat these three ids as the authoritative key and re-read
 * the persisted user message; it must not infer the target from UI state.
 */
data class PrepareVisionAttachmentsRequest(
    val requestId: String,
    val conversationId: String,
    val userMessageId: String
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        require(userMessageId.isNotBlank()) { "userMessageId must not be blank" }
    }
}
