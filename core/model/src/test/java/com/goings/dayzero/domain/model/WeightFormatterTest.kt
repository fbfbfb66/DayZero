package com.goings.dayzero.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class WeightFormatterTest {

    @Test
    fun `formats one decimal place`() {
        assertEquals("87.8", formatWeightKg(87.8))
    }

    @Test
    fun `strips trailing zero for integer value`() {
        assertEquals("88", formatWeightKg(88.0))
    }

    @Test
    fun `rounds floating point noise down to one decimal`() {
        assertEquals("87.8", formatWeightKg(87.800003))
    }

    @Test
    fun `rounds up correctly`() {
        assertEquals("87.8", formatWeightKg(87.84999))
    }

    @Test
    fun `normalizes input to one decimal place`() {
        assertEquals(87.8, normalizeWeightKg(87.800003), 0.0001)
        assertEquals(88.0, normalizeWeightKg(88.0), 0.0001)
        assertEquals(87.8, normalizeWeightKg(87.84999), 0.0001)
    }
}
