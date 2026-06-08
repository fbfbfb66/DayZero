package com.example.domain.model.ai

import com.example.domain.model.MealType
import java.time.LocalDate

sealed class AiRecordConversationState {
    data object Idle : AiRecordConversationState()

    data class AnalyzingFood(
        val sourceText: String
    ) : AiRecordConversationState()

    data class WaitingMealTypeSelection(
        val sourceText: String,
        val pendingDraft: CheckinDraft? = null
    ) : AiRecordConversationState()

    data class ShowingDraft(
        val draftRecordId: String
    ) : AiRecordConversationState()

    data class ConfirmingWeight(
        val weightKg: Double
    ) : AiRecordConversationState()

    data class ShowingAdvice(
        val date: LocalDate
    ) : AiRecordConversationState()

    data class ConfirmingDelete(
        val targetMealType: MealType?
    ) : AiRecordConversationState()

    data class ConfirmingEdit(
        val description: String
    ) : AiRecordConversationState()

    data class Error(
        val message: String
    ) : AiRecordConversationState()
}
