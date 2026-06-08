package com.example.data.remote.dto.assistant

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AssistantActionDto(
    val type: String? = null,
    val interactionId: String? = null,
    val payload: AssistantActionPayloadDto? = null
)

@JsonClass(generateAdapter = true)
data class AssistantActionPayloadDto(
    val title: String? = null,
    val message: String? = null,
    val field: String? = null,
    val originalText: String? = null,
    val options: List<AssistantActionOptionDto>? = null,
    val confirmType: String? = null,
    val mealType: String? = null,
    val items: List<AssistantActionItemDto>? = null,
    val buttons: List<AssistantActionOptionDto>? = null
)

@JsonClass(generateAdapter = true)
data class AssistantActionItemDto(
    val name: String? = null,
    val amountText: String? = null,
    val calories: Int? = null,
    val calorieConfidence: String? = null
)

@JsonClass(generateAdapter = true)
data class AssistantActionOptionDto(
    val id: String? = null,
    val label: String? = null
)
