package com.example.domain.usecase

import com.example.domain.model.media.LocalMediaImportItemResult
import com.example.domain.model.media.MediaLifecycleState
import com.example.domain.model.media.MediaImportFailureCode
import com.example.domain.repository.LocalMediaImportRepository
import com.example.domain.repository.MediaRepository

class RetryLocalMediaImportUseCase(
    private val mediaRepository: MediaRepository,
    private val importRepository: LocalMediaImportRepository
) {
    suspend operator fun invoke(mediaId: String): LocalMediaImportItemResult {
        require(mediaId.isNotBlank()) { "Media ID must not be blank" }

        val assets = mediaRepository.getMediaByIds(listOf(mediaId))
        if (assets.isEmpty()) {
            return LocalMediaImportItemResult.Failed(mediaId, MediaImportFailureCode.SOURCE_MISSING)
        }
        val asset = assets.first()
        if (asset.deletedAt != null) {
            throw IllegalArgumentException("Soft deleted media asset cannot be retried: $mediaId")
        }

        return when (asset.lifecycleState) {
            MediaLifecycleState.READY -> {
                LocalMediaImportItemResult.Ready(mediaId, asset)
            }
            MediaLifecycleState.STAGED, MediaLifecycleState.FAILED -> {
                importRepository.retryImport(mediaId)
            }
        }
    }
}
