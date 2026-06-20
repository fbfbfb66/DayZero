package com.example.data.repository

import com.example.domain.model.ai.assistant.AiAssistantRequest
import com.example.domain.model.ai.assistant.AiAssistantTurn
import com.example.domain.model.ai.assistant.AiIntent
import com.example.domain.repository.AiAssistantRepository
import java.util.UUID

class FakeAiAssistantRepository : AiAssistantRepository {
    override suspend fun sendMessage(request: AiAssistantRequest): AiAssistantTurn {
        return AiAssistantTurn(
            id = UUID.randomUUID().toString(),
            intent = AiIntent.GeneralChat,
            replyText = "AssistantTurnV2 fake reply: ${request.userText}",
            cards = emptyList(),
            suggestedReplies = emptyList()
        )
    }
}
