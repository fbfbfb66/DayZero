package com.goings.dayzero.domain.repository

import com.goings.dayzero.domain.model.ai.AiChatMessage
import com.goings.dayzero.domain.model.ai.AiDraftRequest
import com.goings.dayzero.domain.model.ai.CheckinDraft
import kotlinx.coroutines.flow.Flow

interface AiDraftRepository {
    suspend fun generateDraft(request: AiDraftRequest): CheckinDraft
    
    // Compatibility path for the current single-stream chat UI until the history UI switches to conversation routes.
    fun observeChatMessages(): Flow<List<AiChatMessage>>

    fun observeChatMessages(conversationId: String): Flow<List<AiChatMessage>>

    suspend fun createConversationWithFirstMessage(text: String, now: Long = System.currentTimeMillis()): String?

    /**
     * Ensures a local-only draft conversation row exists so that media attachments can be
     * staged against it before the first message is sent (home-screen photo draft).
     * No-op when the conversation already exists. Returns true when a draft row was inserted.
     * The default no-op keeps in-memory test fakes unchanged.
     */
    suspend fun ensureDraftConversation(conversationId: String, now: Long = System.currentTimeMillis()): Boolean = false

    suspend fun getRecentChatMessages(conversationId: String, limit: Int): List<AiChatMessage>
    
    suspend fun findMessageByAssistantCardId(cardId: String): AiChatMessage?

    suspend fun getChatMessageById(messageId: String): AiChatMessage?

    suspend fun insertChatMessage(message: AiChatMessage)

    suspend fun insertChatMessage(conversationId: String, message: AiChatMessage)

    suspend fun updateChatMessage(message: AiChatMessage)
    
    suspend fun clearChatMessages()

    fun updateStreamingState(conversationId: String, messageId: String, text: String, isStreaming: Boolean)
    
    fun clearStreamingState(conversationId: String)
}
