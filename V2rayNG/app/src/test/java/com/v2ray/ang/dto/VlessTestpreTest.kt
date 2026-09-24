package com.v2ray.ang.dto

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VlessTestpreTest {
    @Test
    fun onlyVisionFlowsAllowBoundedPreconnections() {
        assertTrue(VlessTestpre.supportsFlow("xtls-rprx-vision"))
        assertTrue(VlessTestpre.supportsFlow("xtls-rprx-vision-udp443"))
        assertFalse(VlessTestpre.supportsFlow(""))
        assertNull(VlessTestpre.forOutbound("", 5))
        assertNull(VlessTestpre.forOutbound("xtls-rprx-vision", 0))
        assertNull(VlessTestpre.forOutbound("xtls-rprx-vision", 17))
        assertEquals(5, VlessTestpre.forOutbound("xtls-rprx-vision", 5))
    }

    @Test
    fun parsingRejectsMalformedAndUnboundedValues() {
        assertNull(VlessTestpre.parse(null))
        assertNull(VlessTestpre.parse(""))
        assertNull(VlessTestpre.parse("-1"))
        assertNull(VlessTestpre.parse("17"))
        assertNull(VlessTestpre.parse("invalid"))
        assertEquals(16, VlessTestpre.parse(" 16 "))
    }

    @Test
    fun profileJsonRemainsCompatibleWithEarlierProfiles() {
        val old = JsonUtil.fromJson("{\"configType\":\"VLESS\",\"flow\":\"xtls-rprx-vision\"}", ProfileItem::class.java)
        assertNull(old?.testpre)

        val profile = ProfileItem.create(EConfigType.VLESS).copy(flow = "xtls-rprx-vision", testpre = 5)
        assertEquals(5, JsonUtil.fromJson(JsonUtil.toJson(profile), ProfileItem::class.java)?.testpre)
    }

    @Test
    fun xraySettingUsesTheRequiredJsonField() {
        val settings = V2rayConfig.OutboundBean.OutSettingsBean(testpre = 5)
        assertEquals(5, JsonUtil.parseString(JsonUtil.toJson(settings))?.get("testpre")?.asInt)
        assertFalse(JsonUtil.toJson(V2rayConfig.OutboundBean.OutSettingsBean()).contains("testpre"))
    }

    @Test
    fun vlessShareLinkKeepsExplicitPreconnectionCount() {
        val profile = ProfileItem.create(EConfigType.VLESS).copy(
            server = "example.com", serverPort = "443", password = "00000000-0000-0000-0000-000000000001",
            method = "none", flow = "xtls-rprx-vision", security = "reality", testpre = 5
        )
        val uri = "vless://" + VlessFmt.toUri(profile)
        assertEquals(5, VlessFmt.parse(uri)?.testpre)
        assertNull(VlessFmt.parse(uri.replace("testpre=5", "testpre=999"))?.testpre)
        assertFalse(VlessFmt.toUri(profile.copy(flow = "")).contains("testpre="))
    }
}
