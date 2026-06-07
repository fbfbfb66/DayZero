package com.example.data.repository

import com.example.data.local.dao.AiChatMessageDao
import com.example.data.local.mapper.AiChatMessageMapper
import com.example.data.remote.api.AiDraftApiService
import com.example.data.remote.mapper.AiDraftRemoteMapper
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.AiDraftRequest
import com.example.domain.model.ai.CheckinDraft
import com.example.domain.repository.AiDraftRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RemoteAiDraftRepository(
    private val apiService: AiDraftApiService,
    private val chatDao: AiChatMessageDao
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

    override suspend fun insertChatMessage(message: AiChatMessage) {
        chatDao.insertMessage(chatMapper.toEntity(message))
    }

    override suspend fun clearChatMessages() {
        chatDao.deleteAllMessages()
    }
}
