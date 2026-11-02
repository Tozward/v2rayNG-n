package com.v2ray.ang.dto

import com.v2ray.ang.enums.TunBackend
import com.v2ray.ang.handler.SettingsManager
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
        TunBackend.selectable.forEach { backend ->
            assertEquals(backend, TunBackend.fromSettings(backend.key, true))
            assertEquals(backend, TunBackend.fromSettings(backend.key, false))
        }
        assertFalse(TunBackend.XRAY.usesLocalSocks)
        assertTrue(TunBackend.HEV.usesLocalSocks)
        assertTrue(TunBackend.SING.usesLocalSocks)
        assertFalse(TunBackend.SING_NATIVE.usesLocalSocks)
        assertEquals("sing_native", TunBackend.SING_NATIVE.key)
    }

    @Test
    fun retiredSingKeySelectsTheNativeBackend() {
        assertEquals(TunBackend.SING_NATIVE, TunBackend.fromSettings("sing", true))
        assertEquals(TunBackend.SING_NATIVE, TunBackend.fromSettings("sing", false))
        assertEquals(listOf("xray", "hev", "sing_native"), TunBackend.selectable.map { it.key })
    }

    @Test
    fun onlyAnActualVpnServiceUsesACoreTunForEverySavedBackend() {
        TunBackend.entries.forEach { backend ->
            listOf(false, true).forEach { vpn ->
                listOf(false, true).forEach { root ->
                    assertEquals(vpn && !root, SettingsManager.isVpnServiceMode(vpn, root))
                    assertEquals(
                        vpn && !root && (backend == TunBackend.XRAY || backend == TunBackend.SING_NATIVE),
                        SettingsManager.usesCoreTun(backend, vpn, root),
                        "backend=$backend, vpn=$vpn, root=$root"
                    )
                }
            }
        }
    }
}
