package com.example.domain.model

import java.util.UUID

data class MealEntry(
    val id: String = UUID.randomUUID().toString(),
    val mealType: MealType,
    val hasPhoto: Boolean = false,
    val foods: List<FoodEntry> = emptyList()
) {
    val mealCalories: Int
        get() = foods.sumOf { it.estimatedCalories }
}
