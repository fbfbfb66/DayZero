package com.goings.dayzero.ui.screens

import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import com.goings.dayzero.ui.components.LocalMediaThumbnail
import com.goings.dayzero.ui.components.PhotoViewerOverlay
import com.goings.dayzero.ui.components.PhotoViewerItem
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goings.dayzero.domain.model.AppState
import com.goings.dayzero.domain.model.ai.AiChatMessage
import com.goings.dayzero.domain.model.ai.ChatRole
import com.goings.dayzero.domain.model.ai.Conversation
import com.goings.dayzero.domain.model.ai.assistant.ConfirmCardMeal
import com.goings.dayzero.domain.model.ai.assistant.PayloadSummary
import com.goings.dayzero.domain.ai.isVisionAssistantPlaceholder
import com.goings.dayzero.ui.screens.photoeditor.PhotoAssignmentEditorActions
import com.goings.dayzero.ui.screens.photoeditor.PhotoAssignmentEditorScreen
import com.goings.dayzero.ui.screens.photoeditor.PhotoAssignmentEditorUiState
import com.goings.dayzero.ui.screens.photoeditor.resolveOriginMediaIds
import com.goings.dayzero.ui.theme.BorderNormal
import com.goings.dayzero.ui.theme.BrandGreen
import com.goings.dayzero.ui.theme.CardBackground
import com.goings.dayzero.ui.theme.TextPrimary
import com.goings.dayzero.ui.theme.TextSecondary
import com.goings.dayzero.ui.theme.TextTertiary
import com.goings.dayzero.ui.theme.WarmBackground
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import java.util.Locale

object AiRecordTestTags {
    const val Home = "ai_home"
    const val HomeInput = "ai_home_input"
    const val HomeSend = "ai_home_send"
    const val EmptyHistory = "ai_empty_history"
    const val HistoryList = "ai_history_list"
    const val HistoryItemPrefix = "ai_history_item_"
    const val Conversation = "ai_conversation"
    const val ConversationBack = "ai_conversation_back"
    const val ConversationInput = "ai_conversation_input"
    const val ConversationSend = "ai_conversation_send"
    const val ConversationMessages = "ai_conversation_messages"
}

interface AiRecordActionHandler {
    fun sendAiMessage(text: String): Boolean

    fun sendAiMessage(conversationId: String, text: String): Boolean

    fun startAssistantTurnForExistingUserMessage(conversationId: String, text: String)

    fun startVisionAssistantTurnForExistingUserMessage(conversationId: String, userMessageId: String)

    fun setActiveConversationId(conversationId: String?)

    fun sendInteractionResult(
        interactionId: String,
        actionType: String,
        optionId: String,
        optionLabel: String,
        field: String? = null,
        originalText: String? = null,
        confirmType: String? = null,
        payloadSummary: PayloadSummary? = null
    )

    fun handleDateMismatchGuardResult(guardId: String, approved: Boolean)

    fun updateFoodDraftCard(interactionId: String, weightKg: Double?, meals: List<ConfirmCardMeal>)

    fun clearChatMessages()
    fun clearLocalRecords()
    fun clearAllData()
    fun clearCloudBackupForDebug()
    fun markAssistantMessageRendered(message: AiChatMessage)
    fun markAssistantCardFirstComposed(message: AiChatMessage) = Unit
}

