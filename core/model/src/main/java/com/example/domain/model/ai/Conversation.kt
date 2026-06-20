package com.example.domain.model.ai

import java.time.LocalDate
import java.util.UUID

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val conversationDate: LocalDate,
    val title: String,
    val lastMessagePreview: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val lastActivityAt: Long = createdAt,
    val deletedAt: Long? = null
)
