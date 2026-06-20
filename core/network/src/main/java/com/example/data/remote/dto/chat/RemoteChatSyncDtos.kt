package com.example.data.remote.dto.chat

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RemoteConversationDto(
    @Json(name = "id") val id: String,
    @Json(name = "user_id") val userId: String? = null,
    @Json(name = "conversation_date") val conversationDate: String,
    @Json(name = "title") val title: String,
    @Json(name = "last_message_preview") val lastMessagePreview: String,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "updated_at") val updatedAt: String,
    @Json(name = "last_activity_at") val lastActivityAt: String,
    @Json(name = "deleted_at") val deletedAt: String? = null,
    @Json(name = "server_updated_at") val serverUpdatedAt: String? = null,
    @Json(name = "schema_version") val schemaVersion: Int = 1
)

@JsonClass(generateAdapter = true)
data class RemoteAiChatMessageDto(
    @Json(name = "id") val id: String,
    @Json(name = "user_id") val userId: String? = null,
    @Json(name = "conversation_id") val conversationId: String,
    @Json(name = "role") val role: String,
    @Json(name = "message_type") val messageType: String,
    @Json(name = "text") val text: String,
    @Json(name = "content_json") val contentJson: String? = null,
    @Json(name = "assistant_cards") val assistantCardsJson: String? = null,
    @Json(name = "suggested_replies_json") val suggestedRepliesJson: String? = null,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "updated_at") val updatedAt: String,
    @Json(name = "deleted_at") val deletedAt: String? = null,
    @Json(name = "server_updated_at") val serverUpdatedAt: String? = null,
    @Json(name = "schema_version") val schemaVersion: Int = 1
)

