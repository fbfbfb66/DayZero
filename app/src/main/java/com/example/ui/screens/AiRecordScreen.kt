package com.example.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.DayZeroViewModel
import com.example.domain.model.DailyRecord
import com.example.domain.model.RecordStatus
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.ChatMessageType
import com.example.domain.model.ai.ChatOption
import com.example.domain.model.ai.ChatRole
import com.example.ui.theme.CardBackground
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.BrandGreen
import com.example.ui.theme.BorderNormal
import com.example.ui.theme.LightGreen
import com.example.ui.theme.TextSecondary
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
            listState.animateScrollToItem(uiState.chatMessages.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmBackground)
    ) {
        // LAYER 1: DATA WINDOW (Absolute bottom layer)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = true,
            contentPadding = PaddingValues(
                start = 16.dp, 
                top = 80.dp, 
                end = 16.dp, 
                bottom = 160.dp 
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (uiState.chatMessages.isEmpty()) {
                item {
                    AiMessage("你好！我是你的饮食助手。你可以直接告诉我你吃了什么，我会帮你记录下来。", 0L)
                }
            }

            items(uiState.chatMessages) { message ->
                when (message.messageType) {
                    ChatMessageType.Text -> {
                        if (message.role == ChatRole.User) {
                            UserMessage(message.text, message.createdAt)
                        } else {
                            AiMessage(message.text, message.createdAt)
                        }
                    }
                    ChatMessageType.ChoiceCard -> {
                        ChoiceCardMessage(
                            message = message,
                            onOptionSelected = { option -> viewModel.handleChatAction(message.id, option) }
                        )
                    }
                    else -> {}
                }
            }

            if (uiState.isAnalyzing) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AiMessage("正在分析中...", System.currentTimeMillis())
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.padding(start = 8.dp).size(16.dp),
                            strokeWidth = 2.dp,
                            color = BrandGreen
                        )
                    }
                }
            }

            val draftRecord = uiState.records.find { it.status == RecordStatus.Draft }
            if (draftRecord != null) {
                item {
                    DraftCard(draftRecord, viewModel)
                }
            }

            val confirmedToday = uiState.records.find { it.date == uiState.currentDate && it.status == RecordStatus.Confirmed }
            if (confirmedToday != null) {
                item {
                    ConfirmedSummaryCard(confirmedToday)
                }
            }

            if (draftRecord == null && !uiState.isAnalyzing && uiState.chatMessages.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "暂无待确认草稿",
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // LAYER 2: APP BAR (Floating at top)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = WarmBackground.copy(alpha = 0.96f),
            shadowElevation = 0.dp
        ) {
            Box(modifier = Modifier.statusBarsPadding().height(64.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    text = "AI记录", 
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, 
                    color = TextPrimary
                )
            }
        }

        // LAYER 3: FIXED BOTTOM BAR (Attached to bottom)
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding(),
            color = Color.White,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
            ) {
                Text(
                    text = "AI 分析功能正在开发中 · 当前为本地演示逻辑",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    textAlign = TextAlign.Center,
                    color = com.example.ui.theme.TextTertiary.copy(alpha = 0.6f),
                    fontSize = 10.sp
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { /* Demo */ },
                        enabled = !uiState.isAnalyzing
                    ) {
                        Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "上传图片", tint = BrandGreen.copy(alpha = if (uiState.isAnalyzing) 0.5f else 1f))
                    }
                    
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp),
                        color = WarmBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderNormal.copy(alpha = 0.5f))
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("告诉 AI 你今天吃了什么……", color = TextSecondary) },
                            enabled = !uiState.isAnalyzing,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent
                            ),
                            maxLines = 4
                        )
                    }

                    IconButton(
                        onClick = { 
                            viewModel.generateDraftFromText(inputText)
                            inputText = ""
                        },
                        enabled = !uiState.isAnalyzing && inputText.isNotBlank()
                    ) {
                        Icon(
                            Icons.Filled.Send, 
                            contentDescription = "发送", 
                            tint = if (inputText.isNotBlank()) BrandGreen else TextSecondary.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun UserMessage(text: String, createdAt: Long) {
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
                        modifier = Modifier.align(Alignment.End).padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AiMessage(text: String, createdAt: Long) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .padding(end = 48.dp)
                .clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
                .background(CardBackground)
                .border(1.dp, com.example.ui.theme.BorderNormal, RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
                .padding(12.dp)
        ) {
            Column {
                Text(text, color = TextPrimary, fontSize = 15.sp)
                if (createdAt > 0) {
                    Text(
                        text = formatTime(createdAt),
                        color = TextSecondary.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.End).padding(top = 2.dp)
                    )
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
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderNormal),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
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
                                    containerColor = if (option.label.contains("覆盖")) Color(0xFFE57373) else BrandGreen
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

@Composable
fun ConfirmedSummaryCard(record: DailyRecord) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
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

private fun formatTime(timestamp: Long): String {
    val instant = Instant.ofEpochMilli(timestamp)
    val dateTime = instant.atZone(ZoneId.systemDefault()).toLocalTime()
    return dateTime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)).lowercase()
}

@Composable
fun DraftCard(draft: DailyRecord, viewModel: DayZeroViewModel) {
    var weightInput by remember { mutableStateOf(draft.weightKg?.toString() ?: "") }

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp).padding(bottom = 12.dp)) {
            // Header
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

            // Weight Optional
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

            // Meals
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
                                    IconButton(onClick = { viewModel.removeFood(draft.id, meal.mealType, food.id) }, modifier = Modifier.size(24.dp)) {
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

            // Actions
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
                onClick = { viewModel.confirmDraftWithMerge(draft.id, weightInput.toFloatOrNull()) },
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
