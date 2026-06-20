package com.example.data.repository

import com.example.data.remote.api.AiDraftApiService
import com.example.data.remote.mapper.AiDraftRemoteMapper
import com.example.domain.model.DailyRecord
import com.example.domain.repository.AiSummaryRepository

class RemoteAiSummaryRepository(
    private val apiService: AiDraftApiService
) : AiSummaryRepository {

    private val mapper = AiDraftRemoteMapper()

    override suspend fun generateDailySummary(record: DailyRecord): String {
        val request = mapper.toSummaryRequestDto(record)
        val response = apiService.generateDailySummary(request)
        return response.summary
    }
}
