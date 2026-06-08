package com.example.domain.model.ai

import com.example.domain.model.DailyRecord
import java.time.LocalDate

/**
 * HybridIntentRouter 分类时的上下文信息。
 */
data class HybridIntentContext(
    val date: LocalDate,
    val todayConfirmedRecord: DailyRecord?,
    val recentMessages: List<AiChatMessage>
)
