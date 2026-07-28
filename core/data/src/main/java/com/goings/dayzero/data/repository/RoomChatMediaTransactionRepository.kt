package com.goings.dayzero.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.goings.dayzero.data.local.dao.AiChatMessageDao
import com.goings.dayzero.data.local.dao.ConversationDao
import com.goings.dayzero.data.local.dao.MediaAssetDao
import com.goings.dayzero.data.local.database.DayZeroDatabase
import com.goings.dayzero.data.local.entity.AiChatMessageEntity
import com.goings.dayzero.data.local.mapper.AiChatMessageMapper
import com.goings.dayzero.data.sync.chat.ChatSyncQueueWriter
import com.goings.dayzero.data.sync.media.MediaSyncQueueWriter
import com.goings.dayzero.domain.identity.CurrentIdentityProvider
import com.goings.dayzero.domain.model.ai.AiChatMessage
import com.goings.dayzero.domain.model.ai.ChatMessageType
import com.goings.dayzero.domain.model.ai.ChatRole
import com.goings.dayzero.domain.model.ai.SendUserMessageWithMediaRequest
import com.goings.dayzero.domain.model.ai.SendUserMessageWithMediaResult
import com.goings.dayzero.domain.model.ai.assistant.assistantPlaceholderId
import com.goings.dayzero.domain.model.media.MediaLifecycleState
import com.goings.dayzero.domain.repository.ChatMediaTransactionRepository
import kotlinx.coroutines.CancellationException
import java.nio.charset.StandardCharsets
import java.util.UUID

