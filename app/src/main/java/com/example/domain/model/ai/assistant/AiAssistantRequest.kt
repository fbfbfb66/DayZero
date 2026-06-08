package com.example.domain.model.ai.assistant

import com.example.domain.model.DailyRecord
import com.example.domain.model.ai.AiChatMessage
import java.time.LocalDate

data class AiAssistantRequest(
    val date: LocalDate,
    val userText: String,
    val todayRecord: DailyRecord? = null,
    val pendingDraft: DailyRecord? = null,
    val recentMessages: List<AiChatMessage> = emptyList(),
    val turnType: String = "user_message", // "user_message" or "interaction_result"
    val interactionResult: InteractionResult? = null,
    val primaryIntent: String? = null,
    val speechAct: String? = null,
    val consumptionStatus: String? = null,
    val shouldCreateDraft: Boolean? = null,
    val shouldAskMealTime: Boolean? = null,
    val extractedFoodText: String? = null
)

data class InteractionResult(
    val interactionId: String,
    val actionType: String,
    val selectedOptionId: String,
    val selectedOptionLabel: String,
    val field: String? = null,
    val originalText: String? = null,
    val confirmType: String? = null,
    val payloadSummary: PayloadSummary? = null
)

data class PayloadSummary(
    val originalText: String? = null,
    val mealType: String? = null,
    val items: List<ConfirmCardItem>? = null
)
