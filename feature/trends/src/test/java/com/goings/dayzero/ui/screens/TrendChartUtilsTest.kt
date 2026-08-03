package com.goings.dayzero.ui.screens

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendChartUtilsTest {

    // 1. 最小值映射为绿色
    @Test
    fun `calculateBarColor maps min value to green`() {
        val color = calculateBarColor(value = 10f, minValue = 10f, maxValue = 100f)
        assertEquals(ChartGreen, color)
    }

    // 2. 中间值映射为黄色
    @Test
    fun `calculateBarColor maps mid value to yellow`() {
        val color = calculateBarColor(value = 55f, minValue = 10f, maxValue = 100f)
        assertEquals(ChartYellow, color)
    }

    // 3. 最大值映射为红色
    @Test
    fun `calculateBarColor maps max value to red`() {
        val color = calculateBarColor(value = 100f, minValue = 10f, maxValue = 100f)
        assertEquals(ChartRed, color)
    }

    // 4. 绿色到黄色之间颜色连续插值
    @Test
    fun `calculateBarColor interpolates green to yellow smoothly`() {
        val color25 = calculateBarColor(value = 32.5f, minValue = 10f, maxValue = 100f)
        assertTrue(color25 != ChartGreen && color25 != ChartYellow)
    }

    // 5. 黄色到红色之间颜色连续插值
    @Test
    fun `calculateBarColor interpolates yellow to red smoothly`() {
        val color75 = calculateBarColor(value = 77.5f, minValue = 10f, maxValue = 100f)
        assertTrue(color75 != ChartYellow && color75 != ChartRed)
    }

    // 6. 超出范围的标准化值被限制在 0~1
    @Test
    fun `calculateBarColor clamps out of range values`() {
        val belowMin = calculateBarColor(value = -50f, minValue = 10f, maxValue = 100f)
        val aboveMax = calculateBarColor(value = 200f, minValue = 10f, maxValue = 100f)
        assertEquals(ChartGreen, belowMin)
        assertEquals(ChartRed, aboveMax)
    }

    // 7. 全部数值相同时不会除以零
    @Test
    fun `calculateBarColor handles identical non-zero values without division by zero`() {
        val color = calculateBarColor(value = 50f, minValue = 50f, maxValue = 50f)
        assertEquals(ChartYellow, color)
    }

    // 8. 全部为 0 时使用绿色回退
    @Test
    fun `calculateBarColor handles all zero values with green fallback`() {
        val color = calculateBarColor(value = 0f, minValue = 0f, maxValue = 0f)
        assertEquals(ChartGreen, color)
    }

    // 9. 相同非零值使用黄色回退
    @Test
    fun `calculateBarColor handles identical non-zero values with yellow fallback`() {
        val color = calculateBarColor(value = 65f, minValue = 65f, maxValue = 65f)
        assertEquals(ChartYellow, color)
    }

    // 10. NaN 和无限值不参与范围计算
    @Test
    fun `filterValidChartPoints excludes NaN and infinite values`() {
        val rawPoints = listOf(
            ChartDataPoint("7/1", 70f, "70"),
            ChartDataPoint("7/2", Float.NaN, "NaN"),
            ChartDataPoint("7/3", Float.POSITIVE_INFINITY, "Inf"),
            ChartDataPoint("7/4", Float.NEGATIVE_INFINITY, "-Inf"),
            ChartDataPoint("7/5", 72f, "72")
        )
        val valid = filterValidChartPoints(rawPoints)
        assertEquals(2, valid.size)
        assertEquals("7/1", valid[0].dateLabel)
        assertEquals("7/5", valid[1].dateLabel)
    }

    // 11. 数值越高，柱体高度不会更低
    @Test
    fun `calculateBarHeightFraction is monotonic`() {
        val min = 50f
        val max = 100f
        val h1 = calculateBarHeightFraction(60f, min, max)
        val h2 = calculateBarHeightFraction(80f, min, max)
        val h3 = calculateBarHeightFraction(100f, min, max)
        assertTrue(h1 <= h2)
        assertTrue(h2 <= h3)
    }

    // 12. 相同数值得到相同高度
    @Test
    fun `calculateBarHeightFraction returns identical height for identical value`() {
        val h1 = calculateBarHeightFraction(70f, 50f, 100f)
        val h2 = calculateBarHeightFraction(70f, 50f, 100f)
        assertEquals(h1, h2, 0.0001f)
    }

    // 13. 空数据不会产生柱体
    @Test
    fun `calculateBarPositions returns empty list when point count is zero`() {
        val positions = calculateBarPositions(0, 300f)
        assertTrue(positions.isEmpty())
    }

    // 14. 单数据点可以正常绘制
    @Test
    fun `calculateBarPositions places single bar in center`() {
        val positions = calculateBarPositions(1, 300f)
        assertEquals(1, positions.size)
        assertEquals(150f, positions[0].centerX, 0.001f)
    }

    // 15. 柱体圆角不会超过柱宽和高度的合法范围
    @Test
    fun `calculateBarPositions respects bar width bounds`() {
        val positions = calculateBarPositions(7, 350f)
        assertEquals(7, positions.size)
        positions.forEach {
            assertTrue(it.width > 0f)
            assertTrue(it.width <= 36f)
        }
    }

    // 16. 最大柱顶部仍保留数值标签空间
    @Test
    fun `calculateBarHeightFraction leaves top space for max value`() {
        val maxFraction = calculateBarHeightFraction(100f, 50f, 100f)
        assertTrue(maxFraction < 1.0f)
    }

    // 17 & 18. 日期标签抽样策略
    @Test
    fun `calculateDateLabelIndices prevents label overlap for large datasets`() {
        val indices7 = calculateDateLabelIndices(7, maxLabels = 7)
        assertEquals(7, indices7.size)

        val indices30 = calculateDateLabelIndices(30, maxLabels = 7)
        assertTrue(indices30.size <= 7)
        assertTrue(indices30.contains(0))
        assertTrue(indices30.contains(29))
    }

    // 19 & 20. 错峰生长动画进度计算
    @Test
    fun `calculateBarProgress calculates staggered linear progress`() {
        val p0_start = calculateBarProgress(0, 0f, 5)
        assertEquals(0f, p0_start, 0.001f)

        val p0_end = calculateBarProgress(0, 1f, 5)
        assertEquals(1f, p0_end, 0.001f)

        val pLast_end = calculateBarProgress(4, 1f, 5)
        assertEquals(1f, pLast_end, 0.001f)
    }
}
