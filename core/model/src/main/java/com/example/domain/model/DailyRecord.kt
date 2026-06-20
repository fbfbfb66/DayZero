package com.example.domain.model

import java.time.LocalDate
import java.util.UUID

data class DailyRecord(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,
    val status: RecordStatus,
    val meals: List<MealEntry>,
    val weightKg: Float? = null,
    val aiSummary: String = ""
) {
    val totalCalories: Int
        get() = meals.sumOf { it.mealCalories }
}
