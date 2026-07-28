package com.goings.dayzero.domain.repository

import com.goings.dayzero.domain.model.ai.IntentClassificationResult
import com.goings.dayzero.domain.model.ai.IntentClassifierRequest

/**
 * 意图分类器仓库接口。
 * 远程实现调用 Supabase Edge Function classify-user-intent。
 */
interface IntentClassifierRepository {
    suspend fun classify(request: IntentClassifierRequest): IntentClassificationResult
}
