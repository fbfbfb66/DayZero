package com.example.domain.model.ai

import java.util.UUID

data class AiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val relatedDraftId: String? = null,
    val actionType: ChatActionType? = null
)

enum class ChatActionType {
    None, MealConflict
}
