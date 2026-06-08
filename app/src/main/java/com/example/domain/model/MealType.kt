package com.example.domain.model

import com.example.domain.model.ai.DraftMeal

enum class MealType(val displayName: String) {
    Breakfast("早餐"),
    Lunch("午餐"),
    Dinner("晚餐"),
    Snack("加餐");

    fun sortOrder(): Int {
        return when (this) {
            Breakfast -> 0
            Lunch -> 1
            Dinner -> 2
            Snack -> 3
        }
    }
}

object MealSortPolicy {
    fun sortMeals(meals: List<MealEntry>): List<MealEntry> {
        return meals.sortedBy { it.mealType.sortOrder() }
    }

    fun sortDraftMeals(meals: List<DraftMeal>): List<DraftMeal> {
        return meals.sortedBy { it.mealType.sortOrder() }
    }
}
