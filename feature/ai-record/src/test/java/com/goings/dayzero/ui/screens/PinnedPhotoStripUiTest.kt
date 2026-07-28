package com.goings.dayzero.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.goings.dayzero.domain.model.ai.assistant.ConfirmCardItem
import com.goings.dayzero.domain.model.ai.assistant.ConfirmCardMeal
import com.goings.dayzero.domain.model.ai.assistant.ConfirmCardOption
import com.goings.dayzero.domain.model.ai.assistant.DateMismatchGuardCardPayload
import com.goings.dayzero.domain.model.ai.assistant.ShowConfirmCardPayload
import com.goings.dayzero.ui.components.PhotoViewerItem
import com.goings.dayzero.ui.components.PinnedPhotoStrip
import com.goings.dayzero.ui.components.PinnedPhotoStripTestTags
import com.goings.dayzero.ui.components.ai.FoodDraftConfirmCardTestTags
import com.goings.dayzero.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class PinnedPhotoStripUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun items(count: Int, missingIndices: Set<Int> = emptySet()): List<PhotoViewerItem> =
        (0 until count).map { i ->
            PhotoViewerItem(
                mediaId = "media-$i",
                masterRelativePath = null,
                thumbnailRelativePath = null,
                width = null,
                height = null,
                accessibilityLabel = if (i in missingIndices) {
                    "图片未找到 ${i + 1}，共 $count 张"
                } else {
                    "图片 ${i + 1}，共 $count 张"
                }
            )
        }

    @Test
    fun emptyListRendersNoShell() {
        composeRule.setContent {
            MyApplicationTheme {
                PinnedPhotoStrip(items = emptyList(), onPhotoClick = {}, onEditClick = {})
            }
        }
        composeRule.onAllNodesWithTag(PinnedPhotoStripTestTags.Strip).assertCountEquals(0)
        composeRule.onAllNodesWithTag(PinnedPhotoStripTestTags.EditEntry, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun oneToSixPhotosRenderStablyInOrder() {
        var count by mutableStateOf(1)
        composeRule.setContent {
            MyApplicationTheme {
                PinnedPhotoStrip(items = items(count), onPhotoClick = {})
            }
        }
        for (targetCount in 1..6) {
            count = targetCount
            composeRule.waitForIdle()
            for (i in 0 until targetCount) {
                // assertExists (not assertIsDisplayed): trailing photos may sit
                // beyond the horizontal-scroll viewport but must stay laid out.
                composeRule.onNodeWithTag(PinnedPhotoStripTestTags.photo(i), useUnmergedTree = true)
                    .assertExists()
            }
            composeRule.onAllNodesWithTag("pinned_photo_$targetCount", useUnmergedTree = true).assertCountEquals(0)
            composeRule.onNodeWithContentDescription("餐次照片，共 $targetCount 张", useUnmergedTree = true)
                .assertExists()
        }
    }

    @Test
    fun sixPhotosKeepOrderAndClickReportsExactIndex() {
        val clicked = mutableListOf<Int>()
        composeRule.setContent {
            MyApplicationTheme {
                PinnedPhotoStrip(items = items(6), onPhotoClick = { clicked += it })
            }
        }
        for (i in 0 until 6) {
            composeRule.onNodeWithContentDescription("图片 ${i + 1}，共 6 张", useUnmergedTree = true)
                .assertExists()
        }
        // Clicks on photos inside the horizontal-scroll viewport report their exact index.
        composeRule.onNodeWithTag(PinnedPhotoStripTestTags.photo(1), useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag(PinnedPhotoStripTestTags.photo(0), useUnmergedTree = true).performClick()
        assertEquals(listOf(1, 0), clicked)
    }

    @Test
    fun missingPhotoKeepsItsPositionWithSoftPlaceholderSemantics() {
        composeRule.setContent {
            MyApplicationTheme {
                PinnedPhotoStrip(items = items(3, missingIndices = setOf(1)), onPhotoClick = {})
            }
        }
        composeRule.onNodeWithContentDescription("图片未找到 2，共 3 张", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(PinnedPhotoStripTestTags.photo(1), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(PinnedPhotoStripTestTags.photo(2), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun editEntryCombinesCountAndIsAbsentWithoutCallback() {
        var edits = 0
        composeRule.setContent {
            MyApplicationTheme {
                PinnedPhotoStrip(items = items(3), onPhotoClick = {}, onEditClick = { edits += 1 })
            }
        }
        composeRule.onNodeWithContentDescription("整理餐次照片，共 3 张", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(PinnedPhotoStripTestTags.EditEntry, useUnmergedTree = true).performClick()
        assertEquals(1, edits)
    }

    @Test
    fun withoutEditEntryCountIsWeakTextOnly() {
        composeRule.setContent {
            MyApplicationTheme {
                PinnedPhotoStrip(items = items(2), onPhotoClick = {})
            }
        }
        composeRule.onAllNodesWithTag(PinnedPhotoStripTestTags.EditEntry, useUnmergedTree = true).assertCountEquals(0)
        composeRule.onNodeWithContentDescription("餐次照片，共 2 张", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun recompositionKeepsStripStableWithoutReplayingEntranceAnimation() {
        var tick by mutableStateOf(0)
        val clicked = mutableListOf<Int>()
        composeRule.setContent {
            MyApplicationTheme {
                // The unrelated state read forces recomposition of this scope.
                androidx.compose.material3.Text("tick $tick")
                PinnedPhotoStrip(items = items(3), onPhotoClick = { clicked += it })
            }
        }
        composeRule.onNodeWithTag(PinnedPhotoStripTestTags.photo(2), useUnmergedTree = true).assertIsDisplayed()
        tick = 1
        composeRule.waitForIdle()
        // After recomposition the photos are immediately present and clickable at
        // full size — there is no re-run of any entrance animation to wait out.
        composeRule.onNodeWithTag(PinnedPhotoStripTestTags.photo(2), useUnmergedTree = true).performClick()
        assertEquals(listOf(2), clicked)
    }

    // region Edit entry legality through the real AssistantCardRenderer

    private fun confirmCard(state: String = "pending") = ShowConfirmCardPayload(
        id = "card-1",
        confirmType = "food_record",
        title = "t",
        message = "m",
        originalText = null,
        mealType = null,
        items = emptyList(),
        meals = listOf(
            ConfirmCardMeal(
                "lunch", "午餐", 300,
                listOf(ConfirmCardItem(name = "rice", amountText = null, calories = 300, calorieConfidence = "medium")),
                sourceMediaIds = listOf("m1")
            )
        ),
        buttons = listOf(ConfirmCardOption("cancel", "取消"), ConfirmCardOption("confirm", "确认")),
        state = state
    )

    @Test
    fun pendingCardWithOriginPhotosShowsEntryAndReportsCardAndMeal() {
        val opened = mutableListOf<Pair<String, Int>>()
        composeRule.setContent {
            MyApplicationTheme {
                AssistantCardRenderer(
                    card = confirmCard(),
                    actionHandler = NoOpCardActionHandler,
                    originMediaIds = listOf("m1", "m2"),
                    onEditMealPhotos = { cardId, mealIndex -> opened += cardId to mealIndex }
                )
            }
        }
        composeRule.onNodeWithTag(PinnedPhotoStripTestTags.EditEntry, useUnmergedTree = true).performClick()
        assertEquals(listOf("card-1" to 0), opened)
    }

    @Test
    fun terminalCardsAndTextOnlyCardsShowNoEntry() {
        composeRule.setContent {
            MyApplicationTheme {
                androidx.compose.foundation.layout.Column {
                    AssistantCardRenderer(
                        card = confirmCard(state = "confirmed"),
                        actionHandler = NoOpCardActionHandler,
                        originMediaIds = listOf("m1"),
                        onEditMealPhotos = { _, _ -> }
                    )
                    AssistantCardRenderer(
                        card = confirmCard(state = "cancelled"),
                        actionHandler = NoOpCardActionHandler,
                        originMediaIds = listOf("m1"),
                        onEditMealPhotos = { _, _ -> }
                    )
                    // Text-only turn: no origin image ids.
                    AssistantCardRenderer(
                        card = confirmCard(),
                        actionHandler = NoOpCardActionHandler,
                        originMediaIds = emptyList(),
                        onEditMealPhotos = { _, _ -> }
                    )
                }
            }
        }
        composeRule.onAllNodesWithTag(PinnedPhotoStripTestTags.EditEntry, useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithTag(FoodDraftConfirmCardTestTags.CardLevelPhotoEditEntry, useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun approvedGuardShowsEntryPendingGuardDoesNot() {
        fun guard(state: String) = DateMismatchGuardCardPayload(
            id = "guard-1",
            conversationId = "conv",
            conversationDate = LocalDate.of(2026, 7, 8),
            detectedCurrentDate = LocalDate.of(2026, 7, 9),
            state = state,
            pendingOriginalCard = confirmCard()
        )

        var shown by mutableStateOf("approved")
        composeRule.setContent {
            MyApplicationTheme {
                AssistantCardRenderer(
                    card = guard(shown),
                    actionHandler = NoOpCardActionHandler,
                    originMediaIds = listOf("m1"),
                    onEditMealPhotos = { _, _ -> }
                )
            }
        }
        composeRule.onNodeWithTag(PinnedPhotoStripTestTags.EditEntry, useUnmergedTree = true).assertIsDisplayed()

        shown = "pending"
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(PinnedPhotoStripTestTags.EditEntry, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun cardWithAllPhotosUnassignedShowsSingleWeakCardLevelEntry() {
        val card = confirmCard().copy(
            meals = confirmCard().meals!!.map { it.copy(sourceMediaIds = emptyList()) }
        )
        val opened = mutableListOf<Pair<String, Int>>()
        composeRule.setContent {
            MyApplicationTheme {
                AssistantCardRenderer(
                    card = card,
                    actionHandler = NoOpCardActionHandler,
                    originMediaIds = listOf("m1", "m2", "m3"),
                    onEditMealPhotos = { cardId, mealIndex -> opened += cardId to mealIndex }
                )
            }
        }
        composeRule.onAllNodesWithTag(FoodDraftConfirmCardTestTags.PhotoStrip, useUnmergedTree = true).assertCountEquals(0)
        composeRule.onNodeWithContentDescription("整理照片 · 3 张", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(FoodDraftConfirmCardTestTags.CardLevelPhotoEditEntry, useUnmergedTree = true)
            .performClick()
        assertEquals(listOf("card-1" to 0), opened)
    }

    // endregion
}

internal object NoOpCardActionHandler : AiRecordActionHandler {
    override fun sendAiMessage(text: String) = true
    override fun sendAiMessage(conversationId: String, text: String) = true
    override fun startAssistantTurnForExistingUserMessage(conversationId: String, text: String) = Unit
    override fun startVisionAssistantTurnForExistingUserMessage(conversationId: String, userMessageId: String) = Unit
    override fun setActiveConversationId(conversationId: String?) = Unit
    override fun sendInteractionResult(
        interactionId: String,
        actionType: String,
        optionId: String,
        optionLabel: String,
        field: String?,
        originalText: String?,
        confirmType: String?,
        payloadSummary: com.goings.dayzero.domain.model.ai.assistant.PayloadSummary?
    ) = Unit

    override fun handleDateMismatchGuardResult(guardId: String, approved: Boolean) = Unit
    override fun updateFoodDraftCard(
        interactionId: String,
        weightKg: Double?,
        meals: List<ConfirmCardMeal>
    ) = Unit

    override fun clearChatMessages() = Unit
    override fun clearLocalRecords() = Unit
    override fun clearAllData() = Unit
    override fun clearCloudBackupForDebug() = Unit
    override fun markAssistantMessageRendered(message: com.goings.dayzero.domain.model.ai.AiChatMessage) = Unit
}
