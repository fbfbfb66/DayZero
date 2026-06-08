package com.example.domain.model.ai.assistant

enum class AiChoiceAction {
    Unknown,
    Cancel,
    Confirm,
    AddToMeal,
    ReplaceMeal,
    AddNonConflictingMeals,
    OverrideConflictingMeals,
    SetMealTypeBreakfast,
    SetMealTypeLunch,
    SetMealTypeDinner,
    SetMealTypeSnack,
    ConfirmWeight,
    ConfirmEdit,
    ConfirmDelete
}
