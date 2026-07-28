package com.goings.dayzero.data.repository

import android.util.Log
import com.goings.dayzero.data.remote.api.AiDraftApiService
import com.goings.dayzero.data.remote.mapper.IntentClassificationMapper
import com.goings.dayzero.domain.model.ai.HybridIntent
import com.goings.dayzero.domain.model.ai.IntentClassificationResult
import com.goings.dayzero.domain.model.ai.IntentClassifierRequest
import com.goings.dayzero.domain.repository.IntentClassifierRepository

/**
 * 远程意图分类器实现。
 * 调用 Supabase Edge Function classify-user-intent (Kimi 意图分类)。
 * 只返回意图分类 JSON，不返回卡片，不写 Room。
 */
class RemoteIntentClassifierRepository(
    private val apiService: AiDraftApiService
) : IntentClassifierRepository {

    private val mapper = IntentClassificationMapper()

    override suspend fun classify(request: IntentClassifierRequest): IntentClassificationResult {
        return try {
            val requestDto = mapper.toRequestDto(request)
            Log.d(TAG, "Calling classify-user-intent for text='${request.userText.take(50)}'")
            val responseDto = apiService.classifyUserIntent(requestDto)
            val result = mapper.toDomain(responseDto)
            Log.d(TAG, "Remote classification: intent=${result.primaryIntent}, confidence=${result.confidence}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Remote classification failed", e)
            // 返回 Unsupported 作为 fallback，让 HybridIntentRouter 使用本地结果
            IntentClassificationResult(
                primaryIntent = HybridIntent.Unsupported,
                speechAct = "",
                consumptionStatus = "",
                shouldCreateDraft = false,
                shouldAskMealTime = false,
                shouldWriteData = false,
                confidence = 0.0,
                containsFood = false,
                containsEmotion = false,
                mealTimeMentioned = false,
                weightMentioned = false,
                shouldComfortFirst = false,
                extractedFoodText = null,
                extractedWeightKg = null,
                reason = "Remote classification failed: ${e.message}"
            )
        }
    }

    companion object {
        private const val TAG = "DayZeroHybridIntent"
    }
}
