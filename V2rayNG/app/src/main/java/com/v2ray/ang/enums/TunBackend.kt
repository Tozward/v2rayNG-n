package com.v2ray.ang.enums

enum class TunBackend(val key: String, val usesLocalSocks: Boolean) {
    XRAY("xray", false),
    HEV("hev", true),
    SING("sing", true),
    SING_NATIVE("sing_native", false);

    companion object {
        val selectable: List<TunBackend> = listOf(XRAY, HEV, SING_NATIVE)

        // The retired SOCKS-backed choice upgrades to the native implementation.
        // Keep its serialized key readable for preceding experimental releases.
        fun fromSettings(value: String?, legacyHevEnabled: Boolean): TunBackend =
            if (value == SING.key) SING_NATIVE
            else entries.firstOrNull { it.key == value } ?: if (legacyHevEnabled) HEV else XRAY
    }
}
