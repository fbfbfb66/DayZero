package com.goings.dayzero.domain.usecase

import com.goings.dayzero.domain.repository.VisionAttachmentPreparationRepository

/**
 * Cleans up transient AI derivative files after streaming, fallback, failure, or cancellation.
 *
 * Safe to call multiple times for the same [requestId].
 */
class ReleasePreparedVisionAttachmentsUseCase(
    private val repository: VisionAttachmentPreparationRepository
) {
    suspend operator fun invoke(requestId: String): Result<Unit> {
        return repository.release(requestId)
    }
}
