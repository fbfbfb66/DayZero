package com.example.data.sync.chat

import android.util.Log
import androidx.room.withTransaction
import com.example.data.local.dao.AiChatMessageDao
import com.example.data.local.dao.ConversationDao
import com.example.data.local.dao.SyncQueueDao
import com.example.data.local.database.DayZeroDatabase
import com.example.data.local.entity.AiChatMessageEntity
import com.example.domain.model.sync.CHAT_SYNC_SCHEMA_VERSION
import com.example.domain.model.sync.ChatSyncMessageSnapshot

open class ChatMessageMergeException(message: String) : RuntimeException(message)

class ImmutableMessageConflictException(
    val messageId: String,
    val fieldName: String,
    val localValue: String,
    val remoteValue: String
) : ChatMessageMergeException("Immutable message conflict id=$messageId field=$fieldName")

class ImmutableMessageContentConflictException(
    val messageId: String,
    val fieldName: String
) : ChatMessageMergeException("Immutable message content conflict id=$messageId field=$fieldName")

class MissingParentConversationException(
    val messageId: String,
    val conversationId: String
) : ChatMessageMergeException("Missing parent conversation messageId=$messageId conversationId=$conversationId")

class InvalidRemoteMessageException(
    val messageId: String,
    val reason: String
) : ChatMessageMergeException("Invalid remote message id=$messageId reason=$reason")

