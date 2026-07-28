package com.goings.dayzero.domain.model

import com.goings.dayzero.domain.model.ai.AiChatMessage
import com.goings.dayzero.domain.model.ai.AiRecordConversationState
import java.time.LocalDate

data class AppState(
    val currentDate: LocalDate = LocalDate.now(),
    val records: List<DailyRecord> = emptyList(),
    val activeConversationId: String? = null,
    val chatMessages: List<AiChatMessage> = emptyList(),
    val isAnalyzing: Boolean = false,
    val conversationState: AiRecordConversationState = AiRecordConversationState.Idle
)
