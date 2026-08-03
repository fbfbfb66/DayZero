package com.goings.dayzero.domain.model

import java.util.UUID

data class MealEntry(
    val id: String = UUID.randomUUID().toString(),
    val mealType: MealType,
    /** Ordered media ownership for this meal.  This is the sole photo state. */
    val mediaIds: List<String> = emptyList(),
    val foods: List<FoodEntry> = emptyList()
) {
    /** Compatibility read-only view; never serialized or independently written. */
    val hasPhoto: Boolean
        get() = mediaIds.isNotEmpty()

    val mealCalories: Int
        get() = foods.sumOf { it.estimatedCalories }
}
