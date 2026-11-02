package com.v2ray.ang.handler

import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Protocol
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

class UpdateProxyAuthenticationTest {
    private val authorization = Credentials.basic("local-proxy", "test-only-secret")

    @Test
    fun anHttpsOriginCannotRequestProxyCredentialsInsideAnEstablishedTunnel() {
        val client = UpdateCheckerManager.updateClient(1080, "local-proxy", "test-only-secret")
        val request = Request.Builder().url("https://update-target.invalid/release").build()
        val challenge = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(407).message("Proxy Authentication Required").build()
        assertNull(client.proxyAuthenticator.authenticate(null, challenge))
    }

    @Test
    fun proxyAuthenticationStaysOnConnectAndNeverReachesTheTlsOrigin() {
        val secure = exchangeThroughProxy(preemptiveOriginHeader = false)
        assertEquals(authorization, secure.first["proxy-authorization"])
        assertNull(secure.second["proxy-authorization"])

        // Reproduce the preceding generic helper's original-request header:
        // encryption does not keep that header away from the HTTPS origin.
        val preceding = exchangeThroughProxy(preemptiveOriginHeader = true)
        assertEquals(authorization, preceding.second["proxy-authorization"])
    }

    @Test
    fun aDirectRequestDoesNotCarryConfiguredProxyCredentials() {
        val observed = AtomicReference<Map<String, String>>()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5_000
        val failure = AtomicReference<Throwable?>()
        val responder = thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    observed.set(readHeaders(socket.getInputStream()))
                    respond(socket.getOutputStream())
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        try {
            val client = UpdateCheckerManager.updateClient(0, "local-proxy", "test-only-secret", 5_000)
            client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/release").build())
                .execute().use { assertEquals("{}", it.body.string()) }
        } finally {
            server.close()
            responder.join(10_000)
        }
        assertFalse(responder.isAlive)
        failure.get()?.let { throw AssertionError("direct fixture failed", it) }
        assertNull(observed.get()["proxy-authorization"])
    }

    private fun exchangeThroughProxy(preemptiveOriginHeader: Boolean): Pair<Map<String, String>, Map<String, String>> {
        val store = KeyStore.getInstance("PKCS12")
        val password = "test-proxy".toCharArray()
        javaClass.getResourceAsStream("/update-proxy-test.p12").use { store.load(it, password) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val trust = trusts.trustManagers.filterIsInstance<X509TrustManager>().single()
        val tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, arrayOf(trust), null) }
        val connect = AtomicReference<Map<String, String>>()
        val origin = AtomicReference<Map<String, String>>()
        val failure = AtomicReference<Throwable?>()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5_000
        val responder = thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    connect.set(readHeaders(socket.getInputStream()))
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                        flush()
                    }
                    (tls.socketFactory.createSocket(socket, "update-target.invalid", 443, false) as SSLSocket).use { secure ->
                        secure.useClientMode = false
                        secure.soTimeout = 5_000
                        secure.startHandshake()
                        origin.set(readHeaders(secure.getInputStream()))
                        respond(secure.getOutputStream())
                    }
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        try {
            val client = UpdateCheckerManager.updateClient(server.localPort, "local-proxy", "test-only-secret", 5_000)
                .newBuilder().sslSocketFactory(tls.socketFactory, trust).build()
            val request = Request.Builder().url("https://update-target.invalid/release").apply {
                if (preemptiveOriginHeader) header("Proxy-Authorization", authorization)
            }.build()
            client.newCall(request).execute().use {
                assertTrue(it.isSuccessful)
                assertEquals("{}", it.body.string())
            }
        } finally {
            server.close()
            responder.join(10_000)
        }
        assertFalse(responder.isAlive)
        failure.get()?.let { throw AssertionError("CONNECT/TLS fixture failed", it) }
        return connect.get() to origin.get()
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val reader = input.bufferedReader()
        check(!reader.readLine().isNullOrEmpty()) { "missing HTTP request" }
        return buildMap {
            while (true) {
                val line = reader.readLine() ?: error("incomplete HTTP headers")
                if (line.isEmpty()) break
                put(line.substringBefore(':').lowercase(), line.substringAfter(':').trim())
            }
        }
    }

    private fun respond(output: OutputStream) {
        output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
        output.flush()
    }
}
