package com.example.domain.repository

import com.example.domain.model.DailyRecord
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.IntentClassificationResult

interface AiCompanionReplyRepository {
    suspend fun generateReply(
        userText: String,
        todayRecord: DailyRecord?,
        recentMessages: List<AiChatMessage>,
        semanticResult: IntentClassificationResult? = null
    ): String
}
