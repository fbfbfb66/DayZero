package com.example.domain.repository

import com.example.domain.model.ai.assistant.AiAssistantRequest
import com.example.domain.model.ai.assistant.AiAssistantTurn

interface AiAssistantRepository {
    suspend fun sendMessage(request: AiAssistantRequest): AiAssistantTurn

    suspend fun streamMessage(
        request: AiAssistantRequest,
        onDelta: suspend (String) -> Unit
    ): AiAssistantTurn {
        val turn = sendMessage(request)
        onDelta(turn.replyText)
        return turn
    }
}
