package com.example.domain.model.ai

import java.time.LocalDate

/**
 * 远程意图分类器的请求参数。
 */
data class IntentClassifierRequest(
    val userText: String,
    val date: LocalDate,
    val todayRecordSummary: String,
    val recentMessages: List<RecentMessageItem>
)

data class RecentMessageItem(
    val role: String,
    val text: String
)
