package com.example.data.remote.mapper

import com.example.data.remote.dto.AiDraftRequestDto
import com.example.data.remote.dto.AiDraftResponseDto
import com.example.data.remote.dto.RemoteFoodDto
import com.example.data.remote.dto.RemoteMealDto
import com.example.domain.model.MealType
import com.example.domain.model.ai.AiDraftRequest
import com.example.domain.model.ai.CheckinDraft
import com.example.domain.model.ai.DraftFood
import com.example.domain.model.ai.DraftMeal
import java.time.LocalDate

class AiDraftRemoteMapper {

    fun toRequestDto(request: AiDraftRequest): AiDraftRequestDto {
        return AiDraftRequestDto(
            date = request.date.toString(),
            text = request.text,
            weightKg = request.weightKg?.toDouble()
        )
    }

    fun toDomain(dto: AiDraftResponseDto): CheckinDraft {
        return CheckinDraft(
            id = dto.id,
            date = LocalDate.parse(dto.date),
            meals = dto.meals.map { toMealDomain(it) },
            totalCalories = dto.totalCalories,
            weightKg = dto.weightKg?.toFloat(),
            aiSummary = dto.aiSummary,
            sourceText = dto.sourceText
        )
    }

    private fun toMealDomain(dto: RemoteMealDto): DraftMeal {
        return DraftMeal(
            mealType = mapToMealType(dto.mealType),
            displayName = dto.displayName,
            photoUri = dto.photoUri,
            foods = dto.foods.map { toFoodDomain(it) },
            mealCalories = dto.mealCalories
        )
    }

    private fun toFoodDomain(dto: RemoteFoodDto): DraftFood {
        return DraftFood(
            id = dto.id,
            name = dto.name,
            quantity = dto.quantity,
            estimatedCalories = dto.estimatedCalories,
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
