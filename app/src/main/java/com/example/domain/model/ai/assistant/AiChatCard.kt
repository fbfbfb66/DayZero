package com.example.domain.model.ai.assistant

import com.example.domain.model.MealType
import com.example.domain.model.ai.CheckinDraft
import java.time.LocalDate

sealed interface AiChatCard {
    val id: String
    val type: AiCardType
}

data class DraftCardPayload(
    override val id: String,
    val draft: CheckinDraft,
    override val type: AiCardType = AiCardType.DraftCard
) : AiChatCard

data class ChoiceCardPayload(
    override val id: String,
    val title: String,
    val message: String,
    val options: List<AiChoiceOption>,
    val relatedDraftId: String? = null,
    val resolved: Boolean = false,
    override val type: AiCardType = AiCardType.ChoiceCard
) : AiChatCard

data class SummaryCardPayload(
    override val id: String,
    val title: String,
    val totalCalories: Int?,
    val recordedMeals: List<MealType>,
    val summary: String,
    override val type: AiCardType = AiCardType.SummaryCard
) : AiChatCard

data class WeightCardPayload(
    override val id: String,
    val date: LocalDate,
    val weightKg: Double,
    override val type: AiCardType = AiCardType.WeightCard
) : AiChatCard

data class EditConfirmCardPayload(
    override val id: String,
    val title: String,
    val message: String,
    val targetRecordId: String?,
    val targetMealType: MealType?,
    val targetFoodId: String?,
    override val type: AiCardType = AiCardType.EditConfirmCard
) : AiChatCard

data class DeleteConfirmCardPayload(
    override val id: String,
    val title: String,
    val message: String,
    val targetRecordId: String?,
    val targetMealType: MealType?,
    override val type: AiCardType = AiCardType.DeleteConfirmCard
) : AiChatCard
