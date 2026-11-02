package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Network
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.enums.TunBackend
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.root.RootLanSharing
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

@SuppressLint("VpnServicePolicy")
class CoreVpnService : VpnService(), ServiceControl {
    private companion object {
        const val ACTION_READY = "${AppConfig.ANG_PACKAGE}.action.VPN_READY"
        val teardownBarrier = VpnTeardownBarrier()
    }

    private var mInterface: ParcelFileDescriptor? = null
    @Volatile private var isRunning = false
    @Volatile private var tun2SocksService: Tun2SocksControl? = null
    private val lifecycle = VpnLifecycleGate()
    private val setupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var setupJob: Job? = null
    @Volatile private var tunnelReady = false
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var teardownJob: Job? = null
    private val teardownComplete = Job()
    private var previousTeardown: Job? = null
    // This receiver belongs to the service, including asynchronous setup before
    // the core registers its full command receiver. Repeated stops share one gate.
    private val earlyStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", -1)) {
                AppConfig.MSG_STATE_STOP -> stopAllService()
                AppConfig.MSG_QUERY_LOCAL_PROXY_PORT -> if (isOrderedBroadcast) {
                    // Setup already owns a VPN service but may not have a listener yet.
                    resultData = CoreServiceManager.getRunningHttpProxyPort()?.toString()
                    resultCode = Activity.RESULT_OK
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        previousTeardown = teardownBarrier.register(teardownComplete)
        ContextCompat.registerReceiver(
            this, earlyStopReceiver, IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service created")
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: Permission revoked")
        stopAllService()
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    override fun onDestroy() {
        // Android can destroy the service without an explicit stop command. The same
        // teardown job then owns the tunnel, core and descriptor in their required order.
        stopAllService(isForced = false)
        super.onDestroy()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManager.ensureForeground()
        if (intent?.action == ACTION_READY) {
            if (lifecycle.shouldReuseRunningTunnel(
                    tunnelReady, CoreServiceManager.isRunning(), CoreServiceManager.isReloading
                )) return START_STICKY
            // Android can deliver an old readiness command to a new service instance.
            // Use its startId when no setup/resources exist, so it cannot invalidate
            // a newer start already queued by Android. Retain this while READY is asynchronous.
            if (!lifecycle.isStopping() && setupJob?.isActive != true) {
                if (!tunnelReady) stopSelf(startId) else stopAllService()
            }
            return START_NOT_STICKY
        }
        if (setupJob?.isActive == true) return START_NOT_STICKY
        // Always-on VPN restarts from OS deliver intent.action == SERVICE_INTERFACE or null intent.
        // A repeated system command must not replace the descriptor underneath a live core.
        // A handover temporarily stops the core while keeping this tunnel alive.
        // If the native core exited outside a reload, rebuild the tunnel as before.
        val isSystemVpnStart = intent == null || intent.action == SERVICE_INTERFACE
        if (isSystemVpnStart) {
            if (lifecycle.shouldReuseRunningTunnel(
                    tunnelReady, CoreServiceManager.isRunning(), CoreServiceManager.isReloading
                )) return START_STICKY
            unlockStart()
        }
        if (!tryLockStart()) {
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: Start already in progress")
            return if (lifecycle.shouldReuseRunningTunnel(
                    tunnelReady, CoreServiceManager.isRunning(), CoreServiceManager.isReloading
                )) START_STICKY else START_NOT_STICKY
        }
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service command received, systemVpnStart=$isSystemVpnStart")
        val job = setupScope.launch(start = CoroutineStart.LAZY) {
            try {
                previousTeardown?.join()
                previousTeardown = null
                ensureActive()
                if (!lifecycle.shouldReuseRunningTunnel(
                        tunnelReady, CoreServiceManager.isRunning(), CoreServiceManager.isReloading
                    )) {
                    tunnelReady = false
                    tun2SocksService?.stopTun2Socks()
                    tun2SocksService = null
                    if (!setupVpnService()) {
                        stopAllService()
                        return@launch
                    }
                    ensureActive()
                    startService()
                }
                ensureActive()
                if (tunnelReady && !lifecycle.isStopping() && CoreServiceManager.isRunning()) {
                    // Mark sticky only after both the core and its TUN backend are ready.
                    // The explicit service command performs no setup on the main thread.
                    ContextCompat.startForegroundService(
                        this@CoreVpnService,
                        Intent(this@CoreVpnService, CoreVpnService::class.java).setAction(ACTION_READY)
                    )
                }
            } catch (cancelled: CancellationException) {
                if (!lifecycle.isStopping()) stopAllService()
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Setup failed, guid=${MmkvManager.getSelectServer()}", error)
                MessageHelper.sendMsg2UI(
                    this@CoreVpnService, AppConfig.MSG_STATE_START_FAILURE,
                    error.message?.takeUnless { it.isBlank() } ?: error.javaClass.simpleName
                )
                stopAllService()
            }
        }
        setupJob = job
        job.start()
        return START_NOT_STICKY
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        if (lifecycle.isStopping()) return
        val vpnInterface = mInterface
        if (vpnInterface == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Interface not initialized")
            stopAllService()
            return
        }
        if (!CoreServiceManager.startCoreLoop(vpnInterface)) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
            stopAllService()
            return
        }

        if (lifecycle.isStopping()) return
        runTun2socks()
        if (lifecycle.isStopping()) return
        tunnelReady = true

        // Start LAN sharing if enabled in settings
        RootLanSharing.startClientSharing(this)
    }

    override fun stopService() {
        stopAllService(true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    override fun setUnderlyingNetworks(networks: Array<Network>?): Boolean {
        return super<VpnService>.setUnderlyingNetworks(networks)
    }

    override fun resetTunNetwork() {
        if (!lifecycle.isStopping()) tun2SocksService?.resetNetwork()
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }

    /**
     * Sets up the VPN service.
     * Prepares the VPN and configures it if preparation is successful.
     */
    private fun setupVpnService(): Boolean {
        val prepare = prepare(this)
        if (prepare != null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            return false
        }

        if (configureVpnService() != true) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Configuration failed")
            return false
        }

        return true
    }

    /**
     * Configures the VPN service.
     * @return True if the VPN service was configured successfully, false otherwise.
     */
    private fun configureVpnService(): Boolean {
        val builder = Builder()

        // Configure network settings (addresses, routing and DNS)
        configureNetworkSettings(builder)

        // Configure app-specific settings (session name and per-app proxy)
        configurePerAppProxy(builder)

        // Close the old interface since the parameters have been changed
        try {
            mInterface?.close()
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Failed to close old interface", e)
        } finally {
            mInterface = null
        }

        // Configure platform-specific features
        configurePlatformFeatures(builder)

        // Create a new interface using the builder and save the parameters
        try {
            mInterface = builder.establish() ?: error("VPN interface establishment returned null")
            isRunning = true
            return true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish VPN interface", e)
            stopAllService()
        }
        return false
    }

    /**
     * Configures the basic network settings for the VPN.
     * This includes IP addresses, routing rules, and DNS servers.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configureNetworkSettings(builder: Builder) {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = SettingsManager.routingRulesetsBypassLan()

        // Configure IPv4 settings
        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)

        // Configure routing rules
        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        // Configure IPv6 if enabled
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3) // Currently only 1/8 of total IPv6 is in use
                builder.addRoute("fc00::", 18) // Xray-core default FakeIPv6 Pool
            } else {
                builder.addRoute("::", 0)
            }
        }

        // Configure DNS servers
        //if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
        //  builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        //} else {
        SettingsManager.getVpnDnsServers().forEach {
            if (Utils.isPureIpAddress(it)) {
                builder.addDnsServer(it)
            }
        }

        //builder.setSession(V2RayServiceManager.getRunningServerName())
    }

    /**
     * Configures platform-specific VPN features for different Android versions.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configurePlatformFeatures(builder: Builder) {
        // Android Q (API 29) and above: Configure metering and HTTP proxy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
    }

    /**
     * Configures per-app proxy rules for the VPN builder.
     *
     * - If per-app proxy is not enabled, disallow the VPN service's own package.
     * - If no apps are selected, disallow the VPN service's own package.
     * - If bypass mode is enabled, disallow all selected apps (including self).
     * - If proxy mode is enabled, only allow the selected apps (excluding self).
     *
     * @param builder The VPN Builder to configure.
     */
    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID

        // If per-app proxy is not enabled, disallow the VPN service's own package and return
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == false) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        // If no apps are selected, disallow the VPN service's own package and return
        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (apps.isNullOrEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        // Handle the VPN service's own package according to the mode
        if (bypassApps) apps.add(selfPackageName) else apps.remove(selfPackageName)

        apps.forEach {
            try {
                if (bypassApps) {
                    // In bypass mode, disallow the selected apps
                    builder.addDisallowedApplication(it)
                } else {
                    // In proxy mode, only allow the selected apps
                    builder.addAllowedApplication(it)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to configure app", e)
            }
        }
    }

    /**
     * Runs the tun2socks process.
     * Starts the tun2socks process with the appropriate parameters.
     */
    private fun runTun2socks() {
        val vpnInterface = mInterface ?: return
        if (!isRunning || lifecycle.isStopping()) return
        tun2SocksService = when (CoreServiceManager.getRunningTunBackend() ?: return) {
            TunBackend.HEV -> TProxyService(
                context = applicationContext,
                vpnInterface = vpnInterface,
                isRunningProvider = { isRunning },
                restartCallback = { runTun2socks() }
            )
            TunBackend.SING -> SingTunService(vpnInterface)
            TunBackend.XRAY, TunBackend.SING_NATIVE -> null
        }

        tun2SocksService?.startTun2Socks()
    }

    private fun stopAllService(isForced: Boolean = true) {
        if (!lifecycle.beginStop()) return
        unlockStart()
        isRunning = false
        tunnelReady = false

        val job = teardownScope.launch(start = CoroutineStart.LAZY) {
            setupJob?.cancelAndJoin()
            setupScope.cancel()
            try {
                unregisterReceiver(earlyStopReceiver)
            } catch (error: Exception) {
                LogUtil.w(AppConfig.TAG, "StopCore-VPN: Failed to unregister setup stop receiver", error)
            }
            previousTeardown?.join()
            previousTeardown = null
            isRunning = false
            val tunnel = tun2SocksService
            tun2SocksService = null
            val vpnInterface = mInterface
            mInterface = null
            // Android 7 (API 24-25) can associate a reused tun0 with the old VPN
            // network until onLost. Observe before closing any native fd, then
            // await release off main. Remove when minSdk >= 26 or the rapid
            // stop/start emulator regression passes without this compatibility path.
            val legacyConnectivity = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1 && vpnInterface != null)
                getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager else null
            val legacyLost = legacyConnectivity?.let { CompletableDeferred<Unit>() }
            val legacyCallback = legacyLost?.let { completion ->
                object : ConnectivityManager.NetworkCallback() {
                    override fun onLost(network: Network) { completion.complete(Unit) }
                }
            }
            var legacyCallbackRegistered = false
            if (legacyConnectivity != null && legacyCallback != null) {
                try {
                    legacyConnectivity.registerNetworkCallback(
                        NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),
                        legacyCallback
                    )
                    legacyCallbackRegistered = true
                } catch (error: Exception) {
                    LogUtil.w(AppConfig.TAG, "StopCore-VPN: Cannot observe Android 7 network release", error)
                }
            }
            try {
                try {
                    tunnel?.stopTun2Socks()
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to stop tun2socks", e)
                }
                try {
                    RootLanSharing.stopClientSharing(this@CoreVpnService)
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to stop LAN sharing", e)
                }
                CoreServiceManager.stopCoreLoop(this@CoreVpnService).join()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed during VPN teardown", e)
            } finally {
                // Keep the foreground service alive until the native stop has completed.
                // stopSelf can trigger onDestroy before the fd is closed; the gate above
                // prevents that callback from starting a second teardown.
                if (isForced) {
                    try {
                        stopSelf()
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to stop service", e)
                    }
                }
                try {
                    vpnInterface?.close()
                    LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed")
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface", e)
                } finally {
                    if (legacyCallbackRegistered && legacyConnectivity != null && legacyCallback != null) {
                        try {
                            withTimeout(1500L) { legacyLost?.await() }
                        } catch (error: TimeoutCancellationException) {
                            LogUtil.w(AppConfig.TAG, "StopCore-VPN: Android 7 network release timed out", error)
                        } finally {
                            try {
                                legacyConnectivity.unregisterNetworkCallback(legacyCallback)
                            } catch (error: Exception) {
                                LogUtil.w(AppConfig.TAG, "StopCore-VPN: Cannot unregister Android 7 network observer", error)
                            }
                        }
                    }
                }
            }
        }
        teardownJob = job
        job.invokeOnCompletion {
            teardownComplete.complete()
            teardownScope.cancel()
        }
        job.start()
    }

