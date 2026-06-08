package com.example.data.remote.dto.assistant

import com.example.data.remote.dto.RemoteMealDto
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AiAssistantRequestDto(
    val date: String,
    val userText: String,
    val todayRecord: SimpleRecordDto? = null,
    val pendingDraft: SimpleRecordDto? = null,
    val recentMessages: List<SimpleChatMessageDto> = emptyList(),
    val turnType: String? = null, // "user_message" or "interaction_result"
    val interactionResult: InteractionResultDto? = null,
    val primaryIntent: String? = null,
    val speechAct: String? = null,
    val consumptionStatus: String? = null,
    val shouldCreateDraft: Boolean? = null,
    val shouldAskMealTime: Boolean? = null,
    val extractedFoodText: String? = null
)

@JsonClass(generateAdapter = true)
data class SimpleRecordDto(
    val id: String,
    val date: String,
    val meals: List<RemoteMealDto>,
    val totalCalories: Int,
    val weightKg: Double? = null,
    val aiSummary: String? = null
)

@JsonClass(generateAdapter = true)
data class SimpleChatMessageDto(
    val role: String,
    val text: String
)

@JsonClass(generateAdapter = true)
data class InteractionResultDto(
    val interactionId: String,
    val actionType: String,
    val selectedOptionId: String,
    val selectedOptionLabel: String,
    val field: String? = null,
    val originalText: String? = null,
    val confirmType: String? = null,
    val payloadSummary: PayloadSummaryDto? = null
)

@JsonClass(generateAdapter = true)
data class PayloadSummaryDto(
    val originalText: String? = null,
    val mealType: String? = null,
    val items: List<ConfirmCardItemDto>? = null
)
