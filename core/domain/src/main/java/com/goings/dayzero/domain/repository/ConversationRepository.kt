package com.goings.dayzero.domain.repository

import com.goings.dayzero.domain.model.ai.Conversation
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    suspend fun insertConversation(conversation: Conversation)

    suspend fun getConversationById(id: String): Conversation?

    fun observeConversations(): Flow<List<Conversation>>

    fun observeConversationsByLastActivity(): Flow<List<Conversation>>

    /**
     * Conversations that contain at least one visible message. The history list uses this
     * so local-only draft conversations created for home-screen photo drafts stay hidden
     * until their first message is committed. Defaults to the unfiltered stream so
     * in-memory test fakes keep working.
     */
    fun observeNonEmptyConversationsByLastActivity(): Flow<List<Conversation>> =
        observeConversationsByLastActivity()

    /**
     * Ids of conversations whose AI-generated title is still in flight (submit pending or
     * recently submitted, waiting for the generated title to flow back). Defaults to an
     * empty set so in-memory test fakes keep working.
     */
    fun observePendingAiTitleConversationIds(): Flow<Set<String>> =
        kotlinx.coroutines.flow.flowOf(emptySet())

    suspend fun updateConversationSummary(
        id: String,
        title: String,
        lastMessagePreview: String,
        lastActivityAt: Long,
        updatedAt: Long = lastActivityAt
    )

    suspend fun softDeleteConversation(id: String, deletedAt: Long = System.currentTimeMillis())
}
