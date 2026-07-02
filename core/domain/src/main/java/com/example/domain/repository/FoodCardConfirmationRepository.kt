package com.example.domain.repository

import com.example.domain.model.DailyRecord
import com.example.domain.model.ai.assistant.PayloadSummary

sealed interface ConfirmFoodCardResult {
    data class Confirmed(
        val record: DailyRecord,
        val conversationId: String,
        val messageId: String,
        val cardId: String
    ) : ConfirmFoodCardResult

    data object AlreadyConfirmed : ConfirmFoodCardResult
    data object Cancelled : ConfirmFoodCardResult
    data object CardNotFound : ConfirmFoodCardResult
    data class Failed(val cause: Throwable) : ConfirmFoodCardResult
}

interface FoodCardConfirmationRepository {
    suspend fun confirmFoodCard(
        cardId: String,
        payloadSummary: PayloadSummary?
    ): ConfirmFoodCardResult

    suspend fun cancelFoodCard(cardId: String): ConfirmFoodCardResult
}
