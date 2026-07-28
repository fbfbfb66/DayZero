package com.goings.dayzero.data.repository

import com.goings.dayzero.data.remote.api.AiDraftApiService
import com.goings.dayzero.data.remote.mapper.AiDraftRemoteMapper
import com.goings.dayzero.domain.model.DailyRecord
import com.goings.dayzero.domain.repository.AiSummaryRepository

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
