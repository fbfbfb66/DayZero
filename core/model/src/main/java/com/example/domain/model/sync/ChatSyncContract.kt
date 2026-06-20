package com.example.domain.model.sync

import java.time.LocalDate

data class ChatSyncConversationSnapshot(
    val id: String,
    val conversationDate: LocalDate,
    val title: String,
    val lastMessagePreview: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastActivityAtMillis: Long,
    val deletedAtMillis: Long? = null,
    val schemaVersion: Int = CHAT_SYNC_SCHEMA_VERSION
)

data class ChatSyncMessageSnapshot(
    val id: String,
    val conversationId: String,
    val role: String,
    val text: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val deletedAtMillis: Long? = null,
    val messageType: String = "Text",
    val contentJson: String? = null,
    val assistantCardsJson: String? = null,
    val suggestedRepliesJson: String? = null,
    val schemaVersion: Int = CHAT_SYNC_SCHEMA_VERSION
)

data class ChatSyncServerCursor(
    val serverUpdatedAtMillis: Long,
    val id: String
)

const val CHAT_SYNC_SCHEMA_VERSION = 1

