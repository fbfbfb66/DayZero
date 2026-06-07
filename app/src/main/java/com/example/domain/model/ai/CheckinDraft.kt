package com.example.domain.model.ai

import java.time.LocalDate
import java.util.UUID

data class CheckinDraft(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,
    val meals: List<DraftMeal>,
    val totalCalories: Int,
    val weightKg: Float? = null,
    val aiSummary: String,
    val sourceText: String? = null
)
