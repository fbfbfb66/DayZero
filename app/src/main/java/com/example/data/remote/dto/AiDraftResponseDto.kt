package com.example.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AiDraftResponseDto(
    val id: String,
    val date: String,
    val meals: List<RemoteMealDto>,
    val totalCalories: Int,
    val weightKg: Double?,
    val aiSummary: String,
    val sourceText: String?
)