    fun tryLockStart(): Boolean {
        return lifecycle.tryStart()
    }

    fun unlockStart() {
        lifecycle.releaseStart()
    }
}

/** Process-wide ordering of service instances; each completion is owned by its service. */
internal class VpnTeardownBarrier {
    private val pending = AtomicReference<Job?>()

    fun register(job: Job): Job? {
        // Register on creation, before a stop can race onDestroy. A replacement
        // must observe this completion even if cleanup has not been scheduled yet.
        val previous = pending.getAndSet(job)
        job.invokeOnCompletion { pending.compareAndSet(job, null) }
        return previous
    }
}

/** Serializes duplicate starts with stop requests, including a stop during setup. */
internal class VpnLifecycleGate {
    private val starting = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    fun tryStart(): Boolean {
        if (stopping.get() || !starting.compareAndSet(false, true)) return false
        if (!stopping.get()) return true
        starting.set(false)
        return false
    }

    fun releaseStart() {
        starting.set(false)
    }

    fun beginStop(): Boolean = stopping.compareAndSet(false, true)

    fun isStopping(): Boolean = stopping.get()

    fun shouldReuseRunningTunnel(
        serviceRunning: Boolean,
        coreRunning: Boolean,
        coreReloading: Boolean = false
    ): Boolean = !stopping.get() && serviceRunning && (coreRunning || coreReloading)
}
