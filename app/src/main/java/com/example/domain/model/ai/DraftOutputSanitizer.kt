package com.example.domain.model.ai

import android.util.Log
import com.example.domain.model.MealType

object DraftOutputSanitizer {

    private val BREAKFAST_KEYWORDS = listOf("早", "上午")
    private val LUNCH_KEYWORDS = listOf("中", "午")
    private val DINNER_KEYWORDS = listOf("晚")
    private val SNACK_KEYWORDS = listOf("下", "零食", "夜", "宵", "加餐")

    fun sanitize(draft: CheckinDraft, userText: String): CheckinDraft {
        val userMentionedMealTypes = mutableSetOf<MealType>()
        
        if (BREAKFAST_KEYWORDS.any { userText.contains(it) }) userMentionedMealTypes.add(MealType.Breakfast)
        if (LUNCH_KEYWORDS.any { userText.contains(it) }) userMentionedMealTypes.add(MealType.Lunch)
        if (DINNER_KEYWORDS.any { userText.contains(it) }) userMentionedMealTypes.add(MealType.Dinner)
        if (SNACK_KEYWORDS.any { userText.contains(it) }) userMentionedMealTypes.add(MealType.Snack)

        // If user didn't mention any specific meal type, we keep it as is (let the UI handle the questioning if needed)
        if (userMentionedMealTypes.isEmpty()) return draft

        // Only keep meals that match user mentioned types
        val sanitizedMeals = draft.meals.filter { it.mealType in userMentionedMealTypes }
        
        // If filtering would make it empty, it might be a mapping issue (e.g., user said "吃了包子" and AI guessed Snack)
        // In this case, we prefer keeping AI's guess because it's better than an empty draft.
        if (sanitizedMeals.isEmpty()) {
            Log.d("DayZeroAssistantFlow", "DraftOutputSanitizer: Sanitization resulted in empty meals for text '$userText', keeping original AI guess.")
            return draft
        }

        return draft.copy(
            meals = sanitizedMeals,
            totalCalories = sanitizedMeals.sumOf { it.mealCalories }
        )
    }
}
