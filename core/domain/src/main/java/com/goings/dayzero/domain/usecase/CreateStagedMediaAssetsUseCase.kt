package com.goings.dayzero.domain.usecase

import com.goings.dayzero.domain.model.media.MediaAsset
import com.goings.dayzero.domain.model.media.NewMediaAssetRequest
import com.goings.dayzero.domain.repository.MediaRepository

class CreateStagedMediaAssetsUseCase(
    private val mediaRepository: MediaRepository
) {
    suspend operator fun invoke(
        requests: List<NewMediaAssetRequest>,
        now: Long
    ): List<MediaAsset> {
        require(requests.isNotEmpty()) { "Media request list must not be empty" }
        val ids = requests.map { it.id }
        require(ids.all { it.isNotBlank() }) { "Media id must not be blank" }
        require(ids.toSet().size == ids.size) { "Media ids must be unique within one batch" }
        require(requests.all { it.conversationId.isNotBlank() }) { "Conversation id must not be blank" }
        require(requests.all { it.ownerLocalId.isNotBlank() }) { "Owner local id must not be blank" }
        return mediaRepository.createStagedMedia(requests, now)
    }
}
