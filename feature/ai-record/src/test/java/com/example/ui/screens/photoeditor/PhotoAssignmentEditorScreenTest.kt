package com.example.ui.screens.photoeditor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.ui.components.PhotoViewerItem
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhotoAssignmentEditorScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun state(
        mealLabels: List<String> = listOf("早餐", "午餐"),
        origin: List<String> = listOf("m1", "m2", "m3"),
        assignments: Map<Int, List<String>> = mapOf(0 to listOf("m1")),
        selectedMealIndex: Int = 0,
        isSaving: Boolean = false,
        saveError: String? = null,
        cardNoLongerEditable: Boolean = false,
        showDiscardDialog: Boolean = false
    ): PhotoAssignmentEditorUiState {
        val draft = PhotoAssignmentDraft("card-1", mealLabels.size, origin, assignments)
        return PhotoAssignmentEditorUiState(
            conversationId = "conv-1",
            cardId = "card-1",
            mealLabels = mealLabels,
            selectedMealIndex = selectedMealIndex,
            initialDraft = draft,
            draft = draft,
            isSaving = isSaving,
            saveError = saveError,
            cardNoLongerEditable = cardNoLongerEditable,
            showDiscardDialog = showDiscardDialog
        )
    }

    /** Minimal stateful stand-in for the ViewModel session so the UI loop can be exercised. */
    private class Harness(initial: PhotoAssignmentEditorUiState) {
        var current by mutableStateOf(initial)
        val events = mutableListOf<String>()
        val actions = PhotoAssignmentEditorActions(
            onSelectMeal = { index ->
                events += "select:$index"
                current = current.copy(selectedMealIndex = index)
            },
            onAssignToSelectedMeal = { id ->
                events += "assign:$id"
                current = current.copy(draft = current.draft.assignToMeal(id, current.selectedMealIndex))
            },
            onRemove = { id ->
                events += "remove:$id"
                current = current.copy(draft = current.draft.removeFromMeal(id))
            },
            onMove = { id, target ->
                events += "move:$id:$target"
                current = current.copy(draft = current.draft.assignToMeal(id, target))
            },
            onRequestClose = { events += "close" },
            onDismissDiscard = { events += "dismissDiscard" },
            onConfirmDiscard = { events += "confirmDiscard" },
            onSave = { events += "save" }
        )
    }

    private fun setContent(harness: Harness, onOpenViewer: (List<PhotoViewerItem>, Int) -> Unit = { _, _ -> }) {
        composeRule.setContent {
            MyApplicationTheme {
                PhotoAssignmentEditorScreen(
                    state = harness.current,
                    mediaById = emptyMap(),
                    actions = harness.actions,
                    onOpenViewer = onOpenViewer
                )
            }
        }
    }

    @Test
    fun rendersMealChipsWithCountsAndSelection() {
        val harness = Harness(state(assignments = mapOf(0 to listOf("m1", "m2"))))
        setContent(harness)

        composeRule.onNodeWithContentDescription("早餐，已分配 2 张照片，当前餐次").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("午餐，已分配 0 张照片").assertIsDisplayed()

        composeRule.onNodeWithTag(PhotoEditorTestTags.mealChip(1)).performClick()
        assertEquals("select:1", harness.events.single())
        composeRule.onNodeWithContentDescription("午餐，已分配 0 张照片，当前餐次").assertIsDisplayed()
    }

    @Test
    fun deckShowsUnassignedCountAndCenterTapAssignsToCurrentMeal() {
        val harness = Harness(state(origin = listOf("m1", "m2"), assignments = mapOf(0 to listOf("m1"))))
        setContent(harness)

        composeRule.onNodeWithContentDescription("未分配照片 1 张").assertIsDisplayed()
        composeRule.onNodeWithTag(PhotoEditorTestTags.deckCard("m2")).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("assign:m2"), harness.events)
        // Deck is now empty: restrained completion state appears, wall gains the photo.
        composeRule.onNodeWithTag(PhotoEditorTestTags.CompletionState).assertIsDisplayed()
        composeRule.onNodeWithTag(PhotoEditorTestTags.wallTile("m2"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun completionStateShownWhenAllAssigned() {
        val harness = Harness(state(origin = listOf("m1"), assignments = mapOf(0 to listOf("m1"))))
        setContent(harness)
        composeRule.onNodeWithTag(PhotoEditorTestTags.CompletionState).assertIsDisplayed()
        composeRule.onAllNodesWithTag(PhotoEditorTestTags.Deck).assertCountEquals(0)
    }

    @Test
    fun wallRemoveAndMoveUpdateDraftThroughActions() {
        val harness = Harness(state(assignments = mapOf(0 to listOf("m1", "m2"))))
        setContent(harness)

        composeRule.onNodeWithTag(PhotoEditorTestTags.wallRemove("m1"), useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("remove:m1"), harness.events)
        composeRule.onAllNodesWithTag(PhotoEditorTestTags.wallTile("m1"), useUnmergedTree = true).assertCountEquals(0)

        composeRule.onNodeWithTag(PhotoEditorTestTags.wallMove("m2"), useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag(PhotoEditorTestTags.wallMoveTarget("m2", 1), useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("remove:m1", "move:m2:1"), harness.events)
        // m2 left the breakfast wall (it moved to lunch, which is not selected).
        composeRule.onAllNodesWithTag(PhotoEditorTestTags.wallTile("m2"), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun cancelAndSaveInvokeActionsAndSavingDisablesSave() {
        val harness = Harness(state())
        setContent(harness)

        composeRule.onNodeWithTag(PhotoEditorTestTags.Cancel).performClick()
        composeRule.onNodeWithTag(PhotoEditorTestTags.Save).performClick()
        assertEquals(listOf("close", "save"), harness.events)

        harness.current = harness.current.copy(isSaving = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PhotoEditorTestTags.Save).assertIsNotEnabled()
    }

    @Test
    fun discardDialogConfirmsAndDismisses() {
        val harness = Harness(state(showDiscardDialog = true))
        setContent(harness)

        composeRule.onNodeWithTag(PhotoEditorTestTags.DiscardDialog).assertIsDisplayed()
        composeRule.onNodeWithText("继续整理").performClick()
        composeRule.onNodeWithText("放弃").performClick()
        assertEquals(listOf("dismissDiscard", "confirmDiscard"), harness.events)
    }

    @Test
    fun saveErrorShowsRetryAndTerminalErrorShowsExit() {
        val harness = Harness(state(saveError = "保存失败，请重试"))
        setContent(harness)

        composeRule.onNodeWithTag(PhotoEditorTestTags.ErrorBanner).assertIsDisplayed()
        composeRule.onNodeWithText("重试").performClick()
        assertEquals(listOf("save"), harness.events)

        harness.current = harness.current.copy(
            saveError = "这条记录已进入最终状态，照片无法再调整",
            cardNoLongerEditable = true
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithText("退出").performClick()
        assertTrue(harness.events.contains("confirmDiscard"))
    }

    @Test
    fun missingAssetsRenderPlaceholdersWithoutCrashing() {
        // mediaById is empty for every id, including a 6-photo deck + wall.
        val harness = Harness(
            state(
                origin = listOf("m1", "m2", "m3", "m4", "m5", "m6"),
                assignments = mapOf(0 to listOf("m1"), 1 to listOf("m2"))
            )
        )
        setContent(harness)
        composeRule.onNodeWithTag(PhotoEditorTestTags.Deck).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("未分配照片 4 张").assertIsDisplayed()
        composeRule.onNodeWithTag(PhotoEditorTestTags.wallTile("m1"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun wallTileClickOpensViewerWithAccurateIndex() {
        val opened = mutableListOf<Pair<List<PhotoViewerItem>, Int>>()
        val harness = Harness(state(assignments = mapOf(0 to listOf("m1", "m2"))))
        setContent(harness) { items, index -> opened += items to index }

        composeRule.onNodeWithTag(PhotoEditorTestTags.wallTile("m2"), useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        assertEquals(1, opened.size)
        assertEquals(1, opened.single().second)
        assertEquals(listOf("m1", "m2"), opened.single().first.map { it.mediaId })
    }
}
