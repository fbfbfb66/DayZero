package com.goings.dayzero.domain.usecase

import com.goings.dayzero.domain.model.media.MediaAsset
import com.goings.dayzero.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow

class ObserveConversationMediaUseCase(
    private val mediaRepository: MediaRepository
) {
    operator fun invoke(conversationId: String): Flow<List<MediaAsset>> {
        require(conversationId.isNotBlank()) { "Conversation id must not be blank" }
        return mediaRepository.observeConversationMedia(conversationId)
    }
}
