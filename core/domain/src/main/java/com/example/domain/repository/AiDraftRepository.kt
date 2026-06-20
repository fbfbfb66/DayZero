package com.example.domain.repository

import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.AiDraftRequest
import com.example.domain.model.ai.CheckinDraft
import kotlinx.coroutines.flow.Flow

interface AiDraftRepository {
    suspend fun generateDraft(request: AiDraftRequest): CheckinDraft
    
    fun observeChatMessages(): Flow<List<AiChatMessage>>
    
    suspend fun insertChatMessage(message: AiChatMessage)

    suspend fun updateChatMessage(message: AiChatMessage)
    
    suspend fun clearChatMessages()
}
