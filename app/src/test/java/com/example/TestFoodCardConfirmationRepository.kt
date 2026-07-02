package com.example

import com.example.domain.model.RecordStatus
import com.example.domain.model.ai.assistant.DateMismatchGuardCardPayload
import com.example.domain.model.ai.assistant.PayloadSummary
import com.example.domain.model.ai.assistant.ShowConfirmCardPayload
import com.example.domain.repository.AiDraftRepository
import com.example.domain.repository.ConfirmFoodCardResult
import com.example.domain.repository.ConversationRepository
import com.example.domain.repository.FoodCardConfirmationRepository
import com.example.domain.repository.RecordRepository
import com.example.domain.usecase.ConfirmFoodCardUseCase
import com.example.domain.usecase.ConfirmFoodRecordMerger

fun testConfirmFoodCardUseCase(
    aiDraftRepository: AiDraftRepository,
    conversationRepository: ConversationRepository,
    recordRepository: RecordRepository
): ConfirmFoodCardUseCase {
    return ConfirmFoodCardUseCase(
        TestFoodCardConfirmationRepository(
            aiDraftRepository = aiDraftRepository,
            conversationRepository = conversationRepository,
            recordRepository = recordRepository
        )
    )
}

private class TestFoodCardConfirmationRepository(
    private val aiDraftRepository: AiDraftRepository,
    private val conversationRepository: ConversationRepository,
    private val recordRepository: RecordRepository
) : FoodCardConfirmationRepository {
    override suspend fun confirmFoodCard(
        cardId: String,
        payloadSummary: PayloadSummary?
    ): ConfirmFoodCardResult {
        val message = aiDraftRepository.findMessageByAssistantCardId(cardId)
            ?: return ConfirmFoodCardResult.CardNotFound
        val target = message.assistantCards.firstNotNullOfOrNull { card ->
            when {
                card is ShowConfirmCardPayload && card.id == cardId -> Target.Card(card)
                card is DateMismatchGuardCardPayload && card.pendingOriginalCard.id == cardId -> Target.Guard(card)
                else -> null
            }
        } ?: return ConfirmFoodCardResult.CardNotFound

        val currentCard = target.showCard
        if (target is Target.Guard && target.guard.state != "approved") return ConfirmFoodCardResult.Cancelled
        return when (currentCard.state) {
            "confirmed" -> ConfirmFoodCardResult.AlreadyConfirmed
            "cancelled" -> ConfirmFoodCardResult.Cancelled
            else -> {
                val conversation = conversationRepository.getConversationById(message.conversationId.orEmpty())
                    ?: return ConfirmFoodCardResult.CardNotFound
                val existing = recordRepository.getRecordByDateAndStatus(
                    conversation.conversationDate,
                    RecordStatus.Confirmed
                )
                val updatedRecord = ConfirmFoodRecordMerger.merge(
                    currentRecord = existing,
                    recordDate = conversation.conversationDate,
                    payloadSummary = payloadSummary
                )
                recordRepository.upsertRecord(updatedRecord)
                val updatedCards = message.assistantCards.map { card ->
                    when {
                        card is ShowConfirmCardPayload && card.id == cardId -> {
                            card.copy(
                                state = "confirmed",
                                resolved = true,
                                weightKg = payloadSummary?.weightKg,
                                meals = payloadSummary?.meals ?: card.meals
                            )
                        }
                        card is DateMismatchGuardCardPayload && card.pendingOriginalCard.id == cardId -> {
                            card.copy(
                                pendingOriginalCard = card.pendingOriginalCard.copy(
                                    state = "confirmed",
                                    resolved = true,
                                    weightKg = payloadSummary?.weightKg,
                                    meals = payloadSummary?.meals ?: card.pendingOriginalCard.meals
                                )
                            )
                        }
                        else -> card
                    }
                }
                aiDraftRepository.updateChatMessage(message.copy(assistantCards = updatedCards))
                ConfirmFoodCardResult.Confirmed(updatedRecord, message.conversationId.orEmpty(), message.id, cardId)
            }
        }
    }

    override suspend fun cancelFoodCard(cardId: String): ConfirmFoodCardResult {
        val message = aiDraftRepository.findMessageByAssistantCardId(cardId)
            ?: return ConfirmFoodCardResult.CardNotFound
        val target = message.assistantCards.firstNotNullOfOrNull { card ->
            when {
                card is ShowConfirmCardPayload && card.id == cardId -> Target.Card(card)
                card is DateMismatchGuardCardPayload && card.pendingOriginalCard.id == cardId -> Target.Guard(card)
                else -> null
            }
        } ?: return ConfirmFoodCardResult.CardNotFound
        val currentCard = target.showCard
        if (target is Target.Guard && target.guard.state != "approved") return ConfirmFoodCardResult.Cancelled
        if (currentCard.state == "confirmed") return ConfirmFoodCardResult.AlreadyConfirmed
        if (currentCard.state == "cancelled") return ConfirmFoodCardResult.Cancelled

        val updatedCards = message.assistantCards.map { card ->
            when {
                card is ShowConfirmCardPayload && card.id == cardId -> {
                    card.copy(state = "cancelled", resolved = true)
                }
                card is DateMismatchGuardCardPayload && card.pendingOriginalCard.id == cardId -> {
                    card.copy(
                        pendingOriginalCard = card.pendingOriginalCard.copy(
                            state = "cancelled",
                            resolved = true
                        )
                    )
                }
                else -> card
            }
        }
        aiDraftRepository.updateChatMessage(message.copy(assistantCards = updatedCards))
        return ConfirmFoodCardResult.Cancelled
    }

    private sealed interface Target {
        val showCard: ShowConfirmCardPayload

        data class Card(override val showCard: ShowConfirmCardPayload) : Target
        data class Guard(val guard: DateMismatchGuardCardPayload) : Target {
            override val showCard: ShowConfirmCardPayload = guard.pendingOriginalCard
        }
    }
}
