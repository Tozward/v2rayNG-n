package com.v2ray.ang.enums

enum class TunBackend(val key: String, val usesLocalSocks: Boolean) {
    XRAY("xray", false),
    HEV("hev", true),
    SING("sing", true);

    companion object {
        fun fromSettings(value: String?, legacyHevEnabled: Boolean): TunBackend =
            entries.firstOrNull { it.key == value } ?: if (legacyHevEnabled) HEV else XRAY
    }
}
