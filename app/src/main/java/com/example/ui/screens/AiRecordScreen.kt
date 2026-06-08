package com.example.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.input.KeyboardType
import com.example.domain.model.DailyRecord
import com.example.domain.model.RecordStatus
import com.example.domain.model.ai.AiChatMessage
import com.example.ui.theme.LightGreen
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.DayZeroViewModel
import com.example.domain.model.ai.ChatRole
import com.example.ui.theme.BorderNormal
import com.example.ui.theme.BrandGreen
import com.example.ui.theme.CardBackground
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import com.example.ui.theme.WarmBackground
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiRecordScreen(viewModel: DayZeroViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.chatMessages.size, uiState.isAnalyzing) {
        if (uiState.chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.chatMessages.lastIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmBackground)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = PaddingValues(start = 16.dp, top = 110.dp, end = 16.dp, bottom = 180.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (uiState.chatMessages.isEmpty()) {
                item {
                    AiMessage("Pure chat mode is active for Phase 1.", 0L)
                }
            }

            items(items = uiState.chatMessages, key = { it.id }) { message ->
                if (message.role == ChatRole.User) {
                    UserMessage(message.text, message.createdAt)
                } else {
                    Column {
                        if (message.text.isNotBlank()) {
                            AiMessage(message.text, message.createdAt)
                        }
                        message.assistantCards.forEach { card ->
                            if (card is com.example.domain.model.ai.assistant.DebugChoiceCardPayload) {
                                Spacer(modifier = Modifier.height(8.dp))
                                DebugChoiceCard(
                                    card = card,
                                    onOptionSelected = { interactionId, optionId, optionLabel ->
                                        viewModel.sendInteractionResult(
                                            interactionId = interactionId,
                                            actionType = "debug_show_choice_card",
                                            optionId = optionId,
                                            optionLabel = optionLabel
                                        )
                                    }
                                )
                            } else if (card is com.example.domain.model.ai.assistant.AskRecordIntentCardPayload) {
                                Spacer(modifier = Modifier.height(8.dp))
                                AskRecordIntentCard(
                                    card = card,
                                    onOptionSelected = { interactionId, optionId, optionLabel ->
                                        viewModel.sendInteractionResult(
                                            interactionId = interactionId,
                                            actionType = "ask_record_intent_card",
                                            optionId = optionId,
                                            optionLabel = optionLabel
                                        )
                                    }
                                )
                            } else if (card is com.example.domain.model.ai.assistant.AskMissingInfoCardPayload) {
                                Spacer(modifier = Modifier.height(8.dp))
                                AskMissingInfoCard(
                                    card = card,
                                    onOptionSelected = { interactionId, optionId, optionLabel, field, originalText ->
                                        viewModel.sendInteractionResult(
                                            interactionId = interactionId,
                                            actionType = "ask_missing_info_card",
                                            optionId = optionId,
                                            optionLabel = optionLabel,
                                            field = field,
                                            originalText = originalText
                                        )
                                    }
                                )
                            } else if (card is com.example.domain.model.ai.assistant.ShowConfirmCardPayload) {
                                Spacer(modifier = Modifier.height(8.dp))
                                ShowConfirmCard(
                                    card = card,
                                    onOptionSelected = { interactionId, optionId, optionLabel, payloadSummary ->
                                        viewModel.sendInteractionResult(
                                            interactionId = interactionId,
                                            actionType = "show_confirm_card",
                                            optionId = optionId,
                                            optionLabel = optionLabel,
                                            confirmType = "food_record",
                                            payloadSummary = payloadSummary
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.isAnalyzing) {
                item(key = "analyzing") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AiMessageComponent {
                            TypingIndicator()
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = WarmBackground.copy(alpha = 0.96f),
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier
                    .statusBarsPadding()
                    .height(40.dp)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AI Record",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Text(
                        text = "Clear",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier
                            .clickable { viewModel.clearAllData() }
                            .padding(4.dp)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
        ) {
            Text(
                text = "assistant-turn-v2 pure chat entrypoint",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                textAlign = TextAlign.Center,
                color = TextTertiary.copy(alpha = 0.7f),
                fontSize = 10.sp
            )

            Surface(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .animateContentSize(),
                shape = RoundedCornerShape(28.dp),
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderNormal.copy(alpha = 0.5f)),
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {},
                        enabled = false
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "More", tint = TextSecondary.copy(alpha = 0.5f))
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Chat with DayZero...", color = TextSecondary) },
                        enabled = !uiState.isAnalyzing,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent
                        ),
                        maxLines = 4
                    )

                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (inputText.isNotBlank()) BrandGreen else BrandGreen.copy(alpha = 0.3f))
                            .clickable(enabled = inputText.isNotBlank() && !uiState.isAnalyzing) {
                                viewModel.sendAiMessage(inputText)
                                inputText = ""
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (uiState.isAnalyzing) {
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

    @Composable
    fun Dot(delay: Int) {
        val yOffset by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = -6f,
            animationSpec = infiniteRepeatable(
                animation = tween(400, delayMillis = delay),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dot"
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .size(6.dp)
                .graphicsLayer { translationY = yOffset }
                .background(TextSecondary.copy(alpha = 0.4f), CircleShape)
        )
    }

    Row(
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Dot(0)
        Dot(150)
        Dot(300)
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

private fun formatTime(timestamp: Long): String {
    val instant = Instant.ofEpochMilli(timestamp)
    val dateTime = instant.atZone(ZoneId.systemDefault()).toLocalTime()
    return dateTime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)).lowercase()
}

@Composable
private fun DebugChoiceCard(
    card: com.example.domain.model.ai.assistant.DebugChoiceCardPayload,
    onOptionSelected: (interactionId: String, optionId: String, optionLabel: String) -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(card.id) {
        Log.d("DayZeroAiV2", "render debug_show_choice_card")
    }

    Box(
        modifier = Modifier
            .padding(end = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.dp, BorderNormal, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = card.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = card.message,
                fontSize = 14.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                card.options.forEach { option ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                Log.d("DayZeroAiV2", "debug option clicked interactionId=${card.id} optionId=${option.id} optionLabel=${option.label}")
                                Toast.makeText(context, "已选择：${option.label}", Toast.LENGTH_SHORT).show()
                                onOptionSelected(card.id, option.id, option.label)
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = WarmBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderNormal.copy(alpha = 0.5f))
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = option.label,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = BrandGreen
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AskRecordIntentCard(
    card: com.example.domain.model.ai.assistant.AskRecordIntentCardPayload,
    onOptionSelected: (interactionId: String, optionId: String, optionLabel: String) -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(card.id) {
        Log.d("DayZeroAiV2", "render ask_record_intent_card")
    }

    Box(
        modifier = Modifier
            .padding(end = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.dp, BorderNormal, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = card.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = card.message,
                fontSize = 14.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "\"${card.originalText}\"",
                fontSize = 13.sp,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = TextSecondary.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                card.options.forEach { option ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                Log.d("DayZeroAiV2", "record intent option clicked interactionId=${card.id} optionId=${option.id}")
                                Toast.makeText(context, "已选择：${option.label}", Toast.LENGTH_SHORT).show()
                                onOptionSelected(card.id, option.id, option.label)
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = WarmBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderNormal.copy(alpha = 0.5f))
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = option.label,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = BrandGreen
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AskMissingInfoCard(
    card: com.example.domain.model.ai.assistant.AskMissingInfoCardPayload,
    onOptionSelected: (interactionId: String, optionId: String, optionLabel: String, field: String, originalText: String) -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(card.id) {
        Log.d("DayZeroAiV2", "render ask_missing_info_card")
    }

    Box(
        modifier = Modifier
            .padding(end = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.dp, BorderNormal, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = card.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = card.message,
                fontSize = 14.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "\"${card.originalText}\"",
                fontSize = 13.sp,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = TextSecondary.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                card.options.forEach { option ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                Log.d("DayZeroAiV2", "missing info option clicked interactionId=${card.id} field=${card.field} optionId=${option.id}")
                                Toast.makeText(context, "已选择：${option.label}", Toast.LENGTH_SHORT).show()
                                onOptionSelected(card.id, option.id, option.label, card.field, card.originalText)
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = WarmBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderNormal.copy(alpha = 0.5f))
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = option.label,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = BrandGreen
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowConfirmCard(
    card: com.example.domain.model.ai.assistant.ShowConfirmCardPayload,
    onOptionSelected: (interactionId: String, optionId: String, optionLabel: String, payloadSummary: com.example.domain.model.ai.assistant.PayloadSummary) -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(card.id) {
        Log.d("DayZeroAiV2", "render show_confirm_card")
    }

    Box(
        modifier = Modifier
            .padding(end = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.dp, BorderNormal, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = card.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = card.message,
                fontSize = 14.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF7F8F9))
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        text = "餐次: ${card.mealType}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = BrandGreen
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    card.items.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${item.name} x ${item.amountText ?: "1"}",
                                fontSize = 14.sp,
                                color = TextPrimary
                            )
                            Text(
                                text = "${item.calories} 千卡",
                                fontSize = 14.sp,
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                card.buttons.forEach { option ->
                    val isConfirm = option.id == "confirm"
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isConfirm) BrandGreen else Color.Transparent)
                            .border(
                                1.dp,
                                if (isConfirm) Color.Transparent else BorderNormal,
                                RoundedCornerShape(20.dp)
                            )
                            .clickable {
                                Log.d("DayZeroAiV2", "confirm card clicked interactionId=${card.id} optionId=${option.id}")
                                onOptionSelected(
                                    card.id,
                                    option.id,
                                    option.label,
                                    com.example.domain.model.ai.assistant.PayloadSummary(
                                        originalText = card.originalText,
                                        mealType = card.mealType,
                                        items = card.items
                                    )
                                )
                                Toast.makeText(context, "已选择: ${option.label}", Toast.LENGTH_SHORT).show()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = option.label,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isConfirm) Color.White else TextPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChoiceCardMessage(message: AiChatMessage, onOptionSelected: (com.example.domain.model.ai.ChatOption) -> Unit) {
    val choiceCard = message.choiceCard ?: return
    
    Column(
        modifier = Modifier.fillMaxWidth().padding(end = 48.dp),
        horizontalAlignment = Alignment.Start
    ) {
        AiMessage(message.text, message.createdAt)
        
        if (!choiceCard.resolved) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderNormal),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(choiceCard.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrimary)
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            choiceCard.options.forEach { option ->
                                Button(
                                    onClick = { onOptionSelected(option) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = BrandGreen
                                    ),
                                    contentPadding = PaddingValues(vertical = 10.dp)
                                ) {
                                    Text(option.label, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConfirmedSummaryCard(record: DailyRecord) {
    Box(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = LightGreen.copy(alpha = 0.7f)),
            border = androidx.compose.foundation.BorderStroke(1.dp, BrandGreen.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = BrandGreen, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("今日已记录摘要", fontWeight = FontWeight.Bold, color = BrandGreen, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(record.aiSummary, color = TextPrimary, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
fun DraftCard(draft: DailyRecord, viewModel: DayZeroViewModel) {
    var weightInput by remember { mutableStateOf(draft.weightKg?.toString() ?: "") }

    Box(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth().animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ),
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderNormal),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp).padding(bottom = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("今日记录草稿", color = com.example.ui.theme.TextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(
                            draft.date.format(DateTimeFormatter.ofPattern("yyyy年M月d日")), 
                            style = MaterialTheme.typography.titleMedium, 
                            fontWeight = FontWeight.Bold, 
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                            color = TextPrimary
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("预估总热量", color = com.example.ui.theme.TextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${draft.totalCalories}", color = BrandGreen, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                            Text(" kcal", color = BrandGreen, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().background(com.example.ui.theme.SurfaceSecondary, RoundedCornerShape(12.dp)).padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("体重记录 (kg)", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    androidx.compose.foundation.text.BasicTextField(
                        value = weightInput,
                        onValueChange = { weightInput = it },
                        textStyle = androidx.compose.ui.text.TextStyle(textAlign = androidx.compose.ui.text.style.TextAlign.End, color = com.example.ui.theme.TextMedium, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(60.dp),
                        decorationBox = { innerTextField ->
                            if (weightInput.isEmpty()) {
                                Text("点击填写", color = com.example.ui.theme.TextTertiary, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.fillMaxWidth())
                            }
                            innerTextField()
                        }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                draft.meals.forEach { meal ->
                    if (meal.foods.isNotEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .animateContentSize(),
                            colors = CardDefaults.cardColors(containerColor = CardBackground),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, com.example.ui.theme.BorderLight),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(meal.mealType.displayName, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Text("${meal.mealCalories} kcal", color = BrandGreen, fontWeight = FontWeight.Medium)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                meal.foods.forEach { food ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(food.name, color = TextPrimary)
                                            Text("${food.quantity} · ${food.estimatedCalories} kcal", color = TextSecondary, fontSize = 12.sp)
                                        }
                                        IconButton(onClick = { /* Demo */ }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Filled.Edit, contentDescription = "编辑", tint = TextSecondary, modifier = Modifier.size(16.dp))
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        IconButton(onClick = { /* TODO Phase 4B */ }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Filled.DeleteOutline, contentDescription = "删除", tint = Color.Red.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                if (draft.aiSummary.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "\"${draft.aiSummary}\"",
                        color = com.example.ui.theme.TextTertiary,
                        fontSize = 12.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { /* Demo */ },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandGreen)
                    ) {
                        Text("添加")
                    }
                    OutlinedButton(
                        onClick = { /* Demo */ },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandGreen)
                    ) {
                        Text("重估")
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = { /* TODO Phase 4B */ },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGreen),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("确认录入", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
