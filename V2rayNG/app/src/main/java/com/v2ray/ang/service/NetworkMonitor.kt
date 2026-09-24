package com.v2ray.ang.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.v2ray.ang.AppConfig
import com.v2ray.ang.extension.delay
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Watches the network that carries the tunnel and reports topology changes.
 *
 * Cellular -> Wi-Fi is a make-before-break handover: the new network is announced while the old one
 * is still connected, so the socket to the server is never reset and the core keeps using a dead
 * connection. Deciding that a handover happened is what this class is for, acting on it is not.
 *
 * Only used from Android P and above, see CoreServiceManager.startNetworkMonitor().
 * [onHandover] is invoked on a background thread after the debounce window and should enqueue
 * any slow work, so unregister can wait for an in-flight callback without blocking on native code.
 */
class NetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val onHandover: () -> Unit,
) {
    private companion object {
        const val HANDOVER_DEBOUNCE_MS = 1000L
    }

    private val lock = Any()
    private val handoverState = NetworkHandoverState<Network>()
    private var handoverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var handoverJob: Job? = null
    private var registered = false

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * On Android 12+, registerBestMatchingNetworkCallback provides the same single-best-network
     * handover signal without keeping a network up. Older Android versions still need requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private val request by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(lock) {
                if (registered && handoverState.onAvailable(network)) {
                    scheduleHandover(network)
                }
            }
        }

        override fun onLost(network: Network) {
            synchronized(lock) {
                if (registered && handoverState.onLost(network)) {
                    handoverJob?.cancel()
                    handoverJob = null
                }
            }
        }
    }

    /**
     * Starts watching. Safe to call more than once, only the first call registers.
     */
    fun register() {
        synchronized(lock) {
            if (registered) return
            if (handoverScope.coroutineContext[Job]?.isActive != true) {
                handoverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            }
            registered = true
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    connectivity.registerBestMatchingNetworkCallback(
                        request, callback, Handler(Looper.getMainLooper())
                    )
                } else {
                    connectivity.requestNetwork(request, callback)
                }
            } catch (e: Exception) {
                registered = false
                LogUtil.e(AppConfig.TAG, "NetworkMonitor: Failed to register network callback", e)
            }
        }
    }

    /**
     * Stops watching and drops the tracked state. Safe to call more than once.
     */
    fun unregister() {
        synchronized(lock) {
            handoverJob?.cancel()
            handoverJob = null
            handoverScope.coroutineContext[Job]?.cancel()
            handoverState.reset()
            if (!registered) return
            registered = false
        }
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "NetworkMonitor: Failed to unregister callback", e)
        }
    }

    private fun scheduleHandover(network: Network) {
        LogUtil.i(AppConfig.TAG, "NetworkMonitor: Upstream is now $network")
        handoverJob?.cancel()
        handoverJob = handoverScope.launch {
            try {
                delay(HANDOVER_DEBOUNCE_MS)
                synchronized(lock) {
                    if (registered) onHandover()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "NetworkMonitor: Failed to handle upstream change", e)
            }
        }
    }
}

/** Remembers a lost network so reconnecting to the same Network still reloads the core. */
internal class NetworkHandoverState<T> {
    private var upstream: T? = null
    private var hasObservedNetwork = false

    fun onAvailable(network: T): Boolean {
        val isHandover = hasObservedNetwork && upstream != network
        upstream = network
        hasObservedNetwork = true
        return isHandover
    }

    fun onLost(network: T): Boolean {
        if (upstream != network) return false
        upstream = null
        return true
    }

    fun reset() {
        upstream = null
        hasObservedNetwork = false
    }
}
