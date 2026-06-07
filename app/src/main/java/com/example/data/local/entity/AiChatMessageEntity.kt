package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_chat_messages")
data class AiChatMessageEntity(
    @PrimaryKey val id: String,
    val role: String,
    val text: String,
    val createdAt: Long,
    val relatedDraftId: String?,
    val actionType: String? = null // For interactive messages like conflicts
)
