package com.you.rcagent

import com.you.rcagent.core.ConnPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnPolicyTest {
    private val min = 60_000L

    @Test
    fun flapNeedsEnoughDropsInsideTheWindow() {
        val now = 100 * min
        assertFalse(ConnPolicy.shouldReportFlap(listOf(now - 1 * min, now - 2 * min, now - 3 * min), now, 0))
        assertTrue(ConnPolicy.shouldReportFlap(listOf(now - 1 * min, now - 2 * min, now - 3 * min, now - 4 * min), now, 0))
        // 4 lần nhưng có một lần quá cửa sổ 10 phút → chưa đủ
        assertFalse(ConnPolicy.shouldReportFlap(listOf(now - 1 * min, now - 2 * min, now - 3 * min, now - 20 * min), now, 0))
    }

    @Test
    fun flapReportHasACooldown() {
        val now = 100 * min
        val drops = List(5) { now - (it + 1) * min }
        assertFalse(ConnPolicy.shouldReportFlap(drops, now, now - 5 * min))
        assertTrue(ConnPolicy.shouldReportFlap(drops, now, now - 31 * min))
    }

    @Test
    fun reasonCodesAreNamed() {
        assertEquals("administrative_action(152)", ConnPolicy.reasonName(152))
        assertEquals("session_taken_over(142)", ConnPolicy.reasonName(142))
        assertEquals("keepalive_timeout(141)", ConnPolicy.reasonName(141))
        assertEquals(null, ConnPolicy.reasonName(32109))
        assertEquals(null, ConnPolicy.reasonName(null))
    }

    @Test
    fun summarizeGroupsAndOrdersReasons() {
        assertEquals(
            "EOF x2, Connection lost x1",
            ConnPolicy.summarize(listOf("Connection lost", "EOF", "EOF")),
        )
    }
}
