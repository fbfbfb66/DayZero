package com.example.domain.repository

import com.example.domain.model.ai.SendUserMessageWithMediaRequest
import com.example.domain.model.ai.SendUserMessageWithMediaResult

interface ChatMediaTransactionRepository {
    suspend fun sendUserMessageWithMedia(
        request: SendUserMessageWithMediaRequest
    ): SendUserMessageWithMediaResult
}
