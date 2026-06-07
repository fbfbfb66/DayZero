package com.example.domain.model.ai.assistant

data class AiAssistantTurn(
    val id: String,
    val intent: AiIntent,
    val replyText: String,
    val cards: List<AiChatCard> = emptyList(),
    val suggestedReplies: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)
