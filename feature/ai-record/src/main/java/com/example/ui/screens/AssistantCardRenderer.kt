package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import com.example.domain.model.ai.assistant.AiChatCard
import com.example.domain.model.ai.assistant.AskMissingInfoCardPayload
import com.example.domain.model.ai.assistant.AskRecordIntentCardPayload
import com.example.domain.model.ai.assistant.DateMismatchGuardCardPayload
import com.example.domain.model.ai.assistant.DebugChoiceCardPayload
import com.example.domain.model.ai.assistant.DebugChoiceOption
import com.example.domain.model.ai.assistant.ShowConfirmCardPayload
import com.example.ui.components.ai.AskMissingInfoCard
import com.example.ui.components.ai.AskRecordIntentCard
import com.example.ui.components.ai.DebugChoiceCard
import com.example.ui.components.ai.FoodDraftConfirmCard
import com.example.ui.theme.BrandGreen
import com.example.ui.theme.CardBackground
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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
                RenderShowConfirmCard(card = card, actionHandler = actionHandler)
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

        is DateMismatchGuardCardPayload -> {
            CardSpacer()
            DateMismatchGuardCard(card = card, actionHandler = actionHandler)
        }

        else -> Unit
    }
}

@Composable
private fun RenderShowConfirmCard(
    card: ShowConfirmCardPayload,
    actionHandler: AiRecordActionHandler
) {
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
}

@Composable
private fun DateMismatchGuardCard(
    card: DateMismatchGuardCardPayload,
    actionHandler: AiRecordActionHandler
) {
    when (card.state) {
        "approved" -> RenderShowConfirmCard(card = card.pendingOriginalCard, actionHandler = actionHandler)
        "cancelled" -> DateMismatchTerminalCard("已取消，本次内容未记录")
        else -> DateMismatchPendingCard(card = card, actionHandler = actionHandler)
    }
}

@Composable
private fun DateMismatchPendingCard(
    card: DateMismatchGuardCardPayload,
    actionHandler: AiRecordActionHandler
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = CardBackground,
        border = BorderStroke(1.dp, BrandGreen.copy(alpha = 0.18f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "正在记录到 ${card.conversationDate.formatGuardDate()}",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Text(
                text = "当前日期是 ${card.detectedCurrentDate.formatGuardDate()}。这条内容将保存到该会话所属的 ${card.conversationDate.formatGuardDate()}，是否继续？",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
            ) {
                TextButton(onClick = { actionHandler.handleDateMismatchGuardResult(card.id, approved = false) }) {
                    Text("取消")
                }
                Button(
                    onClick = { actionHandler.handleDateMismatchGuardResult(card.id, approved = true) },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
                ) {
                    Text("继续记录")
                }
            }
        }
    }
}

@Composable
private fun DateMismatchTerminalCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CardBackground,
        border = BorderStroke(1.dp, BrandGreen.copy(alpha = 0.12f))
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

private fun LocalDate.formatGuardDate(): String {
    return DateTimeFormatter.ofPattern("M月d日", Locale.CHINA).format(this)
}

@Composable
private fun CardSpacer() {
    Spacer(modifier = Modifier.height(8.dp))
}
