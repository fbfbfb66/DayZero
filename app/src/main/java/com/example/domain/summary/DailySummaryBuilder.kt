package com.example.domain.summary

import com.example.domain.model.DailyRecord
import com.example.domain.model.MealType

object DailySummaryBuilder {

    fun buildSummary(record: DailyRecord): String {
        val totalCalories = record.totalCalories
        val recordedMealNames = record.meals
            .filter { it.foods.isNotEmpty() }
            .map { it.mealType.displayName }
            .distinct()

        if (recordedMealNames.isEmpty()) {
            return "今天还没有记录饮食哦，告诉 AI 你吃了什么吧。"
        }

        val mealText = recordedMealNames.joinToString("和")
        val baseText = "今天已记录${mealText}，总热量约 ${totalCalories} kcal。"

        val hasBreakfast = record.meals.any { it.mealType == MealType.Breakfast && it.foods.isNotEmpty() }
        val hasLunch = record.meals.any { it.mealType == MealType.Lunch && it.foods.isNotEmpty() }
        val hasDinner = record.meals.any { it.mealType == MealType.Dinner && it.foods.isNotEmpty() }

        return when {
            hasBreakfast && hasLunch && hasDinner -> {
                "$baseText 整体记录很完整，继续保持健康的饮食习惯！"
            }
            hasBreakfast && hasLunch -> {
                "$baseText 摄入还算平稳，晚餐可以优先补充一些蛋白质。"
            }
            hasBreakfast -> {
                "$baseText 后续午餐和晚餐可以继续补充记录，保持记录习惯就很棒。"
            }
            else -> {
                "$baseText 记录是个好习惯，继续完善今天的饮食清单吧。"
            }
        }
    }
}
