package com.goings.dayzero.domain.usecase

import com.goings.dayzero.domain.repository.FoodCardPhotoAssignmentRepository
import com.goings.dayzero.domain.repository.MealPhotoAssignment
import com.goings.dayzero.domain.repository.UpdateFoodCardPhotoAssignmentsResult

class UpdateFoodCardPhotoAssignmentsUseCase(
    private val repository: FoodCardPhotoAssignmentRepository
) {
    suspend operator fun invoke(
        cardId: String,
        assignments: List<MealPhotoAssignment>
    ): UpdateFoodCardPhotoAssignmentsResult = repository.updatePhotoAssignments(cardId, assignments)
}
