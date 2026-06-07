package com.example.data.repository

import com.example.data.remote.api.AiDraftApiService
import com.example.data.remote.mapper.AiDraftRemoteMapper
import com.example.domain.model.ai.AiDraftRequest
import com.example.domain.model.ai.CheckinDraft
import com.example.domain.repository.AiDraftRepository

class RemoteAiDraftRepository(
    private val apiService: AiDraftApiService
) : AiDraftRepository {

    private val mapper = AiDraftRemoteMapper()

    override suspend fun generateDraft(request: AiDraftRequest): CheckinDraft {
        val requestDto = mapper.toRequestDto(request)
        val responseDto = apiService.generateDraft(requestDto)
        return mapper.toDomain(responseDto)
    }
}
