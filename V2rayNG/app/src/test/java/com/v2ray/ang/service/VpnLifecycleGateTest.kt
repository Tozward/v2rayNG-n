package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.Job

class VpnLifecycleGateTest {
    @Test
    fun replacementWaitsForThePreviousInstanceCompletion() {
        val barrier = VpnTeardownBarrier()
        val first = Job()
        val second = Job()
        val third = Job()
        assertNull(barrier.register(first))
        assertSame(first, barrier.register(second))
        first.complete()
        // An old completion must not erase the newer instance's barrier.
        assertSame(second, barrier.register(third))
        second.complete()
        third.complete()
        assertNull(barrier.register(Job().apply { complete() }))
    }

    @Test
    fun coreStartAloneDoesNotMakeAnUnfinishedTunnelReusable() {
        val gate = VpnLifecycleGate()
        assertTrue(gate.tryStart())
        assertFalse(gate.shouldReuseRunningTunnel(false, true))
        assertFalse(gate.tryStart())
        assertTrue(gate.shouldReuseRunningTunnel(true, true))
        gate.beginStop()
        assertFalse(gate.shouldReuseRunningTunnel(true, true))
    }

    @Test
    fun systemRestartReusesOnlyAHealthyTunnel() {
        val gate = VpnLifecycleGate()

        assertFalse(gate.shouldReuseRunningTunnel(serviceRunning = false, coreRunning = false))
        assertTrue(gate.shouldReuseRunningTunnel(serviceRunning = true, coreRunning = true))
        assertFalse(gate.shouldReuseRunningTunnel(serviceRunning = true, coreRunning = false))
        gate.beginStop()
        assertFalse(gate.shouldReuseRunningTunnel(serviceRunning = true, coreRunning = true))
    }

    @Test
    fun systemStartDuringHandoverKeepsTheExistingTunnel() {
        val gate = VpnLifecycleGate()

        assertTrue(gate.shouldReuseRunningTunnel(true, false, coreReloading = true))
        assertTrue(gate.shouldReuseRunningTunnel(true, true, coreReloading = false))
        assertFalse(gate.shouldReuseRunningTunnel(true, false, coreReloading = false))
        assertFalse(gate.shouldReuseRunningTunnel(false, false, coreReloading = true))
        gate.beginStop()
        assertFalse(gate.shouldReuseRunningTunnel(true, false, coreReloading = true))
    }

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
