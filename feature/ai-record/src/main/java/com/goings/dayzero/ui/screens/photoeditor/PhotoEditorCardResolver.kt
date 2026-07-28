package com.goings.dayzero.ui.screens.photoeditor

import com.goings.dayzero.domain.model.ai.AiChatMessage
import com.goings.dayzero.domain.model.ai.ChatRole
import com.goings.dayzero.domain.model.ai.assistant.ConfirmCardMeal
import com.goings.dayzero.domain.model.ai.assistant.DateMismatchGuardCardPayload
import com.goings.dayzero.domain.model.ai.assistant.ShowConfirmCardPayload
import com.goings.dayzero.domain.model.ai.assistant.assistantPlaceholderId

/**
 * Live lookup of an editable food confirmation card inside a conversation's
 * message list, including a card nested in a date-mismatch guard.
 */
internal data class EditorCardLookup(
    val card: ShowConfirmCardPayload,
    val assistantMessageId: String,
    /** Guard state when the card is wrapped in a date-mismatch guard; null otherwise. */
    val guardState: String?
)

internal fun findEditorCard(messages: List<AiChatMessage>, cardId: String): EditorCardLookup? {
    messages.forEach { message ->
        message.assistantCards.forEach { card ->
            when {
                card is ShowConfirmCardPayload && card.id == cardId ->
                    return EditorCardLookup(card, message.id, guardState = null)

                card is DateMismatchGuardCardPayload && card.pendingOriginalCard.id == cardId ->
                    return EditorCardLookup(card.pendingOriginalCard, message.id, guardState = card.state)
            }
        }
    }
    return null
}

/**
 * A card's photos are editable only while the card itself is pending and, when
 * wrapped in a date-mismatch guard, only after the guard was approved. This
 * mirrors the authoritative check in RoomFoodCardPhotoAssignmentRepository.
 */
internal fun isCardPhotoEditable(lookup: EditorCardLookup): Boolean =
    lookup.card.state == "pending" &&
        (lookup.guardState == null || lookup.guardState == "approved")

/**
 * The only legal photo source for a card: the sourceMediaIds of the origin
 * image user message paired to the card's assistant message via the
 * deterministic placeholder id. Never guessed from the whole conversation,
 * the Compose draft, or other image messages.
 */
internal fun resolveOriginMediaIds(
    messages: List<AiChatMessage>,
    assistantMessageId: String
): List<String> =
    messages.firstOrNull { message ->
        message.role == ChatRole.User && assistantPlaceholderId(message.id) == assistantMessageId
    }?.sourceMediaIds.orEmpty()

internal fun mealDisplayLabel(meal: ConfirmCardMeal): String =
    meal.mealLabel ?: when (meal.mealType.lowercase()) {
        "breakfast" -> "早餐"
        "lunch" -> "午餐"
        "dinner" -> "晚餐"
        "snack" -> "加餐"
        else -> meal.mealType
    }
