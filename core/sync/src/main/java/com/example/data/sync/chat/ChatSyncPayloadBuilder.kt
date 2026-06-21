package com.example.data.sync.chat

import com.example.data.local.entity.AiChatMessageEntity
import com.example.data.local.entity.ConversationEntity
import com.example.domain.identity.AppIdentity
import com.example.domain.model.sync.CHAT_SYNC_SCHEMA_VERSION
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

class ChatSyncPayloadBuilder {
    fun conversationPayload(conversation: ConversationEntity, identity: AppIdentity): JSONObject {
        val conversationDate = LocalDate.parse(conversation.conversationDate)
        return JSONObject()
            .put("clientId", conversation.id)
            .put("remoteUserId", identity.remoteUserId ?: JSONObject.NULL)
            .put("conversationDate", conversationDate.toString())
            .put("title", conversation.title)
            .put("lastMessagePreview", conversation.lastMessagePreview)
            .put("createdAt", conversation.createdAt.toIsoInstant())
            .put("updatedAt", conversation.updatedAt.toIsoInstant())
            .put("lastActivityAt", conversation.lastActivityAt.toIsoInstant())
            .putNullableInstant("deletedAt", conversation.deletedAt)
            .put("schemaVersion", CHAT_SYNC_SCHEMA_VERSION)
    }

    fun messagePayload(
        message: AiChatMessageEntity,
        identity: AppIdentity,
        updatedAtMillis: Long
    ): JSONObject {
        return JSONObject()
            .put("clientId", message.id)
            .put("remoteUserId", identity.remoteUserId ?: JSONObject.NULL)
            .put("conversationId", message.conversationId)
            .put("role", message.role)
            .put("messageType", message.messageType)
            .put("text", message.text)
            .putNullableString("contentJson", message.contentJson)
            .putNullableString("assistantCardsJson", message.assistantCardsJson)
            .putNullableString("suggestedRepliesJson", message.suggestedRepliesJson)
            .put("createdAt", message.createdAt.toIsoInstant())
            .put("updatedAt", updatedAtMillis.toIsoInstant())
            .putNullableInstant("deletedAt", message.deletedAt)
            .put("schemaVersion", CHAT_SYNC_SCHEMA_VERSION)
    }

    private fun JSONObject.putNullableString(name: String, value: String?): JSONObject {
        return put(name, value ?: JSONObject.NULL)
    }

    private fun JSONObject.putNullableInstant(name: String, value: Long?): JSONObject {
        return put(name, value?.toIsoInstant() ?: JSONObject.NULL)
    }

    private fun Long.toIsoInstant(): String = Instant.ofEpochMilli(this).toString()
}
