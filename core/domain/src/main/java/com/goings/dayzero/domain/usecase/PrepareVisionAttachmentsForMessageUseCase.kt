package com.goings.dayzero.domain.usecase

import com.goings.dayzero.domain.model.ai.assistant.PrepareVisionAttachmentsRequest
import com.goings.dayzero.domain.model.ai.assistant.PreparedVisionRequest
import com.goings.dayzero.domain.model.ai.assistant.VisionPreparationFailure
import com.goings.dayzero.domain.repository.VisionAttachmentPreparationRepository

/**
 * UseCase entry point for building an in-memory AI vision request from a persisted
 * user message. The result is intended to be shared by streaming and fallback paths.
 */
class PrepareVisionAttachmentsForMessageUseCase(
    private val repository: VisionAttachmentPreparationRepository
) {
    suspend operator fun invoke(
        request: PrepareVisionAttachmentsRequest
    ): Result<PreparedVisionRequest> {
        return repository.prepare(request)
    }

    suspend operator fun invoke(
        requestId: String,
        conversationId: String,
        userMessageId: String
    ): Result<PreparedVisionRequest> {
        return invoke(
            PrepareVisionAttachmentsRequest(
                requestId = requestId,
                conversationId = conversationId,
                userMessageId = userMessageId
            )
        )
    }
}
