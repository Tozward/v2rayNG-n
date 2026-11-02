package com.v2ray.ang.dto

import com.v2ray.ang.enums.TunBackend

data class ConfigResult(
    var status: Boolean,
    var guid: String? = null,
    var content: String = "",
    var errorMessage: String = "",
    // Runtime metadata for the daemon; never inserted into the core JSON or saved profile.
    val tunBackend: TunBackend? = null,
)