class RoomChatMediaTransactionRepository(
    private val database: DayZeroDatabase,
    private val identityProvider: CurrentIdentityProvider,
    private val chatSyncQueueWriter: ChatSyncQueueWriter,
    private val mediaSyncQueueWriter: MediaSyncQueueWriter = MediaSyncQueueWriter(database.syncQueueDao()),
    private val mediaAssetDao: MediaAssetDao = database.mediaAssetDao()
) : ChatMediaTransactionRepository {

    private val chatDao: AiChatMessageDao = database.aiChatMessageDao()
    private val conversationDao: ConversationDao = database.conversationDao()
    private val mediaDao: MediaAssetDao = mediaAssetDao
    private val mapper = AiChatMessageMapper()

    override suspend fun sendUserMessageWithMedia(
        request: SendUserMessageWithMediaRequest
    ): SendUserMessageWithMediaResult {
        // Resolve identity (which may perform remote sign-in/refresh over the network)
        // BEFORE opening the Room write transaction so network latency never blocks
        // while holding the DB write lock.
        val identity = identityProvider.currentIdentity()
        return try {
            database.withTransaction {
                executeTransaction(request, identity)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransactionAbortException) {
            e.result
        } catch (e: SQLiteConstraintException) {
            classifyExistingState(request)
                ?: SendUserMessageWithMediaResult.Conflict(
                    "Primary key conflict for ${request.userMessageId} but no consistent committed state found"
                )
        } catch (e: Exception) {
            SendUserMessageWithMediaResult.Failed(e)
        }
    }

    private suspend fun executeTransaction(
        request: SendUserMessageWithMediaRequest,
        identity: com.goings.dayzero.domain.identity.AppIdentity
    ): SendUserMessageWithMediaResult {
        val conversation = conversationDao.getConversationById(request.conversationId)
        if (conversation == null) {
            abort(SendUserMessageWithMediaResult.InvalidConversation("Conversation not found: ${request.conversationId}"))
        }
        if (conversation!!.deletedAt != null) {
            abort(SendUserMessageWithMediaResult.InvalidConversation("Conversation is deleted: ${request.conversationId}"))
        }

        validateRequestMedia(request)

        val existingState = classifyExistingState(request)
        if (existingState != null) {
            return when (existingState) {
                is SendUserMessageWithMediaResult.AlreadyCommitted -> {
                    ensureAssistantPlaceholder(request)
                    existingState
                }
                else -> abort(existingState)
            }
        }

        val mediaEntities = mediaDao.getByIds(request.orderedMediaIds)
        val mediaById = mediaEntities.associateBy { it.id }
        val alreadyAttachedIds = mutableListOf<String>()
        request.orderedMediaIds.forEach { mediaId ->
            val media = mediaById[mediaId]
            when {
                media == null -> abort(
                    SendUserMessageWithMediaResult.InvalidMedia("Media asset not found: $mediaId")
                )
                media.conversationId != request.conversationId -> abort(
                    SendUserMessageWithMediaResult.InvalidMedia("Media $mediaId does not belong to conversation ${request.conversationId}")
                )
                media.lifecycleState != MediaLifecycleState.READY.name -> abort(
                    SendUserMessageWithMediaResult.InvalidMedia("Media $mediaId is not READY (current=${media.lifecycleState})")
                )
                media.deletedAt != null -> abort(
                    SendUserMessageWithMediaResult.InvalidMedia("Media $mediaId is soft-deleted")
                )
                media.sourceMessageId != null -> alreadyAttachedIds.add(mediaId)
            }
        }
        if (alreadyAttachedIds.isNotEmpty()) {
            abort(SendUserMessageWithMediaResult.MediaAlreadyAttached(alreadyAttachedIds))
        }

        val userMessageEntity = buildUserMessageEntity(request)
        chatDao.insertMessageStrict(userMessageEntity)

        val affectedRows = mediaDao.attachReadyMediaToMessage(
            orderedMediaIds = request.orderedMediaIds,
            conversationId = request.conversationId,
            sourceMessageId = request.userMessageId,
            updatedAt = request.createdAt
        )
        if (affectedRows != request.orderedMediaIds.size) {
            abort(
                SendUserMessageWithMediaResult.Conflict(
                    "Media CAS affected $affectedRows rows, expected ${request.orderedMediaIds.size}"
                )
            )
        }

        val placeholderId = assistantPlaceholderId(request.userMessageId)
        val placeholderEntity = AiChatMessageEntity(
            id = placeholderId,
            conversationId = request.conversationId,
            role = ChatRole.Assistant.name,
            text = "",
            createdAt = request.createdAt + PLACEHOLDER_CREATED_AT_OFFSET_MS,
            relatedDraftId = null,
            messageType = ChatMessageType.Text.name,
            contentJson = null,
            assistantCardsJson = null,
            suggestedRepliesJson = null,
            updatedAt = request.createdAt,
            deletedAt = null
        )
        chatDao.insertMessageStrict(placeholderEntity)

        val preview = buildPreview(request)
        conversationDao.updateConversationSummary(
            id = request.conversationId,
            title = conversation.title.ifBlank { preview },
            lastMessagePreview = preview,
            lastActivityAt = request.createdAt,
            updatedAt = request.createdAt
        )

        val refreshedConversation = conversationDao.getConversationById(request.conversationId)
            ?: abort(SendUserMessageWithMediaResult.InvalidConversation("Conversation disappeared during transaction"))
        chatSyncQueueWriter.enqueueConversationUpsert(refreshedConversation, identity)
        chatSyncQueueWriter.enqueueMessageUpsert(userMessageEntity, identity)

        // Enqueue media upload for each freshly-bound asset. Reload post-attach so the
        // payload reflects the committed sourceMessageId. Only newly-attached READY assets
        // reach here, so this naturally excludes pre-feature/historical photos.
        val boundMedia = mediaDao.getByIds(request.orderedMediaIds)
        boundMedia.forEach { media ->
            mediaSyncQueueWriter.enqueueMediaUpsert(media, identity)
        }

        return SendUserMessageWithMediaResult.Committed(
            userMessageId = request.userMessageId,
            assistantPlaceholderId = placeholderId
        )
    }

    /**
     * Re-reads the database outside the aborted transaction and classifies the
     * existing state as either fully committed (idempotent success) or a conflict.
     */
    private suspend fun classifyExistingState(
        request: SendUserMessageWithMediaRequest
    ): SendUserMessageWithMediaResult? {
        val existingMessage = chatDao.getMessageById(request.userMessageId)
            ?: return null

        if (!isUserMessageConsistent(existingMessage, request)) {
            return SendUserMessageWithMediaResult.Conflict(
                "User message ${request.userMessageId} exists with different content"
            )
        }

        if (!areMediaBindingsConsistent(request)) {
            return SendUserMessageWithMediaResult.Conflict(
                "Media bindings for ${request.userMessageId} are inconsistent"
            )
        }

        val placeholderId = assistantPlaceholderId(request.userMessageId)
        val placeholder = chatDao.getMessageById(placeholderId)
        if (!isPlaceholderConsistent(placeholder, request)) {
            return SendUserMessageWithMediaResult.Conflict(
                "Assistant placeholder for ${request.userMessageId} is inconsistent"
            )
        }

        return SendUserMessageWithMediaResult.AlreadyCommitted
    }

    private fun isUserMessageConsistent(
        entity: AiChatMessageEntity,
        request: SendUserMessageWithMediaRequest
    ): Boolean {
        if (entity.conversationId != request.conversationId) return false
        if (entity.role != ChatRole.User.name) return false
        if (entity.messageType != ChatMessageType.Text.name) return false
        if (entity.deletedAt != null) return false
        if (entity.text != request.text) return false

        val domain = mapper.toDomain(entity)
        return domain.sourceMediaIds == request.orderedMediaIds
    }

    private suspend fun areMediaBindingsConsistent(
        request: SendUserMessageWithMediaRequest
    ): Boolean {
        val boundMedia = mediaDao.getByIds(request.orderedMediaIds)
        val boundById = boundMedia.associateBy { it.id }
        if (boundById.size != request.orderedMediaIds.size) return false

        request.orderedMediaIds.forEach { mediaId ->
            val media = boundById[mediaId] ?: return false
            if (media.conversationId != request.conversationId) return false
            if (media.sourceMessageId != request.userMessageId) return false
            if (media.deletedAt != null) return false
        }

        val allBoundToUser = mediaDao.getActiveByConversation(request.conversationId)
            .filter { it.sourceMessageId == request.userMessageId }
        if (allBoundToUser.size != request.orderedMediaIds.size) return false

        return true
    }

    private fun isPlaceholderConsistent(
        entity: AiChatMessageEntity?,
        request: SendUserMessageWithMediaRequest
    ): Boolean {
        if (entity == null) return false
        if (entity.conversationId != request.conversationId) return false
        if (entity.role != ChatRole.Assistant.name) return false
        if (entity.messageType != ChatMessageType.Text.name) return false
        if (entity.deletedAt != null) return false
        return true
    }

    private fun validateRequestMedia(request: SendUserMessageWithMediaRequest) {
        if (request.orderedMediaIds.isEmpty() || request.orderedMediaIds.size > MAX_MEDIA_COUNT) {
            abort(SendUserMessageWithMediaResult.InvalidMedia("Media count must be 1..$MAX_MEDIA_COUNT"))
        }
        if (request.orderedMediaIds.toSet().size != request.orderedMediaIds.size) {
            abort(SendUserMessageWithMediaResult.InvalidMedia("Duplicate media ids are not allowed"))
        }
    }

    private suspend fun ensureAssistantPlaceholder(request: SendUserMessageWithMediaRequest) {
        val placeholderId = assistantPlaceholderId(request.userMessageId)
        val existing = chatDao.getMessageById(placeholderId)
        if (existing == null) {
            chatDao.insertMessageStrict(
                AiChatMessageEntity(
                    id = placeholderId,
                    conversationId = request.conversationId,
                    role = ChatRole.Assistant.name,
                    text = "",
                    createdAt = request.createdAt + PLACEHOLDER_CREATED_AT_OFFSET_MS,
                    relatedDraftId = null,
                    messageType = ChatMessageType.Text.name,
                    contentJson = null,
                    assistantCardsJson = null,
                    suggestedRepliesJson = null,
                    updatedAt = request.createdAt,
                    deletedAt = null
                )
            )
        }
    }

    private fun buildUserMessageEntity(request: SendUserMessageWithMediaRequest): AiChatMessageEntity {
        val domain = AiChatMessage(
            id = request.userMessageId,
            conversationId = request.conversationId,
            role = ChatRole.User,
            text = request.text,
            createdAt = request.createdAt,
            sourceMediaIds = request.orderedMediaIds,
            updatedAt = request.createdAt
        )
        return mapper.toEntity(domain, request.conversationId)
    }

    private fun buildPreview(request: SendUserMessageWithMediaRequest): String {
        val textPreview = request.text.trim().replace(Regex("\\s+"), " ")
        return if (textPreview.isNotBlank()) {
            textPreview.take(PREVIEW_MAX_LENGTH).let {
                if (textPreview.length > PREVIEW_MAX_LENGTH) it.trimEnd() + "..." else it
            }
        } else {
            "发送了 ${request.orderedMediaIds.size} 张图片"
        }
    }

    private fun abort(result: SendUserMessageWithMediaResult): Nothing {
        throw TransactionAbortException(result)
    }

    private class TransactionAbortException(
        val result: SendUserMessageWithMediaResult
    ) : RuntimeException("Transaction aborted: $result")

    companion object {
        private const val MAX_MEDIA_COUNT = 6
        private const val PREVIEW_MAX_LENGTH = 32
        private const val PLACEHOLDER_CREATED_AT_OFFSET_MS = 1L

        fun assistantPlaceholderId(userMessageId: String): String =
            com.goings.dayzero.domain.model.ai.assistant.assistantPlaceholderId(userMessageId)
    }
}
