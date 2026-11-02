package com.v2ray.ang.util

import com.v2ray.ang.dto.UrlContentRequest
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.withSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class HttpUtilProxyAuthenticationTest {
    @Test
    fun bothHttpHelpersKeepProxyAuthenticationOffTheOriginRequest() {
        for (withUserAgent in listOf(false, true)) {
            val requests = mutableListOf<Request>()
            val client = mock<OkHttpClient>()
            val call = mock<Call>()
            whenever(client.newCall(any())).thenAnswer { invocation ->
                requests.add(invocation.getArgument(0))
                call
            }
            whenever(call.execute()).thenAnswer {
                Response.Builder().request(requests.last()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("{}".toResponseBody("application/json".toMediaType())).build()
            }
            mockConstruction(OkHttpClient.Builder::class.java, withSettings().defaultAnswer(RETURNS_SELF)) { builder, _ ->
                whenever(builder.build()).thenReturn(client)
            }.use { builders ->
                val request = UrlContentRequest(
                    url = "https://update-target.invalid/release", httpPort = 10808,
                    proxyUsername = "test-local-proxy", proxyPassword = "test-only-secret"
                )
                val result = if (withUserAgent) HttpUtil.getUrlContentWithUserAgent(request) else HttpUtil.getUrlContent(request)
                assertEquals("{}", result)
                assertEquals(1, requests.size)
                assertNull(requests.single().header("Proxy-Authorization"))

                val authenticator = argumentCaptor<Authenticator>()
                verify(builders.constructed().single()).proxyAuthenticator(authenticator.capture())
                val originChallenge = Response.Builder().request(requests.single()).protocol(Protocol.HTTP_1_1)
                    .code(407).message("Proxy Authentication Required").build()
                assertNull(authenticator.firstValue.authenticate(null, originChallenge))
                val connect = requests.single().newBuilder().method("CONNECT", null).build()
                val proxyChallenge = originChallenge.newBuilder().request(connect).build()
                assertEquals(
                    Credentials.basic("test-local-proxy", "test-only-secret"),
                    authenticator.firstValue.authenticate(null, proxyChallenge)?.header("Proxy-Authorization")
                )
            }
        }
    }
}