@Composable
fun AiRecordHomeScreen(
    state: AiConversationHistoryState,
    isAnalyzing: Boolean,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenConversation: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmBackground)
            .testTag(AiRecordTestTags.Home)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 28.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "AI Record",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(14.dp))
                HomePromptBox(
                    text = state.homeInputText,
                    enabled = !state.isCreating && !isAnalyzing,
                    isBusy = state.isCreating || isAnalyzing,
                    errorMessage = state.errorMessage,
                    onTextChange = onInputChange,
                    onSubmit = onSubmit
                )
                if (isAnalyzing && !state.isCreating) {
                    Text(
                        text = "DayZero is still replying in the open conversation.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                    )
                }
            }

            item {
                Text(
                    text = "History",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (state.conversations.isEmpty() && !state.isLoading) {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(AiRecordTestTags.EmptyHistory),
                        shape = RoundedCornerShape(22.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, BorderNormal.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "Your conversations will appear here after you send a message.",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(18.dp)
                        )
                    }
                }
            } else {
                items(
                    items = state.conversations,
                    key = { it.id }
                ) { conversation ->
                    ConversationHistoryRow(
                        conversation = conversation,
                        onClick = { onOpenConversation(conversation.id) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AiConversationScreen(
    conversationId: String,
    detailState: AiConversationDetailState,
    appState: AppState,
    actionHandler: AiRecordActionHandler,
    events: SharedFlow<AiRecordConversationEvent>,
    onBack: () -> Unit,
    onImportPhotos: (String, List<String>) -> Unit,
    onRemoveAttachment: (String, String) -> Unit,
    onRetryAttachment: (String, String) -> Unit,
    onNavigateToCamera: (String) -> Unit,
    onSetPickerOpen: (String, Boolean) -> Unit,
    onSubmitMediaMessage: (String, String, List<String>) -> Unit,
    onClearDetailError: () -> Unit = {},
    photoEditorState: PhotoAssignmentEditorUiState? = null,
    photoEditorActions: PhotoAssignmentEditorActions? = null,
    onOpenPhotoEditor: (cardId: String, mealIndex: Int) -> Unit = { _, _ -> }
) {
    LaunchedEffect(conversationId) {
        actionHandler.setActiveConversationId(conversationId)
    }

    val context = LocalContext.current
    val capturedConversationId = remember(conversationId) { conversationId }
    var inputText by remember(conversationId) { mutableStateOf("") }
    var activePhotoViewerItems by remember(conversationId) { mutableStateOf<List<PhotoViewerItem>?>(null) }
    var activePhotoViewerInitialIndex by remember(conversationId) { mutableStateOf(0) }

    LaunchedEffect(events, conversationId) {
        events.collect { event ->
            if (event is AiRecordConversationEvent.MediaMessageCommitted && event.conversationId == conversationId) {
                inputText = ""
            }
        }
    }
    val messagesWithMedia = detailState.messagesWithMedia
    val messages = detailState.messages
    val conversationMediaById = remember(detailState.mediaAssets) { detailState.mediaAssets.associateBy { it.id } }
    val isCurrentConversationAnalyzing = appState.isAnalyzing && appState.activeConversationId == conversationId
    val hasAssistantPlaceholder = messages.lastOrNull()?.let { message ->
        message.role == ChatRole.Assistant && message.text.isBlank() && message.assistantCards.isEmpty()
    } == true

    val appFailure = appState.conversationState as? com.goings.dayzero.domain.model.ai.AiRecordConversationState.Error
    val retryUserMessageId = appFailure
        ?.takeIf { failure ->
            !isCurrentConversationAnalyzing &&
                hasAssistantPlaceholder &&
                failure.retryable &&
                failure.conversationId == conversationId &&
                failure.userMessageId != null
        }
        ?.userMessageId
        ?.takeIf { failedUserMessageId ->
            messages.any { message ->
                message.id == failedUserMessageId &&
                    message.role == ChatRole.User &&
                    message.sourceMediaIds.isNotEmpty()
            }
        }

    val listState = remember(conversationId) {
        val lastMsgIndex = (messages.size - 1).coerceAtLeast(0)
        val hasAnalyzingItem = isCurrentConversationAnalyzing && !hasAssistantPlaceholder
        val initialIndex = if (hasAnalyzingItem) messages.size else lastMsgIndex
        LazyListState(firstVisibleItemIndex = initialIndex)
    }

    val currentMessages by rememberUpdatedState(messages)
    var prevSize by remember(conversationId) { mutableStateOf(messages.size) }
    var prevAnalyzing by remember(conversationId) { mutableStateOf(isCurrentConversationAnalyzing) }
    var userInterrupted by remember(conversationId) { mutableStateOf(false) }
    var followActive by remember(conversationId) { mutableStateOf(false) }

    LaunchedEffect(isCurrentConversationAnalyzing) {
        if (isCurrentConversationAnalyzing) {
            followActive = true
        } else {
            // Keep follow active for a short duration to allow final cards/layout to render and scroll
            delay(800)
            followActive = false
        }
    }

    LaunchedEffect(listState.interactionSource) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                userInterrupted = true
            }
        }
    }

    LaunchedEffect(messages.size, isCurrentConversationAnalyzing) {
        if (currentMessages.isNotEmpty()) {
            val sizeChanged = currentMessages.size > prevSize
            val startedAnalyzing = isCurrentConversationAnalyzing && !prevAnalyzing

            if (sizeChanged || startedAnalyzing) {
                userInterrupted = false
                val lastItemIndex = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                listState.scrollToItem(lastItemIndex)
            }
        }
        prevSize = currentMessages.size
        prevAnalyzing = isCurrentConversationAnalyzing
    }

    LaunchedEffect(listState, followActive, userInterrupted) {
        if (followActive && !userInterrupted) {
            snapshotFlow { listState.layoutInfo }
                .collect { layoutInfo ->
                    val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                    if (lastVisibleItem != null) {
                        val lastItemIndex = (layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                        if (lastVisibleItem.index == lastItemIndex) {
                            val viewportEnd = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
                            val itemEnd = lastVisibleItem.offset + lastVisibleItem.size
                            val delta = itemEnd - viewportEnd
                            if (delta > 0) {
                                listState.animateScrollBy(
                                    value = delta.toFloat(),
                                    animationSpec = tween(durationMillis = 120, easing = LinearEasing)
                                )
                            }
                        } else if (lastVisibleItem.index < lastItemIndex) {
                            listState.scrollToItem(lastItemIndex)
                        }
                    }
                }
        }
    }

    var isPlusMenuOpen by remember { mutableStateOf(false) }

    BackHandler(enabled = isPlusMenuOpen) {
        isPlusMenuOpen = false
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 6)
    ) { uris ->
        onSetPickerOpen(capturedConversationId, false)
        if (uris.isNotEmpty()) {
            onImportPhotos(capturedConversationId, uris.map { it.toString() })
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmBackground)
            .testTag(AiRecordTestTags.Conversation)
    ) {
        val isViewerOpen = activePhotoViewerItems != null
        val isEditorOpen = photoEditorState != null
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    if (isViewerOpen || isEditorOpen) {
                        invisibleToUser()
                    }
                }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(AiRecordTestTags.ConversationMessages),
                contentPadding = PaddingValues(start = 16.dp, top = 92.dp, end = 16.dp, bottom = 116.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (messages.isEmpty() && !detailState.isSending) {
                    item {
                        AiMessage("Start chatting with DayZero.", 0L)
                    }
                }

                items(items = messagesWithMedia, key = { it.message.id }) { messageWithMedia ->
                    ChatMessageRow(
                        messageWithMedia = messageWithMedia,
                        allMessages = messages,
                        isAnalyzing = isCurrentConversationAnalyzing,
                        isLastMessage = messageWithMedia.message.id == messages.lastOrNull()?.id,
                        actionHandler = actionHandler,
                        mediaById = conversationMediaById,
                        onOpenPhotoEditor = onOpenPhotoEditor,
                        onMealPhotoClick = { items, clickedIndex ->
                            activePhotoViewerItems = items
                            activePhotoViewerInitialIndex = clickedIndex
                        },
                        onMediaClick = { clickedIndex ->
                            val items = messageWithMedia.media.mapIndexed { index, reference ->
                                when (reference) {
                                    is MessageMediaReference.LocalReady -> {
                                        PhotoViewerItem(
                                            mediaId = reference.mediaAsset.id,
                                            masterRelativePath = reference.mediaAsset.masterRelativePath,
                                            thumbnailRelativePath = reference.mediaAsset.thumbnailRelativePath,
                                            width = reference.mediaAsset.width,
                                            height = reference.mediaAsset.height,
                                            accessibilityLabel = "图片 ${index + 1}，共 ${messageWithMedia.media.size} 张"
                                        )
                                    }
                                    else -> {
                                        PhotoViewerItem(
                                            mediaId = "missing-$index",
                                            masterRelativePath = null,
                                            thumbnailRelativePath = null,
                                            width = null,
                                            height = null,
                                            accessibilityLabel = "图片未找到 ${index + 1}，共 ${messageWithMedia.media.size} 张"
                                        )
                                    }
                                }
                            }
                            activePhotoViewerItems = items
                            activePhotoViewerInitialIndex = clickedIndex
                        }
                    )
                }

                if (isCurrentConversationAnalyzing && !hasAssistantPlaceholder) {
                    item(key = "analyzing") {
                        AiMessageComponent {
                            TypingIndicator()
                        }
                    }
                }

                if (retryUserMessageId != null) {
                    item(key = "vision_retry") {
                        VisionRetryCard(
                            errorMessage = appFailure?.message ?: "图片识别失败",
                            onRetry = {
                                onClearDetailError()
                                actionHandler.startVisionAssistantTurnForExistingUserMessage(
                                    conversationId,
                                    retryUserMessageId
                                )
                            }
                        )
                    }
                }
            }

            ConversationTopBar(
                title = detailState.currentConversation?.title ?: "Conversation",
                subtitle = detailState.currentConversation?.let { formatConversationDateLabel(it.conversationDate) },
                onBack = onBack
            )

            if (isPlusMenuOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { isPlusMenuOpen = false }
                )
            }

            ConversationInputBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                inputText = inputText,
                enabled = !appState.isAnalyzing && !detailState.isSubmitting,
                isAnalyzing = isCurrentConversationAnalyzing,
                isSubmitting = detailState.isSubmitting,
                inputTestTag = AiRecordTestTags.ConversationInput,
                sendTestTag = AiRecordTestTags.ConversationSend,
                onInputChange = { inputText = it },
                detailState = detailState,
                isPlusMenuOpen = isPlusMenuOpen,
                onPlusMenuToggle = { isPlusMenuOpen = it },
                onTakePhoto = {
                    isPlusMenuOpen = false
                    onNavigateToCamera(capturedConversationId)
                },
                onSelectPhotos = {
                    isPlusMenuOpen = false
                    val draft = detailState.draftState
                    val currentCount = (draft?.attachmentIds?.size ?: 0) + (draft?.importingCount ?: 0)
                    if (currentCount >= 6) {
                        Toast.makeText(context, "最多只能添加6张图片", Toast.LENGTH_SHORT).show()
                    } else {
                        onSetPickerOpen(capturedConversationId, true)
                        pickerLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }
                },
                onRemoveAttachment = { mediaId ->
                    onRemoveAttachment(capturedConversationId, mediaId)
                },
                onRetryAttachment = { mediaId ->
                    onRetryAttachment(capturedConversationId, mediaId)
                },
                onSubmit = {
                    val draft = detailState.draftState
                    val targetConversationId = conversationId
                    val currentText = inputText
                    val orderedAttachmentIds = draft?.attachmentIds.orEmpty()
                    val importingCount = draft?.importingCount ?: 0

                    when {
                        orderedAttachmentIds.isEmpty() && currentText.isBlank() -> {
                            // Nothing to send; ignore.
                        }

                        orderedAttachmentIds.isNotEmpty() && importingCount > 0 -> {
                            Toast.makeText(context, "图片仍在导入中，请稍后再试", Toast.LENGTH_SHORT).show()
                        }

                        orderedAttachmentIds.isNotEmpty() -> {
                            onSubmitMediaMessage(targetConversationId, currentText, orderedAttachmentIds)
                        }

                        else -> {
                            if (currentText.isNotBlank()) {
                                val accepted = actionHandler.sendAiMessage(targetConversationId, currentText)
                                if (accepted) inputText = ""
                            }
                        }
                    }
                }
            )
        }

        if (photoEditorState != null && photoEditorActions != null) {
            PhotoAssignmentEditorScreen(
                state = photoEditorState,
                mediaById = conversationMediaById,
                actions = photoEditorActions,
                onOpenViewer = { items, index ->
                    activePhotoViewerItems = items
                    activePhotoViewerInitialIndex = index
                },
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        if (isViewerOpen) {
                            invisibleToUser()
                        }
                    }
                    .pointerInput(Unit) {}
            )
        }

        if (activePhotoViewerItems != null) {
            PhotoViewerOverlay(
                items = activePhotoViewerItems!!,
                initialIndex = activePhotoViewerInitialIndex,
                onDismiss = { activePhotoViewerItems = null },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {}
            )
        }
    }
}

