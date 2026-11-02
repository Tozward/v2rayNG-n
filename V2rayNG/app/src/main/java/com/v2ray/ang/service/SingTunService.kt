package com.v2ray.ang.service

import android.os.ParcelFileDescriptor
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import singtun.Singtun

/** Owns the native stack for one VPN instance; the Go side owns only a duplicated TUN fd. */
class SingTunService(private val vpnInterface: ParcelFileDescriptor) : Tun2SocksControl {
    private val controller = Singtun.newController()

    override fun startTun2Socks() {
        val addresses = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        controller.start(
            vpnInterface.fd,
            SettingsManager.getVpnMtu(),
            SettingsManager.getSocksPort(),
            "${addresses.ipv4Client}/30",
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED))
                "${addresses.ipv6Client}/126" else "",
            SettingsManager.getSocksUsername().orEmpty(),
            SettingsManager.getSocksPassword().orEmpty()
        )
    }

    override fun stopTun2Socks() = controller.stop()

    override fun resetNetwork() = controller.resetNetwork()
}
