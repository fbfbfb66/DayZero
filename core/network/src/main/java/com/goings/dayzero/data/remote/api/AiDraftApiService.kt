package com.goings.dayzero.data.remote.api

import com.goings.dayzero.data.remote.dto.AiDraftRequestDto
import com.goings.dayzero.data.remote.dto.AiDraftResponseDto
import com.goings.dayzero.data.remote.dto.AiSummaryRequestDto
import com.goings.dayzero.data.remote.dto.AiSummaryResponseDto
import com.goings.dayzero.data.remote.dto.IntentClassifierRequestDto
import com.goings.dayzero.data.remote.dto.IntentClassificationResultDto
import com.goings.dayzero.data.remote.dto.assistant.AiAssistantRequestDto
import com.goings.dayzero.data.remote.dto.assistant.AssistantTurnV2ResponseDto
import retrofit2.http.Body
import retrofit2.http.POST

interface AiDraftApiService {
    @POST("functions/v1/generate-checkin-draft")
    suspend fun generateDraft(@Body request: AiDraftRequestDto): AiDraftResponseDto

    @POST("functions/v1/generate-daily-summary")
    suspend fun generateDailySummary(@Body request: AiSummaryRequestDto): AiSummaryResponseDto

    @POST("functions/v1/assistant-turn-v2")
    suspend fun sendAssistantTurnV2WithResponse(@Body request: AiAssistantRequestDto): retrofit2.Response<AssistantTurnV2ResponseDto>

    @POST("functions/v1/classify-user-intent")
    suspend fun classifyUserIntent(@Body request: IntentClassifierRequestDto): IntentClassificationResultDto
}
