package com.example.domain.usecase

import com.example.domain.model.ai.assistant.PayloadSummary
import com.example.domain.repository.ConfirmFoodCardResult
import com.example.domain.repository.FoodCardConfirmationRepository

class ConfirmFoodCardUseCase(
    private val repository: FoodCardConfirmationRepository
) {
    suspend operator fun invoke(
        cardId: String,
        payloadSummary: PayloadSummary?
    ): ConfirmFoodCardResult {
        return try {
            repository.confirmFoodCard(cardId, payloadSummary)
        } catch (e: Throwable) {
            ConfirmFoodCardResult.Failed(e)
        }
    }

    suspend fun cancel(cardId: String): ConfirmFoodCardResult {
        return try {
            repository.cancelFoodCard(cardId)
        } catch (e: Throwable) {
            ConfirmFoodCardResult.Failed(e)
        }
    }
}
