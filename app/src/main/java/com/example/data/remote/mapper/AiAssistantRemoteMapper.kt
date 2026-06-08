package com.example.data.remote.mapper

import com.example.data.remote.dto.RemoteFoodDto
import com.example.data.remote.dto.RemoteMealDto
import com.example.data.remote.dto.assistant.*
import com.example.domain.model.*
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.assistant.*
import java.time.LocalDate

class AiAssistantRemoteMapper {
    private val draftMapper = AiDraftRemoteMapper()

    fun toRequestDto(request: AiAssistantRequest): AiAssistantRequestDto {
        return AiAssistantRequestDto(
            date = request.date.toString(),
            userText = request.userText,
            todayRecord = request.todayRecord?.let { toSimpleRecordDto(it) },
            pendingDraft = request.pendingDraft?.let { toSimpleRecordDto(it) },
            recentMessages = request.recentMessages.map { 
                SimpleChatMessageDto(role = it.role.name, text = it.text) 
            },
            turnType = request.turnType,
            interactionResult = request.interactionResult?.let { 
                InteractionResultDto(
                    interactionId = it.interactionId,
                    actionType = it.actionType,
                    selectedOptionId = it.selectedOptionId,
                    selectedOptionLabel = it.selectedOptionLabel
                )
            },
            primaryIntent = request.primaryIntent,
            speechAct = request.speechAct,
            consumptionStatus = request.consumptionStatus,
            shouldCreateDraft = request.shouldCreateDraft,
            shouldAskMealTime = request.shouldAskMealTime,
            extractedFoodText = request.extractedFoodText
        )
    }

    private fun toSimpleRecordDto(record: DailyRecord): SimpleRecordDto {
        return SimpleRecordDto(
            id = record.id,
            date = record.date.toString(),
            meals = record.meals.map { toMealDto(it) },
            totalCalories = record.totalCalories,
            weightKg = record.weightKg?.toDouble(),
            aiSummary = record.aiSummary
        )
    }

    private fun toMealDto(domain: MealEntry): RemoteMealDto {
        return RemoteMealDto(
            mealType = domain.mealType.name,
            displayName = domain.mealType.displayName,
            photoUri = null,
            foods = domain.foods.map { toFoodDto(it) },
            mealCalories = domain.mealCalories
        )
    }

    private fun toFoodDto(domain: FoodEntry): RemoteFoodDto {
        return RemoteFoodDto(
            id = domain.id,
            name = domain.name,
            quantity = domain.quantity,
            estimatedCalories = domain.estimatedCalories,
            confidence = domain.confidence
        )
    }

    fun toDomain(dto: AiAssistantTurnDto): AiAssistantTurn {
        return AiAssistantTurn(
            id = dto.id,
            intent = mapIntent(dto.intent),
            replyText = dto.replyText,
            cards = dto.cards.mapNotNull { toCardDomain(it) },
            suggestedReplies = dto.suggestedReplies,
            createdAt = dto.createdAt
        )
    }

    private fun mapIntent(intent: String): AiIntent {
        return when (intent) {
            "food_logging" -> AiIntent.FoodLogging
            "meal_time_clarification" -> AiIntent.MealTimeClarification
            "food_edit" -> AiIntent.FoodEdit
            "food_delete" -> AiIntent.FoodDelete
            "weight_logging" -> AiIntent.WeightLogging
            "daily_advice" -> AiIntent.DailyAdvice
            "daily_summary" -> AiIntent.DailySummary
            "motivation" -> AiIntent.Motivation
            "general_chat" -> AiIntent.GeneralChat
            else -> AiIntent.Unsupported
        }
    }

    fun toCardDomain(dto: AiChatCardDto): AiChatCard? {
        return when (dto.type) {
            "draft_card" -> dto.draft?.let { 
                DraftCardPayload(id = dto.id, draft = draftMapper.toDomain(it)) 
            }
            "choice_card" -> ChoiceCardPayload(
                id = dto.id,
                title = dto.title ?: "请选择",
                message = dto.message ?: "",
                options = dto.options?.map { toOptionDomain(it) } ?: emptyList(),
                relatedDraftId = dto.relatedDraftId,
                resolved = dto.resolved ?: false
            )
            "summary_card" -> SummaryCardPayload(
                id = dto.id,
                title = dto.title ?: "今日总结",
                totalCalories = dto.totalCalories,
                recordedMeals = dto.recordedMeals?.map { mapToMealType(it) } ?: emptyList(),
                summary = dto.summary ?: ""
            )
            "weight_card" -> WeightCardPayload(
                id = dto.id,
                date = dto.date?.let { LocalDate.parse(it) } ?: LocalDate.now(),
                weightKg = dto.weightKg ?: 0.0
            )
            "edit_confirm_card" -> EditConfirmCardPayload(
                id = dto.id,
                title = dto.title ?: "确认修改",
                message = dto.message ?: "",
                targetRecordId = dto.targetRecordId,
                targetMealType = dto.targetMealType?.let { mapToMealType(it) },
                targetFoodId = dto.targetFoodId
            )
            "delete_confirm_card" -> DeleteConfirmCardPayload(
                id = dto.id,
                title = dto.title ?: "确认删除",
                message = dto.message ?: "",
                targetRecordId = dto.targetRecordId,
                targetMealType = dto.targetMealType?.let { mapToMealType(it) }
            )
            "debug_choice_card" -> DebugChoiceCardPayload(
                id = dto.id,
                title = dto.title ?: "请选择",
                message = dto.message ?: "",
                options = dto.options?.map { DebugChoiceOption(id = it.id, label = it.label) } ?: emptyList(),
                resolved = dto.resolved ?: false
            )
            "ask_record_intent_card" -> AskRecordIntentCardPayload(
                id = dto.id,
                title = dto.title ?: "意图确认",
                message = dto.message ?: "",
                originalText = dto.originalText ?: "",
                options = dto.options?.map { AskRecordIntentOption(id = it.id, label = it.label) } ?: emptyList(),
                resolved = dto.resolved ?: false
            )
            else -> null
        }
    }

