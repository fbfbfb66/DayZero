package com.example.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RemoteMealDto(
    val mealType: String,
    val displayName: String,
    val photoUri: String?,
    val foods: List<RemoteFoodDto>,
    val mealCalories: Int
)
