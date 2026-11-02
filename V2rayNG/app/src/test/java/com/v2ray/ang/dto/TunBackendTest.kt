package com.v2ray.ang.dto

import com.v2ray.ang.enums.TunBackend
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TunBackendTest {
    @Test
    fun precedingReleaseSettingsKeepTheirBackend() {
        assertEquals(TunBackend.HEV, TunBackend.fromSettings(null, true))
        assertEquals(TunBackend.XRAY, TunBackend.fromSettings(null, false))
        assertEquals(TunBackend.HEV, TunBackend.fromSettings("unknown", true))
        assertEquals(TunBackend.XRAY, TunBackend.fromSettings("", false))
    }

    @Test
    fun explicitBackendOverridesTheLegacySwitch() {
        TunBackend.entries.forEach { backend ->
            assertEquals(backend, TunBackend.fromSettings(backend.key, true))
            assertEquals(backend, TunBackend.fromSettings(backend.key, false))
        }
        assertFalse(TunBackend.XRAY.usesLocalSocks)
        assertTrue(TunBackend.HEV.usesLocalSocks)
        assertTrue(TunBackend.SING.usesLocalSocks)
    }
}
