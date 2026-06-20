package com.example.domain.model.ai

import java.util.UUID

data class DraftFood(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val quantity: String,
    val estimatedCalories: Int,
    val confidence: String? = "high"
)
