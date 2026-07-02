package com.example.domain.usecase

import com.example.domain.model.ai.SendUserMessageWithMediaRequest
import com.example.domain.model.ai.SendUserMessageWithMediaResult
import com.example.domain.repository.ChatMediaTransactionRepository

class SendUserMessageWithMediaUseCase(
    private val repository: ChatMediaTransactionRepository
) {
    suspend operator fun invoke(
        request: SendUserMessageWithMediaRequest
    ): SendUserMessageWithMediaResult {
        return repository.sendUserMessageWithMedia(request)
    }
}
