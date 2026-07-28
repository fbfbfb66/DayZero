package com.goings.dayzero.data.sync.chat

import android.util.Log
import com.goings.dayzero.data.local.dao.SyncQueueDao
import com.goings.dayzero.data.local.entity.AiChatMessageEntity
import com.goings.dayzero.data.local.entity.ConversationEntity
import com.goings.dayzero.data.local.entity.SyncQueueEntity
import com.goings.dayzero.data.sync.DayZeroSyncConstants
import com.goings.dayzero.domain.identity.AppIdentity

class ChatSyncQueueWriter(
    private val syncQueueDao: SyncQueueDao,
    private val payloadBuilder: ChatSyncPayloadBuilder = ChatSyncPayloadBuilder()
) {
    suspend fun enqueueConversationUpsert(
        conversation: ConversationEntity,
        identity: AppIdentity
    ): Boolean {
        val now = System.currentTimeMillis()
        return enqueueLatest(
            item = SyncQueueEntity(
                entityType = ChatSyncQueueContract.ENTITY_CONVERSATION,
                entityLocalId = conversation.id,
                operation = ChatSyncQueueContract.OP_UPSERT_CONVERSATION,
                payloadJson = payloadBuilder.conversationPayload(conversation, identity).toString(),
                status = DayZeroSyncConstants.STATUS_PENDING,
                createdAt = now,
                updatedAt = now,
                ownerLocalId = identity.localOwnerId
            ),
            reason = "chat_conversation_latest"
        )
    }

    suspend fun enqueueMessageUpsert(
        message: AiChatMessageEntity,
        identity: AppIdentity
    ): Boolean {
        if (!isSyncableFinalMessage(message)) {
            Log.d(LOG_PREFIX, "enqueue skipped placeholder messageId=${message.id}")
            return false
        }
        val now = System.currentTimeMillis()
        return enqueueLatest(
            item = SyncQueueEntity(
                entityType = ChatSyncQueueContract.ENTITY_MESSAGE,
                entityLocalId = message.id,
                operation = ChatSyncQueueContract.OP_UPSERT_MESSAGE,
                payloadJson = payloadBuilder.messagePayload(message, identity, message.updatedAt).toString(),
                status = DayZeroSyncConstants.STATUS_PENDING,
                createdAt = now,
                updatedAt = now,
                ownerLocalId = identity.localOwnerId
            ),
            reason = "chat_message_latest"
        )
    }

    fun isSyncableFinalMessage(message: AiChatMessageEntity): Boolean {
        if (message.deletedAt != null) return true
        if (message.role.equals("Assistant", ignoreCase = true)) {
            val hasStoredPayload = message.text.isNotBlank() ||
                !message.contentJson.isNullOrBlank() ||
                !message.assistantCardsJson.isNullOrBlank() ||
                !message.suggestedRepliesJson.isNullOrBlank()
            return hasStoredPayload
        }
        return message.text.isNotBlank() || hasMediaAttachment(message.contentJson)
    }

    /**
     * Returns true when [contentJson] contains a valid Phase 2B media attachment
     * contract with at least one sourceMediaId. Non-object contentJson values,
     * unknown fields, and missing/empty media arrays are treated as "no media".
     */
    private fun hasMediaAttachment(contentJson: String?): Boolean {
        if (contentJson.isNullOrBlank()) return false
        return try {
            val json = org.json.JSONObject(contentJson)
            if (!json.has("media") || json.isNull("media")) return false
            val media = json.optJSONObject("media") ?: return false
            if (media.optInt("schemaVersion", -1) != MEDIA_SCHEMA_VERSION) return false
            val ids = media.optJSONArray("sourceMediaIds") ?: return false
            ids.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun enqueueLatest(item: SyncQueueEntity, reason: String): Boolean {
        val coalesced = syncQueueDao.coalescePendingTask(
            ownerLocalId = item.ownerLocalId,
            entityType = item.entityType,
            entityLocalId = item.entityLocalId,
            operation = item.operation,
            payloadJson = item.payloadJson,
            updatedAt = item.updatedAt,
            reason = reason
        )
        if (coalesced > 0) {
            Log.d(LOG_PREFIX, "enqueue coalesced entityType=${item.entityType} clientId=${item.entityLocalId}")
            return false
        }

        syncQueueDao.insert(item)
        Log.d(LOG_PREFIX, "enqueue success entityType=${item.entityType} clientId=${item.entityLocalId}")
        return true
    }

    private companion object {
        private const val LOG_PREFIX = "DayZeroChatSync"
        private const val MEDIA_SCHEMA_VERSION = 1
    }
}
