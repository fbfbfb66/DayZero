package com.example.data.remote.api

import com.example.data.remote.dto.AiDraftRequestDto
import com.example.data.remote.dto.AiDraftResponseDto
import com.example.data.remote.dto.AiSummaryRequestDto
import com.example.data.remote.dto.AiSummaryResponseDto
import com.example.data.remote.dto.IntentClassifierRequestDto
import com.example.data.remote.dto.IntentClassificationResultDto
import com.example.data.remote.dto.assistant.AiAssistantRequestDto
import com.example.data.remote.dto.assistant.AiAssistantTurnDto
import com.example.data.remote.dto.assistant.AssistantTurnV2ResponseDto
import retrofit2.http.Body
import retrofit2.http.POST

interface AiDraftApiService {
    @POST("functions/v1/generate-checkin-draft")
    suspend fun generateDraft(@Body request: AiDraftRequestDto): AiDraftResponseDto

    @POST("functions/v1/generate-daily-summary")
    suspend fun generateDailySummary(@Body request: AiSummaryRequestDto): AiSummaryResponseDto

    @POST("functions/v1/ai-assistant-turn")
    suspend fun sendMessage(@Body request: AiAssistantRequestDto): AiAssistantTurnDto

    @POST("functions/v1/ai-assistant-turn")
    suspend fun sendMessageWithResponse(@Body request: AiAssistantRequestDto): retrofit2.Response<AiAssistantTurnDto>

    @POST("functions/v1/assistant-turn-v2")
    suspend fun sendAssistantTurnV2WithResponse(@Body request: AiAssistantRequestDto): retrofit2.Response<AssistantTurnV2ResponseDto>

    @POST("functions/v1/classify-user-intent")
    suspend fun classifyUserIntent(@Body request: IntentClassifierRequestDto): IntentClassificationResultDto
}
