package com.you.rcagent

import com.you.rcagent.core.HwAnomaly
import com.you.rcagent.core.HwSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HwAnomalyTest {
    private fun ok() = HwSample(
        ts = 0, phase = "IDLE", thermal = 0, battTempC = 30f, memAvailMb = 1000, memTotalMb = 3000,
        diskFreeMb = 5000, display = 2, touchDevs = 1, mainLagMs = 5,
    )

    @Test
    fun healthySampleIsQuiet() {
        assertTrue(HwAnomaly.classify(ok(), emptyList()).isEmpty())
    }

    @Test
    fun blackPanelWhileSystemThinksScreenIsOn() {
        val s = ok().copy(display = HwAnomaly.STATE_OFF, interactive = true)
        assertTrue(HwAnomaly.classify(s, emptyList()).isEmpty())
        assertTrue(HwAnomaly.classify(s, listOf(ok())).isEmpty())
        assertEquals(listOf("PANEL_OFF"), HwAnomaly.classify(s, listOf(s)))
        assertTrue(HwAnomaly.classify(s.copy(interactive = false), listOf(s)).isEmpty())
    }

    @Test
    fun touchGoneNeedsAPreviousTouchDevice() {
        val gone = ok().copy(touchDevs = 0)
        assertTrue("TOUCH_GONE" in HwAnomaly.classify(gone, listOf(ok())))
        assertFalse("TOUCH_GONE" in HwAnomaly.classify(gone, listOf(gone)))
        assertTrue("TOUCH_GONE" in HwAnomaly.classify(gone, emptyList()))
    }

    @Test
    fun unreadableSensorsAreNotAnomalies() {
        val s = ok().copy(battTempC = null, zoneMaxC = null, cpuPct = null, gpuPct = null, touchDevs = -1)
        assertTrue(HwAnomaly.classify(s, listOf(s, s)).isEmpty())
    }

    @Test
    fun gpuMustStayPeggedForSeveralSamples() {
        val hot = ok().copy(gpuPct = 99)
        assertFalse("GPU_PEGGED" in HwAnomaly.classify(hot, listOf(hot)))
        assertFalse("GPU_PEGGED" in HwAnomaly.classify(hot, listOf(ok(), hot)))
        assertTrue("GPU_PEGGED" in HwAnomaly.classify(hot, listOf(hot, hot)))
    }

    @Test
    fun thermalMemoryDiskAndLag() {
        assertTrue("THERMAL" in HwAnomaly.classify(ok().copy(thermal = 3), emptyList()))
        assertTrue("THERMAL" in HwAnomaly.classify(ok().copy(battTempC = 48f), emptyList()))
        assertTrue("LOW_MEM" in HwAnomaly.classify(ok().copy(memAvailMb = 100), emptyList()))
        assertTrue("DISK_LOW" in HwAnomaly.classify(ok().copy(diskFreeMb = 50), emptyList()))
        assertFalse("DISK_LOW" in HwAnomaly.classify(ok().copy(diskFreeMb = -1), emptyList()))
        assertTrue("MAIN_LAG" in HwAnomaly.classify(ok().copy(mainLagMs = 5000), emptyList()))
    }

    @Test
    fun anrDialogAndLabels() {
        assertTrue("ANR_DIALOG" in HwAnomaly.classify(ok().copy(anrDialog = true), emptyList()))
        assertTrue(com.you.rcagent.core.UiPulse.isAnrText("Kiosk isn't responding"))
        assertTrue(com.you.rcagent.core.UiPulse.isAnrText("Ứng dụng không phản hồi"))
        assertFalse(com.you.rcagent.core.UiPulse.isAnrText("Start now"))
        assertFalse(com.you.rcagent.core.UiPulse.isAnrText("Recording has stopped"))
        assertTrue(com.you.rcagent.core.UiPulse.isAnrText("Kiosk has stopped", broad = true))
        assertTrue(com.you.rcagent.core.UiPulse.isSystemPkg("android"))
        assertFalse(com.you.rcagent.core.UiPulse.isSystemPkg("com.kiosk.app"))
    }

    @Test
    fun reportCooldownPerCode() {
        val last = mapOf("THERMAL" to 1_000L)
        assertFalse(HwAnomaly.shouldReport("THERMAL", 2_000L, last, 600_000L))
        assertTrue(HwAnomaly.shouldReport("THERMAL", 700_000L, last, 600_000L))
        assertTrue(HwAnomaly.shouldReport("LOW_MEM", 2_000L, last, 600_000L))
    }
}
