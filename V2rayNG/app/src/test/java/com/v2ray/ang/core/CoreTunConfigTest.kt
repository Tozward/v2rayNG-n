package com.v2ray.ang.core

import com.google.gson.JsonParser
import com.v2ray.ang.R
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.enums.TunBackend
import com.v2ray.ang.enums.VpnInterfaceAddressConfig
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CoreTunConfigTest {
    private fun template() = V2rayConfig.InboundBean(
        tag = "tun", port = null, protocol = "tun",
        settings = V2rayConfig.InboundBean.InSettingsBean(name = "xray0", mtu = 1500, userLevel = 8),
        sniffing = V2rayConfig.InboundBean.SniffingBean(
            enabled = true, destOverride = arrayListOf("http", "tls", "quic", "fakedns"), routeOnly = true
        )
    )

    @Test
    fun nativeInboundUsesOnlySupportedSettingsAndAndroidClientPrefixes() {
        VpnInterfaceAddressConfig.entries.forEach { addresses ->
            listOf(false, true).forEach { ipv6 ->
                val inbound = template()
                val sniffing = inbound.sniffing
                inbound.port = 10808
                inbound.listen = "127.0.0.1"
                inbound.settings?.auth = "password"
                inbound.settings?.udp = true
                CoreConfigManager.configureTunInbound(inbound, TunBackend.SING_NATIVE, 1280, addresses, ipv6)
                val json = JsonParser.parseString(JsonUtil.toJson(inbound)).asJsonObject
                assertEquals("sing-tun", inbound.protocol)
                assertEquals("tun", inbound.tag)
                assertNull(inbound.port)
                assertNull(inbound.listen)
                assertSame(sniffing, inbound.sniffing)
                val settings = json.getAsJsonObject("settings")
                assertEquals(setOf("name", "mtu", "userLevel", "address"), settings.keySet())
                assertEquals(1280, settings["mtu"].asInt)
                assertEquals(8, settings["userLevel"].asInt)
                assertEquals(
                    listOf("${addresses.ipv4Client}/30") + if (ipv6) listOf("${addresses.ipv6Client}/126") else emptyList(),
                    settings.getAsJsonArray("address").map { it.asString }
                )
            }
        }
    }

    @Test
    fun xrayInboundKeepsItsProtocolSettingsAndSniffing() {
        val inbound = template()
        val settings = inbound.settings
        val sniffing = inbound.sniffing
        CoreConfigManager.configureTunInbound(inbound, TunBackend.XRAY, 1400, VpnInterfaceAddressConfig.OPTION_1, true)
        assertEquals("tun", inbound.protocol)
        assertSame(settings, inbound.settings)
        assertEquals(1400, settings?.mtu)
        assertNull(settings?.address)
        assertSame(sniffing, inbound.sniffing)
        listOf(TunBackend.HEV, TunBackend.SING).forEach { backend ->
            assertThrows(IllegalArgumentException::class.java) {
                CoreConfigManager.configureTunInbound(template(), backend, 1400, VpnInterfaceAddressConfig.OPTION_1, false)
            }
        }
    }

    @Test
    fun customExplicitCoreProtocolWinsWithoutAddingOrRewritingAnInbound() {
        listOf("tun" to TunBackend.XRAY, "sing-tun" to TunBackend.SING_NATIVE).forEach { (protocol, expected) ->
            val json = JsonParser.parseString("""{"inbounds":[{"tag":"user-tun","protocol":"$protocol","settings":{"mtu":1380}}]}""").asJsonObject
            val original = json.deepCopy()
            CoreConfigManager.addTunInboundIfAbsent(json) { error("Explicit TUN must not load a template") }
            assertEquals(original, json)
            TunBackend.entries.forEach { selected ->
                assertEquals(expected, CoreConfigManager.resolveTunBackend(CoreConfigManager.explicitTunBackend(json), selected))
            }
        }
    }

    @Test
    fun customNativeTunRequiresActualVpnModeRegardlessOfSavedBackend() {
        val json = JsonParser.parseString("""{"inbounds":[{"protocol":"sing-tun"}]}""").asJsonObject
        val original = json.deepCopy()
        TunBackend.entries.forEach { backend ->
            listOf(false, true).forEach { vpn ->
                listOf(false, true).forEach { root ->
                    val actualVpn = SettingsManager.isVpnServiceMode(vpn, root)
                    if (actualVpn) {
                        val explicit = CoreConfigManager.explicitTunBackend(json, actualVpn)
                        assertEquals(TunBackend.SING_NATIVE, CoreConfigManager.resolveTunBackend(explicit, backend))
                    } else {
                        val error = assertThrows(CoreTunConfigException::class.java) {
                            CoreConfigManager.explicitTunBackend(json, actualVpn)
                        }
                        assertEquals(R.string.error_tun_vpn_mode_required, error.messageRes)
                    }
                    assertEquals(original, json)
                }
            }
        }
    }

    @Test
    fun nativeProtocolCannotHideInCustomNonVpnCaseOrNullOverwriteFields() {
        listOf(
            """{"INBOUNDS":[{"PROTOCOL":"SING-TUN"}]}""",
            """{"inbounds":[{"protocol":"sing-tun","PROTOCOL":null}]}""",
            """{"inbounds":[{"protocol":"sing-tun"}],"INBOUNDS":null}"""
        ).forEach { content ->
            val json = JsonParser.parseString(content).asJsonObject
            val error = assertThrows(CoreTunConfigException::class.java) { CoreConfigManager.explicitTunBackend(json, false) }
            assertEquals(R.string.error_tun_vpn_mode_required, error.messageRes)
        }
    }

    @Test
    fun nonVpnLegacyCustomConfigsKeepTheirOriginalInbounds() {
        listOf(
            """{"inbounds":[{"protocol":"tun"},{"protocol":"tun"}]}""",
            """{"inbounds":[{"protocol":"socks","port":10808}]}"""
        ).forEach { content ->
            val json = JsonParser.parseString(content).asJsonObject
            val original = json.deepCopy()
            assertNull(CoreConfigManager.explicitTunBackend(json, false))
            assertEquals(original, json)
        }
    }

    @Test
    fun customConfigWithoutCoreTunGetsExactlyOneNativeInbound() {
        val json = JsonParser.parseString("""{"inbounds":[{"protocol":"socks","port":10808}],"dns":{"servers":["fakedns"]},"routing":{"rules":[{"process":["10001"]}]}}""").asJsonObject
        val dns = json.getAsJsonObject("dns").deepCopy()
        val routing = json.getAsJsonObject("routing").deepCopy()
        repeat(2) {
            CoreConfigManager.addTunInboundIfAbsent(json) {
                template().also {
                    CoreConfigManager.configureTunInbound(it, TunBackend.SING_NATIVE, 1500, VpnInterfaceAddressConfig.OPTION_2, true)
                }
            }
        }
        assertEquals(2, json.getAsJsonArray("inbounds").size())
        assertEquals(TunBackend.SING_NATIVE, CoreConfigManager.explicitTunBackend(json))
        assertEquals(dns, json.getAsJsonObject("dns"))
        assertEquals(routing, json.getAsJsonObject("routing"))
    }

    @Test
    fun multipleCoreTunReadersAreRejectedIncludingRepeatedSameProtocol() {
        listOf("tun", "TUN", "sing-tun", "SING-TUN").forEach { first ->
            listOf("tun", "TUN", "sing-tun", "SING-TUN").forEach { second ->
                val json = JsonParser.parseString("""{"inbounds":[{"protocol":"$first"},{"protocol":"$second"}]}""").asJsonObject
                assertThrows(IllegalArgumentException::class.java) { CoreConfigManager.explicitTunBackend(json) }
                assertThrows(IllegalArgumentException::class.java) {
                    CoreConfigManager.addTunInboundIfAbsent(json) { template() }
                }
            }
        }
    }

    @Test
    fun explicitProtocolCaseDoesNotCreateASecondReader() {
        listOf("TUN" to TunBackend.XRAY, "Sing-Tun" to TunBackend.SING_NATIVE,
            "SİNG-TUN" to TunBackend.SING_NATIVE).forEach { (protocol, expected) ->
            val json = JsonParser.parseString("""{"inbounds":[{"protocol":"$protocol"}]}""").asJsonObject
            val original = json.deepCopy()
            assertEquals(expected, CoreConfigManager.explicitTunBackend(json))
            CoreConfigManager.addTunInboundIfAbsent(json) { error("An explicit core TUN already exists") }
            assertEquals(original, json)
        }
    }

    @Test
    fun customFieldCaseFollowsCoreJsonWithoutReplacingUserInbounds() {
        val explicit = JsonParser.parseString("""{"INBOUNDS":[{"PROTOCOL":"SING-TUN"}]}""").asJsonObject
        val original = explicit.deepCopy()
        CoreConfigManager.addTunInboundIfAbsent(explicit) { error("Explicit TUN must be preserved") }
        assertEquals(original, explicit)
        assertEquals(TunBackend.SING_NATIVE, CoreConfigManager.explicitTunBackend(explicit))

        val absent = JsonParser.parseString("""{"INBOUNDS":[{"PROTOCOL":"socks","port":10808}]}""").asJsonObject
        CoreConfigManager.addTunInboundIfAbsent(absent) { template() }
        assertEquals(setOf("INBOUNDS"), absent.keySet())
        assertEquals(2, absent.getAsJsonArray("INBOUNDS").size())

        val ambiguous = JsonParser.parseString("""{"inbounds":[{"protocol":"tun"}],"INBOUNDS":[{}]}""").asJsonObject
        assertThrows(IllegalArgumentException::class.java) { CoreConfigManager.explicitTunBackend(ambiguous) }
    }

    @Test
    fun ambiguousProtocolFieldsCannotHideAnExistingCoreReader() {
        for (inbound in listOf(
            """{"protocol":"tun","PROTOCOL":"sing-tun"}""",
            """{"protocol":"sing-tun","PROTOCOL":null}""",
            """{"PROTOCOL":null,"protocol":"sing-tun"}""",
            """{"protocol":"socks","PROTOCOL":"SING-TUN"}"""
        )) {
            val json = JsonParser.parseString("""{"inbounds":[$inbound]}""").asJsonObject
            assertThrows(IllegalArgumentException::class.java) { CoreConfigManager.explicitTunBackend(json) }
            assertThrows(IllegalArgumentException::class.java) {
                CoreConfigManager.addTunInboundIfAbsent(json) { template() }
            }
        }
    }

    @Test
    fun inPlaceHandoverKeepsItsReaderAndRequiresFullRestartToChangeIt() {
        TunBackend.entries.forEach { backend ->
            val config = when (backend) {
                TunBackend.XRAY -> """{"inbounds":[{"protocol":"tun"}]}"""
                TunBackend.SING_NATIVE -> """{"inbounds":[{"protocol":"sing-tun"}]}"""
                TunBackend.HEV, TunBackend.SING -> """{"inbounds":[{"protocol":"socks"}]}"""
            }
            val explicit = CoreConfigManager.explicitTunBackend(JsonParser.parseString(config).asJsonObject)
            assertEquals(backend, CoreConfigManager.resolveTunBackend(explicit, backend, backend))
            TunBackend.entries.filter { it != backend }.forEach { running ->
                assertThrows(IllegalArgumentException::class.java) {
                    CoreConfigManager.resolveTunBackend(explicit, backend, running)
                }
            }
        }
    }
}
