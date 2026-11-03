package com.v2ray.ang.core

import android.content.Context
import android.content.res.AssetManager
import android.text.TextUtils
import com.google.gson.JsonParser
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.TunBackend
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.reset
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class CoreBackendSnapshotTest {
    @Test
    fun changingBackendAfterItsFirstReadCannotMixTemplateListenerAndResult() {
        for ((initial, later) in listOf(TunBackend.HEV to TunBackend.SING_NATIVE,
            TunBackend.SING_NATIVE to TunBackend.HEV, TunBackend.XRAY to TunBackend.HEV)) {
            Fixture().use { fixture ->
                fixture.initialBackend = initial
                fixture.laterBackend = later
                val result = CoreConfigManager.buildConfigResult(fixture.context())
                assertTrue(result.status)
                assertEquals(initial, result.tunBackend)
                val config = JsonUtil.fromJson(result.content, V2rayConfig::class.java)!!
                val core = config.inbounds.filter { it.protocol == "tun" || it.protocol == "sing-tun" }
                if (initial == TunBackend.HEV) {
                    assertTrue(core.isEmpty())
                    assertTrue(config.inbounds.any { it.protocol == "socks" })
                } else {
                    assertEquals(listOf(if (initial == TunBackend.XRAY) "tun" else "sing-tun"), core.map { it.protocol })
                    assertFalse(config.inbounds.any { it.protocol == "socks" })
                }
                assertEquals(listOf(if (initial.usesLocalSocks) "socks" else "tun"),
                    config.routing.rules.single { it.port == "53" }.inboundTag)
                fixture.assertSingleSnapshot()
            }
        }
    }

    @Test
    fun changingSavedModeCannotPartiallyEnableVpnOrRootConfiguration() {
        for ((vpn, root) in listOf(true to false, false to false, true to true, false to true)) {
            Fixture().use { fixture ->
                fixture.initialBackend = TunBackend.SING_NATIVE
                fixture.initialVpn = vpn
                fixture.initialRoot = root
                fixture.flipModes = true
                val result = CoreConfigManager.buildConfigResult(fixture.context())
                val config = JsonUtil.fromJson(result.content, V2rayConfig::class.java)!!
                val actualVpn = vpn && !root
                assertEquals(if (actualVpn) TunBackend.SING_NATIVE else null, result.tunBackend)
                assertEquals(actualVpn, config.inbounds.any { it.protocol == "sing-tun" })
                assertEquals(root, config.inbounds.any { it.protocol == "socks" })
                val dns = config.routing.rules.singleOrNull { it.port == "53" }
                assertEquals(if (actualVpn) listOf("tun") else if (root) listOf("socks") else null, dns?.inboundTag)
                fixture.assertSingleSnapshot()
            }
        }
    }

    @Test
    fun customConfigAddsTheCapturedNativeReaderDespiteLaterHevSelection() {
        Fixture().use { fixture ->
            fixture.initialBackend = TunBackend.SING_NATIVE
            fixture.laterBackend = TunBackend.HEV
            fixture.raw = """{"inbounds":[{"tag":"user","protocol":"socks","port":18080}]}"""
            val result = CoreConfigManager.buildConfigResult(fixture.context(custom = true))
            val json = JsonParser.parseString(result.content).asJsonObject
            assertEquals(TunBackend.SING_NATIVE, result.tunBackend)
            assertEquals(listOf("socks", "sing-tun"), json.getAsJsonArray("inbounds").map { it.asJsonObject["protocol"].asString })
            assertEquals(18080, json.getAsJsonArray("inbounds")[0].asJsonObject["port"].asInt)
            fixture.assertSingleSnapshot()
        }
    }

    @Test
    fun explicitCustomReaderWinsWithoutRewritingItsSettings() {
        for ((protocol, backend) in listOf("tun" to TunBackend.XRAY, "sing-tun" to TunBackend.SING_NATIVE)) {
            Fixture().use { fixture ->
                fixture.initialBackend = TunBackend.HEV
                fixture.laterBackend = TunBackend.SING_NATIVE
                fixture.raw = """{"inbounds":[{"tag":"own-reader","protocol":"$protocol","settings":{"mtu":1379,"tcpLocalJumbo":false}}]}"""
                val result = CoreConfigManager.buildConfigResult(fixture.context(custom = true))
                assertEquals(backend, result.tunBackend)
                assertEquals(JsonParser.parseString(fixture.raw), JsonParser.parseString(result.content))
                fixture.assertSingleSnapshot()
            }
        }
    }

    @Test
    fun customNativeCannotUseALaterModeReadToBypassTheVpnRequirement() {
        for ((vpn, root) in listOf(false to false, true to true)) {
            Fixture().use { fixture ->
                fixture.initialVpn = vpn
                fixture.initialRoot = root
                fixture.flipModes = true
                fixture.raw = """{"inbounds":[{"protocol":"sing-tun"}]}"""
                val error = assertThrows(CoreTunConfigException::class.java) {
                    CoreConfigManager.buildConfigResult(fixture.context(custom = true))
                }
                assertEquals(R.string.error_tun_vpn_mode_required, error.messageRes)
                fixture.assertSingleSnapshot()
            }
        }
    }

    @Test
    fun speedtestMetadataDoesNotRereadTheBackendAfterRemovingItsInbounds() {
        Fixture().use { fixture ->
            fixture.initialBackend = TunBackend.SING_NATIVE
            fixture.laterBackend = TunBackend.HEV
            val result = CoreConfigManager.buildConfigResult(fixture.context(), speedtest = true)
            assertEquals(TunBackend.SING_NATIVE, result.tunBackend)
            val config = JsonUtil.fromJson(result.content, V2rayConfig::class.java)!!
            assertTrue(config.inbounds.isEmpty())
            assertNull(config.dns)
            fixture.assertSingleSnapshot()
        }
    }

    private class Fixture : AutoCloseable {
        // Gradle's app test task uses the app project directory as its working directory.
        private val assetsDirectory = File("src/main/assets").also { check(it.isDirectory) }
        private val mmkv = mockStatic(MMKV::class.java)
        private val textUtils = mockStatic(TextUtils::class.java)
        private val context = mock<Context>()
        private val assets = mock<AssetManager>()
        private var settings: MMKV? = null
        private var rawStorage: MMKV? = null
        private var backendReads = 0
        private var modeReads = 0
        private var rootReads = 0
        private var routeOnlyReads = 0
        var initialBackend = TunBackend.HEV
        var laterBackend = TunBackend.SING_NATIVE
        var initialVpn = true
        var initialRoot = false
        var flipModes = false
        var raw: String? = null

        init {
            mmkv.`when`<MMKV> { MMKV.mmkvWithID("SETTING", MMKV.MULTI_PROCESS_MODE) }.thenReturn(mock<MMKV>())
            mmkv.`when`<MMKV> { MMKV.mmkvWithID("SERVER_RAW", MMKV.MULTI_PROCESS_MODE) }.thenReturn(mock<MMKV>())
            // MMKV handles are lazy singletons. Bind to the active mocks rather than
            // assume another test has not already initialized either handle.
            whenever(MmkvManager.decodeSettingsString(AppConfig.PREF_MODE)).thenAnswer {
                settings = it.mock as MMKV
                null
            }
            MmkvManager.decodeSettingsString(AppConfig.PREF_MODE)
            whenever(MmkvManager.decodeServerRaw("snapshot")).thenAnswer {
                rawStorage = it.mock as MMKV
                raw
            }
            MmkvManager.decodeServerRaw("snapshot")
            val storage = checkNotNull(settings)
            whenever(storage.decodeBool(any(), any())).thenAnswer { it.getArgument<Boolean>(1) }
            whenever(storage.decodeString(AppConfig.PREF_TUN_BACKEND)).thenAnswer {
                if (backendReads++ == 0) initialBackend.key else laterBackend.key
            }
            whenever(storage.decodeString(AppConfig.PREF_MODE)).thenAnswer {
                val value = if (modeReads++ == 0 || !flipModes) initialVpn else !initialVpn
                if (value) AppConfig.VPN else "Proxy only"
            }
            whenever(storage.decodeBool(AppConfig.PREF_ROOT_MODE_ENABLE, false)).thenAnswer {
                if (rootReads++ == 0 || !flipModes) initialRoot else !initialRoot
            }
            whenever(storage.decodeBool(AppConfig.PREF_ROUTE_ONLY_ENABLED, false)).thenAnswer { routeOnlyReads++; true }
            whenever(storage.decodeBool(AppConfig.PREF_LOCAL_DNS_ENABLED, false)).thenReturn(true)
            whenever(storage.decodeBool(AppConfig.PREF_ENABLE_LOCAL_PROXY, true)).thenReturn(false)
            whenever(storage.decodeString(AppConfig.PREF_REMOTE_DNS)).thenReturn("1.1.1.1")
            whenever(storage.decodeString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD,
                AppConfig.DEFAULT_OUTBOUND_DOMAIN_RESOLVE_METHOD)).thenReturn("0")
            whenever(context.assets).thenReturn(assets)
            whenever(assets.open(any())).thenAnswer { File(assetsDirectory, it.getArgument<String>(0)).inputStream() }
        }

        fun context(custom: Boolean = false): CoreConfigContext {
            val profile = ProfileItem.create(EConfigType.SOCKS).apply {
                remarks = "Synthetic snapshot fixture"
                server = "127.0.0.1"
                serverPort = "47183"
            }
            return CoreConfigContext(context, "snapshot", isCustom = custom, resolvedOutbounds = listOf(
                CoreConfigContext.ResolvedOutbound(AppConfig.TAG_PROXY, profile, listOf(profile), CoreResolvedType.NORMAL)
            ))
        }

        fun assertSingleSnapshot() {
            assertEquals(listOf(1, 1, 1, 1), listOf(backendReads, modeReads, rootReads, routeOnlyReads))
        }

        override fun close() {
            settings?.let { reset(it) }
            rawStorage?.let { reset(it) }
            textUtils.close()
            mmkv.close()
        }
    }
}