    private fun toOptionDomain(dto: AiChoiceOptionDto): AiChoiceOption {
        return AiChoiceOption(
            id = dto.id,
            label = dto.label,
            action = mapAction(dto.action ?: "unknown")
        )
    }

    private fun mapAction(action: String): AiChoiceAction {
        return when (action) {
            "unknown" -> AiChoiceAction.Unknown
            "cancel" -> AiChoiceAction.Cancel
            "confirm" -> AiChoiceAction.Confirm
            "add_to_meal" -> AiChoiceAction.AddToMeal
            "replace_meal" -> AiChoiceAction.ReplaceMeal
            "add_non_conflicting_meals" -> AiChoiceAction.AddNonConflictingMeals
            "override_conflicting_meals" -> AiChoiceAction.OverrideConflictingMeals
            "set_meal_type_breakfast" -> AiChoiceAction.SetMealTypeBreakfast
            "set_meal_type_lunch" -> AiChoiceAction.SetMealTypeLunch
            "set_meal_type_dinner" -> AiChoiceAction.SetMealTypeDinner
            "set_meal_type_snack" -> AiChoiceAction.SetMealTypeSnack
            "confirm_weight" -> AiChoiceAction.ConfirmWeight
            "confirm_edit" -> AiChoiceAction.ConfirmEdit
            "confirm_delete" -> AiChoiceAction.ConfirmDelete
            else -> AiChoiceAction.Unknown
        }
    }

    private fun mapToMealType(mealType: String): MealType {
        return try {
            MealType.valueOf(mealType)
        } catch (e: Exception) {
            MealType.Snack
        }
    }

    fun toDto(card: AiChatCard): AiChatCardDto? {
        return when (card) {
            is DraftCardPayload -> AiChatCardDto(
                type = "draft_card",
                id = card.id,
                draft = null // Too complex to map back for now, drafts handled via records repo
            )
            is ChoiceCardPayload -> AiChatCardDto(
                type = "choice_card",
                id = card.id,
                title = card.title,
                message = card.message,
                options = card.options.map { toOptionDto(it) },
                relatedDraftId = card.relatedDraftId,
                resolved = card.resolved
            )
            is SummaryCardPayload -> AiChatCardDto(
                type = "summary_card",
                id = card.id,
                title = card.title,
                totalCalories = card.totalCalories,
                recordedMeals = card.recordedMeals.map { it.name },
                summary = card.summary
            )
            is WeightCardPayload -> AiChatCardDto(
                type = "weight_card",
                id = card.id,
                date = card.date.toString(),
                weightKg = card.weightKg
            )
            is EditConfirmCardPayload -> AiChatCardDto(
                type = "edit_confirm_card",
                id = card.id,
                title = card.title,
                message = card.message,
                targetRecordId = card.targetRecordId,
                targetMealType = card.targetMealType?.name,
                targetFoodId = card.targetFoodId
            )
            is DeleteConfirmCardPayload -> AiChatCardDto(
                type = "delete_confirm_card",
                id = card.id,
                title = card.title,
                message = card.message,
                targetRecordId = card.targetRecordId,
                targetMealType = card.targetMealType?.name
            )
            is DebugChoiceCardPayload -> AiChatCardDto(
                type = "debug_choice_card",
                id = card.id,
                title = card.title,
                message = card.message,
                options = card.options.map { AiChoiceOptionDto(id = it.id, label = it.label) },
                resolved = card.resolved
            )
            is AskRecordIntentCardPayload -> AiChatCardDto(
                type = "ask_record_intent_card",
                id = card.id,
                title = card.title,
                message = card.message,
                originalText = card.originalText,
                options = card.options.map { AiChoiceOptionDto(id = it.id, label = it.label) },
                resolved = card.resolved
            )
            else -> null
        }
    }

    private fun toOptionDto(domain: AiChoiceOption): AiChoiceOptionDto {
        return AiChoiceOptionDto(
            id = domain.id,
            label = domain.label,
            action = mapActionToDto(domain.action)
        )
    }

    private fun mapActionToDto(action: AiChoiceAction): String {
        return when (action) {
            AiChoiceAction.Unknown -> "unknown"
            AiChoiceAction.Cancel -> "cancel"
            AiChoiceAction.Confirm -> "confirm"
            AiChoiceAction.AddToMeal -> "add_to_meal"
            AiChoiceAction.ReplaceMeal -> "replace_meal"
            AiChoiceAction.AddNonConflictingMeals -> "add_non_conflicting_meals"
            AiChoiceAction.OverrideConflictingMeals -> "override_conflicting_meals"
            AiChoiceAction.SetMealTypeBreakfast -> "set_meal_type_breakfast"
            AiChoiceAction.SetMealTypeLunch -> "set_meal_type_lunch"
            AiChoiceAction.SetMealTypeDinner -> "set_meal_type_dinner"
            AiChoiceAction.SetMealTypeSnack -> "set_meal_type_snack"
            AiChoiceAction.ConfirmWeight -> "confirm_weight"
            AiChoiceAction.ConfirmEdit -> "confirm_edit"
            AiChoiceAction.ConfirmDelete -> "confirm_delete"
        }
    }
}
