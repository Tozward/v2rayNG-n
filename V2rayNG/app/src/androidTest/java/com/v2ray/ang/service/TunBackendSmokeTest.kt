package com.v2ray.ang.service

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.TunBackend
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Runs only on a disposable CI emulator with the host SOCKS echo fixture. */
@RunWith(AndroidJUnit4::class)
class TunBackendSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test
    fun allBackendsTransferAndRecoverFromLifecycleTransitions() {
        MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.VPN)
        MmkvManager.encodeSettings(AppConfig.PREF_LOGLEVEL, "info")
        MmkvManager.encodeSettings(AppConfig.PREF_ROOT_MODE_ENABLE, false)
        MmkvManager.encodeSettings(AppConfig.PREF_ROOT_LAN_SHARING, false)
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, false)
        MmkvManager.encodeSettings(AppConfig.PREF_VPN_BYPASS_LAN, "2")
        MmkvManager.encodeSettings(AppConfig.PREF_LOCAL_DNS_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_FAKE_DNS_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_IPV6_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_SNIFFING_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_USERNAME, "tun-user")
        MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_PASSWORD, "tun-secret")
        MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_ENABLE_UDP, true)
        // External stacks must keep their required SOCKS listener even when
        // the optional user-facing local proxy is disabled. Core TUNs need none.
        MmkvManager.encodeSettings(AppConfig.PREF_ENABLE_LOCAL_PROXY, false)
        SettingsManager.initAssets(context, context.assets)
        // A nonempty rule list also prevents a fresh daemon from installing its
        // default LAN bypass rules over this isolated fixture configuration.
        MmkvManager.encodeRoutingRulesets(mutableListOf(RulesetItem(
            id = "ci-tun-smoke", outboundTag = AppConfig.TAG_PROXY, network = "tcp,udp"
        )))
        assertTrue("probe must fail without a VPN", probe(udp = false).isEmpty())
        val guid = MmkvManager.encodeServerConfig("", ProfileItem.create(EConfigType.SOCKS).apply {
            remarks = "CI loopback fixture"
            server = "10.0.2.2"
            serverPort = "18080"
        })
        val invalidGuid = MmkvManager.encodeServerConfig("", ProfileItem.create(EConfigType.CUSTOM).apply {
            remarks = "CI invalid configuration"
        })
        MmkvManager.encodeServerRaw(invalidGuid, "{invalid-json")
        val customGuid = MmkvManager.encodeServerConfig("", ProfileItem.create(EConfigType.CUSTOM).apply {
            remarks = "CI explicit core TUN"
        })
        val successes = AtomicInteger()
        val failures = AtomicInteger()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.getIntExtra("key", -1)) {
                    AppConfig.MSG_STATE_START_SUCCESS -> successes.incrementAndGet()
                    AppConfig.MSG_STATE_START_FAILURE -> failures.incrementAndGet()
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            for (backend in listOf(TunBackend.SING, TunBackend.HEV, TunBackend.XRAY, TunBackend.SING_NATIVE)) {
                assertTrue(MmkvManager.encodeSettings(AppConfig.PREF_TUN_BACKEND, backend.key))
                LauncherManager.startService(context, guid)
                LauncherManager.startService(context, guid)
                awaitTransfer(backend)
                assertEquals(1, Regex("(?m)^\\s*\\* ServiceRecord\\{").findAll(serviceDump()).count())
                assertTransfer(udp = true)

                val startsBeforeRestart = successes.get()
                LauncherManager.restartService(context)
                await("restart acknowledgement: $backend") { successes.get() > startsBeforeRestart }
                awaitTransfer(backend)
                stopAndAwait()
                LauncherManager.stopService(context)
                assertTrue(isStopped())

                // Failed core setup occurs after VpnService has acquired its descriptor.
                val failuresBeforeStart = failures.get()
                LauncherManager.startService(context, invalidGuid)
                await("setup failure acknowledgement: $backend") { failures.get() > failuresBeforeStart }
                await("failed setup service removal: $backend") { !serviceDump().contains("ServiceRecord{") }
                assertTrue(isStopped())

                if (backend == TunBackend.SING) {
                    // Xray accepts this account, but SOCKS5 cannot encode its username.
                    // Exercise native startup failure after fd/core acquisition and recovery.
                    MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_USERNAME, "x".repeat(256))
                    val failuresBeforeNativeStart = failures.get()
                    LauncherManager.startService(context, guid)
                    await("native startup failure acknowledgement") { failures.get() > failuresBeforeNativeStart }
                    await("native failure cleanup") { isStopped() && !serviceDump().contains("ServiceRecord{") }
                    MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_USERNAME, "tun-user")
                }

                // A stop racing asynchronous setup must not leave a tunnel or recreate one.
                LauncherManager.startService(context, guid)
                // A ServiceRecord can exist before onStartCommand. Wait for foreground
                // promotion before sending the stop, so the service has received its
                // command and registered its setup receiver. Force-stopping a pending
                // FGS instead triggers Android's foreground-start contract exception.
                await("foreground setup command delivery: $backend") {
                    serviceDump().contains("isForeground=true")
                }
                LauncherManager.stopService(context)
                await("stop during setup: $backend") { !serviceDump().contains("ServiceRecord{") }
                assertTrue(isStopped())

                LauncherManager.startService(context, guid)
                awaitTransfer(backend)
                stopAndAwait()
            }

            // The explicit custom protocol owns the sole reader even when the saved
            // choice would otherwise start a different core or an external SOCKS stack.
            for ((protocol, selected) in listOf("tun" to TunBackend.SING_NATIVE, "sing-tun" to TunBackend.SING)) {
                MmkvManager.encodeSettings(AppConfig.PREF_TUN_BACKEND, selected.key)
                MmkvManager.encodeServerRaw(customGuid, customTunConfig(protocol))
                LauncherManager.startService(context, customGuid)
                awaitTransfer(if (protocol == "tun") TunBackend.XRAY else TunBackend.SING_NATIVE)
                assertTransfer(udp = true)
                assertEquals(selected, SettingsManager.getTunBackend())
                stopAndAwait()
            }

            // Exercise the same stored GUI value through VpnService and both
            // Sing entry paths. Inspect Android's active VPN LinkProperties.
            for (mtu in listOf(1280, 1500, 9000)) {
                MmkvManager.encodeSettings(AppConfig.PREF_VPN_MTU, mtu.toString())
                for (backend in listOf(TunBackend.SING, TunBackend.SING_NATIVE)) {
                    MmkvManager.encodeSettings(AppConfig.PREF_TUN_BACKEND, backend.key)
                    LauncherManager.startService(context, guid)
                    awaitTransfer(backend)
                    // UiAutomation's shell domain cannot use RTM_GETLINK on
                    // these emulator images. ConnectivityService exposes the
                    // active VPN's applied MTU without that netlink permission.
                    val dump = shell("dumpsys connectivity")
                    val currentNetworks = dump.substringAfter("Current Networks:", "")
                        .substringBefore("Network Requests:")
                    val vpnLinks = currentNetworks.lineSequence().filter {
                        it.contains("NetworkAgentInfo") && Regex("InterfaceName: tun\\d+\\b").containsMatchIn(it)
                    }.toList()
                    assertEquals("one active VPN: $backend\n$dump", 1, vpnLinks.size)
                    val reportedMtu = Regex("\\bMTU: (\\d+)\\b").find(vpnLinks.single())
                        ?.groupValues?.get(1)?.toInt() ?: error("VPN MTU missing: $currentNetworks")
                    // Before Android 11, Vpn.makeLinkProperties omits MTU even
                    // though jniCreate applies it. Remove this exception when
                    // the oldest tested Android version is API 30 or newer.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || reportedMtu != 0) {
                        assertEquals("applied VPN MTU: $backend\n$currentNetworks", mtu, reportedMtu)
                    }
                    println("VPN MTU requested=$mtu backend=${backend.key} reported=$reportedMtu")
                    assertEquals("tun-smoke", probe(udp = false, bulk = true))
                    assertTransfer(udp = true)
                    stopAndAwait()
                }
            }

            MmkvManager.encodeServerRaw(customGuid, customTunConfig("sing-tun", duplicate = true))
            val failuresBeforeDuplicate = failures.get()
            LauncherManager.startService(context, customGuid)
            await("duplicate core TUN failure acknowledgement") { failures.get() > failuresBeforeDuplicate }
            await("duplicate core TUN cleanup") { isStopped() && !serviceDump().contains("ServiceRecord{") }
            assertTrue("app processes must not crash during lifecycle transitions",
                !shell("logcat -d -s AndroidRuntime:E").contains("Process: ${context.packageName}"))
        } finally {
            LauncherManager.stopService(context)
            context.stopService(Intent(context, CoreVpnService::class.java))
            context.unregisterReceiver(receiver)
            MmkvManager.removeServer(guid)
            MmkvManager.removeServer(invalidGuid)
            MmkvManager.removeServer(customGuid)
        }
    }

    private fun customTunConfig(protocol: String, duplicate: Boolean = false): String {
        val addresses = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val prefixes = if (protocol == "sing-tun") """, "address":["${addresses.ipv4Client}/30"]""" else ""
        val inbound = """{"tag":"tun","protocol":"$protocol","settings":{"name":"xray0","mtu":${SettingsManager.getVpnMtu()},"userLevel":8$prefixes}}"""
        return """{"inbounds":[$inbound${if (duplicate) ",$inbound" else ""}],"outbounds":[{"tag":"proxy","protocol":"socks","settings":{"servers":[{"address":"10.0.2.2","port":18080}]}}]}"""
    }

    private fun isStopped() = runBlocking {
        MessageHelper.queryRunningHttpProxy(context) == MessageHelper.LocalProxyStatus.Stopped
    }

    private fun stopAndAwait() {
        LauncherManager.stopService(context)
        await("normal stop") { isStopped() && !serviceDump().contains("ServiceRecord{") }
    }

    private fun awaitTransfer(backend: TunBackend) = await("TCP transfer: $backend") {
        probe(udp = false) == "tun-smoke"
    }

    private fun assertTransfer(udp: Boolean) {
        assertEquals("tun-smoke", probe(udp))
    }

    private fun probe(udp: Boolean, bulk: Boolean = false): String {
        val classpath = "${instrumentation.context.applicationInfo.sourceDir}:${context.applicationInfo.sourceDir}"
        val protocol = if (udp) "udp" else if (bulk) "tcp-bulk" else "tcp"
        return shell("env CLASSPATH=$classpath app_process /system/bin ${TunProbe::class.java.name} $protocol").trim()
    }

    private fun serviceDump() = shell("dumpsys activity services com.v2ray.ang/.service.CoreVpnService")

    private fun await(operation: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out: $operation\n${serviceDump()}")
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}
