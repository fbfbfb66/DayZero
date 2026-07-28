package com.goings.dayzero.domain.usecase

import com.goings.dayzero.domain.repository.AiDraftRepository

class CreateConversationWithFirstMessageUseCase(
    private val aiDraftRepository: AiDraftRepository
) {
    suspend operator fun invoke(text: String, now: Long = System.currentTimeMillis()): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null
        return aiDraftRepository.createConversationWithFirstMessage(trimmed, now)
    }
}
