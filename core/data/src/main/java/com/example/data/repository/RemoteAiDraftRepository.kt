package com.example.data.repository

import com.example.data.local.dao.AiChatMessageDao
import com.example.data.local.dao.ConversationDao
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.mapper.AiChatMessageMapper
import com.example.data.remote.api.AiDraftApiService
import com.example.data.remote.mapper.AiDraftRemoteMapper
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.AiDraftRequest
import com.example.domain.model.ai.CheckinDraft
import com.example.domain.model.ai.ChatRole
import com.example.domain.repository.AiDraftRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class RemoteAiDraftRepository(
    private val apiService: AiDraftApiService,
    private val chatDao: AiChatMessageDao,
    private val conversationDao: ConversationDao
) : AiDraftRepository {

    private val mapper = AiDraftRemoteMapper()
    private val chatMapper = AiChatMessageMapper()

    override suspend fun generateDraft(request: AiDraftRequest): CheckinDraft {
        val requestDto = mapper.toRequestDto(request)
        val responseDto = apiService.generateDraft(requestDto)
        return mapper.toDomain(responseDto)
    }

    override fun observeChatMessages(): Flow<List<AiChatMessage>> {
        return chatDao.observeAllMessages().map { entities ->
            entities.map { chatMapper.toDomain(it) }
        }
    }

    override fun observeChatMessages(conversationId: String): Flow<List<AiChatMessage>> {
        return chatDao.observeMessagesByConversationId(conversationId).map { entities ->
            entities.map { chatMapper.toDomain(it) }
        }
    }

    override suspend fun insertChatMessage(message: AiChatMessage) {
        val conversationId = message.conversationId ?: ensureCurrentConversation(message).id
        chatDao.insertMessage(chatMapper.toEntity(message, conversationId))
        refreshConversationSummary(conversationId, message)
    }

    override suspend fun updateChatMessage(message: AiChatMessage) {
        val conversationId = message.conversationId
            ?: chatDao.getMessageById(message.id)?.conversationId
            ?: ensureCurrentConversation(message).id
        chatDao.updateMessage(chatMapper.toEntity(message, conversationId))
        refreshConversationSummary(conversationId, message)
    }

    override suspend fun clearChatMessages() {
        chatDao.deleteAllMessages()
        conversationDao.deleteAllConversations()
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
        val preview = message.previewText()
        val conversation = ConversationEntity(
            id = UUID.randomUUID().toString(),
            conversationDate = date,
            title = title.ifBlank { neutralTitle(date) },
            lastMessagePreview = preview,
            createdAt = message.createdAt,
            updatedAt = message.createdAt,
            lastActivityAt = message.createdAt,
            deletedAt = null
        )
        conversationDao.insertConversation(conversation)
        return conversation
    }

    private suspend fun refreshConversationSummary(conversationId: String, message: AiChatMessage) {
        val conversation = conversationDao.getConversationById(conversationId) ?: return
        val title = if (conversation.title.isBlank() && message.role == ChatRole.User) {
            message.text.normalizedPreviewText().limitPreview().ifBlank { neutralTitle(conversation.conversationDate) }
        } else {
            conversation.title
        }
        conversationDao.updateConversationSummary(
            id = conversationId,
            title = title,
            lastMessagePreview = message.previewText(),
            lastActivityAt = message.createdAt,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun AiChatMessage.previewText(): String {
        return text.normalizedPreviewText().limitPreview().ifBlank {
            if (assistantCards.isNotEmpty() || choiceCard != null) "这条对话包含一张记录卡片" else ""
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
