package com.example.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.domain.model.ai.assistant.AiChatCard
import com.example.domain.model.ai.assistant.AskMissingInfoCardPayload
import com.example.domain.model.ai.assistant.AskRecordIntentCardPayload
import com.example.domain.model.ai.assistant.DebugChoiceCardPayload
import com.example.domain.model.ai.assistant.DebugChoiceOption
import com.example.domain.model.ai.assistant.ShowConfirmCardPayload
import com.example.ui.components.ai.AskMissingInfoCard
import com.example.ui.components.ai.AskRecordIntentCard
import com.example.ui.components.ai.DebugChoiceCard
import com.example.ui.components.ai.FoodDraftConfirmCard

@Composable
internal fun AssistantCardRenderer(
    card: AiChatCard,
    actionHandler: AiRecordActionHandler
) {
    when (card) {
        is DebugChoiceCardPayload -> {
            if (!card.resolved) {
                CardSpacer()
                DebugChoiceCard(
                    card = card,
                    onOptionSelected = { interactionId, optionId, optionLabel ->
                        actionHandler.sendInteractionResult(
                            interactionId = interactionId,
                            actionType = "debug_show_choice_card",
                            optionId = optionId,
                            optionLabel = optionLabel
                        )
                    }
                )
            }
        }

        is AskRecordIntentCardPayload -> {
            if (!card.resolved) {
                CardSpacer()
                AskRecordIntentCard(
                    card = card,
                    onOptionSelected = { interactionId, optionId, optionLabel ->
                        actionHandler.sendInteractionResult(
                            interactionId = interactionId,
                            actionType = "ask_record_intent_card",
                            optionId = optionId,
                            optionLabel = optionLabel,
                            originalText = card.originalText
                        )
                    }
                )
            }
        }

        is AskMissingInfoCardPayload -> {
            if (!card.resolved) {
                CardSpacer()
                AskMissingInfoCard(
                    card = card,
                    onOptionSelected = { interactionId, optionId, optionLabel, field, originalText ->
                        actionHandler.sendInteractionResult(
                            interactionId = interactionId,
                            actionType = "ask_missing_info_card",
                            optionId = optionId,
                            optionLabel = optionLabel,
                            field = field,
                            originalText = originalText
                        )
                    }
                )
            }
        }

        is ShowConfirmCardPayload -> {
            if (card.confirmType == "food_record") {
                CardSpacer()
                FoodDraftConfirmCard(
                    card = card,
                    onOptionSelected = { interactionId, optionId, optionLabel, payloadSummary ->
                        actionHandler.sendInteractionResult(
                            interactionId = interactionId,
                            actionType = "show_confirm_card",
                            optionId = optionId,
                            optionLabel = optionLabel,
                            confirmType = "food_record",
                            payloadSummary = payloadSummary
                        )
                    }
                )
            } else if (!card.resolved) {
                CardSpacer()
                DebugChoiceCard(
                    card = DebugChoiceCardPayload(
                        id = card.id,
                        title = card.title,
                        message = card.message,
                        options = card.buttons.map { DebugChoiceOption(it.id, it.label) }
                    ),
                    onOptionSelected = { interactionId, optionId, optionLabel ->
                        actionHandler.sendInteractionResult(
                            interactionId = interactionId,
                            actionType = "show_confirm_card",
                            optionId = optionId,
                            optionLabel = optionLabel,
                            confirmType = card.confirmType
                        )
                    }
                )
            }
        }

        else -> Unit
    }
}

@Composable
private fun CardSpacer() {
    Spacer(modifier = Modifier.height(8.dp))
}
