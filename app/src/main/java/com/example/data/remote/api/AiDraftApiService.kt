package com.example.data.remote.api

import com.example.data.remote.dto.AiDraftRequestDto
import com.example.data.remote.dto.AiDraftResponseDto
import com.example.data.remote.dto.AiSummaryRequestDto
import com.example.data.remote.dto.AiSummaryResponseDto
import retrofit2.http.Body
import retrofit2.http.POST

interface AiDraftApiService {
    @POST("functions/v1/generate-checkin-draft")
    suspend fun generateDraft(@Body request: AiDraftRequestDto): AiDraftResponseDto

    @POST("functions/v1/generate-daily-summary")
    suspend fun generateDailySummary(@Body request: AiSummaryRequestDto): AiSummaryResponseDto
}
