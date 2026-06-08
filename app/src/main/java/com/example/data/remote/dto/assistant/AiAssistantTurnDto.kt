package com.example.data.remote.dto.assistant

import com.example.data.remote.dto.AiDraftResponseDto
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AiAssistantTurnDto(
    val id: String,
    val intent: String,
    val replyText: String,
    val cards: List<AiChatCardDto> = emptyList(),
    val suggestedReplies: List<String> = emptyList(),
    val createdAt: Long
)

@JsonClass(generateAdapter = true)
data class AiChatCardDto(
    val type: String,
    val id: String,
    // DraftCard
    val draft: AiDraftResponseDto? = null,
    // ChoiceCard
    val title: String? = null,
    val message: String? = null,
    val options: List<AiChoiceOptionDto>? = null,
    val relatedDraftId: String? = null,
    val resolved: Boolean? = null,
    val originalText: String? = null,
    // SummaryCard
    val totalCalories: Int? = null,
    val recordedMeals: List<String>? = null,
    val summary: String? = null,
    // WeightCard
    val date: String? = null,
    val weightKg: Double? = null,
    // Edit/Delete Confirm
    val targetRecordId: String? = null,
    val targetMealType: String? = null,
    val targetFoodId: String? = null,
    val targetFoodName: String? = null,
    val newQuantity: String? = null,
    val newEstimatedCalories: Int? = null,
    val newMealType: String? = null,
    val operationDescription: String? = null
)

@JsonClass(generateAdapter = true)
data class AiChoiceOptionDto(
    val id: String,
    val label: String,
    val action: String? = null
)
