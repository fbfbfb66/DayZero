package com.example.domain.model.ai

data class SendUserMessageWithMediaRequest(
    val conversationId: String,
    val userMessageId: String,
    val text: String,
    val orderedMediaIds: List<String>,
    val createdAt: Long
)