@Composable
private fun HomePromptBox(
    text: String,
    enabled: Boolean,
    isBusy: Boolean,
    errorMessage: String?,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = Color.White,
        border = BorderStroke(1.dp, BorderNormal.copy(alpha = 0.5f)),
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 10.dp, bottom = 10.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 92.dp, max = 180.dp)
                    .testTag(AiRecordTestTags.HomeInput),
                placeholder = { Text("Tell DayZero what happened...", color = TextSecondary) },
                enabled = enabled,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = errorMessage.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (text.isNotBlank() && enabled) BrandGreen else BrandGreen.copy(alpha = 0.3f))
                        .clickable(enabled = text.isNotBlank() && enabled && !isBusy) { onSubmit() }
                        .testTag(AiRecordTestTags.HomeSend),
                    contentAlignment = Alignment.Center
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationHistoryRow(
    conversation: Conversation,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .testTag(AiRecordTestTags.HistoryItemPrefix + conversation.id),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, BorderNormal.copy(alpha = 0.45f)),
        shadowElevation = 0.dp
    ) {
        Text(
            text = conversation.title.ifBlank { "Conversation" },
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        )
    }
}

@Composable
private fun ConversationTopBar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alignTopBar(),
        color = WarmBackground.copy(alpha = 0.97f),
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .height(54.dp)
                .padding(start = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag(AiRecordTestTags.ConversationBack)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatMessageRow(
    messageWithMedia: MessageWithMedia,
    allMessages: List<AiChatMessage>,
    isAnalyzing: Boolean,
    isLastMessage: Boolean,
    actionHandler: AiRecordActionHandler,
    mediaById: Map<String, com.goings.dayzero.domain.model.media.MediaAsset>,
    onOpenPhotoEditor: (cardId: String, mealIndex: Int) -> Unit,
    onMealPhotoClick: (List<PhotoViewerItem>, Int) -> Unit,
    onMediaClick: (Int) -> Unit
) {
    val message = messageWithMedia.message
    if (message.role == ChatRole.User) {
        UserMessage(
            text = message.text,
            createdAt = message.createdAt,
            media = messageWithMedia.media,
            onMediaClick = onMediaClick
        )
    } else {
        LaunchedEffect(message.id, message.text, message.assistantCards.map { it.id }) {
            if (message.text.isNotBlank() || message.assistantCards.isNotEmpty()) {
                if (message.assistantCards.isNotEmpty()) {
                    actionHandler.markAssistantCardFirstComposed(message)
                } else {
                    actionHandler.markAssistantMessageRendered(message)
                }
            }
        }
        val isVisionPlaceholder = com.goings.dayzero.domain.ai.isVisionAssistantPlaceholder(message, allMessages)
        Column {
            if (message.text.isNotBlank()) {
                AiMessage(message.text, message.createdAt)
            } else if (isAnalyzing && isLastMessage && message.assistantCards.isEmpty()) {
                // Only show the typing/recognizing indicator while a turn is actively in
                // progress. A leftover empty assistant placeholder (e.g. after a stream +
                // fallback both fail) must not display a permanent spinner.
                if (isVisionPlaceholder) {
                    VisionImageRecognizingIndicator()
                } else {
                    AiMessageComponent {
                        TypingIndicator()
                    }
                }
            }
            val originMediaIds = remember(message.id, allMessages) {
                resolveOriginMediaIds(allMessages, message.id)
            }
            message.assistantCards.forEach { card ->
                AssistantCardRenderer(
                    card = card,
                    actionHandler = actionHandler,
                    mediaById = mediaById,
                    onMealPhotoClick = onMealPhotoClick,
                    originMediaIds = originMediaIds,
                    onEditMealPhotos = onOpenPhotoEditor
                )
            }
        }
    }
}


@Composable
private fun VisionRetryCard(
    errorMessage: String,
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFFDE8E8),
        border = BorderStroke(1.dp, Color(0xFFE53E3E).copy(alpha = 0.5f)),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = errorMessage,
                color = Color(0xFFE53E3E),
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onRetry() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Retry",
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "重试识别",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConversationInputBar(
    modifier: Modifier = Modifier,
    inputText: String,
    enabled: Boolean,
    isAnalyzing: Boolean,
    isSubmitting: Boolean = false,
    inputTestTag: String,
    sendTestTag: String,
    onInputChange: (String) -> Unit,
    detailState: AiConversationDetailState,
    isPlusMenuOpen: Boolean,
    onPlusMenuToggle: (Boolean) -> Unit,
    onTakePhoto: () -> Unit,
    onSelectPhotos: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRetryAttachment: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val draft = detailState.draftState
    val isPlusDisabled = !enabled || (draft != null && draft.importingCount > 0)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(bottom = 8.dp)
    ) {
        AnimatedVisibility(
            visible = isPlusMenuOpen,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            PlusMenuCard(
                onTakePhoto = onTakePhoto,
                onSelectPhotos = onSelectPhotos
            )
        }

        val showDraftBar = draft != null && (draft.attachmentIds.isNotEmpty() || draft.importingCount > 0)
        AnimatedVisibility(
            visible = showDraftBar,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            if (draft != null) {
                AttachmentDraftBar(
                    draft = draft,
                    onRemove = onRemoveAttachment,
                    onRetry = onRetryAttachment
                )
            }
        }

        var isFocused by remember { mutableStateOf(false) }
        val imeTargetBottom = WindowInsets.imeAnimationTarget.getBottom(LocalDensity.current)
        val isSeparated = isFocused || (imeTargetBottom > 0) || inputText.isNotEmpty()

        val focusManager = LocalFocusManager.current
        LaunchedEffect(imeTargetBottom) {
            if (imeTargetBottom == 0 && isFocused) {
                focusManager.clearFocus()
            }
        }

        val transition = updateTransition(targetState = isSeparated, label = "InputState")
        val plusBgAlpha by transition.animateFloat(
            transitionSpec = { spring(stiffness = Spring.StiffnessMedium, dampingRatio = 1f) },
            label = "plusBgAlpha"
        ) { separated -> if (separated) 1f else 0f }
        val plusBorderColor by transition.animateColor(
            transitionSpec = { spring(stiffness = Spring.StiffnessMedium, dampingRatio = 1f) },
            label = "plusBorderColor"
        ) { separated -> if (separated) BorderNormal.copy(alpha = 0.5f) else Color.Transparent }
        val plusOffset by transition.animateDp(
            transitionSpec = { spring(stiffness = 600f, dampingRatio = 0.6f) },
            label = "plusOffset"
        ) { separated -> if (separated) 0.dp else 4.dp }
        val plusScale by transition.animateFloat(
            transitionSpec = { spring(stiffness = 600f, dampingRatio = 0.6f) },
            label = "plusScale"
        ) { separated -> if (separated) 1f else 0.9f }
        val textFieldPaddingStart by transition.animateDp(
            transitionSpec = { spring(stiffness = 600f, dampingRatio = 0.6f) },
            label = "textFieldPaddingStart"
        ) { separated -> if (separated) 56.dp else 0.dp }
        val innerGap by transition.animateDp(
            transitionSpec = { spring(stiffness = 600f, dampingRatio = 0.6f) },
            label = "innerGap"
        ) { separated -> if (separated) 0.dp else 48.dp }

        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = plusOffset)
                    .padding(bottom = 6.dp)
                    .size(44.dp)
                    .graphicsLayer {
                        scaleX = plusScale
                        scaleY = plusScale
                    }
                    .background(Color.White.copy(alpha = plusBgAlpha), CircleShape)
                    .border(1.dp, plusBorderColor, CircleShape)
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = textFieldPaddingStart.coerceAtLeast(0.dp))
                    .heightIn(max = 220.dp)
                    .animateContentSize(),
                shape = RoundedCornerShape(26.dp),
                color = Color.White,
                border = BorderStroke(1.dp, BorderNormal.copy(alpha = 0.5f)),
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = innerGap.coerceAtLeast(0.dp), end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    val hasDraftAttachments = draft != null && (draft.attachmentIds.isNotEmpty() || draft.importingCount > 0)
                    val sendEnabled = (inputText.isNotBlank() || hasDraftAttachments) && enabled && !isSubmitting

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = onInputChange,
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { isFocused = it.isFocused }
                            .testTag(inputTestTag),
                        placeholder = { Text("Chat with DayZero...", color = TextSecondary) },
                        enabled = enabled,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent
                        )
                    )

                    Box(
                        modifier = Modifier
                            .padding(end = 2.dp, bottom = 6.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (sendEnabled) BrandGreen else BrandGreen.copy(alpha = 0.3f))
                            .clickable(enabled = sendEnabled) { onSubmit() }
                            .testTag(sendTestTag),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isAnalyzing || isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            val errorMessage = detailState.errorMessage
            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = plusOffset)
                    .padding(bottom = 6.dp)
                    .size(44.dp)
                    .graphicsLayer {
                        scaleX = plusScale
                        scaleY = plusScale
                    },
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = {
                        if (!isPlusDisabled) {
                            onPlusMenuToggle(!isPlusMenuOpen)
                        }
                    },
                    enabled = !isPlusDisabled
                ) {
                    val angle by androidx.compose.animation.core.animateFloatAsState(targetValue = if (isPlusMenuOpen) 45f else 0f)
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "More",
                        tint = if (isPlusDisabled) TextSecondary.copy(alpha = 0.3f) else TextSecondary,
                        modifier = Modifier.graphicsLayer { rotationZ = angle }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlusMenuCard(
    onTakePhoto: () -> Unit,
    onSelectPhotos: () -> Unit
) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth(0.5f),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFFAF7F2),
        border = BorderStroke(1.dp, BorderNormal.copy(alpha = 0.5f)),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTakePhoto() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = "Take Photo",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(12.dp))
                Text("拍照", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectPhotos() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.PhotoLibrary,
                    contentDescription = "Select Photos",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(12.dp))
                Text("从照片选择", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun AttachmentDraftBar(
    draft: ConversationAttachmentDraftState,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(
            items = draft.assets,
            key = { it.id }
        ) { asset ->
            Box(
                modifier = Modifier
                    .size(64.dp)
            ) {
                when (asset.lifecycleState) {
                    com.goings.dayzero.domain.model.media.MediaLifecycleState.READY -> {
                        LocalMediaThumbnail(
                            thumbnailRelativePath = asset.thumbnailRelativePath,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                        )
                    }
                    com.goings.dayzero.domain.model.media.MediaLifecycleState.STAGED -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFFF5F0EA), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = BrandGreen
                            )
                        }
                    }
                    com.goings.dayzero.domain.model.media.MediaLifecycleState.FAILED -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFFFDE8E8), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFFE53E3E), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(onClick = { onRetry(asset.id) }) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Retry",
                                    tint = Color(0xFFE53E3E)
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable { onRemove(asset.id) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }

        items(draft.importingCount) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(Color(0xFFF5F0EA), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = BrandGreen
                )
            }
        }
    }
}


