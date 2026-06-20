package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.database.DayZeroDatabase
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.mapper.AiChatMessageMapper
import com.example.data.local.dao.SyncQueueDao
import com.example.data.identity.StaticLocalIdentityProvider
import com.example.data.remote.api.AiDraftApiService
import com.example.data.remote.mapper.AiDraftRemoteMapper
import com.example.data.sync.chat.ChatSyncQueueWriter
import com.example.domain.identity.CurrentIdentityProvider
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.AiDraftRequest
import com.example.domain.model.ai.ChatRole
import com.example.domain.model.ai.CheckinDraft
import com.example.domain.model.ai.assistant.DateMismatchGuardCardPayload
import com.example.domain.repository.AiDraftRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class RemoteAiDraftRepository(
    private val apiService: AiDraftApiService,
    private val database: DayZeroDatabase,
    syncQueueDao: SyncQueueDao? = null,
    private val identityProvider: CurrentIdentityProvider = StaticLocalIdentityProvider()
) : AiDraftRepository {

    private val mapper = AiDraftRemoteMapper()
    private val chatMapper = AiChatMessageMapper()
    private val chatDao = database.aiChatMessageDao()
    private val conversationDao = database.conversationDao()
    private val chatSyncQueueWriter = syncQueueDao?.let { ChatSyncQueueWriter(it) }

    override suspend fun generateDraft(request: AiDraftRequest): CheckinDraft {
        val requestDto = mapper.toRequestDto(request)
        val responseDto = apiService.generateDraft(requestDto)
        return mapper.toDomain(responseDto)
    }

    private val streamingStates = kotlinx.coroutines.flow.MutableStateFlow<Map<String, StreamingState>>(emptyMap())

    data class StreamingState(
        val conversationId: String,
        val messageId: String,
        val text: String,
        val isStreaming: Boolean
    )

    override fun updateStreamingState(conversationId: String, messageId: String, text: String, isStreaming: Boolean) {
        streamingStates.value = streamingStates.value + (conversationId to StreamingState(conversationId, messageId, text, isStreaming))
    }

    override fun clearStreamingState(conversationId: String) {
        streamingStates.value = streamingStates.value - conversationId
    }

    override fun observeChatMessages(): Flow<List<AiChatMessage>> {
        return kotlinx.coroutines.flow.combine(chatDao.observeAllMessages(), streamingStates) { entities, states ->
            entities.map { chatMapper.toDomain(it) }.map { msg ->
                val state = states[msg.conversationId]
                if (state != null && msg.id == state.messageId) {
                    msg.copy(text = state.text)
                } else {
                    msg
                }
            }
        }
    }

    override fun observeChatMessages(conversationId: String): Flow<List<AiChatMessage>> {
        return kotlinx.coroutines.flow.combine(chatDao.observeMessagesByConversationId(conversationId), streamingStates) { entities, states ->
            val state = states[conversationId]
            entities.map { chatMapper.toDomain(it) }.map { msg ->
                if (state != null && msg.id == state.messageId) {
                    msg.copy(text = state.text)
                } else {
                    msg
                }
            }
        }
    }

    override suspend fun createConversationWithFirstMessage(text: String, now: Long): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null

        val conversationId = UUID.randomUUID().toString()
        val date = Instant.ofEpochMilli(now)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
        val firstMessage = AiChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = ChatRole.User,
            text = trimmed,
            createdAt = now
        )
        val conversation = ConversationEntity(
            id = conversationId,
            conversationDate = date,
            title = trimmed.normalizedPreviewText().limitPreview().ifBlank { neutralTitle(date) },
            lastMessagePreview = firstMessage.previewText(),
            createdAt = now,
            updatedAt = now,
            lastActivityAt = now,
            deletedAt = null
        )

        val identity = identityProvider.currentIdentity()
        database.withTransaction {
            conversationDao.insertConversation(conversation)
            val firstMessageEntity = chatMapper.toEntity(firstMessage, conversationId)
            chatDao.insertMessage(firstMessageEntity)
            chatSyncQueueWriter?.enqueueConversationUpsert(conversation, identity)
            chatSyncQueueWriter?.enqueueMessageUpsert(firstMessageEntity, identity, updatedAtMillis = now)
        }
        return conversationId
    }

    override suspend fun getRecentChatMessages(conversationId: String, limit: Int): List<AiChatMessage> {
        return chatDao.getRecentMessagesByConversationId(conversationId, limit)
            .asReversed()
            .map { chatMapper.toDomain(it) }
    }

    override suspend fun findMessageByAssistantCardId(cardId: String): AiChatMessage? {
        return chatDao.getMessagesWithCards()
            .firstNotNullOfOrNull { entity ->
                val message = chatMapper.toDomain(entity)
                message.takeIf {
                    it.assistantCards.any { card ->
                        card.id == cardId ||
                            (card is DateMismatchGuardCardPayload && card.pendingOriginalCard.id == cardId)
                    }
                }
            }
    }

    override suspend fun insertChatMessage(message: AiChatMessage) {
        val conversationId = message.conversationId ?: ensureCurrentConversation(message).id
        insertChatMessage(conversationId, message)
    }

    override suspend fun insertChatMessage(conversationId: String, message: AiChatMessage) {
        val messageWithConversation = message.copy(conversationId = conversationId)
        val messageEntity = chatMapper.toEntity(messageWithConversation, conversationId)
        val identity = identityProvider.currentIdentity()
        val updatedAt = System.currentTimeMillis()
        database.withTransaction {
            chatDao.insertMessage(messageEntity)
            refreshConversationSummaryInTransaction(conversationId, messageWithConversation)
            conversationDao.getConversationById(conversationId)?.let { conversation ->
                chatSyncQueueWriter?.enqueueConversationUpsert(conversation, identity)
            }
            chatSyncQueueWriter?.enqueueMessageUpsert(messageEntity, identity, updatedAtMillis = updatedAt)
        }
    }

    override suspend fun updateChatMessage(message: AiChatMessage) {
        val conversationId = message.conversationId
            ?: chatDao.getMessageById(message.id)?.conversationId
            ?: ensureCurrentConversation(message).id
        val messageWithConversation = message.copy(conversationId = conversationId)
        val messageEntity = chatMapper.toEntity(messageWithConversation, conversationId)
        val identity = identityProvider.currentIdentity()
        val updatedAt = System.currentTimeMillis()
        database.withTransaction {
            chatDao.updateMessage(messageEntity)
            refreshConversationSummaryInTransaction(conversationId, messageWithConversation)
            conversationDao.getConversationById(conversationId)?.let { conversation ->
                chatSyncQueueWriter?.enqueueConversationUpsert(conversation, identity)
            }
            chatSyncQueueWriter?.enqueueMessageUpsert(messageEntity, identity, updatedAtMillis = updatedAt)
        }
    }

    override suspend fun clearChatMessages() {
        database.withTransaction {
            chatDao.deleteAllMessages()
            conversationDao.deleteAllConversations()
        }
    }

    private suspend fun ensureCurrentConversation(message: AiChatMessage): ConversationEntity {
        val latest = conversationDao.getLatestActiveConversation()
        if (latest != null) return latest

        val date = Instant.ofEpochMilli(message.createdAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
        val title = if (message.role == ChatRole.User) {
            message.text.normalizedPreviewText().limitPreview()
        } else {
            neutralTitle(date)
        }
        val conversation = ConversationEntity(
            id = UUID.randomUUID().toString(),
            conversationDate = date,
            title = title.ifBlank { neutralTitle(date) },
            lastMessagePreview = message.previewText(),
            createdAt = message.createdAt,
            updatedAt = message.createdAt,
            lastActivityAt = message.createdAt,
            deletedAt = null
        )
        conversationDao.insertConversation(conversation)
        return conversation
    }

    private suspend fun refreshConversationSummaryInTransaction(conversationId: String, message: AiChatMessage) {
        val conversation = conversationDao.getConversationById(conversationId) ?: return
        val title = conversation.title.ifBlank {
            if (message.role == ChatRole.User) {
                message.text.normalizedPreviewText().limitPreview().ifBlank { neutralTitle(conversation.conversationDate) }
            } else {
                neutralTitle(conversation.conversationDate)
            }
        }
        val preview = message.previewText().ifBlank { conversation.lastMessagePreview }
        conversationDao.updateConversationSummary(
            id = conversationId,
            title = title,
            lastMessagePreview = preview,
            lastActivityAt = message.createdAt,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun AiChatMessage.previewText(): String {
        return text.normalizedPreviewText().limitPreview().ifBlank {
            if (assistantCards.isNotEmpty() || choiceCard != null) {
                "Card message"
            } else {
                ""
            }
        }
    }

    private fun String.normalizedPreviewText(): String {
        return trim().replace(Regex("\\s+"), " ")
    }

    private fun String.limitPreview(maxLength: Int = 32): String {
        return if (length <= maxLength) this else take(maxLength).trimEnd() + "..."
    }

    private fun neutralTitle(date: String): String {
        val localDate = java.time.LocalDate.parse(date)
        return DateTimeFormatter.ofPattern("M月d日的对话").format(localDate)
    }
}
