package com.example.domain.usecase

import com.example.domain.repository.FoodCardPhotoAssignmentRepository
import com.example.domain.repository.MealPhotoAssignment
import com.example.domain.repository.UpdateFoodCardPhotoAssignmentsResult

class UpdateFoodCardPhotoAssignmentsUseCase(
    private val repository: FoodCardPhotoAssignmentRepository
) {
    suspend operator fun invoke(
        cardId: String,
        assignments: List<MealPhotoAssignment>
    ): UpdateFoodCardPhotoAssignmentsResult = repository.updatePhotoAssignments(cardId, assignments)
}
