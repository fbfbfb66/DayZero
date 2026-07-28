package com.goings.dayzero.data.remote.mapper

import com.goings.dayzero.data.remote.dto.AiDraftRequestDto
import com.goings.dayzero.data.remote.dto.AiDraftResponseDto
import com.goings.dayzero.data.remote.dto.AiSummaryRequestDto
import com.goings.dayzero.data.remote.dto.RemoteFoodDto
import com.goings.dayzero.data.remote.dto.RemoteMealDto
import com.goings.dayzero.domain.model.DailyRecord
import com.goings.dayzero.domain.model.FoodEntry
import com.goings.dayzero.domain.model.MealEntry
import com.goings.dayzero.domain.model.MealType
import com.goings.dayzero.domain.model.ai.AiDraftRequest
import com.goings.dayzero.domain.model.ai.CheckinDraft
import com.goings.dayzero.domain.model.ai.DraftFood
import com.goings.dayzero.domain.model.ai.DraftMeal
import java.time.LocalDate
import java.util.UUID

class AiDraftRemoteMapper {

    fun toRequestDto(request: AiDraftRequest): AiDraftRequestDto {
        return AiDraftRequestDto(
            date = request.date.toString(),
            text = request.text,
            weightKg = request.weightKg?.toDouble(),
            context = request.context
        )
    }

    fun toSummaryRequestDto(record: DailyRecord): AiSummaryRequestDto {
        return AiSummaryRequestDto(
            meals = com.goings.dayzero.domain.model.MealSortPolicy.sortMeals(record.meals).map { toMealDto(it) },
            totalCalories = record.totalCalories,
            weightKg = record.weightKg?.toDouble()
        )
    }

    private fun toMealDto(domain: MealEntry): RemoteMealDto {
        return RemoteMealDto(
            mealType = domain.mealType.name,
            displayName = domain.mealType.displayName,
            photoUri = null,
            foods = domain.foods.map { toFoodDto(it) },
            mealCalories = domain.mealCalories
        )
    }

    private fun toFoodDto(domain: FoodEntry): RemoteFoodDto {
        return RemoteFoodDto(
            id = domain.id,
            name = domain.name,
            quantity = domain.quantity,
            estimatedCalories = domain.estimatedCalories,
            confidence = domain.confidence
        )
    }

    fun toDomain(dto: AiDraftResponseDto): CheckinDraft {
        val meals = dto.meals.orEmpty().map { toMealDomain(it) }
        val sortedMeals = com.goings.dayzero.domain.model.MealSortPolicy.sortDraftMeals(meals)
        val recalculatedTotalCalories = sortedMeals.sumOf { it.mealCalories }
        return CheckinDraft(
            id = dto.id ?: UUID.randomUUID().toString(),
            date = dto.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(),
            meals = sortedMeals,
            totalCalories = dto.totalCalories?.takeIf { it > 0 } ?: recalculatedTotalCalories,
            weightKg = dto.weightKg?.toFloat(),
            aiSummary = dto.aiSummary.orEmpty(),
            sourceText = dto.sourceText
        )
    }

    private fun toMealDomain(dto: RemoteMealDto): DraftMeal {
        val mealType = mapToMealType(dto.mealType.orEmpty())
        val foods = dto.foods.orEmpty().map { toFoodDomain(it) }
        val recalculatedMealCalories = foods.sumOf { it.estimatedCalories }
        return DraftMeal(
            mealType = mealType,
            displayName = dto.displayName ?: mealType.displayName,
            photoUri = dto.photoUri,
            foods = foods,
            mealCalories = dto.mealCalories?.takeIf { it > 0 } ?: recalculatedMealCalories
        )
    }

    private fun toFoodDomain(dto: RemoteFoodDto): DraftFood {
        return DraftFood(
            id = dto.id ?: UUID.randomUUID().toString(),
            name = dto.name.orEmpty(),
            quantity = dto.quantity ?: "1份",
            estimatedCalories = dto.estimatedCalories ?: 0,
            confidence = dto.confidence
        )
    }

    private fun mapToMealType(mealType: String): MealType {
        return when (mealType.lowercase()) {
            "breakfast", "早餐", "早上" -> MealType.Breakfast
            "lunch", "午餐", "中午" -> MealType.Lunch
            "dinner", "晚餐", "晚上" -> MealType.Dinner
            "snack", "加餐", "加餐/下午茶/夜宵/其他", "下午茶", "夜宵", "其他" -> MealType.Snack
            else -> MealType.Snack
        }
    }
}
