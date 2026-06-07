package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import com.example.DayZeroViewModel
import com.example.domain.model.MealEntry
import com.example.domain.model.RecordStatus
import com.example.ui.theme.CardBackground
import com.example.ui.theme.LightGreen
import com.example.ui.theme.BrandGreen
import com.example.ui.theme.BorderNormal
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarmBackground
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(viewModel: DayZeroViewModel, onNavigateToAi: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedDate by remember { mutableStateOf(uiState.currentDate) }

    val daysInMonth = 30 // Simplified for prototype
    val firstDayOffset = 1 // Assuming 1st is Tuesday

    val confirmedRecords = uiState.records.filter { it.status == RecordStatus.Confirmed }
    val recordForSelectedDate = confirmedRecords.find { it.date == selectedDate }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "DayZero", 
                        fontWeight = FontWeight.Bold, 
                        color = TextPrimary, 
                        fontFamily = FontFamily.Serif, 
                        fontStyle = FontStyle.Italic 
                    ) 
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = WarmBackground
                )
            )
        },
        containerColor = WarmBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val currentStreak = remember(uiState.records, uiState.currentDate) {
                var s = 0
                var d = uiState.currentDate
                val confirmed = uiState.records.filter { it.status == RecordStatus.Confirmed }.map { it.date }.toSet()
                if (confirmed.contains(d)) {
                    s++
                    d = d.minusDays(1)
                } else {
                    d = d.minusDays(1)
                }
                while (confirmed.contains(d)) {
                    s++
                    d = d.minusDays(1)
                }
                s
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = selectedDate.format(DateTimeFormatter.ofPattern("yyyy年 MM月")),
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Surface(
                    color = LightGreen,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.LocalFireDepartment, 
                            contentDescription = "Streak", 
                            tint = Color(0xFFE27D60), 
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "已连续记录 ${currentStreak} 天",
                            color = BrandGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Simple Calendar Grid
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Days of week header
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                            Text(it, color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val weeks = (daysInMonth + firstDayOffset) / 7 + 1
                    for (week in 0 until weeks) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            for (dayOfWeek in 0..6) {
                                val dayNum = week * 7 + dayOfWeek - firstDayOffset + 1
                                if (dayNum in 1..daysInMonth) {
                                    val date = LocalDate.of(2026, 6, dayNum)
                                    val hasRecord = confirmedRecords.any { it.date == date }
                                    val isSelected = date == selectedDate
                                    
                                    val backgroundColor = when {
                                        isSelected -> BrandGreen
                                        hasRecord -> LightGreen
                                        else -> Color.Transparent
                                    }
                                    val textColor = when {
                                        isSelected -> Color.White
                                        hasRecord -> BrandGreen
                                        else -> TextPrimary
                                    }

                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(backgroundColor)
                                            .clickable { selectedDate = date }
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = "$dayNum",
                                                color = textColor,
                                                fontWeight = if (isSelected || hasRecord) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 14.sp
                                            )
                                            AnimatedVisibility(
                                                visible = hasRecord,
                                                enter = scaleIn() + fadeIn(),
                                                exit = scaleOut() + fadeOut()
                                            ) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = "已打卡",
                                                    tint = if (isSelected) Color.White else BrandGreen,
                                                    modifier = Modifier.size(10.dp)
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Spacer(modifier = Modifier.size(36.dp))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Summary Section
            androidx.compose.animation.AnimatedContent(
                targetState = recordForSelectedDate,
                label = "DailySummaryAnimation",
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) + slideInVertically(initialOffsetY = { 20 }) togetherWith fadeOut(animationSpec = tween(300))
                }
            ) { targetRecord ->
                if (targetRecord == null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp).fillMaxWidth()
                    ) {
                        Text(
                            "今天还没有记录，告诉 AI 你吃了什么吧。",
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onNavigateToAi,
                            colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
                        ) {
                            Text("用 AI 记录", color = Color.White)
                        }
                    }
                } else {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        shape = RoundedCornerShape(24.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        modifier = Modifier.fillMaxWidth().animateContentSize()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("总热量", color = TextSecondary)
                                Text("${targetRecord.totalCalories} kcal", fontWeight = FontWeight.Bold, color = BrandGreen, fontSize = 20.sp)
                            }
                            if (targetRecord.weightKg != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("体重", color = TextSecondary)
                                    Text("${targetRecord.weightKg} kg", color = TextPrimary)
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            targetRecord.meals.forEach { meal ->
                                ExpandableMealItem(meal)
                            }
                            
                            if (targetRecord.aiSummary.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = WarmBackground),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        targetRecord.aiSummary,
                                        modifier = Modifier.padding(12.dp),
                                        color = TextPrimary,
                                        fontSize = 14.sp
                                    )
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
fun ExpandableMealItem(meal: MealEntry) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(meal.mealType.displayName, color = TextPrimary, fontWeight = FontWeight.Medium)
                if (meal.hasPhoto) {
                    Spacer(modifier = Modifier.size(4.dp))
                    Icon(Icons.Default.Image, contentDescription = "有照片", modifier = Modifier.size(16.dp), tint = BorderNormal)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${meal.mealCalories} kcal", color = TextSecondary)
                if (meal.foods.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Expand",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        
        AnimatedVisibility(visible = expanded && meal.foods.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, start = 12.dp, end = 4.dp, bottom = 4.dp)
            ) {
                meal.foods.forEach { food ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${food.name} (${food.quantity})", color = TextSecondary, fontSize = 12.sp)
                        Text("${food.estimatedCalories} kcal", color = com.example.ui.theme.TextTertiary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
