package com.example.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RemoteFoodDto(
    val id: String,
    val name: String,
    val quantity: String,
    val estimatedCalories: Int,
    val confidence: String?
)
