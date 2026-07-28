package com.goings.dayzero.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.goings.dayzero.domain.model.AppState
import com.goings.dayzero.ui.screens.photoeditor.PhotoAssignmentDraft
import com.goings.dayzero.ui.screens.photoeditor.PhotoAssignmentEditorActions
import com.goings.dayzero.ui.screens.photoeditor.PhotoAssignmentEditorUiState
import com.goings.dayzero.ui.screens.photoeditor.PhotoEditorTestTags
import com.goings.dayzero.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The editor must reuse the single existing PhotoViewerOverlay host of
 * AiConversationScreen — no second viewer — and the edit session must survive
 * the viewer opening and closing without any reordering or auto-save.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoEditorViewerReuseTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val recordedActions = mutableListOf<String>()

    private fun editorState(): PhotoAssignmentEditorUiState {
        val draft = PhotoAssignmentDraft(
            cardId = "card-1",
            mealCount = 1,
            originMediaIds = listOf("m1", "m2"),
            assignments = mapOf(0 to listOf("m1", "m2"))
        )
        return PhotoAssignmentEditorUiState(
            conversationId = "conv-1",
            cardId = "card-1",
            mealLabels = listOf("午餐"),
            selectedMealIndex = 0,
            initialDraft = draft,
            draft = draft
        )
    }

    private fun actions() = PhotoAssignmentEditorActions(
        onSelectMeal = { recordedActions += "select" },
        onAssignToSelectedMeal = { recordedActions += "assign" },
        onRemove = { recordedActions += "remove" },
        onMove = { _, _ -> recordedActions += "move" },
        onRequestClose = { recordedActions += "close" },
        onDismissDiscard = { recordedActions += "dismissDiscard" },
        onConfirmDiscard = { recordedActions += "confirmDiscard" },
        onSave = { recordedActions += "save" }
    )

    @Test
    fun editorWallClickOpensExistingViewerAndClosingKeepsEditState() {
        composeRule.setContent {
            MyApplicationTheme {
                AiConversationScreen(
                    conversationId = "conv-1",
                    detailState = AiConversationDetailState(),
                    appState = AppState(activeConversationId = "conv-1"),
                    actionHandler = NoOpCardActionHandler,
                    events = MutableSharedFlow(),
                    onBack = {},
                    onImportPhotos = { _, _ -> },
                    onRemoveAttachment = { _, _ -> },
                    onRetryAttachment = { _, _ -> },
                    onNavigateToCamera = {},
                    onSetPickerOpen = { _, _ -> },
                    onSubmitMediaMessage = { _, _, _ -> },
                    photoEditorState = editorState(),
                    photoEditorActions = actions()
                )
            }
        }

        composeRule.onNodeWithTag(PhotoEditorTestTags.Root).assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("关闭大图查看器").assertCountEquals(0)

        // Open the existing viewer from the second wall photo — accurate initialIndex.
        composeRule.onNodeWithTag(PhotoEditorTestTags.wallTile("m2"), useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("关闭大图查看器").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("当前处于第 2 张图片，共 2 张").assertIsDisplayed()

        // Close the viewer: the editor and its wall order are untouched, and no
        // save/assign/remove action was triggered by the viewer round trip.
        composeRule.onNodeWithContentDescription("关闭大图查看器").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithContentDescription("关闭大图查看器").assertCountEquals(0)
        composeRule.onNodeWithTag(PhotoEditorTestTags.Root).assertIsDisplayed()
        composeRule.onNodeWithTag(PhotoEditorTestTags.wallTile("m1"), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(PhotoEditorTestTags.wallTile("m2"), useUnmergedTree = true).assertIsDisplayed()
        assertEquals(emptyList<String>(), recordedActions)
    }

    @Test
    fun editorAbsentWhenNoSessionIsOpen() {
        composeRule.setContent {
            MyApplicationTheme {
                AiConversationScreen(
                    conversationId = "conv-1",
                    detailState = AiConversationDetailState(),
                    appState = AppState(activeConversationId = "conv-1"),
                    actionHandler = NoOpCardActionHandler,
                    events = MutableSharedFlow(),
                    onBack = {},
                    onImportPhotos = { _, _ -> },
                    onRemoveAttachment = { _, _ -> },
                    onRetryAttachment = { _, _ -> },
                    onNavigateToCamera = {},
                    onSetPickerOpen = { _, _ -> },
                    onSubmitMediaMessage = { _, _, _ -> }
                )
            }
        }
        composeRule.onAllNodesWithTag(PhotoEditorTestTags.Root).assertCountEquals(0)
    }
}
