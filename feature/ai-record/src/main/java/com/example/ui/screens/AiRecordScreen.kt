package com.example.ui.screens

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.AppState
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.ChatRole
import com.example.domain.model.ai.Conversation
import com.example.domain.model.ai.assistant.PayloadSummary
import com.example.ui.theme.BorderNormal
import com.example.ui.theme.BrandGreen
import com.example.ui.theme.CardBackground
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import com.example.ui.theme.WarmBackground
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
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
    fun sendAiMessage(text: String)

    fun sendAiMessage(conversationId: String, text: String)

    fun startAssistantTurnForExistingUserMessage(conversationId: String, text: String)

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

    fun clearChatMessages()
    fun clearLocalRecords()
    fun clearAllData()
    fun clearCloudBackupForDebug()
    fun markAssistantMessageRendered(message: AiChatMessage)
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

@Composable
fun AiConversationScreen(
    conversationId: String,
    detailState: AiConversationDetailState,
    appState: AppState,
    actionHandler: AiRecordActionHandler,
    onBack: () -> Unit
) {
    LaunchedEffect(conversationId) {
        actionHandler.setActiveConversationId(conversationId)
    }

    var inputText by remember(conversationId) { mutableStateOf("") }
    val messages = detailState.messages
    val isCurrentConversationAnalyzing = appState.isAnalyzing && appState.activeConversationId == conversationId
    val hasAssistantPlaceholder = messages.lastOrNull()?.let { message ->
        message.role == ChatRole.Assistant && message.text.isBlank() && message.assistantCards.isEmpty()
    } == true

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmBackground)
            .testTag(AiRecordTestTags.Conversation)
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

            items(items = messages, key = { it.id }) { message ->
                ChatMessageRow(
                    message = message,
                    isAnalyzing = isCurrentConversationAnalyzing,
                    isLastMessage = message.id == messages.lastOrNull()?.id,
                    actionHandler = actionHandler
                )
            }

            if (isCurrentConversationAnalyzing && !hasAssistantPlaceholder) {
                item(key = "analyzing") {
                    AiMessageComponent {
                        TypingIndicator()
                    }
                }
            }
        }

        ConversationTopBar(
            title = detailState.currentConversation?.title ?: "Conversation",
            subtitle = detailState.currentConversation?.let { formatConversationDateLabel(it.conversationDate) },
            onBack = onBack
        )

        ConversationInputBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            inputText = inputText,
            enabled = !appState.isAnalyzing,
            isAnalyzing = isCurrentConversationAnalyzing,
            inputTestTag = AiRecordTestTags.ConversationInput,
            sendTestTag = AiRecordTestTags.ConversationSend,
            onInputChange = { inputText = it },
            onSubmit = {
                val text = inputText
                if (text.isNotBlank()) {
                    actionHandler.sendAiMessage(conversationId, text)
                    inputText = ""
                }
            }
        )
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
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.title.ifBlank { "Conversation" },
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatConversationTime(conversation.lastActivityAt),
                    color = TextTertiary,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = conversation.lastMessagePreview.ifBlank { "No preview" },
                color = TextSecondary,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
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
    message: AiChatMessage,
    isAnalyzing: Boolean,
    isLastMessage: Boolean,
    actionHandler: AiRecordActionHandler
) {
    if (message.role == ChatRole.User) {
        UserMessage(message.text, message.createdAt)
    } else {
        LaunchedEffect(message.id, message.text, message.assistantCards.size) {
            if (message.text.isNotBlank() || message.assistantCards.isNotEmpty()) {
                actionHandler.markAssistantMessageRendered(message)
            }
        }
        Column {
            if (message.text.isNotBlank()) {
                AiMessage(message.text, message.createdAt)
            } else if ((isAnalyzing || message.text.isBlank()) && isLastMessage && message.assistantCards.isEmpty()) {
                AiMessageComponent {
                    TypingIndicator()
                }
            }
            message.assistantCards.forEach { card ->
                AssistantCardRenderer(card = card, actionHandler = actionHandler)
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
    inputTestTag: String,
    sendTestTag: String,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(bottom = 8.dp)
    ) {
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
                            .background(if (inputText.isNotBlank() && enabled) BrandGreen else BrandGreen.copy(alpha = 0.3f))
                            .clickable(enabled = inputText.isNotBlank() && enabled) { onSubmit() }
                            .testTag(sendTestTag),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isAnalyzing) {
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
                IconButton(onClick = {}, enabled = false) {
                    Icon(Icons.Filled.Add, contentDescription = "More", tint = TextSecondary.copy(alpha = 0.5f))
                }
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

@Composable
private fun UserMessage(text: String, createdAt: Long) {
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
                Text(text, color = Color.White, fontSize = 15.sp)
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
