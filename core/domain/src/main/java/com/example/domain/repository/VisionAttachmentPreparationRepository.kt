package com.example.domain.repository

import com.example.domain.model.ai.assistant.PreparedVisionRequest
import com.example.domain.model.ai.assistant.PrepareVisionAttachmentsRequest
import com.example.domain.model.ai.assistant.VisionPreparationFailure

/**
 * Repository responsible for turning a persisted user message with media into an
 * in-memory AI vision request payload.
 *
 * Implementations must:
 * - read the user message and its media from the authoritative local persistence layer;
 * - validate the media contract and binding;
 * - generate AI-specific derivative JPEGs and encode them as Base64;
 * - keep transient files under an isolated request-scoped cache directory;
 * - expose a release method to clean up those transient files.
 */
interface VisionAttachmentPreparationRepository {

    /**
     * Prepares the vision request payload for the given persisted user message.
     *
     * @return [PreparedVisionRequest] on success, or a typed [VisionPreparationFailure]
     */
    suspend fun prepare(request: PrepareVisionAttachmentsRequest): Result<PreparedVisionRequest>

    /**
     * Releases all transient files created for [requestId]. Idempotent.
     */
    suspend fun release(requestId: String): Result<Unit>
}
