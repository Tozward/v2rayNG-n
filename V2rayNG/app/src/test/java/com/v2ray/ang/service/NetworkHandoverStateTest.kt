package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkHandoverStateTest {
    @Test
    fun initialNetworkAndRepeatedAvailabilityDoNotReload() {
        val state = NetworkHandoverState<String>()

        assertFalse(state.onAvailable("cellular"))
        assertFalse(state.onAvailable("cellular"))
    }

    @Test
    fun switchingNetworksReloadsAndLateLossDoesNotClearCurrentNetwork() {
        val state = NetworkHandoverState<String>()

        assertFalse(state.onAvailable("cellular"))
        assertTrue(state.onAvailable("wifi"))
        assertFalse(state.onLost("cellular"))
        assertFalse(state.onAvailable("wifi"))
    }

    @Test
    fun reconnectingSameNetworkReloadsAfterLoss() {
        val state = NetworkHandoverState<String>()

        assertFalse(state.onAvailable("cellular"))
        assertTrue(state.onLost("cellular"))
        assertTrue(state.onAvailable("cellular"))
    }

    @Test
    fun losingNewNetworkCancelsPendingHandover() {
        val state = NetworkHandoverState<String>()

        assertFalse(state.onAvailable("cellular"))
        assertTrue(state.onAvailable("wifi"))
        assertTrue(state.onLost("wifi"))
        assertTrue(state.onAvailable("cellular"))
    }

    @Test
    fun resetTreatsNextNetworkAsInitial() {
        val state = NetworkHandoverState<String>()

        assertFalse(state.onAvailable("cellular"))
        state.reset()
        assertFalse(state.onAvailable("wifi"))
    }
}
