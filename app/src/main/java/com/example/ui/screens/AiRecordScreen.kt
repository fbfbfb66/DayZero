package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.ConflictAction
import com.example.DayZeroViewModel
import com.example.domain.model.DailyRecord
import com.example.domain.model.RecordStatus
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.ChatRole
import com.example.ui.theme.CardBackground
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.BrandGreen
import com.example.ui.theme.BorderNormal
import com.example.ui.theme.LightGreen
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarmBackground
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiRecordScreen(viewModel: DayZeroViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Scroll to bottom when messages change
    LaunchedEffect(uiState.chatMessages.size) {
        if (uiState.chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.chatMessages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI记录", fontWeight = FontWeight.Bold, color = TextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WarmBackground)
            )
        },
        containerColor = WarmBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Intro Message
                if (uiState.chatMessages.isEmpty()) {
                    item {
                        AiMessage("你好！我是你的饮食助手。你可以直接告诉我你吃了什么，我会帮你记录下来。")
                    }
                }

                // Chat Messages
                items(uiState.chatMessages) { message ->
                    if (message.role == ChatRole.User) {
                        UserMessage(message.text)
                    } else {
                        AiMessage(message.text)
                    }
                }

                // Analyzing indicator in list
                if (uiState.isAnalyzing) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AiMessage("正在分析中...")
                            Spacer(modifier = Modifier.width(8.dp))
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = BrandGreen
                            )
                        }
                    }
                }

                // Draft Card Area
                val draftRecord = uiState.records.find { it.status == RecordStatus.Draft }
                if (draftRecord != null) {
                    item {
                        DraftCard(draftRecord, viewModel)
                    }
                }

                // Daily Summary if exists
                val confirmedToday = uiState.records.find { it.date == uiState.currentDate && it.status == RecordStatus.Confirmed }
                if (confirmedToday != null) {
                    item {
                        ConfirmedSummaryCard(confirmedToday)
                    }
                }

                // Empty State if nothing is happening
                if (draftRecord == null && !uiState.isAnalyzing) {
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

            // Input Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
            ) {
                // Mock Disclaimer
                Text(
                    text = "AI 分析功能正在开发中 · 当前为本地演示逻辑",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                    color = com.example.ui.theme.TextTertiary,
                    fontSize = 10.sp
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { /* Demo */ },
                            enabled = !uiState.isAnalyzing
                        ) {
                            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "上传图片", tint = BrandGreen.copy(alpha = if (uiState.isAnalyzing) 0.5f else 1f))
                        }
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("告诉 AI 你今天吃了什么……", color = TextSecondary) },
                            enabled = !uiState.isAnalyzing,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = BorderNormal,
                                focusedBorderColor = BrandGreen,
                                unfocusedContainerColor = WarmBackground,
                                focusedContainerColor = WarmBackground
                            ),
                            shape = RoundedCornerShape(24.dp)
                        )
                        IconButton(
                            onClick = { 
                                viewModel.generateDraftFromText(inputText)
                                inputText = ""
                            },
                            enabled = !uiState.isAnalyzing && inputText.isNotBlank()
                        ) {
                            Icon(Icons.Filled.Send, contentDescription = "发送", tint = if (inputText.isNotBlank()) BrandGreen else TextSecondary)
                        }
                    }
                }
            }
        }
    }

    // Conflict Dialog
    uiState.conflictState?.let { state ->
        val conflictNames = state.existingMealTypes.joinToString("、") { it.displayName }
        AlertDialog(
            onDismissRequest = { viewModel.handleConflictResolution(ConflictAction.Cancel) },
            title = { Text("已有餐次记录") },
            text = { Text("今天已经记录过：$conflictNames。你想如何处理本次 AI 生成的这些餐次？") },
            confirmButton = {
                TextButton(onClick = { viewModel.handleConflictResolution(ConflictAction.Overwrite) }) {
                    Text("覆盖并录入", color = Color.Red)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.handleConflictResolution(ConflictAction.Cancel) }) {
                        Text("取消")
                    }
                    TextButton(onClick = { viewModel.handleConflictResolution(ConflictAction.AddNonConflicting) }) {
                        Text("仅添加未冲突餐次")
                    }
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = CardBackground
        )
    }
}

@Composable
fun UserMessage(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .padding(start = 48.dp)
                .clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
                .background(BrandGreen)
                .padding(12.dp)
        ) {
            Text(text, color = Color.White, fontSize = 15.sp)
        }
    }
}

@Composable
fun AiMessage(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .padding(end = 48.dp)
                .clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
                .background(CardBackground)
                .border(1.dp, com.example.ui.theme.BorderNormal, RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
                .padding(12.dp)
        ) {
            Text(text, color = TextPrimary, fontSize = 15.sp)
        }
    }
}

@Composable
fun ConfirmedSummaryCard(record: DailyRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = LightGreen.copy(alpha = 0.5f))
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

@Composable
fun DraftCard(draft: DailyRecord, viewModel: DayZeroViewModel) {
    var weightInput by remember { mutableStateOf(draft.weightKg?.toString() ?: "") }

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
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
