package com.example.ui.screens.photoeditor

/**
 * UI state of one photo assignment editing session. Held in the ViewModel so it
 * survives recomposition, viewer open/close and configuration changes. (Full
 * process-death restoration is an explicit non-goal of Phase 4B-1; after
 * process death the editor simply does not reopen and the real card is intact.)
 */
data class PhotoAssignmentEditorUiState(
    val conversationId: String,
    val cardId: String,
    val mealLabels: List<String>,
    val selectedMealIndex: Int,
    val initialDraft: PhotoAssignmentDraft,
    val draft: PhotoAssignmentDraft,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    /** Set when a save attempt found the card terminal/missing; only exit remains. */
    val cardNoLongerEditable: Boolean = false,
    val showDiscardDialog: Boolean = false
) {
    val isDirty: Boolean get() = draft.assignments != initialDraft.assignments
}
