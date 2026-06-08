package com.example.data.remote.mapper

import com.example.data.remote.dto.IntentClassificationResultDto
import com.example.data.remote.dto.IntentClassifierRequestDto
import com.example.data.remote.dto.RecentMessageDto
import com.example.domain.model.ai.HybridIntent
import com.example.domain.model.ai.IntentClassificationResult
import com.example.domain.model.ai.IntentClassifierRequest
import java.time.format.DateTimeFormatter

/**
 * 意图分类器 DTO <-> Domain 映射器。
 */
class IntentClassificationMapper {

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun toRequestDto(request: IntentClassifierRequest): IntentClassifierRequestDto {
        return IntentClassifierRequestDto(
            userText = request.userText,
            date = request.date.format(dateFormatter),
            todayRecordSummary = request.todayRecordSummary,
            recentMessages = request.recentMessages.map { msg ->
                RecentMessageDto(role = msg.role, text = msg.text)
            }
        )
    }

    fun toDomain(dto: IntentClassificationResultDto): IntentClassificationResult {
        return IntentClassificationResult(
            primaryIntent = mapIntentString(dto.primaryIntent),
            speechAct = dto.speechAct,
            consumptionStatus = dto.consumptionStatus,
            shouldCreateDraft = dto.shouldCreateDraft,
            shouldAskMealTime = dto.shouldAskMealTime,
            shouldWriteData = dto.shouldWriteData,
            confidence = dto.confidence,
            containsFood = dto.containsFood,
            containsEmotion = dto.containsEmotion,
            mealTimeMentioned = dto.mealTimeMentioned,
            weightMentioned = dto.weightMentioned,
            shouldComfortFirst = dto.shouldComfortFirst,
            extractedFoodText = dto.foodText,
            extractedWeightKg = null, // Backend schema removed this, we can leave null for now or remove
            reason = dto.reason
        )
    }

    private fun mapIntentString(intent: String): HybridIntent {
        return when (intent) {
            "food_logging" -> HybridIntent.FoodLogging
            "food_info_query" -> HybridIntent.FoodInfoQuery
            "craving_support" -> HybridIntent.CravingSupport
            "emotional_food_logging" -> HybridIntent.EmotionalFoodLogging
            "weight_logging" -> HybridIntent.WeightLogging
            "daily_advice" -> HybridIntent.DailyAdvice
            "daily_summary" -> HybridIntent.DailySummary
            "food_edit" -> HybridIntent.FoodEdit
            "food_delete" -> HybridIntent.FoodDelete
            "general_chat" -> HybridIntent.GeneralChat
            "unsupported" -> HybridIntent.Unsupported
            else -> HybridIntent.Unsupported
        }
    }
}
