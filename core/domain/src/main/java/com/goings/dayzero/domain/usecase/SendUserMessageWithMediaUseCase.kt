package com.goings.dayzero.domain.usecase

import com.goings.dayzero.domain.model.ai.SendUserMessageWithMediaRequest
import com.goings.dayzero.domain.model.ai.SendUserMessageWithMediaResult
import com.goings.dayzero.domain.repository.ChatMediaTransactionRepository

class SendUserMessageWithMediaUseCase(
    private val repository: ChatMediaTransactionRepository
) {
    suspend operator fun invoke(
        request: SendUserMessageWithMediaRequest
    ): SendUserMessageWithMediaResult {
        return repository.sendUserMessageWithMedia(request)
    }
}
