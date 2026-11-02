package com.v2ray.ang.service

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelFileDescriptor
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
        MmkvManager.encodeSettings(AppConfig.PREF_ROOT_MODE_ENABLE, false)
        MmkvManager.encodeSettings(AppConfig.PREF_ROOT_LAN_SHARING, false)
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, false)
        MmkvManager.encodeSettings(AppConfig.PREF_VPN_BYPASS_LAN, "2")
        MmkvManager.encodeSettings(AppConfig.PREF_LOCAL_DNS_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_FAKE_DNS_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_SNIFFING_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_USERNAME, "tun-user")
        MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_PASSWORD, "tun-secret")
        MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_ENABLE_UDP, true)
        // External stacks must keep their required SOCKS listener even when
        // the optional user-facing local proxy is disabled. Xray TUN needs none.
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
            for (backend in listOf(TunBackend.SING, TunBackend.HEV, TunBackend.XRAY)) {
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
                await("setup command delivery: $backend") { serviceDump().contains("ServiceRecord{") }
                LauncherManager.stopService(context)
                // Before the daemon receiver registers, a stop broadcast can be unhandled.
                // Destroy through Android as well to exercise teardown during setup.
                context.stopService(Intent(context, CoreVpnService::class.java))
                await("stop during setup: $backend") { !serviceDump().contains("ServiceRecord{") }
                assertTrue(isStopped())

                LauncherManager.startService(context, guid)
                awaitTransfer(backend)
                stopAndAwait()
            }
        } finally {
            LauncherManager.stopService(context)
            context.stopService(Intent(context, CoreVpnService::class.java))
            context.unregisterReceiver(receiver)
            MmkvManager.removeServer(guid)
            MmkvManager.removeServer(invalidGuid)
        }
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

    private fun probe(udp: Boolean): String {
        val classpath = "${instrumentation.context.applicationInfo.sourceDir}:${context.applicationInfo.sourceDir}"
        val protocol = if (udp) "udp" else "tcp"
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
