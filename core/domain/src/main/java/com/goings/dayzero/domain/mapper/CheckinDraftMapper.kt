package com.goings.dayzero.domain.mapper

import com.goings.dayzero.domain.model.DailyRecord
import com.goings.dayzero.domain.model.FoodEntry
import com.goings.dayzero.domain.model.MealEntry
import com.goings.dayzero.domain.model.RecordStatus
import com.goings.dayzero.domain.model.ai.CheckinDraft
import com.goings.dayzero.domain.model.ai.DraftFood
import com.goings.dayzero.domain.model.ai.DraftMeal

class CheckinDraftMapper {

    fun toDailyRecord(draft: CheckinDraft): DailyRecord {
        return DailyRecord(
            id = draft.id,
            date = draft.date,
            status = RecordStatus.Draft,
            meals = com.goings.dayzero.domain.model.MealSortPolicy.sortMeals(draft.meals.map { toMealEntry(it) }),
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
