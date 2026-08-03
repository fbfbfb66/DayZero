package com.goings.dayzero.ui.screens

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goings.dayzero.domain.model.AppState
import com.goings.dayzero.domain.model.RecordStatus
import com.goings.dayzero.domain.model.formatWeightKg
import com.goings.dayzero.ui.theme.CardBackground
import com.goings.dayzero.ui.theme.LightGreen
import com.goings.dayzero.ui.theme.TextPrimary
import com.goings.dayzero.ui.theme.TextSecondary
import com.goings.dayzero.ui.theme.WarmBackground
import com.goings.dayzero.ui.sync.SyncStatusPanel
import com.goings.dayzero.ui.sync.SyncStatusUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsScreen(
    uiState: AppState,
    syncStatusUiState: SyncStatusUiState,
    onManualSync: () -> Unit,
    onManualRestoreCheck: () -> Unit
) {
    var selectedRange by remember { mutableStateOf("7天") }

    // Aggregate records by date to avoid duplicate X-axis labels
    val aggregatedRecords = remember(uiState.records) {
        uiState.records
            .filter { it.status == RecordStatus.Confirmed }
            .groupBy { it.date }
            .map { (date, records) ->
                // If there are multiple (though there should only be one after our merge logic), 
                // we take the first or sum them up. For total calories, summing or taking the merged one.
                // After Phase 6, there's only one confirmed record per date.
                records.first() 
            }
            .sortedBy { it.date }
    }

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
                    val points = aggregatedRecords.map { 
                        ChartDataPoint(
                            dateLabel = it.date.format(formatter),
                            value = it.totalCalories.toFloat(),
                            formattedValue = "${it.totalCalories}"
                        )
                    }
                    AnimatedRoundedBarChart(
                        data = points,
                        modifier = Modifier.fillMaxWidth().height(180.dp)
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
                    val points = aggregatedRecords.mapNotNull { record ->
                        record.weightKg?.let { weight ->
                            ChartDataPoint(
                                dateLabel = record.date.format(formatter),
                                value = weight,
                                formattedValue = formatWeightKg(weight.toDouble())
                            )
                        }
                    }
                    AnimatedRoundedBarChart(
                        data = points,
                        modifier = Modifier.fillMaxWidth().height(180.dp)
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
            
            SyncStatusPanel(
                state = syncStatusUiState,
                onManualSync = onManualSync,
                onManualRestoreCheck = onManualRestoreCheck
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

data class ChartDataPoint(
    val dateLabel: String,
    val value: Float,
    val formattedValue: String
)

@Composable
fun AnimatedRoundedBarChart(
    data: List<ChartDataPoint>,
    modifier: Modifier = Modifier
) {
    val validData = remember(data) { filterValidChartPoints(data) }

    if (validData.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("暂无数据", color = TextSecondary)
        }
        return
    }

    val minVal = remember(validData) { validData.minOf { it.value } }
    val maxVal = remember(validData) { validData.maxOf { it.value } }

    val animationProgress = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(validData) {
        animationProgress.snapTo(0f)
        animationProgress.animateTo(
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 700,
                easing = androidx.compose.animation.core.LinearEasing
            )
        )
    }

    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current

    val labelTextStyle = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 0.2.sp
    )

    val dateTextStyle = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        color = TextSecondary
    )

    Canvas(
        modifier = modifier.semantics {
            contentDescription = "趋势图表, 共 ${validData.size} 条记录"
        }
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        val topLabelSpacePx = 26.dp.toPx()
        val bottomDateSpacePx = 22.dp.toPx()
        val maxBarHeightPx = (canvasHeight - topLabelSpacePx - bottomDateSpacePx).coerceAtLeast(10.dp.toPx())
        val baselineY = canvasHeight - bottomDateSpacePx

        // Grid lines
        val gridLineColor = TextSecondary.copy(alpha = 0.12f)
        val gridStrokeWidth = 1.dp.toPx()
        val gridSteps = 3
        for (i in 0..gridSteps) {
            val gridY = topLabelSpacePx + (maxBarHeightPx * (i.toFloat() / gridSteps))
            drawLine(
                color = gridLineColor,
                start = Offset(0f, gridY),
                end = Offset(canvasWidth, gridY),
                strokeWidth = gridStrokeWidth
            )
        }

        val positions = calculateBarPositions(validData.size, canvasWidth)
        val dateIndices = calculateDateLabelIndices(validData.size)

        validData.forEachIndexed { index, point ->
            val pos = positions[index]
            val barColor = calculateBarColor(point.value, minVal, maxVal)
            val fullHeightFraction = calculateBarHeightFraction(point.value, minVal, maxVal)
            val fullBarHeightPx = maxBarHeightPx * fullHeightFraction

            val barAnimProgress = calculateBarProgress(
                index = index,
                masterProgress = animationProgress.value,
                totalCount = validData.size
            )

            val currentBarHeightPx = fullBarHeightPx * barAnimProgress
            val barTopY = baselineY - currentBarHeightPx
            val barLeft = pos.centerX - (pos.width / 2f)

            // Draw Rounded Bar
            if (currentBarHeightPx > 0f) {
                val maxRadiusPx = minOf(pos.width / 2f, currentBarHeightPx / 2f)
                val preferredRadiusPx = 10.dp.toPx()
                val cornerRadiusPx = preferredRadiusPx.coerceAtMost(maxRadiusPx).coerceAtLeast(0f)

                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(barLeft, barTopY),
                    size = androidx.compose.ui.geometry.Size(pos.width, currentBarHeightPx),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx, cornerRadiusPx)
                )
            }

            // Draw Top Numerical Label
            if (barAnimProgress > 0f) {
                val labelLayout = textMeasurer.measure(
                    text = point.formattedValue,
                    style = labelTextStyle.copy(color = barColor.copy(alpha = barAnimProgress))
                )
                val textWidth = labelLayout.size.width.toFloat()
                val textHeight = labelLayout.size.height.toFloat()

                val textX = (pos.centerX - textWidth / 2f).coerceIn(0f, (canvasWidth - textWidth).coerceAtLeast(0f))
                val textY = (barTopY - textHeight - 2.dp.toPx()).coerceAtLeast(0f)

                drawText(
                    textLayoutResult = labelLayout,
                    topLeft = Offset(textX, textY)
                )
            }

            // Draw X-Axis Date Label
            if (index in dateIndices) {
                val dateLayout = textMeasurer.measure(
                    text = point.dateLabel,
                    style = dateTextStyle
                )
                val dateWidth = dateLayout.size.width.toFloat()
                val dateX = (pos.centerX - dateWidth / 2f).coerceIn(0f, (canvasWidth - dateWidth).coerceAtLeast(0f))
                val dateY = baselineY + 4.dp.toPx()

                drawText(
                    textLayoutResult = dateLayout,
                    topLeft = Offset(dateX, dateY)
                )
            }
        }
    }
}


