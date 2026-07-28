package com.goings.dayzero.domain.model.ai.assistant

/** Defensive normalization for photo ownership on one confirmation card. */
object ConfirmCardPhotoAssignments {
    fun normalize(
        meals: List<ConfirmCardMeal>,
        allowedSourceMediaIds: List<String>,
        applySingleMealDefault: Boolean = true
    ): List<ConfirmCardMeal> {
        val allowed = allowedSourceMediaIds
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val allowedSet = allowed.toSet()
        val hasAnyExplicitAssignment = meals.any { it.sourceMediaIds != null }
        val defaultIds = if (
            applySingleMealDefault && meals.size == 1 && !hasAnyExplicitAssignment && allowed.size in 1..6
        ) allowed else null
        val claimed = mutableSetOf<String>()

        return meals.map { meal ->
            val raw = meal.sourceMediaIds ?: defaultIds
            if (raw == null) {
                meal
            } else {
                val normalized = raw.map(String::trim).filter { id ->
                    id.isNotEmpty() && id in allowedSet && claimed.add(id)
                }
                meal.copy(sourceMediaIds = normalized)
            }
        }
    }
}

/**
 * Applies [ConfirmCardPhotoAssignments.normalize] to every food confirmation card in a card list,
 * including a card wrapped inside a local-only [DateMismatchGuardCardPayload].
 *
 * This is the single source of truth for client-side photo ownership normalization and must be
 * called on both the vision-turn finalize path and the interaction_result finalize path so the
 * two produce identical `sourceMediaIds`. Normalization must run before the assistant card is
 * persisted to Room. [allowedSourceMediaIds] must originate from a persisted image user message /
 * PreparedVisionRequest — never from the live UI draft or a guess over the whole conversation.
 */
fun List<AiChatCard>.normalizeCardPhotoAssignments(
    allowedSourceMediaIds: List<String>
): List<AiChatCard> = map { card ->
    when (card) {
        is ShowConfirmCardPayload -> card.copy(
            meals = card.meals?.let { meals ->
                ConfirmCardPhotoAssignments.normalize(meals, allowedSourceMediaIds)
            }
        )
        is DateMismatchGuardCardPayload -> card.copy(
            pendingOriginalCard = card.pendingOriginalCard.copy(
                meals = card.pendingOriginalCard.meals?.let { meals ->
                    ConfirmCardPhotoAssignments.normalize(meals, allowedSourceMediaIds)
                }
            )
        )
        else -> card
    }
}

/**
 * Final assistant card sanitizer for protocol-level mutually-exclusive cards.
 *
 * A food confirmation card is the terminal business card for the turn. Once it
 * exists, earlier ask cards are stale and must not be persisted beside it. If a
 * model returns multiple pre-answer cards without a confirmation card, keep one
 * deterministic ask so the user is not asked conflicting questions.
 */
fun List<AiChatCard>.sanitizeFinalAssistantCards(): List<AiChatCard> {
    if (isEmpty()) return this

    val hasConfirmCard = any { it.isConfirmTerminalCard() }
    if (hasConfirmCard) {
        return filterNot { it.isPreAnswerInteractionCard() }
    }

    val preferredAsk = firstOrNull { it is AskMissingInfoCardPayload }
        ?: firstOrNull { it is AskRecordIntentCardPayload }
        ?: firstOrNull { it is DebugChoiceCardPayload }
        ?: return this
    var keptPreferredAsk = false

    return filter { card ->
        if (!card.isPreAnswerInteractionCard()) {
            true
        } else if (card === preferredAsk && !keptPreferredAsk) {
            keptPreferredAsk = true
            true
        } else {
            false
        }
    }
}

private fun AiChatCard.isConfirmTerminalCard(): Boolean =
    this is ShowConfirmCardPayload || this is DateMismatchGuardCardPayload

private fun AiChatCard.isPreAnswerInteractionCard(): Boolean =
    this is AskMissingInfoCardPayload ||
        this is AskRecordIntentCardPayload ||
        this is DebugChoiceCardPayload
