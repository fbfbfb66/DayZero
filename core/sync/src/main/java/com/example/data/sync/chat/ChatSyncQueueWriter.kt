package com.example.data.sync.chat

import android.util.Log
import com.example.data.local.dao.SyncQueueDao
import com.example.data.local.entity.AiChatMessageEntity
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.SyncQueueEntity
import com.example.data.sync.DayZeroSyncConstants
import com.example.domain.identity.AppIdentity

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
        identity: AppIdentity,
        updatedAtMillis: Long = System.currentTimeMillis()
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
                payloadJson = payloadBuilder.messagePayload(message, identity, updatedAtMillis).toString(),
                status = DayZeroSyncConstants.STATUS_PENDING,
                createdAt = now,
                updatedAt = now,
                ownerLocalId = identity.localOwnerId
            ),
            reason = "chat_message_latest"
        )
    }

    fun isSyncableFinalMessage(message: AiChatMessageEntity): Boolean {
        if (message.role.equals("Assistant", ignoreCase = true)) {
            val hasStoredPayload = message.text.isNotBlank() ||
                !message.contentJson.isNullOrBlank() ||
                !message.assistantCardsJson.isNullOrBlank() ||
                !message.suggestedRepliesJson.isNullOrBlank()
            return hasStoredPayload
        }
        return message.text.isNotBlank()
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
    }
}
