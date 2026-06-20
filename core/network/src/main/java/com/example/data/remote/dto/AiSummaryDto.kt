package com.example.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AiSummaryRequestDto(
    val meals: List<RemoteMealDto>,
    val totalCalories: Int,
    val weightKg: Double?
)

@JsonClass(generateAdapter = true)
data class AiSummaryResponseDto(
    val summary: String
)
