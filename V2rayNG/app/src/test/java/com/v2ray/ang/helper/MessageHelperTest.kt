package com.v2ray.ang.helper

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageHelperTest {
    @Test
    fun proxyStatusRequiresDaemonAcknowledgementAndValidPort() {
        assertEquals(
            MessageHelper.LocalProxyStatus.Running(10808),
            MessageHelper.parseRunningHttpProxyStatus(Activity.RESULT_OK, "10808")
        )
        assertEquals(
            MessageHelper.LocalProxyStatus.Stopped,
            MessageHelper.parseRunningHttpProxyStatus(Activity.RESULT_CANCELED, "10808")
        )
        assertEquals(
            MessageHelper.LocalProxyStatus.Stopped,
            MessageHelper.parseRunningHttpProxyStatus(Activity.RESULT_FIRST_USER, null)
        )
        assertEquals(MessageHelper.LocalProxyStatus.Unknown, MessageHelper.parseRunningHttpProxyStatus(Activity.RESULT_OK, null))
        assertEquals(MessageHelper.LocalProxyStatus.Unknown, MessageHelper.parseRunningHttpProxyStatus(Activity.RESULT_OK, "0"))
        assertEquals(MessageHelper.LocalProxyStatus.Unknown, MessageHelper.parseRunningHttpProxyStatus(Activity.RESULT_OK, "65536"))
        assertEquals(MessageHelper.LocalProxyStatus.Unknown, MessageHelper.parseRunningHttpProxyStatus(Activity.RESULT_OK, "invalid"))
    }
}
