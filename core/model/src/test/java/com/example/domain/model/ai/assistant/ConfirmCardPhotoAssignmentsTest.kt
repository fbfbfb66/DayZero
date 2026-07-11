package com.example.domain.model.ai.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfirmCardPhotoAssignmentsTest {
    private fun meal(ids: List<String>? = null) = ConfirmCardMeal("lunch", null, 1, emptyList(), ids)
    private fun confirm(id: String = "confirm") = ShowConfirmCardPayload(
        id = id,
        confirmType = "food_record",
        title = "confirm",
        message = "confirm",
        originalText = null,
        mealType = null,
        items = emptyList(),
        meals = listOf(meal()),
        buttons = listOf(ConfirmCardOption("confirm", "Confirm"), ConfirmCardOption("cancel", "Cancel"))
    )

    private fun askMissing(id: String = "ask-missing") = AskMissingInfoCardPayload(
        id = id,
        title = "missing",
        message = "missing",
        field = "mealType",
        originalText = "food",
        options = listOf(AskMissingInfoOption("lunch", "Lunch"))
    )

    private fun askRecord(id: String = "ask-record") = AskRecordIntentCardPayload(
        id = id,
        title = "record",
        message = "record",
        originalText = "food",
        options = listOf(AskRecordIntentOption("record", "Record"))
    )

    @Test fun nullAndExplicitEmptyRemainDistinct() {
        assertNull(ConfirmCardPhotoAssignments.normalize(listOf(meal()), emptyList()).single().sourceMediaIds)
        assertEquals(emptyList<String>(), ConfirmCardPhotoAssignments.normalize(listOf(meal(emptyList())), listOf("a")).single().sourceMediaIds)
    }

    @Test fun singleMealDefaultsToAllowedAttachmentOrder() {
        assertEquals(listOf("b", "a"), ConfirmCardPhotoAssignments.normalize(listOf(meal()), listOf("b", "a")).single().sourceMediaIds)
    }

    @Test fun multipleMealsNeverReceiveAnInventedDefault() {
        val result = ConfirmCardPhotoAssignments.normalize(listOf(meal(), meal()), listOf("a", "b"))
        assertNull(result[0].sourceMediaIds)
        assertNull(result[1].sourceMediaIds)
    }

    @Test fun rejectsBlankFictionAndCrossMealDuplicatesWhileKeepingCardOrder() {
        val result = ConfirmCardPhotoAssignments.normalize(
            listOf(meal(listOf("b", " ", "fake", "b")), meal(listOf("b", "a"))),
            listOf("a", "b"),
            applySingleMealDefault = false
        )
        assertEquals(listOf("b"), result[0].sourceMediaIds)
        assertEquals(listOf("a"), result[1].sourceMediaIds)
    }

    @Test fun sanitizerRemovesAskCardsWhenConfirmExistsRegardlessOfOrder() {
        assertEquals(
            listOf(confirm("c")),
            listOf(confirm("c"), askMissing()).sanitizeFinalAssistantCards()
        )
        assertEquals(
            listOf(confirm("c")),
            listOf(askMissing(), confirm("c"), askRecord()).sanitizeFinalAssistantCards()
        )
    }

    @Test fun sanitizerKeepsOnlyOneDeterministicAskWhenNoConfirmExists() {
        assertEquals(
            listOf(askMissing("m1")),
            listOf(askRecord("r1"), askMissing("m1"), askMissing("m2")).sanitizeFinalAssistantCards()
        )
        assertEquals(
            listOf(askRecord("r1")),
            listOf(askRecord("r1"), askRecord("r2")).sanitizeFinalAssistantCards()
        )
    }
}
