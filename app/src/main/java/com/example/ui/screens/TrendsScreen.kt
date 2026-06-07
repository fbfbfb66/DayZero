package com.example.ui.screens

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.DayZeroViewModel
import com.example.domain.model.RecordStatus
import com.example.ui.theme.CardBackground
import com.example.ui.theme.LightGreen
import com.example.ui.theme.BrandGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarmBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsScreen(viewModel: DayZeroViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedRange by remember { mutableStateOf("7天") }

    val confirmedRecords = uiState.records
        .filter { it.status == RecordStatus.Confirmed }
        .sortedBy { it.date }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("趋势", fontWeight = FontWeight.Bold, color = TextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WarmBackground)
            )
        },
        containerColor = WarmBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LightGreen, RoundedCornerShape(20.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf("7天", "30天").forEach { text ->
                    val isSelected = selectedRange == text
                    TextButton(
                        onClick = { selectedRange = text },
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (isSelected) CardBackground else Color.Transparent,
                                RoundedCornerShape(16.dp)
                            )
                    ) {
                        Text(
                            text,
                            color = if (isSelected) TextPrimary else TextSecondary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // Calories Chart
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("热量趋势 (kcal)", fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    val formatter = DateTimeFormatter.ofPattern("M/d")
                    val points = confirmedRecords.map { 
                        ChartDataPoint(
                            dateLabel = it.date.format(formatter),
                            value = it.totalCalories.toFloat(),
                            tooltipText = "${it.date.format(formatter)}\n${it.totalCalories} kcal"
                        )
                    }
                    SimpleLineChart(
                        data = points,
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        lineColor = BrandGreen
                    )
                }
            }

            // Weight Chart
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("体重趋势 (kg)", fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(modifier = Modifier.height(16.dp))

                    val formatter = DateTimeFormatter.ofPattern("M/d")
                    val points = confirmedRecords.mapNotNull { record ->
                        record.weightKg?.let { weight ->
                            ChartDataPoint(
                                dateLabel = record.date.format(formatter),
                                value = weight,
                                tooltipText = "${record.date.format(formatter)}\n${weight} kg"
                            )
                        }
                    }
                    SimpleLineChart(
                        data = points,
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        lineColor = TextPrimary
                    )
                }
            }

            // AI Insight
            Card(
                colors = CardDefaults.cardColors(containerColor = LightGreen),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("💡 AI 洞察", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "本周记录比较稳定，继续保持每天记录就很棒。晚餐的热量控制得很不错！",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

data class ChartDataPoint(
    val dateLabel: String,
    val value: Float,
    val tooltipText: String
)

@Composable
fun SimpleLineChart(data: List<ChartDataPoint>, modifier: Modifier = Modifier, lineColor: Color) {
    if (data.isEmpty() || data.all { it.value == 0f }) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("暂无数据", color = TextSecondary)
        }
        return
    }

    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    
    var animationTriggered by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(data) {
        animationTriggered = true
    }
    val progress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (animationTriggered) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 800, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "ChartProgress"
    )

    val maxVal = data.maxOf { it.value }.coerceAtLeast(1f)
    val minVal = data.minOf { it.value }
    val range = (maxVal - minVal).coerceAtLeast(1f) * 1.5f
    val base = minVal - (range * 0.15f)

    BoxWithConstraints(modifier = modifier) {
        val widthDp = maxWidth
        val heightDp = maxHeight - 20.dp

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp)
                .pointerInput(data) {
                    detectTapGestures { offset ->
                        val stepX = if (data.size > 1) size.width.toFloat() / (data.size - 1) else size.width.toFloat()
                        val index = (offset.x / stepX).roundToInt().coerceIn(0, data.size - 1)
                        selectedIndex = if (selectedIndex == index) null else index
                    }
                }
        ) {
            val width = size.width
            val height = size.height
            val stepX = if (data.size > 1) width / (data.size - 1) else width
            val path = Path()

            data.forEachIndexed { index, point ->
                val x = index * stepX
                val y = height - ((point.value - base) / range * height).coerceIn(0f, height)
                
                if (index == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            clipRect(right = width * progress) {
                drawPath(
                    path = path,
                    color = lineColor.copy(alpha = 0.5f),
                    style = Stroke(
                        width = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }

            data.forEachIndexed { index, point ->
                val x = index * stepX
                val y = height - ((point.value - base) / range * height).coerceIn(0f, height)
                val isSelected = selectedIndex == index
                
                if (index.toFloat() / data.size <= progress) {
                    drawCircle(
                        color = if (isSelected) Color.White else lineColor,
                        radius = (if (isSelected) 6.dp else 4.dp).toPx(),
                        center = Offset(x, y)
                    )
                    if (isSelected) {
                        drawCircle(
                            color = lineColor,
                            radius = 6.dp.toPx(),
                            center = Offset(x, y),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
            }
        }
        
        data.forEachIndexed { index, point ->
            val xDp = if (data.size > 1) widthDp * (index.toFloat() / (data.size - 1)) else 0.dp
            Text(
                text = point.dateLabel,
                color = TextSecondary,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .absoluteOffset(x = xDp - 10.dp, y = 0.dp)
            )
        }
        
        selectedIndex?.let { index ->
            val point = data[index]
            val xDp = if (data.size > 1) widthDp * (index.toFloat() / (data.size - 1)) else widthDp / 2
            val finalXDp = when {
                xDp < 40.dp -> 0.dp
                xDp > widthDp - 40.dp -> widthDp - 80.dp
                else -> xDp - 40.dp
            }
            
            val yPxLocal = heightDp.value - ((point.value - base) / range * heightDp.value).coerceIn(0f, heightDp.value)
            val finalYDp = (yPxLocal.dp - 40.dp).coerceAtLeast(0.dp)
            
            androidx.compose.animation.AnimatedVisibility(
                visible = selectedIndex == index,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
                modifier = Modifier.absoluteOffset(x = finalXDp, y = finalYDp)
            ) {
                Box(
                    modifier = Modifier
                        .background(Color(0xD93A3A35), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = point.tooltipText,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}
