package com.example.domain.model.ai

/**
 * 意图分类结果，由 HybridIntentRouter 返回。
 * 包含最终意图、置信度以及辅助信号。
 */
data class IntentClassificationResult(
    val primaryIntent: HybridIntent,
    val speechAct: String,
    val consumptionStatus: String,
    val shouldCreateDraft: Boolean,
    val shouldAskMealTime: Boolean,
    val shouldWriteData: Boolean,
    val confidence: Double,
    val containsFood: Boolean,
    val containsEmotion: Boolean,
    val mealTimeMentioned: Boolean,
    val weightMentioned: Boolean,
    val shouldComfortFirst: Boolean,
    val extractedFoodText: String?,
    val extractedWeightKg: Double?,
    val reason: String?,
    val isFollowUp: Boolean = false
)
