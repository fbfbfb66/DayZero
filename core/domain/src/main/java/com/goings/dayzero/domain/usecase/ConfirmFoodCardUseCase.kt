package com.goings.dayzero.domain.usecase

import com.goings.dayzero.domain.model.ai.assistant.PayloadSummary
import com.goings.dayzero.domain.repository.ConfirmFoodCardResult
import com.goings.dayzero.domain.repository.FoodCardConfirmationRepository

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
