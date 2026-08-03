package com.goings.dayzero.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.roundToInt

// Recommended reference colors for bar chart
val ChartGreen = Color(0xFF73B58A)
val ChartYellow = Color(0xFFD6B65F)
val ChartRed = Color(0xFFD87870)

/**
 * Filter out data points containing NaN or infinite values.
 */
fun filterValidChartPoints(data: List<ChartDataPoint>): List<ChartDataPoint> {
    return data.filter { it.value.isFinite() && !it.value.isNaN() }
}

/**
 * Maps a numeric value to a color interpolated between Green, Yellow, and Red
 * based on min and max values in the visible dataset.
 */
fun calculateBarColor(
    value: Float,
    minValue: Float,
    maxValue: Float,
    greenColor: Color = ChartGreen,
    yellowColor: Color = ChartYellow,
    redColor: Color = ChartRed
): Color {
    if (value.isNaN() || value.isInfinite()) return greenColor
    if (maxValue == minValue) {
        return if (maxValue == 0f) greenColor else yellowColor
    }
    val normalized = ((value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
    return if (normalized <= 0.5f) {
        val fraction = normalized / 0.5f
        lerp(greenColor, yellowColor, fraction)
    } else {
        val fraction = (normalized - 0.5f) / 0.5f
        lerp(yellowColor, redColor, fraction)
    }
}

/**
 * Maps a numeric value to a normalized bar height fraction (0f to 1f).
 */
fun calculateBarHeightFraction(value: Float, minValue: Float, maxValue: Float): Float {
    if (value.isNaN() || value.isInfinite() || value == 0f) return 0f
    if (maxValue == minValue) return 0.5f

    val range = (maxValue - minValue).coerceAtLeast(1f) * 1.35f
    val base = minValue - (range * 0.1f)
    return ((value - base) / range).coerceIn(0.05f, 1f)
}

/**
 * Calculates individual bar progress (0f to 1f) based on master animation progress (0f to 1f).
 */
fun calculateBarProgress(
    index: Int,
    masterProgress: Float,
    totalCount: Int,
    durationPerBarMs: Int = 600,
    staggerMs: Int = 45
): Float {
    if (totalCount <= 0) return 1f
    val totalDurationMs = durationPerBarMs + (totalCount - 1) * staggerMs
    val currentTimeMs = masterProgress.coerceIn(0f, 1f) * totalDurationMs
    val startTimeMs = index * staggerMs
    return ((currentTimeMs - startTimeMs) / durationPerBarMs).coerceIn(0f, 1f)
}

data class BarPosition(
    val centerX: Float,
    val width: Float
)

/**
 * Calculates X center position and bar width for each data point across canvas width.
 */
fun calculateBarPositions(pointCount: Int, canvasWidth: Float): List<BarPosition> {
    if (pointCount <= 0 || canvasWidth <= 0f) return emptyList()
    if (pointCount == 1) {
        val width = (canvasWidth * 0.25f).coerceIn(16f, 48f)
        return listOf(BarPosition(centerX = canvasWidth / 2f, width = width))
    }
    val slotWidth = canvasWidth / pointCount
    val preferredWidth = slotWidth * 0.55f
    val width = preferredWidth.coerceIn(4f, 36f)
    return List(pointCount) { index ->
        val centerX = (index + 0.5f) * slotWidth
        BarPosition(centerX = centerX, width = width)
    }
}

/**
 * Selects date label indices to prevent overlapping when there are many data points.
 */
fun calculateDateLabelIndices(pointCount: Int, maxLabels: Int = 7): Set<Int> {
    if (pointCount <= 0) return emptySet()
    if (pointCount <= maxLabels) return (0 until pointCount).toSet()

    val indices = mutableSetOf<Int>()
    val step = (pointCount - 1).toFloat() / (maxLabels - 1)
    for (i in 0 until maxLabels) {
        indices.add((i * step).roundToInt().coerceIn(0, pointCount - 1))
    }
    return indices
}

