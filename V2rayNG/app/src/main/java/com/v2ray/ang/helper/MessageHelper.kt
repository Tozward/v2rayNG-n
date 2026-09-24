package com.v2ray.ang.helper

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.SubscriptionUpdateMessage
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.service.CoreTestService
import com.v2ray.ang.service.SubscriptionUpdateService
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.Serializable
import kotlin.coroutines.resume

object MessageHelper {
    const val EXTRA_REQUEST_ID = "requestId"

    sealed interface LocalProxyStatus {
        data class Running(val port: Int) : LocalProxyStatus
        data object Stopped : LocalProxyStatus
        data object Unknown : LocalProxyStatus
    }

    /**
     * Sends a message to the service.
     *
     * @param ctx The context.
     * @param what The message identifier.
     * @param content The message content.
     */
    fun sendMsg2Service(ctx: Context, what: Int, content: Serializable) {
        sendMsg(ctx, AppConfig.BROADCAST_ACTION_SERVICE, what, content)
    }

    /**
     * Sends an ordered service message and reports whether a daemon receiver handled it.
     * With no running daemon, the initial canceled result reaches [onResult] unchanged.
     */
    internal fun sendMsg2ServiceForResult(
        ctx: Context,
        what: Int,
        content: Serializable,
        onResult: (handled: Boolean) -> Unit,
    ) {
        val resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                onResult(resultCode == Activity.RESULT_OK)
            }
        }
        try {
            ctx.sendOrderedBroadcast(
                messageIntent(AppConfig.BROADCAST_ACTION_SERVICE, what, content),
                null,
                resultReceiver,
                null,
                Activity.RESULT_CANCELED,
                null,
                null,
            )
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to send ordered message to service", e)
            onResult(false)
        }
    }

    /** Asks the daemon for its current port; dynamic ports are process-local to the daemon. */
    suspend fun queryRunningHttpProxy(ctx: Context): LocalProxyStatus = withTimeoutOrNull(1000L) {
        suspendCancellableCoroutine { continuation ->
            val resultReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (continuation.isActive) {
                        continuation.resume(parseRunningHttpProxyStatus(resultCode, resultData))
                    }
                }
            }
            try {
                ctx.sendOrderedBroadcast(
                    messageIntent(AppConfig.BROADCAST_ACTION_SERVICE, AppConfig.MSG_QUERY_LOCAL_PROXY_PORT, ""),
                    null,
                    resultReceiver,
                    null,
                    Activity.RESULT_CANCELED,
                    null,
                    null,
                )
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to query daemon HTTP proxy port", e)
                if (continuation.isActive) continuation.resume(LocalProxyStatus.Unknown)
            }
        }
    } ?: LocalProxyStatus.Unknown

    /** Null means the daemon is stopped; an uncertain result must never select direct access. */
    suspend fun knownHttpProxyPort(ctx: Context): Int? = when (val status = queryRunningHttpProxy(ctx)) {
        is LocalProxyStatus.Running -> status.port
        LocalProxyStatus.Stopped -> null
        LocalProxyStatus.Unknown -> throw IOException("Could not determine proxy service state")
    }

    internal fun parseRunningHttpProxyStatus(resultCode: Int, resultData: String?): LocalProxyStatus =
        when (resultCode) {
            Activity.RESULT_OK -> resultData?.toIntOrNull()?.takeIf { it in 1..65535 }
                ?.let(LocalProxyStatus::Running) ?: LocalProxyStatus.Unknown
            Activity.RESULT_CANCELED, Activity.RESULT_FIRST_USER -> LocalProxyStatus.Stopped
            else -> LocalProxyStatus.Unknown
        }

    /**
     * Sends a message to the UI.
     *
     * @param ctx The context.
     * @param what The message identifier.
     * @param content The message content.
     */
    fun sendMsg2UI(ctx: Context, what: Int, content: Serializable, requestId: String? = null) {
        sendMsg(ctx, AppConfig.BROADCAST_ACTION_ACTIVITY, what, content, requestId)
    }

    /**
     * Sends a message to the test service.
     *
     * @param ctx The context.
     * @param message The test service message containing key, subscriptionId, and serverGuids.
     */
    fun sendMsg2TestService(ctx: Context, message: TestServiceMessage, requestId: String? = null) {
        try {
            val intent = Intent()
            intent.component = ComponentName(ctx, CoreTestService::class.java)
            intent.putExtra("content", message)
            requestId?.let { intent.putExtra(EXTRA_REQUEST_ID, it) }
            when (message.key) {
                AppConfig.MSG_MEASURE_CONFIG_START -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(ctx, intent)
                    } else {
                        ctx.startService(intent)
                    }
                }

                AppConfig.MSG_MEASURE_CONFIG_CANCEL -> {
                    // Do not wake up service just to cancel; stop only if it is already running.
                    ctx.stopService(intent)
                }

                else -> {
                    ctx.startService(intent)
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to send message to test service", e)
            if (message.key == AppConfig.MSG_MEASURE_CONFIG_START) {
                sendMsg2UI(ctx, AppConfig.MSG_MEASURE_CONFIG_CANCEL, "", requestId)
            }
        }
    }

    /**
     * Sends a message to the subscription service.
     *
     * @param ctx The context.
     * @param message The subscription service message containing key and subId.
     */
    fun sendMsg2SubscriptionService(ctx: Context, message: SubscriptionUpdateMessage) {
        try {
            val intent = Intent()
            intent.component = ComponentName(ctx, SubscriptionUpdateService::class.java)
            intent.putExtra("content", message)
            when (message.key) {
                AppConfig.MSG_SUB_UPDATE_START -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(ctx, intent)
                    } else {
                        ctx.startService(intent)
                    }
                }

                AppConfig.MSG_SUB_UPDATE_CANCEL -> {
                    ctx.stopService(intent)
                }

                else -> {
                    ctx.startService(intent)
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to send message to subscription service", e)
        }
    }

    /**
     * Sends a message with the specified action.
     *
     * @param ctx The context.
     * @param action The action string.
     * @param what The message identifier.
     * @param content The message content.
     */
    private fun sendMsg(ctx: Context, action: String, what: Int, content: Serializable, requestId: String? = null) {
        try {
            ctx.sendBroadcast(messageIntent(action, what, content).apply {
                requestId?.let { putExtra(EXTRA_REQUEST_ID, it) }
            })
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to send message with action: $action", e)
        }
    }

    private fun messageIntent(action: String, what: Int, content: Serializable): Intent =
        Intent(action).apply {
            `package` = AppConfig.ANG_PACKAGE
            putExtra("key", what)
            putExtra("content", content)
        }
}
