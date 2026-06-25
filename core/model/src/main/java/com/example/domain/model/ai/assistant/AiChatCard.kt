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

data class AskMissingInfoCardPayload(
    override val id: String,
    val title: String,
    val message: String,
    val field: String,
    val originalText: String,
    val options: List<AskMissingInfoOption>,
    val resolved: Boolean = false,
    override val type: AiCardType = AiCardType.AskMissingInfoCard
) : AiChatCard

data class AskMissingInfoOption(
    val id: String,
    val label: String
)

data class ShowConfirmCardPayload(
    override val id: String,
    val confirmType: String,
    val title: String,
    val message: String,
    val originalText: String?,
    val mealType: String?, // Legacy
    val items: List<ConfirmCardItem>, // Legacy
    val date: String? = null,
    val weightKg: Double? = null,
    val totalCalories: Int? = null,
    val meals: List<ConfirmCardMeal>? = null,
    val buttons: List<ConfirmCardOption>,
    val resolved: Boolean = false,
    val state: String = "pending",
    override val type: AiCardType = AiCardType.ShowConfirmCard
) : AiChatCard

data class DateMismatchGuardCardPayload(
    override val id: String,
    val conversationId: String,
    val conversationDate: LocalDate,
    val detectedCurrentDate: LocalDate,
    val state: String = "pending",
    val pendingOriginalCard: ShowConfirmCardPayload,
    val createdAt: Long = System.currentTimeMillis(),
    override val type: AiCardType = AiCardType.DateMismatchGuardCard
) : AiChatCard

data class ConfirmCardMeal(
    val mealType: String,
    val mealLabel: String?,
    val subtotalCalories: Int?,
    val items: List<ConfirmCardItem>
)

data class ConfirmCardItem(
    val id: String? = null,
    val name: String,
    val amountText: String?,
    val calories: Int,
    val calorieConfidence: String,
    val carbohydratesG: Float? = null,
    val proteinG: Float? = null,
    val fatG: Float? = null,
    val fiberG: Float? = null
)

data class ConfirmCardOption(
    val id: String,
    val label: String
)
