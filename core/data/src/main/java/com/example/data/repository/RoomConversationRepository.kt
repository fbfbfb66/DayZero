package com.example.data.repository

import com.example.data.local.dao.ConversationDao
import com.example.data.local.mapper.ConversationEntityMapper
import com.example.domain.model.ai.Conversation
import com.example.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomConversationRepository(
    private val conversationDao: ConversationDao
) : ConversationRepository {
    private val mapper = ConversationEntityMapper()

    override suspend fun insertConversation(conversation: Conversation) {
        conversationDao.insertConversation(mapper.toEntity(conversation))
    }

    override suspend fun getConversationById(id: String): Conversation? {
        return conversationDao.getConversationById(id)?.let(mapper::toDomain)
    }

    override fun observeConversations(): Flow<List<Conversation>> {
        return conversationDao.observeConversations().map { entities -> entities.map(mapper::toDomain) }
    }

    override fun observeConversationsByLastActivity(): Flow<List<Conversation>> {
        return conversationDao.observeConversationsByLastActivity().map { entities -> entities.map(mapper::toDomain) }
    }

    override suspend fun updateConversationSummary(
        id: String,
        title: String,
        lastMessagePreview: String,
        lastActivityAt: Long,
        updatedAt: Long
    ) {
        conversationDao.updateConversationSummary(
            id = id,
            title = title,
            lastMessagePreview = lastMessagePreview,
            lastActivityAt = lastActivityAt,
            updatedAt = updatedAt
        )
    }

    override suspend fun softDeleteConversation(id: String, deletedAt: Long) {
        conversationDao.softDeleteConversation(id, deletedAt)
    }
}
