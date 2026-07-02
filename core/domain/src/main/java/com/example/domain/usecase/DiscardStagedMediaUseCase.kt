package com.example.domain.usecase

import com.example.domain.model.media.MediaLifecycleState
import com.example.domain.repository.LocalMediaImportRepository
import com.example.domain.repository.MediaRepository

class DiscardStagedMediaUseCase(
    private val mediaRepository: MediaRepository,
    private val importRepository: LocalMediaImportRepository
) {
    suspend operator fun invoke(mediaId: String, now: Long): Boolean {
        require(mediaId.isNotBlank()) { "Media ID must not be blank" }

        val assets = mediaRepository.getMediaByIds(listOf(mediaId))
        if (assets.isEmpty()) {
            return true
        }
        val asset = assets.first()
        if (asset.deletedAt != null) {
            return true
        }

        if (asset.lifecycleState == MediaLifecycleState.READY) {
            throw IllegalArgumentException("Cannot discard a READY media asset: $mediaId")
        }

        val filesCleaned = importRepository.discardStagedMedia(mediaId)
        mediaRepository.softDeleteMedia(mediaId, asset.conversationId, now)
        return filesCleaned
    }
}
