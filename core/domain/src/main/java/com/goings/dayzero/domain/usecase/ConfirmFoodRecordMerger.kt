package com.goings.dayzero.domain.usecase

import com.goings.dayzero.domain.model.DailyRecord
import com.goings.dayzero.domain.model.FoodEntry
import com.goings.dayzero.domain.model.MealEntry
import com.goings.dayzero.domain.model.MealType
import com.goings.dayzero.domain.model.RecordStatus
import com.goings.dayzero.domain.model.ai.assistant.ConfirmCardMeal
import com.goings.dayzero.domain.model.ai.assistant.PayloadSummary
import java.time.LocalDate

object ConfirmFoodRecordMerger {
    fun merge(
        currentRecord: DailyRecord?,
        recordDate: LocalDate,
        payloadSummary: PayloadSummary?
    ): DailyRecord {
        val baseRecord = currentRecord
            ?: DailyRecord(date = recordDate, status = RecordStatus.Confirmed, meals = emptyList())

        val updatedMeals = baseRecord.meals.toMutableList()
        payloadSummary.toMealsToProcess().forEach { cardMeal ->
            val mealType = cardMeal.mealType.toMealType()
            val newFoods = cardMeal.items.map { item ->
                FoodEntry(
                    name = item.name,
                    quantity = item.amountText ?: "1份",
                    estimatedCalories = item.calories,
                    confidence = item.calorieConfidence,
                    carbohydratesG = item.carbohydratesG,
                    proteinG = item.proteinG,
                    fatG = item.fatG,
                    fiberG = item.fiberG
                )
            }

            val existingMealIndex = updatedMeals.indexOfFirst { it.mealType == mealType }
            if (existingMealIndex != -1) {
                val existingMeal = updatedMeals[existingMealIndex]
                updatedMeals[existingMealIndex] = existingMeal.copy(foods = existingMeal.foods + newFoods)
            } else {
                updatedMeals.add(MealEntry(mealType = mealType, foods = newFoods))
            }
        }

        return baseRecord.copy(
            meals = updatedMeals,
            weightKg = payloadSummary?.weightKg?.toFloat() ?: baseRecord.weightKg
        )
    }

    private fun PayloadSummary?.toMealsToProcess(): List<ConfirmCardMeal> {
        val summary = this ?: return emptyList()
        val summaryMeals = summary.meals
        val summaryMealType = summary.mealType
        val summaryItems = summary.items
        return summaryMeals ?: if (summaryMealType != null && summaryItems != null) {
            listOf(
                ConfirmCardMeal(
                    mealType = summaryMealType,
                    mealLabel = summaryMealType,
                    subtotalCalories = summaryItems.sumOf { it.calories },
                    items = summaryItems
                )
            )
        } else {
            emptyList()
        }
    }

    private fun String.toMealType(): MealType {
        return when (lowercase()) {
            "breakfast", "早餐" -> MealType.Breakfast
            "lunch", "午餐" -> MealType.Lunch
            "dinner", "晚餐" -> MealType.Dinner
            "snack", "加餐" -> MealType.Snack
            else -> MealType.Snack
        }
    }
}
