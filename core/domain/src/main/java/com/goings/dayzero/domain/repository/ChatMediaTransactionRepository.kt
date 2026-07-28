package com.goings.dayzero.domain.repository

import com.goings.dayzero.domain.model.ai.SendUserMessageWithMediaRequest
import com.goings.dayzero.domain.model.ai.SendUserMessageWithMediaResult

interface ChatMediaTransactionRepository {
    suspend fun sendUserMessageWithMedia(
        request: SendUserMessageWithMediaRequest
    ): SendUserMessageWithMediaResult
}
