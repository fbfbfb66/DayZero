package com.goings.dayzero.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AiDraftRequestDto(
    val date: String,
    val text: String,
    val weightKg: Double?,
    val context: String? = null
)
