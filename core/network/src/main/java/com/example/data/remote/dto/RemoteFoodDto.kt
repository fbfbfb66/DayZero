package com.example.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RemoteFoodDto(
    val id: String? = null,
    val name: String? = null,
    val quantity: String? = null,
    val estimatedCalories: Int? = null,
    val confidence: String? = null
)
