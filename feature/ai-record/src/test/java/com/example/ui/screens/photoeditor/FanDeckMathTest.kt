package com.example.ui.screens.photoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FanDeckMathTest {

    @Test
    fun centerCardIsFlatLargestAndOnTop() {
        assertEquals(0f, FanDeckMath.rotationFor(0f), 0.0001f)
        assertEquals(1f, FanDeckMath.scaleFor(0f), 0.0001f)
        assertEquals(0f, FanDeckMath.translationXFor(0f, 300f), 0.0001f)
        assertEquals(0f, FanDeckMath.verticalSinkDpFor(0f), 0.0001f)
        assertEquals(1f, FanDeckMath.alphaFor(0f), 0.0001f)
        assertTrue(FanDeckMath.zIndexFor(0f) > FanDeckMath.zIndexFor(1f))
        assertTrue(FanDeckMath.zIndexFor(0f) > FanDeckMath.zIndexFor(-2f))
    }

    @Test
    fun neighborsRotateRestrainedlyAndClamped() {
        assertEquals(FanDeckMath.ROTATION_PER_SLOT, FanDeckMath.rotationFor(1f), 0.0001f)
        assertEquals(-FanDeckMath.ROTATION_PER_SLOT, FanDeckMath.rotationFor(-1f), 0.0001f)
        for (offset in listOf(-9f, -3f, 3f, 9f)) {
            assertTrue(abs(FanDeckMath.rotationFor(offset)) <= FanDeckMath.MAX_ROTATION)
        }
    }

    @Test
    fun scaleAndAlphaNeverCollapse() {
        for (offset in listOf(-6f, -2.5f, 2.5f, 6f)) {
            assertTrue(FanDeckMath.scaleFor(offset) >= FanDeckMath.MIN_SCALE)
            assertTrue(FanDeckMath.alphaFor(offset) >= FanDeckMath.MIN_ALPHA)
            assertTrue(FanDeckMath.verticalSinkDpFor(offset) <= FanDeckMath.MAX_VERTICAL_SINK_DP)
        }
    }

    @Test
    fun snapRoundsToNearestSlot() {
        assertEquals(1, FanDeckMath.snapTargetIndex(1.2f, 0f, 5))
        assertEquals(2, FanDeckMath.snapTargetIndex(1.6f, 0f, 5))
        assertEquals(0, FanDeckMath.snapTargetIndex(-0.4f, 0f, 5))
        assertEquals(4, FanDeckMath.snapTargetIndex(4.4f, 0f, 5))
    }

    @Test
    fun snapClampsToDeckBounds() {
        assertEquals(0, FanDeckMath.snapTargetIndex(-3f, 0f, 4))
        assertEquals(3, FanDeckMath.snapTargetIndex(9f, 0f, 4))
        assertEquals(0, FanDeckMath.snapTargetIndex(2f, 0f, 0))
    }

    @Test
    fun modestFlingNudgesOneSlotInFlingDirection() {
        // Fling left (negative velocity) advances to the next slot.
        assertEquals(2, FanDeckMath.snapTargetIndex(1.1f, -2000f, 5))
        // Fling right (positive velocity) goes back one slot.
        assertEquals(1, FanDeckMath.snapTargetIndex(1.9f, 2000f, 5))
        // Below the threshold the fling is ignored.
        assertEquals(1, FanDeckMath.snapTargetIndex(1.1f, -300f, 5))
    }

    @Test
    fun dragConvertsToSlotsAgainstDragDirection() {
        val cardWidth = 300f
        val slotWidth = cardWidth * FanDeckMath.TRANSLATION_PER_SLOT_FRACTION
        assertEquals(-1f, FanDeckMath.dragToSlotDelta(slotWidth, cardWidth), 0.0001f)
        assertEquals(1f, FanDeckMath.dragToSlotDelta(-slotWidth, cardWidth), 0.0001f)
        assertEquals(0f, FanDeckMath.dragToSlotDelta(100f, 0f), 0.0001f)
    }

    @Test
    fun continuousCenterAllowsOnlySoftOverscroll() {
        assertEquals(-0.33f, FanDeckMath.clampContinuousCenter(-5f, 4), 0.0001f)
        assertEquals(3.33f, FanDeckMath.clampContinuousCenter(9f, 4), 0.0001f)
        assertEquals(1.5f, FanDeckMath.clampContinuousCenter(1.5f, 4), 0.0001f)
    }
}
