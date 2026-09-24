package com.v2ray.ang.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnLifecycleGateTest {
    @Test
    fun normalStartAndRepeatedStartKeepOneSetup() {
        val gate = VpnLifecycleGate()

        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        gate.releaseStart()
        assertTrue(gate.tryStart())
    }

    @Test
    fun normalStopAndRepeatedStopRunTeardownOnce() {
        val gate = VpnLifecycleGate()

        assertTrue(gate.tryStart())
        assertTrue(gate.beginStop())
        assertTrue(gate.isStopping())
        assertFalse(gate.beginStop())
        assertFalse(gate.tryStart())
    }

    @Test
    fun stopDuringSetupPreventsAnotherStartEvenAfterUnlock() {
        val gate = VpnLifecycleGate()

        assertTrue(gate.tryStart())
        assertTrue(gate.beginStop())
        gate.releaseStart()
        assertFalse(gate.tryStart())
    }
}
