package com.goings.dayzero.domain.repository

import com.goings.dayzero.domain.model.ai.assistant.AiAssistantRequest
import com.goings.dayzero.domain.model.ai.assistant.AiAssistantTurn

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
