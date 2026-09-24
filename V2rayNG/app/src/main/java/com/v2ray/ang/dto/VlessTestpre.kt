package com.v2ray.ang.dto

/** Bounds the number of pre-connected sockets created by the Xray VLESS outbound. */
object VlessTestpre {
    const val MAX_CONNECTIONS = 16

    fun supportsFlow(flow: String?): Boolean =
        flow == "xtls-rprx-vision" || flow == "xtls-rprx-vision-udp443"

    fun parse(input: String?): Int? = input?.trim()?.toIntOrNull()?.takeIf { it in 1..MAX_CONNECTIONS }

    fun forOutbound(flow: String?, count: Int?): Int? =
        count?.takeIf { supportsFlow(flow) && it in 1..MAX_CONNECTIONS }
}
