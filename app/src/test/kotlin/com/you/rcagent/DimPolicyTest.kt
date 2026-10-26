package com.you.rcagent

import com.you.rcagent.core.DimPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DimPolicyTest {
    @Test
    fun parseAfterMinHandlesOffAndBounds() {
        assertEquals(0, DimPolicy.parseAfterMin("off"))
        assertEquals(0, DimPolicy.parseAfterMin("-1"))
        assertEquals(5, DimPolicy.parseAfterMin(" 5 "))
        assertEquals(24 * 60, DimPolicy.parseAfterMin("99999"))
        assertNull(DimPolicy.parseAfterMin("abc"))
    }

    @Test
    fun levelIsClamped() {
        assertEquals(DimPolicy.MIN_LEVEL, DimPolicy.clampLevel(0))
        assertEquals(DimPolicy.MAX_LEVEL, DimPolicy.clampLevel(100))
        assertEquals(30, DimPolicy.clampLevel(30))
    }

    @Test
    fun alphaDarkensButStaysReadable() {
        assertEquals(0.7f, DimPolicy.alphaFor(30), 0.001f)
        assertEquals(0.8f, DimPolicy.alphaFor(5), 0.001f)
        assertEquals(0.2f, DimPolicy.alphaFor(80), 0.001f)
    }

    @Test
    fun dimsOnlyWhenIdleAndAllowed() {
        val min = 60_000L
        assertTrue(DimPolicy.shouldDim(5 * min, 5 * min, false, true, true))
        assertFalse(DimPolicy.shouldDim(4 * min, 5 * min, false, true, true))
        assertFalse(DimPolicy.shouldDim(9 * min, 0, false, true, true))
        assertFalse(DimPolicy.shouldDim(9 * min, 5 * min, true, true, true))
        assertFalse(DimPolicy.shouldDim(9 * min, 5 * min, false, false, true))
        assertFalse(DimPolicy.shouldDim(9 * min, 5 * min, false, true, false))
    }
}
