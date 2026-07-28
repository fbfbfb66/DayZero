package com.goings.dayzero.domain.repository

import com.goings.dayzero.domain.model.DailyRecord
import com.goings.dayzero.domain.model.ai.AiChatMessage
import com.goings.dayzero.domain.model.ai.IntentClassificationResult

interface AiCompanionReplyRepository {
    suspend fun generateReply(
        userText: String,
        todayRecord: DailyRecord?,
        recentMessages: List<AiChatMessage>,
        semanticResult: IntentClassificationResult? = null
    ): String
}
