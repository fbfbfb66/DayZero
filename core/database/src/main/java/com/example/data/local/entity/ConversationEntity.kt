package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["conversationDate"]),
        Index(value = ["lastActivityAt"])
    ]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val conversationDate: String,
    val title: String,
    val lastMessagePreview: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastActivityAt: Long,
    val deletedAt: Long? = null
)
