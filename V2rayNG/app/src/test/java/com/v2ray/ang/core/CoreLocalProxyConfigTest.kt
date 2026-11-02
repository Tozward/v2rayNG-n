package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CoreLocalProxyConfigTest {
    private fun port(
        inbounds: String,
        preferred: Int = 10808,
        username: String? = null,
        password: String? = null,
        mixedSocks: Boolean = true
    ): Int? = CoreConfigManager.runningHttpProxyPort(
        """{"inbounds":[$inbounds]}""", preferred, username, password, mixedSocks
    )

    @Test
    fun reportsActualListenerInsteadOfAnUnappliedSetting() {
        assertEquals(18080, port("""{"protocol":"socks","listen":"127.0.0.1","port":18080}"""))
        assertNull(port("""{"protocol":"sing-tun","settings":{"name":"sing0","mtu":1500}}"""))
        assertNull(port("""{"protocol":"tun","settings":{"name":"tun0"}}"""))
        assertNull(port(""))
    }

    @Test
    fun prefersConfiguredPortOnlyWhenAnActualCompatibleListenerExists() {
        val inbounds = """{"protocol":"http","port":18080},{"protocol":"socks","port":10808}"""
        assertEquals(10808, port(inbounds))
        assertEquals(18080, port(inbounds, preferred = 10000))
        assertEquals(18080, port(inbounds, mixedSocks = false))
        assertNull(port("""{"protocol":"socks","port":10808}""", mixedSocks = false))
    }

    @Test
    fun acceptsOnlyPortsReachableByTheIpv4LoopbackClient() {
        for (listen in listOf("127.0.0.1", "0.0.0.0")) {
            assertEquals(10808, port("""{"protocol":"http","listen":"$listen","port":"10808"}"""))
        }
        for (listen in listOf("192.0.2.1", "127.0.0.2", "::1", "/data/socket")) {
            assertNull(port("""{"protocol":"http","listen":"$listen","port":10808}"""))
        }
        for (value in listOf("0", "65536", "\"10808-10810\"", "1.5")) {
            assertNull(port("""{"protocol":"http","port":$value}"""))
        }
    }

    @Test
    fun customAuthenticationMustMatchTheClientsExistingCredentials() {
        val account = """"accounts":[{"user":"name","pass":"secret"}]"""
        val socks = """{"protocol":"socks","port":10808,"settings":{"auth":"password",$account}}"""
        assertNull(port(socks))
        assertNull(port(socks, username = "name", password = "different"))
        assertEquals(10808, port(socks, username = "name", password = "secret"))
        assertNull(port("""{"protocol":"socks","port":10808,"settings":{"auth":"password"}}"""))
        val http = """{"protocol":"http","port":10808,"settings":{$account}}"""
        assertNull(port(http))
        assertEquals(10808, port(http, username = "name", password = "secret"))
        val alias = """{"protocol":"http","port":10808,"settings":{"users":[{"user":"name","pass":"secret"}]}}"""
        assertEquals(10808, port(alias, username = "name", password = "secret"))
        // SOCKS accounts are not active when the inbound explicitly uses noauth.
        assertEquals(10808, port("""{"protocol":"socks","port":10808,"settings":{"auth":"noauth",$account}}"""))
    }

    @Test
    fun wrappedOrProxyProtocolListenersAreNotPlainHttpProxies() {
        for (stream in listOf(
            """{"security":"tls"}""",
            """{"network":"ws"}""",
            """{"sockopt":{"acceptProxyProtocol":true}}""",
            """{"rawSettings":{"acceptProxyProtocol":true}}""",
            """{"tcpSettings":{"header":{"type":"http"}}}""",
            """{"finalmask":{"tcp":[{"type":"sudoku"}]}}"""
        )) {
            assertNull(port("""{"protocol":"http","port":10808,"streamSettings":$stream}"""))
        }
        assertEquals(10808, port("""{"protocol":"http","port":10808,"streamSettings":{"network":"raw","security":"none","sockopt":{"tcpNoDelay":true}}}"""))
        assertEquals(10808, port("""{"protocol":"http","port":10808,"streamSettings":{"network":"RAW","security":"NONE"}}"""))
    }

    @Test
    fun caseVariantsDoNotHideAmbiguousEndpointFields() {
        assertEquals(18080, CoreConfigManager.runningHttpProxyPort(
            """{"Inbounds":[{"Protocol":"http","Listen":"127.0.0.1","Port":18080}]}""",
            10808, null, null, true
        ))
        assertNull(CoreConfigManager.runningHttpProxyPort(
            """{"inbounds":[{"protocol":"http","port":10808}],"Inbounds":[{"protocol":"http","port":18080}]}""",
            10808, null, null, true
        ))
        assertNull(port("""{"protocol":"http","listen":"127.0.0.1","Listen":"192.0.2.1","port":10808}"""))
        assertNull(port("""{"protocol":"http","port":10808,"Port":18080}"""))
    }
}
