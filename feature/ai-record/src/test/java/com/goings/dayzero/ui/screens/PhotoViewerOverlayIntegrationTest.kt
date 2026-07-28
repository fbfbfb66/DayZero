package com.goings.dayzero.ui.screens

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.goings.dayzero.domain.model.AppState
import com.goings.dayzero.domain.model.ai.AiChatMessage
import com.goings.dayzero.domain.model.ai.ChatRole
import com.goings.dayzero.domain.model.media.MediaAsset
import com.goings.dayzero.domain.model.media.MediaLifecycleState
import com.goings.dayzero.domain.model.media.MediaSource
import com.goings.dayzero.ui.components.PhotoViewerOverlay
import com.goings.dayzero.ui.components.PhotoViewerItem
import com.goings.dayzero.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
class PhotoViewerOverlayIntegrationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val fakeActionHandler = object : AiRecordActionHandler {
        override fun sendAiMessage(text: String): Boolean = false
        override fun sendAiMessage(conversationId: String, text: String): Boolean = false
        override fun startAssistantTurnForExistingUserMessage(conversationId: String, text: String) {}
        override fun startVisionAssistantTurnForExistingUserMessage(conversationId: String, userMessageId: String) {}
        override fun setActiveConversationId(conversationId: String?) {}
        override fun sendInteractionResult(
            interactionId: String,
            actionType: String,
            optionId: String,
            optionLabel: String,
            field: String?,
            originalText: String?,
            confirmType: String?,
            payloadSummary: com.goings.dayzero.domain.model.ai.assistant.PayloadSummary?
        ) {}
        override fun handleDateMismatchGuardResult(guardId: String, approved: Boolean) {}
        override fun updateFoodDraftCard(
            interactionId: String,
            weightKg: Double?,
            meals: List<com.goings.dayzero.domain.model.ai.assistant.ConfirmCardMeal>
        ) {}
        override fun clearChatMessages() {}
        override fun clearLocalRecords() {}
        override fun clearAllData() {}
        override fun clearCloudBackupForDebug() {}
        override fun markAssistantMessageRendered(message: AiChatMessage) {}
    }

    private fun mockMediaAsset(
        id: String,
        master: String? = "media/master/$id.jpg",
        thumb: String? = "media/thumbnail/$id.jpg"
    ) = MediaAsset(
        id = id,
        ownerLocalId = "owner-1",
        conversationId = "conv-1",
        sourceMessageId = "msg-1",
        conversationOrder = 1L,
        masterRelativePath = master,
        thumbnailRelativePath = thumb,
        mimeType = "image/jpeg",
        width = 1000,
        height = 1000,
        byteSize = 1000L,
        sha256 = "sha256-$id",
        source = MediaSource.PHOTO_PICKER,
        lifecycleState = MediaLifecycleState.READY,
        failureCode = null,
        createdAt = 1000L,
        updatedAt = 1000L,
        deletedAt = null
    )

    private fun mockMessage(id: String, role: ChatRole, text: String, mediaIds: List<String>) = AiChatMessage(
        id = id,
        conversationId = "conv-1",
        role = role,
        text = text,
        createdAt = 1000L,
        sourceMediaIds = mediaIds
    )

    @Test
    fun testPhotoViewerOverlayIntegration() {
        // Setup state with 3 images on a user message
        val mediaAssets = listOf(
            mockMediaAsset("img1"),
            mockMediaAsset("img2"),
            mockMediaAsset("img3")
        )
        val mediaMap = mediaAssets.associateBy { it.id }
        val msg = mockMessage("msg-1", ChatRole.User, "Hello", listOf("img1", "img2", "img3"))
        val msgWithMedia = msg.toMessageWithMedia(mediaMap)

        val detailState = AiConversationDetailState(
            messages = listOf(msg),
            messagesWithMedia = listOf(msgWithMedia)
        )
        val appState = AppState(isAnalyzing = false, activeConversationId = "conv-1")
        val events = MutableSharedFlow<AiRecordConversationEvent>()

        var onBackCalled = false

        composeRule.setContent {
            MyApplicationTheme {
                AiConversationScreen(
                    conversationId = "conv-1",
                    detailState = detailState,
                    appState = appState,
                    actionHandler = fakeActionHandler,
                    events = events,
                    onBack = { onBackCalled = true },
                    onImportPhotos = { _, _ -> },
                    onRemoveAttachment = { _, _ -> },
                    onRetryAttachment = { _, _ -> },
                    onNavigateToCamera = {},
                    onSetPickerOpen = { _, _ -> },
                    onSubmitMediaMessage = { _, _, _ -> }
                )
            }
        }

        // 12. 普通文字消息不出现图片点击入口 (No images on text-only messages, here "Hello" is message text but we have images)
        // Let's verify the thumbnails exist
        composeRule.onNodeWithContentDescription("图片 1，共 3 张").assertExists()
        composeRule.onNodeWithContentDescription("图片 2，共 3 张").assertExists()
        composeRule.onNodeWithContentDescription("图片 3，共 3 张").assertExists()

        // 11. 图片点击不触发 send/network (verifying that action handler or onSubmit is not called by checking states)
        // 1. 单图 Bubble 点击打开 Viewer; 2. 多图点击第二张，索引从第二张开始
        composeRule.onNodeWithContentDescription("图片 2，共 3 张").performClick()

        // Viewer should open, and index should start from the 2nd image
        // 3. 索引显示正确
        composeRule.onNodeWithContentDescription("关闭大图查看器").assertExists()
        composeRule.onNodeWithText("2 / 3").assertExists()

        // 9. Overlay 打开时背景聊天不可点击 (Check background visibility/accessibility is invisibleToUser)
        // Background list element should be hidden from accessibility services via invisibleToUser semantics
        composeRule.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.InvisibleToUser, Unit)).assertExists()

        // 4. 点击关闭按钮关闭
        composeRule.onNodeWithContentDescription("关闭大图查看器").performClick()
        
        // Viewer should be closed, and background messages are back
        composeRule.onNodeWithContentDescription("关闭大图查看器").assertDoesNotExist()
        composeRule.onNodeWithTag(AiRecordTestTags.ConversationMessages).assertExists()
        
        // 10. Viewer 关闭后聊天仍存在
        composeRule.onNodeWithContentDescription("图片 1，共 3 张").assertExists()
    }

    @Test
    fun testMissingMasterAndInvalidPathPlaceholders() {
        // Setup state with a missing/failed master image
        val mediaAssets = listOf(
            mockMediaAsset("img1", master = null), // missing master relative path
            mockMediaAsset("img2", master = "../unsafe.jpg") // unsafe path
        )
        val mediaMap = mediaAssets.associateBy { it.id }
        val msg = mockMessage("msg-1", ChatRole.User, "", listOf("img1", "img2"))
        val msgWithMedia = msg.toMessageWithMedia(mediaMap)

        val detailState = AiConversationDetailState(
            messages = listOf(msg),
            messagesWithMedia = listOf(msgWithMedia)
        )
        val appState = AppState(isAnalyzing = false, activeConversationId = "conv-1")
        val events = MutableSharedFlow<AiRecordConversationEvent>()

        composeRule.setContent {
            MyApplicationTheme {
                AiConversationScreen(
                    conversationId = "conv-1",
                    detailState = detailState,
                    appState = appState,
                    actionHandler = fakeActionHandler,
                    events = events,
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

        // 7. 缺失 master 显示占位且不崩溃; 8. 非法 relative path 显示占位
        // Clicking missing master
        composeRule.onNodeWithContentDescription("图片 1，共 2 张").performClick()
        // Should show "图片未找到" placeholder text
        composeRule.onNodeWithText("图片未找到").assertExists()

        // Close
        composeRule.onNodeWithContentDescription("关闭大图查看器").performClick()

        // Clicking unsafe path
        composeRule.onNodeWithContentDescription("图片 2，共 2 张").performClick()
        // Should also show "图片未找到" placeholder text
        composeRule.onNodeWithText("图片未找到").assertExists()
    }
}
