package com.example.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AiDraftResponseDto(
    val id: String? = null,
    val date: String? = null,
    val meals: List<RemoteMealDto>? = emptyList(),
    val totalCalories: Int? = null,
    val weightKg: Double? = null,
    val aiSummary: String? = null,
    val sourceText: String? = null
)
