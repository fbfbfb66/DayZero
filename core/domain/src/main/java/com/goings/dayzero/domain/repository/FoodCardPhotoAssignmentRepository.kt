package com.goings.dayzero.domain.repository

data class MealPhotoAssignment(
    val mealIndex: Int,
    val orderedMediaIds: List<String>
)

sealed interface UpdateFoodCardPhotoAssignmentsResult {
    data object Updated : UpdateFoodCardPhotoAssignmentsResult
    data object Unchanged : UpdateFoodCardPhotoAssignmentsResult
    data object CardNotFound : UpdateFoodCardPhotoAssignmentsResult
    data object NotEditable : UpdateFoodCardPhotoAssignmentsResult
    data object InvalidAssignments : UpdateFoodCardPhotoAssignmentsResult
}

interface FoodCardPhotoAssignmentRepository {
    suspend fun updatePhotoAssignments(
        cardId: String,
        assignments: List<MealPhotoAssignment>
    ): UpdateFoodCardPhotoAssignmentsResult
}
