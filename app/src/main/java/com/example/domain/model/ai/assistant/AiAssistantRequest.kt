package com.example.domain.model.ai.assistant

import com.example.domain.model.DailyRecord
import com.example.domain.model.ai.AiChatMessage
import java.time.LocalDate

data class AiAssistantRequest(
    val date: LocalDate,
    val userText: String,
    val todayRecord: DailyRecord? = null,
    val pendingDraft: DailyRecord? = null,
    val recentMessages: List<AiChatMessage> = emptyList()
)
