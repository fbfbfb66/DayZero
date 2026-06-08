package com.example.domain.mapper

import com.example.domain.model.DailyRecord
import com.example.domain.model.FoodEntry
import com.example.domain.model.MealEntry
import com.example.domain.model.RecordStatus
import com.example.domain.model.ai.CheckinDraft
import com.example.domain.model.ai.DraftFood
import com.example.domain.model.ai.DraftMeal

class CheckinDraftMapper {

    fun toDailyRecord(draft: CheckinDraft): DailyRecord {
        return DailyRecord(
            id = draft.id,
            date = draft.date,
            status = RecordStatus.Draft,
            meals = com.example.domain.model.MealSortPolicy.sortMeals(draft.meals.map { toMealEntry(it) }),
            weightKg = draft.weightKg,
            aiSummary = draft.aiSummary
        )
    }

    private fun toMealEntry(draftMeal: DraftMeal): MealEntry {
        return MealEntry(
            mealType = draftMeal.mealType,
            hasPhoto = draftMeal.photoUri != null,
            foods = draftMeal.foods.map { toFoodEntry(it) }
        )
    }

    private fun toFoodEntry(draftFood: DraftFood): FoodEntry {
        return FoodEntry(
            id = draftFood.id,
            name = draftFood.name,
            quantity = draftFood.quantity,
            estimatedCalories = draftFood.estimatedCalories,
            confidence = draftFood.confidence ?: "medium"
        )
    }
}
