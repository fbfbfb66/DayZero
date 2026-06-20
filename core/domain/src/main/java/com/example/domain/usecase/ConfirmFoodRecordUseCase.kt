package com.example.domain.usecase

import com.example.domain.model.DailyRecord
import com.example.domain.model.FoodEntry
import com.example.domain.model.MealEntry
import com.example.domain.model.MealType
import com.example.domain.model.RecordStatus
import com.example.domain.model.ai.assistant.ConfirmCardMeal
import com.example.domain.model.ai.assistant.PayloadSummary
import com.example.domain.repository.RecordRepository
import java.time.LocalDate

class ConfirmFoodRecordUseCase(
    private val recordRepository: RecordRepository
) {
    suspend operator fun invoke(currentDate: LocalDate, payloadSummary: PayloadSummary?): DailyRecord {
        val todayRecord = recordRepository.getRecordByDateAndStatus(currentDate, RecordStatus.Confirmed)
            ?: DailyRecord(date = currentDate, status = RecordStatus.Confirmed, meals = emptyList())

        val mealsToProcess = payloadSummary.toMealsToProcess()
        val updatedMeals = todayRecord.meals.toMutableList()

        mealsToProcess.forEach { cardMeal ->
            val mealType = cardMeal.mealType.toMealType()
            val newFoods = cardMeal.items.map { item ->
                FoodEntry(
                    name = item.name,
                    quantity = item.amountText ?: "1份",
                    estimatedCalories = item.calories,
                    confidence = item.calorieConfidence
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

        val updatedRecord = todayRecord.copy(
            meals = updatedMeals,
            weightKg = payloadSummary?.weightKg?.toFloat() ?: todayRecord.weightKg
        )
        recordRepository.upsertRecord(updatedRecord)
        return updatedRecord
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
