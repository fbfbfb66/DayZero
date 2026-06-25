package com.example.domain.model

import java.util.UUID

data class FoodEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val quantity: String,
    val estimatedCalories: Int,
    val confidence: String = "high",
    val carbohydratesG: Float? = null,
    val proteinG: Float? = null,
    val fatG: Float? = null,
    val fiberG: Float? = null
)
