package com.example.domain.repository

import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.AiDraftRequest
import com.example.domain.model.ai.CheckinDraft
import kotlinx.coroutines.flow.Flow

interface AiDraftRepository {
    suspend fun generateDraft(request: AiDraftRequest): CheckinDraft
    
    // Compatibility path for the current single-stream chat UI until the history UI switches to conversation routes.
    fun observeChatMessages(): Flow<List<AiChatMessage>>

    fun observeChatMessages(conversationId: String): Flow<List<AiChatMessage>>

    suspend fun createConversationWithFirstMessage(text: String, now: Long = System.currentTimeMillis()): String?

    suspend fun getRecentChatMessages(conversationId: String, limit: Int): List<AiChatMessage>
    
    suspend fun findMessageByAssistantCardId(cardId: String): AiChatMessage?
    
    suspend fun insertChatMessage(message: AiChatMessage)

    suspend fun insertChatMessage(conversationId: String, message: AiChatMessage)

    suspend fun updateChatMessage(message: AiChatMessage)
    
    suspend fun clearChatMessages()
}
