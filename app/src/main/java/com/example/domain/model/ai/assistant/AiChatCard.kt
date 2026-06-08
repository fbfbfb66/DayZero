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
    val resolved: Boolean = false,
    override val type: AiCardType = AiCardType.WeightCard
) : AiChatCard

data class EditConfirmCardPayload(
    override val id: String,
    val title: String,
    val message: String,
    val targetRecordId: String? = null,
    val targetMealType: com.example.domain.model.MealType? = null,
    val targetFoodId: String? = null,
    val targetFoodName: String? = null,
    val newQuantity: String? = null,
    val newEstimatedCalories: Int? = null,
    val newMealType: com.example.domain.model.MealType? = null,
    val operationDescription: String? = null,
    val resolved: Boolean = false,
    override val type: AiCardType = AiCardType.EditConfirmCard
) : AiChatCard

data class DeleteConfirmCardPayload(
    override val id: String,
    val title: String,
    val message: String,
    val targetRecordId: String? = null,
    val targetMealType: com.example.domain.model.MealType? = null,
    val resolved: Boolean = false,
    override val type: AiCardType = AiCardType.DeleteConfirmCard
) : AiChatCard

data class DebugChoiceCardPayload(
    override val id: String,
    val title: String,
    val message: String,
    val options: List<DebugChoiceOption>,
    val resolved: Boolean = false,
    override val type: AiCardType = AiCardType.DebugChoiceCard
) : AiChatCard

data class DebugChoiceOption(
    val id: String,
    val label: String
)

data class AskRecordIntentCardPayload(
    override val id: String,
    val title: String,
    val message: String,
    val originalText: String,
    val options: List<AskRecordIntentOption>,
    val resolved: Boolean = false,
    override val type: AiCardType = AiCardType.AskRecordIntentCard
) : AiChatCard

data class AskRecordIntentOption(
    val id: String,
    val label: String
)
