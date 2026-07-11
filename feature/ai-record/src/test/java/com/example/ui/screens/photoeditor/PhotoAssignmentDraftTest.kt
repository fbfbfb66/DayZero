package com.example.ui.screens.photoeditor

import com.example.domain.model.ai.assistant.ConfirmCardItem
import com.example.domain.model.ai.assistant.ConfirmCardMeal
import com.example.domain.repository.MealPhotoAssignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoAssignmentDraftTest {

    private fun meal(type: String, ids: List<String>?) = ConfirmCardMeal(
        mealType = type,
        mealLabel = null,
        subtotalCalories = 100,
        items = listOf(ConfirmCardItem(name = "food", amountText = null, calories = 100, calorieConfidence = "medium")),
        sourceMediaIds = ids
    )

    private val origin = listOf("m1", "m2", "m3", "m4")

    private fun draft(assignments: Map<Int, List<String>> = emptyMap(), mealCount: Int = 2) =
        PhotoAssignmentDraft("card", mealCount, origin, assignments)

    @Test
    fun fromMealsBuildsInitialAssignments() {
        val d = PhotoAssignmentDraft.fromMeals(
            "card",
            listOf(meal("breakfast", listOf("m2", "m1")), meal("lunch", listOf("m3"))),
            origin
        )
        assertEquals(mapOf(0 to listOf("m2", "m1"), 1 to listOf("m3")), d.assignments)
        assertEquals(listOf("m4"), d.unassignedIds)
        assertEquals(2, d.mealCount)
    }

    @Test
    fun fromMealsDropsForeignIdsAndCrossMealDuplicates() {
        val d = PhotoAssignmentDraft.fromMeals(
            "card",
            listOf(meal("breakfast", listOf("m1", "fake")), meal("lunch", listOf("m1", "m2"))),
            origin
        )
        assertEquals(mapOf(0 to listOf("m1"), 1 to listOf("m2")), d.assignments)
        assertTrue(d.isValid())
    }

    @Test
    fun unassignedKeepsOriginOrder() {
        val d = draft(mapOf(0 to listOf("m3")))
        assertEquals(listOf("m1", "m2", "m4"), d.unassignedIds)
    }

    @Test
    fun assignAppendsToSelectedMealPreservingOrder() {
        var d = draft(mapOf(0 to listOf("m2")))
        d = d.assignToMeal("m4", 0)
        d = d.assignToMeal("m1", 0)
        assertEquals(listOf("m2", "m4", "m1"), d.assignedTo(0))
        assertTrue(d.isValid())
    }

    @Test
    fun removeReturnsPhotoToUnassignedPool() {
        var d = draft(mapOf(0 to listOf("m1", "m2")))
        d = d.removeFromMeal("m1")
        assertEquals(listOf("m2"), d.assignedTo(0))
        assertTrue("m1" in d.unassignedIds)
    }

    @Test
    fun moveAcrossMealsRemovesFromSourceAndNeverDuplicates() {
        var d = draft(mapOf(0 to listOf("m1", "m2"), 1 to listOf("m3")))
        d = d.assignToMeal("m1", 1)
        assertEquals(listOf("m2"), d.assignedTo(0))
        assertEquals(listOf("m3", "m1"), d.assignedTo(1))
        val all = d.assignments.values.flatten()
        assertEquals(all.distinct().size, all.size)
        assertTrue(d.isValid())
    }

    @Test
    fun assignIsNoOpForUnknownIdInvalidMealOrSameMeal() {
        val d = draft(mapOf(0 to listOf("m1")))
        assertEquals(d, d.assignToMeal("fake", 0))
        assertEquals(d, d.assignToMeal("m2", 5))
        assertEquals(d, d.assignToMeal("m1", 0))
        assertEquals(d, d.removeFromMeal("m4"))
    }

    @Test
    fun validityRejectsForeignDuplicateAndOutOfRange() {
        assertFalse(draft(mapOf(0 to listOf("fake"))).isValid())
        assertFalse(draft(mapOf(0 to listOf("m1"), 1 to listOf("m1"))).isValid())
        assertFalse(draft(mapOf(7 to listOf("m1"))).isValid())
        assertTrue(draft(mapOf(0 to listOf("m1"), 1 to listOf("m2"))).isValid())
    }

    @Test
    fun toMealPhotoAssignmentsCoversEveryMealIndex() {
        val d = draft(mapOf(1 to listOf("m2", "m3")), mealCount = 3)
        assertEquals(
            listOf(
                MealPhotoAssignment(0, emptyList()),
                MealPhotoAssignment(1, listOf("m2", "m3")),
                MealPhotoAssignment(2, emptyList())
            ),
            d.toMealPhotoAssignments()
        )
    }

    @Test
    fun legalOriginSetRules() {
        assertTrue(PhotoAssignmentDraft.isLegalOriginSet(listOf("a")))
        assertTrue(PhotoAssignmentDraft.isLegalOriginSet(listOf("a", "b", "c", "d", "e", "f")))
        assertFalse(PhotoAssignmentDraft.isLegalOriginSet(emptyList()))
        assertFalse(PhotoAssignmentDraft.isLegalOriginSet(List(7) { "id$it" }))
        assertFalse(PhotoAssignmentDraft.isLegalOriginSet(listOf("a", "a")))
        assertFalse(PhotoAssignmentDraft.isLegalOriginSet(listOf("a", " ")))
    }
}
