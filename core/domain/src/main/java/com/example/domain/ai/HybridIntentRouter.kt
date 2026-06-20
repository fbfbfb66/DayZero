package com.example.domain.ai

import com.example.domain.model.ai.HybridIntent
import com.example.domain.model.ai.HybridIntentContext
import com.example.domain.model.ai.IntentClassificationResult
import com.example.domain.model.ai.IntentClassifierRequest
import com.example.domain.model.ai.RecentMessageItem
import com.example.domain.repository.IntentClassifierRepository

/**
 * 混合意图路由器，结合本地规则和 Kimi 远程意图分类。
 */
class HybridIntentRouter(
    private val remoteClassifier: IntentClassifierRepository
) {
    suspend fun classify(
        text: String,
        context: HybridIntentContext
    ): IntentClassificationResult {
        // A. 识别 Follow-up (追问)
        val followUpKeywords = listOf("意思是", "也就是说", "所以", "那我可以", "是不是", "你的意思是", "那就是")
        val hasPreviousAssistantReply = context.recentMessages.any { it.role == com.example.domain.model.ai.ChatRole.Assistant }
        val isFollowUp = followUpKeywords.any { text.contains(it) } && hasPreviousAssistantReply

        // 1. 先进行本地分类
        val localIntent = LocalIntentRouter.classify(text)
        val signals = LocalIntentRouter.detectSignals(text)
        val localConfidence = determineLocalConfidence(localIntent)
        
        var remoteCalled = false
        
        // 2. 如果本地高置信度且不是追问，直接返回
        if (!isFollowUp && localConfidence == LocalConfidence.HighConfidence) {
            val result = createResult(
                intent = mapLocalToHybrid(localIntent),
                confidence = 1.0,
                signals = signals,
                text = text,
                localIntent = localIntent
            ).copy(reason = "Local high confidence accepted", isFollowUp = isFollowUp)
            
            logRouting(text, true, false, result)
            return result
        }

        // 3. 本地不能确定，或属于追问场景，调用远程分类
        remoteCalled = true
        val request = IntentClassifierRequest(
            userText = text,
            date = context.date,
            todayRecordSummary = context.todayConfirmedRecord?.let { record ->
                val meals = record.meals.filter { m -> m.foods.isNotEmpty() }
                    .joinToString { m -> "${m.mealType.displayName}:${m.foods.joinToString { f -> f.name }}" }
                "今天已记录：$meals，总热量约 ${record.totalCalories} kcal"
            } ?: "今天还没有记录饮食",
            recentMessages = context.recentMessages.takeLast(6).map { 
                RecentMessageItem(role = it.role.name, text = it.text) 
            }
        )

        val remoteResult = try {
            remoteClassifier.classify(request)
        } catch (e: Exception) {
            null
        }

        // 4. 如果远程置信度高，使用远程结果
        if (remoteResult != null && (isFollowUp || (remoteResult.primaryIntent != HybridIntent.Unsupported && remoteResult.confidence >= 0.7))) {
            val result = remoteResult.copy(reason = "Remote high confidence accepted", isFollowUp = isFollowUp)
            logRouting(text, false, true, result)
            return result
        }

        // 5. 否则 fallback 回本地结果
        val fallbackIntent = HybridIntent.Unsupported
        val finalReason = if (remoteResult == null) "Remote failed, fallback to local unsupported" else "Remote low confidence, fallback to local unsupported"
        
        val fallbackResult = createResult(
            intent = fallbackIntent,
            confidence = 0.5,
            signals = signals,
            text = text,
            localIntent = localIntent
        ).copy(reason = finalReason, isFollowUp = isFollowUp)

        logRouting(text, false, true, fallbackResult)
        return fallbackResult
    }

    private fun logRouting(
        rawText: String,
        localMatched: Boolean,
        remoteCalled: Boolean,
        result: IntentClassificationResult
    ) {
        // Deprecated legacy router: keep the hook for call-site stability without Android logging.
    }

    private fun determineLocalConfidence(localIntent: LocalUserIntent): LocalConfidence {
        return when (localIntent) {
            is LocalUserIntent.WeightLogging,
            is LocalUserIntent.InvalidWeight,
            is LocalUserIntent.AppCommandClearData,
            is LocalUserIntent.ClearFoodLogging -> LocalConfidence.HighConfidence
            
            is LocalUserIntent.Unknown -> LocalConfidence.NeedsAiClassification
        }
    }

    private fun mapLocalToHybrid(localIntent: LocalUserIntent): HybridIntent {
        return when (localIntent) {
            is LocalUserIntent.ClearFoodLogging -> HybridIntent.FoodLogging
            is LocalUserIntent.WeightLogging, is LocalUserIntent.InvalidWeight -> HybridIntent.WeightLogging
            is LocalUserIntent.AppCommandClearData -> HybridIntent.FoodDelete // Map clear data command to FoodDelete for now
            is LocalUserIntent.Unknown -> HybridIntent.Unsupported
        }
    }

    private fun createResult(
        intent: HybridIntent,
        confidence: Double,
        signals: LocalIntentSignals,
        text: String,
        localIntent: LocalUserIntent
    ): IntentClassificationResult {
        val consumptionStatus = if (localIntent is LocalUserIntent.ClearFoodLogging) "consumed" else "unknown"
        val speechAct = if (localIntent is LocalUserIntent.ClearFoodLogging) "logging" else "unknown"
        val shouldCreateDraft = localIntent is LocalUserIntent.ClearFoodLogging
        val shouldAskMealTime = false // local rule ensures mealTimeDetected

        return IntentClassificationResult(
            primaryIntent = intent,
            speechAct = speechAct,
            consumptionStatus = consumptionStatus,
            shouldCreateDraft = shouldCreateDraft,
            shouldAskMealTime = shouldAskMealTime,
            shouldWriteData = false, // Never true directly from classifier
            confidence = confidence,
            containsFood = signals.foodDetected,
            containsEmotion = false,
            mealTimeMentioned = signals.mealTimeDetected,
            weightMentioned = localIntent is LocalUserIntent.WeightLogging || localIntent is LocalUserIntent.InvalidWeight,
            shouldComfortFirst = false,
            extractedFoodText = if (signals.foodDetected) text else null,
            extractedWeightKg = if (localIntent is LocalUserIntent.WeightLogging) localIntent.weightKg else null,
            reason = "Local rule classification",
            isFollowUp = false
        )
    }

    enum class LocalConfidence {
        HighConfidence,
        NeedsAiClassification
    }

}
