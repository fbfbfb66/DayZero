package com.goings.dayzero.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PinnedPhotoStripTest {

    @Test
    fun singlePhotoLiesFlat() {
        for (id in listOf("media1", "x", "long_string_id_3", "123")) {
            assertEquals(0f, PinnedPhotoStripLogic.calculateStableRotation(id, 0, 1), 0.0001f)
        }
    }

    @Test
    fun sameMediaIdStableRotation() {
        val rot1 = PinnedPhotoStripLogic.calculateStableRotation("media1", 1, 3)
        val rot2 = PinnedPhotoStripLogic.calculateStableRotation("media1", 1, 3)
        assertEquals(rot1, rot2, 0.0001f)
    }

    @Test
    fun rotationAngleWithinRestrainedBounds() {
        val ids = listOf("media1", "media2", "long_string_id_3", "123", "id", "a", "b", "c")
        for (id in ids) {
            for (index in 0 until 5) {
                val rot = PinnedPhotoStripLogic.calculateStableRotation(id, index, 5)
                assertTrue(
                    "Rotation $rot for $id at $index exceeds ${PinnedPhotoStripLogic.MAX_ROTATION_DEGREES}",
                    abs(rot) <= PinnedPhotoStripLogic.MAX_ROTATION_DEGREES
                )
            }
        }
    }

    @Test
    fun endPhotosAreDampedRelativeToMiddle() {
        // Same mediaId at an end slot must never rotate more than in a middle slot.
        for (id in listOf("media1", "abc", "42")) {
            val middle = abs(PinnedPhotoStripLogic.calculateStableRotation(id, 1, 4))
            val first = abs(PinnedPhotoStripLogic.calculateStableRotation(id, 0, 4))
            val last = abs(PinnedPhotoStripLogic.calculateStableRotation(id, 3, 4))
            assertTrue(first <= middle + 0.0001f)
            assertTrue(last <= middle + 0.0001f)
        }
    }

    @Test
    fun verticalOffsetIsGentleStairStep() {
        assertEquals(0f, PinnedPhotoStripLogic.verticalOffsetDp(0, 1), 0.0001f)
        assertEquals(0f, PinnedPhotoStripLogic.verticalOffsetDp(0, 4), 0.0001f)
        assertEquals(3f, PinnedPhotoStripLogic.verticalOffsetDp(1, 4), 0.0001f)
        assertEquals(0f, PinnedPhotoStripLogic.verticalOffsetDp(2, 4), 0.0001f)
        assertEquals(3f, PinnedPhotoStripLogic.verticalOffsetDp(3, 4), 0.0001f)
    }

    @Test
    fun tapeRotationIsStableAndSmall() {
        for (id in listOf("media1", "media2", "z", "0")) {
            val r1 = PinnedPhotoStripLogic.tapeRotation(id)
            val r2 = PinnedPhotoStripLogic.tapeRotation(id)
            assertEquals(r1, r2, 0.0001f)
            assertTrue("Tape rotation $r1 out of bounds", abs(r1) in 2f..6f)
        }
    }
}
