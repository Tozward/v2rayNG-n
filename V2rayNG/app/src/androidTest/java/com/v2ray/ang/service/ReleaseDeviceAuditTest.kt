package com.v2ray.ang.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.health.SystemHealthManager
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Opt-in device audit: synthetic app storage and shell-UID traffic only. */
@RunWith(AndroidJUnit4::class)
class ReleaseDeviceAuditTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private var probeBinary: String? = null

    @Test
    fun isolatedVpnLifecycleLatencyThroughputAndIdle() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("device audit requires explicit opt-in", arguments.getString("deviceAudit") == "true")
        require(Regex("""com\.v2ray\.ang\.audit(?:\.r[0-9]+)?""").matches(context.packageName)) {
            "device audit must use the isolated APK"
        }
        val idleSeconds = arguments.getString("idleSeconds")?.let { it.toIntOrNull() ?: error("invalid idleSeconds") } ?: 0
        require(idleSeconds in 0..300) { "idleSeconds must be within 0..300" }
        val speedArgument = arguments.getString("speedNotification") ?: "false"
        require(speedArgument in listOf("true", "false")) { "speedNotification must be true or false" }
        val speedNotification = speedArgument.toBoolean()
        assertEquals("probes must run as the allowlisted shell UID", "2000", shell("id -u").trim())
        probeBinary = arguments.getString("probeBinary")?.also {
            require(it == "/data/local/tmp/native-release-probe-20261103") { "unsupported isolated audit probe path" }
        }
        reportPowerMonitors()

        val profiles = mutableListOf<String>()
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
        ContextCompat.registerReceiver(context, receiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            configureSyntheticSettings(speedNotification)
            val guid = MmkvManager.encodeServerConfig("", ProfileItem.create(EConfigType.SOCKS).apply {
                remarks = "Isolated device audit fixture"
                server = "127.0.0.1"
                serverPort = "47183"
            }).also { profiles.add(it) }
            val invalidGuid = MmkvManager.encodeServerConfig("", ProfileItem.create(EConfigType.CUSTOM).apply {
                remarks = "Isolated invalid audit fixture"
            }).also { profiles.add(it) }
            MmkvManager.encodeServerRaw(invalidGuid, "{invalid-json")
            assertTrue("reserved endpoint must be unreachable without this VPN", probe("tcp").isEmpty())

            for (backend in TunBackend.selectable) {
                phase(backend.key, "backend_begin")
                MmkvManager.encodeSettings(AppConfig.PREF_TUN_BACKEND, backend.key)
                for (mtu in listOf(1280, 9000, 1500)) {
                    MmkvManager.encodeSettings(AppConfig.PREF_VPN_MTU, mtu.toString())
                    phase(backend.key, "start_mtu_$mtu")
                    LauncherManager.startService(context, guid)
                    LauncherManager.startService(context, guid)
                    awaitTransfer(backend.key)
                    assertEquals("duplicate starts must share one VPN service", 1,
                        Regex("(?m)^\\s*\\* ServiceRecord\\{").findAll(serviceDump()).count())
                    assertEquals("tun-smoke", probe("udp"))
                    assertEquals("tun-smoke", probe("tcp-bulk"))
                    val status = runBlocking { MessageHelper.queryRunningHttpProxy(context) }
                    if (backend == TunBackend.HEV) assertTrue("HEV requires its listener", status is MessageHelper.LocalProxyStatus.Running)
                    else assertEquals("disabled local proxy must not claim an endpoint", MessageHelper.LocalProxyStatus.Unknown, status)
                    phase(backend.key, "transfer_mtu_${mtu}_passed")
                    if (mtu == 1500) {
                        // Exercise the shared restart path before measured traffic.
                        val before = successes.get()
                        LauncherManager.restartService(context)
                        await("restart acknowledgement") { successes.get() > before }
                        awaitTransfer(backend.key)
                        performance(backend.key, "latency")
                        performance(backend.key, "bulk")
                        phase(backend.key, "idle_begin", "seconds=$idleSeconds speed_notification=$speedNotification")
                        SystemClock.sleep(idleSeconds * 1000L)
                        phase(backend.key, "idle_end")
                        assertEquals("tun-smoke", probe("tcp"))
                    }
                    stopAndAwait()
                    LauncherManager.stopService(context)
                    assertTrue("repeated stop", stopped())
                }

                phase(backend.key, "invalid_setup")
                val beforeFailure = failures.get()
                LauncherManager.startService(context, invalidGuid)
                await("invalid configuration acknowledgement") { failures.get() > beforeFailure }
                await("invalid setup cleanup") { stopped() && !serviceDump().contains("ServiceRecord{") }
                LauncherManager.startService(context, guid)
                awaitTransfer(backend.key)
                stopAndAwait()

                phase(backend.key, "stop_after_foreground_setup")
                LauncherManager.startService(context, guid)
                await("foreground setup command delivery") { serviceDump().contains("isForeground=true") }
                LauncherManager.stopService(context)
                await("stop after foreground setup") { stopped() && !serviceDump().contains("ServiceRecord{") }
                LauncherManager.startService(context, guid)
                awaitTransfer(backend.key)
                stopAndAwait()
                phase(backend.key, "backend_passed")
            }

            // A persisted pre-release value must resolve to the surviving backend.
            phase("sing", "legacy_migration")
            MmkvManager.encodeSettings(AppConfig.PREF_TUN_BACKEND, "sing")
            assertEquals(TunBackend.SING_NATIVE, SettingsManager.getTunBackend())
            LauncherManager.startService(context, guid)
            awaitTransfer("legacy_sing_native")
            assertEquals("tun-smoke", probe("udp"))
            stopAndAwait()
            phase("sing_native", "legacy_migration_passed")
            proxyOnlyScenario(guid, invalidGuid, successes, failures)
        } finally {
            // Guards above ensure these commands and deletions target only scratch data.
            try {
                LauncherManager.stopService(context)
                context.stopService(Intent(context, CoreVpnService::class.java))
                context.stopService(Intent(context, CoreProxyOnlyService::class.java))
                await("final isolated service cleanup") {
                    !serviceDump().contains("ServiceRecord{") && !serviceDump(CoreProxyOnlyService::class.java).contains("ServiceRecord{")
                }
            } finally {
                try {
                    context.unregisterReceiver(receiver)
                } finally {
                    profiles.forEach(MmkvManager::removeServer)
                    MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.VPN)
                }
            }
        }
    }

    private fun configureSyntheticSettings(speedNotification: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.VPN)
        MmkvManager.encodeSettings(AppConfig.PREF_ROOT_MODE_ENABLE, false)
        MmkvManager.encodeSettings(AppConfig.PREF_ROOT_LAN_SHARING, false)
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, true)
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, mutableSetOf("com.android.shell"))
        MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, false)
        MmkvManager.encodeSettings(AppConfig.PREF_VPN_BYPASS_LAN, "2")
        MmkvManager.encodeSettings(AppConfig.PREF_LOCAL_DNS_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_FAKE_DNS_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_IPV6_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_SNIFFING_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_AUTO_CHECK_UPDATE, false)
        MmkvManager.encodeSettings(AppConfig.PREF_SPEED_ENABLED, speedNotification)
        MmkvManager.encodeSettings(AppConfig.PREF_LOGLEVEL, "warning")
        MmkvManager.encodeSettings(AppConfig.PREF_PROXY_SHARING, false)
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_SOCKS_PORT, true)
        MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_USERNAME, "audit-local")
        MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_PASSWORD, "audit-test-only")
        MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_ENABLE_UDP, true)
        MmkvManager.encodeSettings(AppConfig.PREF_ENABLE_LOCAL_PROXY, false)
        SettingsManager.initAssets(context, context.assets)
        MmkvManager.encodeRoutingRulesets(mutableListOf(RulesetItem(
            id = "isolated-device-audit", outboundTag = AppConfig.TAG_PROXY, network = "tcp,udp"
        )))
    }

    private fun proxyOnlyScenario(guid: String, invalidGuid: String, successes: AtomicInteger, failures: AtomicInteger) {
        val service = CoreProxyOnlyService::class.java
        // This is the existing settings value; AppConfig declares only the VPN constant.
        MmkvManager.encodeSettings(AppConfig.PREF_MODE, "Proxy only")
        MmkvManager.encodeSettings(AppConfig.PREF_ENABLE_LOCAL_PROXY, true)
        phase("proxy", "start")
        LauncherManager.startService(context, guid)
        LauncherManager.startService(context, guid)
        await("proxy-only HTTP transfer") { proxyTransfer() }
        assertEquals(1, Regex("(?m)^\\s*\\* ServiceRecord\\{").findAll(serviceDump(service)).count())
        val before = successes.get()
        LauncherManager.restartService(context)
        await("proxy-only restart acknowledgement") { successes.get() > before }
        await("proxy-only restarted HTTP transfer") { proxyTransfer() }
        stopAndAwait(service)
        LauncherManager.stopService(context)
        assertTrue("proxy-only repeated stop", stopped())

        val beforeFailure = failures.get()
        phase("proxy", "invalid_setup")
        LauncherManager.startService(context, invalidGuid)
        await("proxy-only invalid configuration acknowledgement") { failures.get() > beforeFailure }
        await("proxy-only invalid setup cleanup") { stopped() && !serviceDump(service).contains("ServiceRecord{") }
        LauncherManager.startService(context, guid)
        await("proxy-only setup recovery") { proxyTransfer() }
        stopAndAwait(service)
        phase("proxy", "stop_after_foreground_setup")
        LauncherManager.startService(context, guid)
        await("proxy-only foreground setup") { serviceDump(service).contains("isForeground=true") }
        LauncherManager.stopService(context)
        await("proxy-only stop after setup") { stopped() && !serviceDump(service).contains("ServiceRecord{") }
        LauncherManager.startService(context, guid)
        await("proxy-only final recovery") { proxyTransfer() }
        stopAndAwait(service)
        MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.VPN)
        MmkvManager.encodeSettings(AppConfig.PREF_ENABLE_LOCAL_PROXY, false)
        phase("proxy", "passed")
    }

    private fun proxyTransfer(): Boolean {
        val state = runBlocking { MessageHelper.queryRunningHttpProxy(context) }
        if (state !is MessageHelper.LocalProxyStatus.Running) return false
        return probeProcess(ReleaseDeviceProbe::class.java.name, "http ${state.port}").trim() == "tun-smoke"
    }

    private fun reportPowerMonitors() {
        val count = AtomicInteger(-1)
        if (Build.VERSION.SDK_INT >= 35) {
            val done = CountDownLatch(1)
            try {
                val health = context.getSystemService(Context.SYSTEM_HEALTH_SERVICE) as? SystemHealthManager
                health?.getSupportedPowerMonitors(Executor { it.run() }) { monitors ->
                    count.set(monitors.size)
                    done.countDown()
                }
                if (health != null) done.await(5, TimeUnit.SECONDS)
            } catch (_: RuntimeException) { /* Optional hardware capability, not a UID history query. */ }
        }
        instrumentation.sendStatus(1, Bundle().apply {
            putString("stream", "AUDIT_POWER_MONITORS ${if (count.get() >= 0) "count=${count.get()}" else "unavailable"}\n")
        })
    }

    private fun performance(backend: String, mode: String) {
        phase(backend, "${mode}_begin")
        val output = probeProcess(ReleaseDeviceProbe::class.java.name, mode).trim()
        val result = JSONObject(output)
        assertEquals(mode, result.getString("kind"))
        if (mode == "latency") {
            assertEquals(500, result.getInt("samples"))
            for (key in listOf("p50_us", "p95_us", "p99_us", "max_us")) assertTrue(result.getDouble(key) > 0)
        } else {
            for (key in listOf("bytes", "elapsed_ms", "duplex_MB_s")) assertTrue(result.getDouble(key) > 0)
        }
        instrumentation.sendStatus(1, Bundle().apply {
            putString("stream", "AUDIT_METRIC backend=$backend mode=$mode $output\n")
        })
        phase(backend, "${mode}_end")
    }

    private fun phase(backend: String, stage: String, detail: String = "") {
        instrumentation.sendStatus(1, Bundle().apply {
            putString("stream", "AUDIT_PHASE backend=$backend stage=$stage $detail\n")
        })
    }

    private fun stopped() = runBlocking { MessageHelper.queryRunningHttpProxy(context) == MessageHelper.LocalProxyStatus.Stopped }

    private fun stopAndAwait(service: Class<*> = CoreVpnService::class.java) {
        LauncherManager.stopService(context)
        await("normal stop") { stopped() && !serviceDump(service).contains("ServiceRecord{") }
    }

    private fun awaitTransfer(backend: String) = await("TCP transfer: $backend") { probe("tcp") == "tun-smoke" }

    private fun probe(mode: String) = probeProcess(TunProbe::class.java.name, mode).trim()

    private fun probeProcess(className: String, mode: String): String {
        val classpath = "${instrumentation.context.applicationInfo.sourceDir}:${context.applicationInfo.sourceDir}"
        val command = probeBinary?.let { "'$it' $mode" }
            ?: "env CLASSPATH='$classpath' app_process /system/bin $className $mode"
        val marker = "\nAUDIT_PROBE_EXIT="
        val result = shell("{ $command; } 2>&1; code=\$?; printf '\\nAUDIT_PROBE_EXIT=%s\\n' \"\$code\"")
        val exitCode = result.substringAfterLast(marker, "").trim().toIntOrNull()
        val output = result.substringBeforeLast(marker, result).trim()
        if (exitCode != 0) {
            instrumentation.sendStatus(1, Bundle().apply {
                putString("stream", "AUDIT_PROBE_FAILED mode=$mode exit=$exitCode ${output.take(2048)}\n")
            })
            return ""
        }
        return output
    }

    private fun serviceDump(service: Class<*> = CoreVpnService::class.java) = shell("dumpsys activity services ${context.packageName}/${service.name}")

    private fun await(operation: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out: $operation\n${serviceDump()}\n${serviceDump(CoreProxyOnlyService::class.java)}")
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}