@Composable
private fun AiMessageComponent(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .padding(end = 48.dp)
                .clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
                .background(CardBackground)
                .border(1.dp, BorderNormal, RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
                .padding(12.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val delays = listOf(0, 150, 300)

    Row(
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        delays.forEach { delay ->
            val yOffset by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -6f,
                animationSpec = infiniteRepeatable(
                    animation = tween(400, delayMillis = delay),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_$delay"
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(6.dp)
                    .graphicsLayer { translationY = yOffset }
                    .background(TextSecondary.copy(alpha = 0.4f), CircleShape)
            )
        }
    }
}

/**
 * Loading indicator shown while the assistant is recognizing images.
 *
 * Displays a low-opacity "识别图片" label with a narrow, low-saturation light beam
 * sweeping from left to right. No bubble background, no bouncing dots, and no
 * character displacement. When the system disables animations (reduced motion),
 * the text is rendered as static semi-transparent text.
 */
@Composable
private fun VisionImageRecognizingIndicator() {
    val context = LocalContext.current
    val animationsEnabled = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) > 0f
    }

    val text = "识别图片"
    val baseColor = TextSecondary.copy(alpha = 0.45f)
    val highlightColor = TextPrimary.copy(alpha = 0.78f)
    val beamColor = Color.White.copy(alpha = 0.55f)

    Box(
        modifier = Modifier
            .padding(vertical = 12.dp, horizontal = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "正在识别图片"
            }
    ) {
        Text(
            text = text,
            color = baseColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1
        )

        if (animationsEnabled) {
            val infiniteTransition = rememberInfiniteTransition(label = "vision_shimmer")
            val shimmerProgress by infiniteTransition.animateFloat(
                initialValue = -0.4f,
                targetValue = 1.4f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1100, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "vision_shimmer_progress"
            )

            Text(
                text = text,
                color = highlightColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier.drawWithContent {
                    val beamWidth = size.width * 0.22f
                    val beamStart = shimmerProgress * (size.width + beamWidth) - beamWidth
                    clipRect(left = beamStart, right = beamStart + beamWidth) {
                        this@drawWithContent.drawContent()
                    }
                    // Draw a narrow low-saturation light beam overlay for extra shimmer.
                    val beamCenter = beamStart + beamWidth / 2f
                    val gradientAlpha = 0.18f
                    drawRect(
                        color = beamColor,
                        topLeft = androidx.compose.ui.geometry.Offset(
                            beamCenter - beamWidth / 6f,
                            0f
                        ),
                        size = androidx.compose.ui.geometry.Size(
                            beamWidth / 3f,
                            size.height
                        ),
                        alpha = gradientAlpha
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserMessage(
    text: String,
    createdAt: Long,
    media: List<MessageMediaReference> = emptyList(),
    onMediaClick: (Int) -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        Box(
            modifier = Modifier
                .padding(start = 48.dp)
                .clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
                .background(BrandGreen)
                .padding(12.dp)
        ) {
            Column {
                if (media.isNotEmpty()) {
                    UserMessageMediaGrid(media = media, onMediaClick = onMediaClick)
                    if (text.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                if (text.isNotBlank()) {
                    Text(text, color = Color.White, fontSize = 15.sp)
                }
                if (createdAt > 0) {
                    Text(
                        text = formatTime(createdAt),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserMessageMediaGrid(
    media: List<MessageMediaReference>,
    onMediaClick: (Int) -> Unit
) {
    when (media.size) {
        1 -> UserMessageSingleMedia(media = media.single(), onClick = { onMediaClick(0) })
        2 -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.widthIn(max = 260.dp)
            ) {
                media.forEachIndexed { index, reference ->
                    UserMessageMediaItem(
                        reference = reference,
                        contentDescription = "图片 ${index + 1}，共 ${media.size} 张",
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clickable { onMediaClick(index) }
                    )
                }
            }
        }
        else -> {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                maxItemsInEachRow = 2,
                modifier = Modifier.widthIn(max = 260.dp)
            ) {
                media.forEachIndexed { index, reference ->
                    val fraction = if (media.size == 3 || media.size >= 5) 0.48f else 0.48f
                    UserMessageMediaItem(
                        reference = reference,
                        contentDescription = "图片 ${index + 1}，共 ${media.size} 张",
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .aspectRatio(1f)
                            .clickable { onMediaClick(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun UserMessageSingleMedia(media: MessageMediaReference, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .widthIn(max = 220.dp)
            .heightIn(max = 220.dp)
            .aspectRatio(1f)
            .clickable { onClick() }
    ) {
        UserMessageMediaItem(
            reference = media,
            contentDescription = "图片 1，共 1 张",
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun UserMessageMediaItem(
    reference: MessageMediaReference,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF4A7C59))
            .semantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when (reference) {
            is MessageMediaReference.LocalReady -> {
                LocalMediaThumbnail(
                    thumbnailRelativePath = reference.mediaAsset.thumbnailRelativePath,
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = contentDescription
                )
            }
            is MessageMediaReference.RemotePending -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CircularProgressIndicator(
                        color = Color.White.copy(alpha = 0.9f),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "图片下载中",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
            is MessageMediaReference.MissingLocalAsset -> {
                Text(
                    text = "图片暂未同步",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }
            is MessageMediaReference.MissingLocalFile -> {
                Text(
                    text = "图片不可用",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }
            is MessageMediaReference.InvalidReference -> {
                Text(
                    text = "图片引用无效",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun AiMessage(text: String, createdAt: Long) {
    AiMessageComponent {
        Column {
            Text(text, color = TextPrimary, fontSize = 15.sp)
            if (createdAt > 0) {
                Text(
                    text = formatTime(createdAt),
                    color = TextSecondary.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 2.dp)
                )
            }
        }
    }
}

private fun Modifier.alignTopBar(): Modifier = this

private fun formatTime(timestamp: Long): String {
    val instant = Instant.ofEpochMilli(timestamp)
    val dateTime = instant.atZone(ZoneId.systemDefault()).toLocalTime()
    return dateTime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)).lowercase()
}

private fun formatConversationTime(timestamp: Long): String {
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(timestamp).atZone(zone)
    val today = LocalDate.now(zone)
    val date = dateTime.toLocalDate()
    return when {
        date == today -> dateTime.toLocalTime().format(DateTimeFormatter.ofPattern("H:mm"))
        date == today.minusDays(1) -> "Yesterday"
        date.year == today.year -> date.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))
        else -> date.format(DateTimeFormatter.ofPattern("yyyy MMM d", Locale.ENGLISH))
    }
}

private fun formatConversationDateLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date.year == today.year -> date.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))
        else -> date.format(DateTimeFormatter.ofPattern("yyyy MMM d", Locale.ENGLISH))
    }
}
