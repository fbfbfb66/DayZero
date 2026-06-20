package com.example.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RemoteMealDto(
    val mealType: String? = null,
    val displayName: String? = null,
    val photoUri: String? = null,
    val foods: List<RemoteFoodDto>? = emptyList(),
    val mealCalories: Int? = null
)
