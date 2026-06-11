package com.example.data.remote.mapper

import com.example.data.remote.dto.assistant.AssistantActionDto
import com.example.data.remote.dto.assistant.AssistantActionPayloadDto
import com.example.data.remote.dto.assistant.AssistantTurnV2ResponseDto
import com.example.domain.model.ai.assistant.AiAssistantTurn
import com.example.domain.model.ai.assistant.AiChatCard
import com.example.domain.model.ai.assistant.AiIntent
import com.example.domain.model.ai.assistant.AskMissingInfoCardPayload
import com.example.domain.model.ai.assistant.AskMissingInfoOption
import com.example.domain.model.ai.assistant.AskRecordIntentCardPayload
import com.example.domain.model.ai.assistant.AskRecordIntentOption
import com.example.domain.model.ai.assistant.ConfirmCardItem
import com.example.domain.model.ai.assistant.ConfirmCardMeal
import com.example.domain.model.ai.assistant.ConfirmCardOption
import com.example.domain.model.ai.assistant.DebugChoiceCardPayload
import com.example.domain.model.ai.assistant.DebugChoiceOption
import com.example.domain.model.ai.assistant.ProtocolException
import com.example.domain.model.ai.assistant.ShowConfirmCardPayload
import java.util.UUID

class AssistantTurnV2ResponseMapper {
    fun toDomain(response: AssistantTurnV2ResponseDto): AiAssistantTurn {
        val reply = response.reply?.trim().orEmpty()
        if (reply.isBlank()) throw ProtocolException("协议错误")

        val actions = response.actions ?: throw ProtocolException("协议错误")
        val cards = actions.map { toCard(it) }

        return AiAssistantTurn(
            id = UUID.randomUUID().toString(),
            intent = AiIntent.GeneralChat,
            replyText = reply,
            cards = cards,
            suggestedReplies = emptyList()
        )
    }

    private fun toCard(action: AssistantActionDto): AiChatCard {
        return when (action.type) {
            "debug_show_choice_card" -> toDebugChoiceCard(action)
            "ask_record_intent_card" -> toAskRecordIntentCard(action)
            "ask_missing_info_card" -> toAskMissingInfoCard(action)
            "show_confirm_card" -> toShowConfirmCard(action)
            else -> throw ProtocolException("协议错误")
        }
    }

    private fun toDebugChoiceCard(action: AssistantActionDto): DebugChoiceCardPayload {
        val interactionId = action.interactionId()
        val payload = action.requiredPayload()
        val options = payload.options.orEmpty().map {
            DebugChoiceOption(
                id = it.id.required(),
                label = it.label.required()
            )
        }
        if (payload.title.isNullOrBlank() || payload.message.isNullOrBlank() || options.isEmpty()) {
            throw ProtocolException("协议错误")
        }
        return DebugChoiceCardPayload(
            id = interactionId,
            title = payload.title,
            message = payload.message,
            options = options
        )
    }

    private fun toAskRecordIntentCard(action: AssistantActionDto): AskRecordIntentCardPayload {
        val interactionId = action.interactionId()
        val payload = action.requiredPayload()
        val options = payload.options.orEmpty().map {
            AskRecordIntentOption(
                id = it.id.required(),
                label = it.label.required()
            )
        }
        val optionIds = options.map { it.id }
        if (
            payload.title.isNullOrBlank() ||
            payload.message.isNullOrBlank() ||
            payload.originalText.isNullOrBlank() ||
            options.isEmpty() ||
            !optionIds.containsAll(listOf("record", "chat_only", "not_now"))
        ) {
            throw ProtocolException("协议错误")
        }
        return AskRecordIntentCardPayload(
            id = interactionId,
            title = payload.title,
            message = payload.message,
            originalText = payload.originalText,
            options = options
        )
    }

    private fun toAskMissingInfoCard(action: AssistantActionDto): AskMissingInfoCardPayload {
        val interactionId = action.interactionId()
        val payload = action.requiredPayload()
        val options = payload.options.orEmpty().map {
            AskMissingInfoOption(
                id = it.id.required(),
                label = it.label.required()
            )
        }
        val optionIds = options.map { it.id }
        if (
            payload.title.isNullOrBlank() ||
            payload.message.isNullOrBlank() ||
            payload.field.isNullOrBlank() ||
            payload.originalText.isNullOrBlank() ||
            options.isEmpty()
        ) {
            throw ProtocolException("协议错误")
        }
        if (payload.field == "mealType" && !optionIds.containsAll(listOf("breakfast", "lunch", "dinner", "snack"))) {
            throw ProtocolException("协议错误")
        }
        return AskMissingInfoCardPayload(
            id = interactionId,
            title = payload.title,
            message = payload.message,
            field = payload.field,
            originalText = payload.originalText,
            options = options
        )
    }

    private fun toShowConfirmCard(action: AssistantActionDto): ShowConfirmCardPayload {
        val interactionId = action.interactionId()
        val payload = action.requiredPayload()
        if (
            payload.confirmType != "food_record" ||
            payload.title.isNullOrBlank() ||
            payload.message.isNullOrBlank() ||
            payload.buttons.isNullOrEmpty()
        ) {
            throw ProtocolException("协议错误")
        }

        val items = payload.items?.map { item ->
            ConfirmCardItem(
                name = item.name.required(),
                amountText = item.amountText,
                calories = item.calories ?: throw ProtocolException("协议错误"),
                calorieConfidence = item.calorieConfidence.required()
            )
        } ?: emptyList()

        val meals = payload.meals?.map { meal ->
            if (meal.mealType.isBlank() || meal.items.isEmpty()) throw ProtocolException("协议错误")
            ConfirmCardMeal(
                mealType = meal.mealType,
                mealLabel = meal.mealLabel,
                subtotalCalories = meal.subtotalCalories,
                items = meal.items.map { item ->
                    ConfirmCardItem(
                        name = item.name.required(),
                        amountText = item.amountText,
                        calories = item.calories ?: throw ProtocolException("协议错误"),
                        calorieConfidence = item.calorieConfidence.required()
                    )
                }
            )
        }

        if (items.isEmpty() && meals.isNullOrEmpty()) {
            throw ProtocolException("协议错误")
        }

        val buttons = payload.buttons.map {
            ConfirmCardOption(
                id = it.id.required(),
                label = it.label.required()
            )
        }
        if (!buttons.map { it.id }.containsAll(listOf("confirm", "cancel"))) {
            throw ProtocolException("协议错误")
        }

        return ShowConfirmCardPayload(
            id = interactionId,
            confirmType = payload.confirmType,
            title = payload.title,
            message = payload.message,
            originalText = payload.originalText ?: "",
            date = payload.date ?: "",
            totalCalories = payload.totalCalories,
            weightKg = payload.weightKg,
            mealType = payload.mealType,
            items = items,
            meals = meals,
            buttons = buttons
        )
    }

    private fun AssistantActionDto.interactionId(): String {
        return (id ?: interactionId).required()
    }

    private fun AssistantActionDto.requiredPayload(): AssistantActionPayloadDto {
        return payload ?: throw ProtocolException("协议错误")
    }

    private fun String?.required(): String {
        if (isNullOrBlank()) throw ProtocolException("协议错误")
        return this
    }
}
