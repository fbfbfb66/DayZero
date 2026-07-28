package com.goings.dayzero.ui.screens.photoeditor

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure geometry for the fan photo deck: the center card lies flat, largest and
 * on top; neighbors rotate and shift with a restrained, distance-based falloff.
 * All functions take a continuous "slot offset" (item index minus the current
 * continuous center position) so dragging animates smoothly between slots.
 */
object FanDeckMath {
    /** Degrees of rotation per slot away from the center; clamped so the deck never fans wildly. */
    const val ROTATION_PER_SLOT = 7f
    const val MAX_ROTATION = 16f

    /** Horizontal travel per slot, as a fraction of the card width. */
    const val TRANSLATION_PER_SLOT_FRACTION = 0.56f

    /** Scale falloff per slot; the center card is 1.0. */
    const val SCALE_FALLOFF_PER_SLOT = 0.10f
    const val MIN_SCALE = 0.72f

    /** Vertical sink per slot in dp, mimicking cards resting lower in the fan. */
    const val VERTICAL_SINK_DP_PER_SLOT = 12f
    const val MAX_VERTICAL_SINK_DP = 30f

    const val MIN_ALPHA = 0.42f

    fun rotationFor(slotOffset: Float): Float =
        (slotOffset * ROTATION_PER_SLOT).coerceIn(-MAX_ROTATION, MAX_ROTATION)

    fun scaleFor(slotOffset: Float): Float =
        (1f - SCALE_FALLOFF_PER_SLOT * abs(slotOffset)).coerceAtLeast(MIN_SCALE)

    fun translationXFor(slotOffset: Float, cardWidthPx: Float): Float =
        slotOffset * cardWidthPx * TRANSLATION_PER_SLOT_FRACTION

    fun verticalSinkDpFor(slotOffset: Float): Float =
        (abs(slotOffset) * VERTICAL_SINK_DP_PER_SLOT).coerceAtMost(MAX_VERTICAL_SINK_DP)

    fun alphaFor(slotOffset: Float): Float =
        (1f - 0.20f * abs(slotOffset)).coerceIn(MIN_ALPHA, 1f)

    /** Center card draws on top; z falls off with distance. */
    fun zIndexFor(slotOffset: Float): Float = -abs(slotOffset)

    /**
     * Snap target after a drag ends. [continuousCenter] is the fractional center
     * position; a modest fling nudges one extra slot in the fling direction.
     */
    fun snapTargetIndex(
        continuousCenter: Float,
        velocityPxPerSecond: Float,
        itemCount: Int,
        flingThresholdPxPerSecond: Float = 900f
    ): Int {
        if (itemCount <= 0) return 0
        var target = continuousCenter.roundToInt()
        if (velocityPxPerSecond <= -flingThresholdPxPerSecond) target = continuousCenter.toInt() + 1
        if (velocityPxPerSecond >= flingThresholdPxPerSecond) target = kotlin.math.ceil(continuousCenter).toInt() - 1
        return target.coerceIn(0, itemCount - 1)
    }

    /** Converts a horizontal drag distance into slot units (drag right moves the deck to earlier slots). */
    fun dragToSlotDelta(dragPx: Float, cardWidthPx: Float): Float {
        if (cardWidthPx <= 0f) return 0f
        return -dragPx / (cardWidthPx * TRANSLATION_PER_SLOT_FRACTION)
    }

    /** Clamp a continuous center position within the deck, allowing a soft third-of-slot overscroll. */
    fun clampContinuousCenter(center: Float, itemCount: Int): Float {
        if (itemCount <= 0) return 0f
        return center.coerceIn(-0.33f, (itemCount - 1) + 0.33f)
    }
}
