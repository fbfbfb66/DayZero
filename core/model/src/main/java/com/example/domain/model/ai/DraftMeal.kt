package com.example.domain.model.ai

import com.example.domain.model.MealType

data class DraftMeal(
    val mealType: MealType,
    val displayName: String,
    val photoUri: String? = null,
    val foods: List<DraftFood> = emptyList(),
    val mealCalories: Int
)
