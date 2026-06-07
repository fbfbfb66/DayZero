package com.example.domain.model

data class MealEntry(
    val mealType: MealType,
    val hasPhoto: Boolean = false,
    val foods: List<FoodEntry> = emptyList()
) {
    val mealCalories: Int
        get() = foods.sumOf { it.estimatedCalories }
}
