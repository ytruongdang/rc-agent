package com.you.rcagent

import com.you.rcagent.boot.KioskReturn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskReturnTest {
    @Test
    fun mdmTargetBeatsSeenTarget() {
        assertEquals("a.kiosk", KioskReturn.pickTarget("a.kiosk", "b.seen"))
        assertEquals("b.seen", KioskReturn.pickTarget(null, "b.seen"))
    }

    @Test
    fun neverTargetsSelfOrInstallerOrSystem() {
        assertNull(KioskReturn.pickTarget("com.you.rcagent", "com.android.systemui"))
        assertEquals("b.seen", KioskReturn.pickTarget("com.google.android.packageinstaller", "b.seen"))
        assertFalse(KioskReturn.isCandidate("android"))
        assertFalse(KioskReturn.isCandidate(""))
        assertTrue(KioskReturn.isCandidate("com.hmdm.launcher"))
    }

    @Test
    fun returnsOnlyWhenWeCoverTheScreen() {
        assertTrue(KioskReturn.needsReturn(null))
        assertTrue(KioskReturn.needsReturn("com.you.rcagent"))
        assertFalse(KioskReturn.needsReturn("a.kiosk"))
    }
}