class ChatMessageRemoteMerger(
    private val database: DayZeroDatabase,
    private val messageDao: AiChatMessageDao,
    private val conversationDao: ConversationDao,
    private val syncQueueDao: SyncQueueDao,
    private val cardMergePolicy: ChatMessageCardMergePolicy = ChatMessageCardMergePolicy()
) {

    suspend fun mergeMessagePage(
        identityLocalId: String,
        remoteSnapshots: List<ChatSyncMessageSnapshot>
    ): ChatMessageMergeStats {
        if (remoteSnapshots.isEmpty()) return ChatMessageMergeStats()

        var stats = ChatMessageMergeStats()
        database.withTransaction {
            remoteSnapshots.forEach { remote ->
                stats += applyRemoteMessage(identityLocalId, remote)
            }
        }
        return stats
    }

    private suspend fun applyRemoteMessage(
        identityLocalId: String,
        remote: ChatSyncMessageSnapshot
    ): ChatMessageMergeStats {
        validateRemoteSchema(remote)
        conversationDao.getConversationById(remote.conversationId)
            ?: throw MissingParentConversationException(remote.id, remote.conversationId)
        val local = messageDao.getMessageByIdIncludingDeleted(remote.id)
        val remoteDeleted = remote.deletedAtMillis != null

        if (!remoteDeleted && isRemotePlaceholder(remote)) {
            throw InvalidRemoteMessageException(remote.id, "remote_placeholder_or_delta")
        }

        if (local == null) {
            return insertMissingLocal(remote, remoteDeleted)
        }

        validateImmutableFields(local, remote)
        val isDirty = isLocalDirty(identityLocalId, remote.id)
        if (isDirty) {
            Log.d(TAG, "message merge deferred dirty id=${remote.id.take(8)}")
            return ChatMessageMergeStats(deferredLocalDirtyCount = 1)
        }

        val localDeleted = local.deletedAt != null
        if (localDeleted && !remoteDeleted) {
            Log.d(TAG, "message merge refused resurrection id=${remote.id.take(8)}")
            return ChatMessageMergeStats(skippedCount = 1)
        }
        if (remoteDeleted) {
            return applyRemoteTombstone(local, remote)
        }

        validateFinalText(local, remote)
        val preferRemoteSnapshot = remote.updatedAtMillis >= local.updatedAt
        val mergedCards = try {
            cardMergePolicy.mergeAssistantCards(
                messageId = remote.id,
                localRaw = local.assistantCardsJson,
                remoteRaw = remote.assistantCardsJson,
                preferRemoteSnapshot = preferRemoteSnapshot
            )
        } catch (e: CardMergeConflictException) {
            throw e
        }

        val mergedContent = mergeJsonField(local.contentJson, remote.contentJson, preferRemoteSnapshot)
        val mergedSuggestions = mergeJsonField(local.suggestedRepliesJson, remote.suggestedRepliesJson, preferRemoteSnapshot)
        val newText = if (isLocalAssistantPlaceholder(local)) remote.text else local.text

        val changed = newText != local.text ||
            !cardMergePolicy.semanticallyEqual(local.contentJson, mergedContent) ||
            !cardMergePolicy.semanticallyEqual(local.assistantCardsJson, mergedCards) ||
            !cardMergePolicy.semanticallyEqual(local.suggestedRepliesJson, mergedSuggestions) ||
            local.updatedAt != remote.updatedAtMillis ||
            local.deletedAt != remote.deletedAtMillis

        if (!changed) return ChatMessageMergeStats(skippedCount = 1)

        messageDao.applyRemoteMutableFields(
            id = local.id,
            text = newText,
            contentJson = mergedContent,
            assistantCardsJson = mergedCards,
            suggestedRepliesJson = mergedSuggestions,
            updatedAt = remote.updatedAtMillis,
            deletedAt = remote.deletedAtMillis
        )
        return ChatMessageMergeStats(updatedCount = 1)
    }

    private suspend fun insertMissingLocal(
        remote: ChatSyncMessageSnapshot,
        remoteDeleted: Boolean
    ): ChatMessageMergeStats {
        val cardsJson = cardMergePolicy.mergeAssistantCards(
            messageId = remote.id,
            localRaw = null,
            remoteRaw = remote.assistantCardsJson,
            preferRemoteSnapshot = true
        )
        val entity = AiChatMessageEntity(
            id = remote.id,
            conversationId = remote.conversationId,
            role = remote.role,
            text = remote.text,
            createdAt = remote.createdAtMillis,
            relatedDraftId = null,
            messageType = remote.messageType,
            contentJson = remote.contentJson,
            assistantCardsJson = cardsJson,
            suggestedRepliesJson = remote.suggestedRepliesJson,
            updatedAt = remote.updatedAtMillis,
            deletedAt = remote.deletedAtMillis
        )
        messageDao.insertMessage(entity)
        Log.d(TAG, "message merge inserted id=${remote.id.take(8)} tombstone=$remoteDeleted")
        return if (remoteDeleted) {
            ChatMessageMergeStats(insertedCount = 1, tombstoneCount = 1)
        } else {
            ChatMessageMergeStats(insertedCount = 1)
        }
    }

    private suspend fun applyRemoteTombstone(
        local: AiChatMessageEntity,
        remote: ChatSyncMessageSnapshot
    ): ChatMessageMergeStats {
        if (local.deletedAt != null) {
            if (remote.updatedAtMillis <= local.updatedAt) return ChatMessageMergeStats(skippedCount = 1)
        } else if (remote.updatedAtMillis < local.updatedAt) {
            return ChatMessageMergeStats(skippedCount = 1)
        }

        messageDao.applyRemoteMutableFields(
            id = local.id,
            text = local.text,
            contentJson = local.contentJson,
            assistantCardsJson = local.assistantCardsJson,
            suggestedRepliesJson = local.suggestedRepliesJson,
            updatedAt = remote.updatedAtMillis,
            deletedAt = remote.deletedAtMillis
        )
        return ChatMessageMergeStats(tombstoneCount = 1)
    }

    private fun validateRemoteSchema(remote: ChatSyncMessageSnapshot) {
        if (remote.schemaVersion > CHAT_SYNC_SCHEMA_VERSION) {
            throw InvalidRemoteMessageException(remote.id, "unsupported_schema_version")
        }
        if (remote.id.isBlank() || remote.conversationId.isBlank() || remote.role.isBlank() || remote.messageType.isBlank()) {
            throw InvalidRemoteMessageException(remote.id, "missing_identity_field")
        }
    }

    private fun validateImmutableFields(local: AiChatMessageEntity, remote: ChatSyncMessageSnapshot) {
        if (local.conversationId != remote.conversationId) {
            throw ImmutableMessageConflictException(remote.id, "conversationId", local.conversationId ?: "", remote.conversationId)
        }
        if (local.role != remote.role) {
            throw ImmutableMessageConflictException(remote.id, "role", local.role, remote.role)
        }
        if (local.messageType != remote.messageType) {
            throw ImmutableMessageConflictException(remote.id, "messageType", local.messageType, remote.messageType)
        }
        if (local.createdAt != remote.createdAtMillis) {
            throw ImmutableMessageConflictException(remote.id, "createdAt", local.createdAt.toString(), remote.createdAtMillis.toString())
        }
    }

    private fun validateFinalText(local: AiChatMessageEntity, remote: ChatSyncMessageSnapshot) {
        if (local.role.equals(ROLE_USER, ignoreCase = true)) {
            if (local.text != remote.text) {
                throw ImmutableMessageContentConflictException(remote.id, "user_final_text")
            }
            return
        }

        val localPlaceholder = isLocalAssistantPlaceholder(local)
        if (localPlaceholder) return
        if (local.text != remote.text) {
            throw ImmutableMessageContentConflictException(remote.id, "assistant_final_text")
        }
    }

    private suspend fun isLocalDirty(identityLocalId: String, messageId: String): Boolean {
        return syncQueueDao.countActiveTasksForEntityAndOperation(
            ownerLocalId = identityLocalId,
            entityType = ChatSyncQueueContract.ENTITY_MESSAGE,
            entityLocalId = messageId,
            operation = ChatSyncQueueContract.OP_UPSERT_MESSAGE
        ) > 0
    }

    private fun isLocalAssistantPlaceholder(local: AiChatMessageEntity): Boolean {
        return local.role.equals(ROLE_ASSISTANT, ignoreCase = true) &&
            local.text.isBlank() &&
            local.contentJson.isNullOrBlank() &&
            local.assistantCardsJson.isNullOrBlank() &&
            local.suggestedRepliesJson.isNullOrBlank()
    }

    private fun isRemotePlaceholder(remote: ChatSyncMessageSnapshot): Boolean {
        return remote.role.equals(ROLE_ASSISTANT, ignoreCase = true) &&
            remote.text.isBlank() &&
            remote.contentJson.isNullOrBlank() &&
            remote.assistantCardsJson.isNullOrBlank() &&
            remote.suggestedRepliesJson.isNullOrBlank()
    }

    private fun mergeJsonField(localRaw: String?, remoteRaw: String?, preferRemoteSnapshot: Boolean): String? {
        if (cardMergePolicy.semanticallyEqual(localRaw, remoteRaw)) return localRaw
        return if (preferRemoteSnapshot) remoteRaw else localRaw
    }

    companion object {
        private const val TAG = "DayZeroChatMsgPull"
        private const val ROLE_USER = "User"
        private const val ROLE_ASSISTANT = "Assistant"
    }
}

data class ChatMessageMergeStats(
    val insertedCount: Int = 0,
    val updatedCount: Int = 0,
    val tombstoneCount: Int = 0,
    val deferredLocalDirtyCount: Int = 0,
    val skippedCount: Int = 0
) {
    operator fun plus(other: ChatMessageMergeStats): ChatMessageMergeStats {
        return ChatMessageMergeStats(
            insertedCount = insertedCount + other.insertedCount,
            updatedCount = updatedCount + other.updatedCount,
            tombstoneCount = tombstoneCount + other.tombstoneCount,
            deferredLocalDirtyCount = deferredLocalDirtyCount + other.deferredLocalDirtyCount,
            skippedCount = skippedCount + other.skippedCount
        )
    }
}
