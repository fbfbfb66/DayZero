package com.example.domain.repository

import com.example.domain.model.ai.Conversation
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    suspend fun insertConversation(conversation: Conversation)

    suspend fun getConversationById(id: String): Conversation?

    fun observeConversations(): Flow<List<Conversation>>

    fun observeConversationsByLastActivity(): Flow<List<Conversation>>

    suspend fun updateConversationSummary(
        id: String,
        title: String,
        lastMessagePreview: String,
        lastActivityAt: Long,
        updatedAt: Long = lastActivityAt
    )

    suspend fun softDeleteConversation(id: String, deletedAt: Long = System.currentTimeMillis())
}
