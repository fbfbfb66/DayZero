package com.example.ui.screens.photoeditor

import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.ChatRole
import com.example.domain.model.ai.assistant.ConfirmCardItem
import com.example.domain.model.ai.assistant.ConfirmCardMeal
import com.example.domain.model.ai.assistant.ConfirmCardOption
import com.example.domain.model.ai.assistant.DateMismatchGuardCardPayload
import com.example.domain.model.ai.assistant.ShowConfirmCardPayload
import com.example.domain.model.ai.assistant.assistantPlaceholderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PhotoEditorCardResolverTest {

    private fun confirmCard(id: String = "card-1", state: String = "pending") = ShowConfirmCardPayload(
        id = id,
        confirmType = "food_record",
        title = "t",
        message = "m",
        originalText = null,
        mealType = null,
        items = emptyList(),
        meals = listOf(
            ConfirmCardMeal(
                "lunch", "午餐", 100,
                listOf(ConfirmCardItem(name = "rice", amountText = null, calories = 100, calorieConfidence = "medium"))
            )
        ),
        buttons = listOf(ConfirmCardOption("confirm", "确认")),
        state = state
    )

    private fun guard(state: String, inner: ShowConfirmCardPayload) = DateMismatchGuardCardPayload(
        id = "guard-1",
        conversationId = "conv",
        conversationDate = LocalDate.of(2026, 7, 8),
        detectedCurrentDate = LocalDate.of(2026, 7, 9),
        state = state,
        pendingOriginalCard = inner
    )

    private fun assistantMessage(id: String, vararg cards: com.example.domain.model.ai.assistant.AiChatCard) =
        AiChatMessage(id = id, conversationId = "conv", role = ChatRole.Assistant, text = "", assistantCards = cards.toList())

    @Test
    fun findsDirectCardAndGuardWrappedCard() {
        val direct = confirmCard("card-a")
        val wrapped = confirmCard("card-b")
        val messages = listOf(
            assistantMessage("msg-1", direct),
            assistantMessage("msg-2", guard("approved", wrapped))
        )
        val a = findEditorCard(messages, "card-a")!!
        assertEquals("msg-1", a.assistantMessageId)
        assertNull(a.guardState)

        val b = findEditorCard(messages, "card-b")!!
        assertEquals("msg-2", b.assistantMessageId)
        assertEquals("approved", b.guardState)

        assertNull(findEditorCard(messages, "missing"))
    }

    @Test
    fun editabilityFollowsCardAndGuardState() {
        assertTrue(isCardPhotoEditable(EditorCardLookup(confirmCard(), "m", null)))
        assertFalse(isCardPhotoEditable(EditorCardLookup(confirmCard(state = "confirmed"), "m", null)))
        assertFalse(isCardPhotoEditable(EditorCardLookup(confirmCard(state = "cancelled"), "m", null)))
        assertTrue(isCardPhotoEditable(EditorCardLookup(confirmCard(), "m", "approved")))
        assertFalse(isCardPhotoEditable(EditorCardLookup(confirmCard(), "m", "pending")))
        assertFalse(isCardPhotoEditable(EditorCardLookup(confirmCard(), "m", "cancelled")))
        assertFalse(isCardPhotoEditable(EditorCardLookup(confirmCard(state = "confirmed"), "m", "approved")))
    }

    @Test
    fun originIdsComeOnlyFromThePairedOriginUserMessage() {
        val userId = "user-1"
        val assistantId = assistantPlaceholderId(userId)
        val messages = listOf(
            // Another image message in the same conversation must never be used.
            AiChatMessage(id = "other-user", conversationId = "conv", role = ChatRole.User, text = "", sourceMediaIds = listOf("x1")),
            AiChatMessage(id = userId, conversationId = "conv", role = ChatRole.User, text = "", sourceMediaIds = listOf("m1", "m2")),
            assistantMessage(assistantId, confirmCard())
        )
        assertEquals(listOf("m1", "m2"), resolveOriginMediaIds(messages, assistantId))
        assertEquals(emptyList<String>(), resolveOriginMediaIds(messages, "unrelated-assistant"))
    }

    @Test
    fun mealLabelFallsBackToLocalizedType() {
        assertEquals("自定义", mealDisplayLabel(ConfirmCardMeal("dinner", "自定义", null, emptyList())))
        assertEquals("早餐", mealDisplayLabel(ConfirmCardMeal("breakfast", null, null, emptyList())))
        assertEquals("加餐", mealDisplayLabel(ConfirmCardMeal("SNACK", null, null, emptyList())))
        assertEquals("brunch", mealDisplayLabel(ConfirmCardMeal("brunch", null, null, emptyList())))
    }
}
