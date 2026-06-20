package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.dao.ConversationDao
import com.example.data.local.dao.SyncQueueDao
import com.example.data.local.database.DayZeroDatabase
import com.example.data.local.mapper.ConversationEntityMapper
import com.example.data.identity.StaticLocalIdentityProvider
import com.example.data.sync.chat.ChatSyncQueueWriter
import com.example.domain.identity.CurrentIdentityProvider
import com.example.domain.model.ai.Conversation
import com.example.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomConversationRepository(
    private val conversationDao: ConversationDao,
    private val database: DayZeroDatabase? = null,
    syncQueueDao: SyncQueueDao? = null,
    private val identityProvider: CurrentIdentityProvider = StaticLocalIdentityProvider()
) : ConversationRepository {
    private val mapper = ConversationEntityMapper()
    private val chatSyncQueueWriter = syncQueueDao?.let { ChatSyncQueueWriter(it) }

    override suspend fun insertConversation(conversation: Conversation) {
        val entity = mapper.toEntity(conversation)
        val writer = chatSyncQueueWriter
        if (database != null && writer != null) {
            val identity = identityProvider.currentIdentity()
            database.withTransaction {
                conversationDao.insertConversation(entity)
                writer.enqueueConversationUpsert(entity, identity)
            }
        } else {
            conversationDao.insertConversation(entity)
        }
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
        val writer = chatSyncQueueWriter
        if (database != null && writer != null) {
            val identity = identityProvider.currentIdentity()
            database.withTransaction {
                updateSummary(id, title, lastMessagePreview, lastActivityAt, updatedAt)
                conversationDao.getConversationById(id)?.let { writer.enqueueConversationUpsert(it, identity) }
            }
        } else {
            updateSummary(id, title, lastMessagePreview, lastActivityAt, updatedAt)
        }
    }

    override suspend fun softDeleteConversation(id: String, deletedAt: Long) {
        val writer = chatSyncQueueWriter
        if (database != null && writer != null) {
            val identity = identityProvider.currentIdentity()
            database.withTransaction {
                conversationDao.softDeleteConversation(id, deletedAt)
                conversationDao.getConversationById(id)?.let { writer.enqueueConversationUpsert(it, identity) }
            }
        } else {
            conversationDao.softDeleteConversation(id, deletedAt)
        }
    }

    private suspend fun updateSummary(
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
}
