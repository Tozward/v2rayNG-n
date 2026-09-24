package com.v2ray.ang.handler

import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class UpdateCheckerManagerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val digest = "sha256:" + "a".repeat(64)

    @Test
    fun testpreVersionsCompareNumericallyAndSkipOlderReleases() {
        val releases = listOf(
            release("v2.3.9-testpre.9"),
            release("v2.3.9-testpre.11"),
            release("v2.3.9-testpre.10")
        )
        val result = UpdateCheckerManager.selectUpdate(
            releases, "2.3.9-testpre.10", true, listOf("arm64-v8a"), false
        )
        assertTrue(result.hasUpdate)
        assertEquals("2.3.9-testpre.11", result.latestVersion)
        assertEquals("https://api.github.com/repos/Tozward/v2rayNG-n/releases/assets/11", result.downloadUrl)
        assertFalse(UpdateCheckerManager.selectUpdate(
            releases, "2.3.9-testpre.11", true, listOf("arm64-v8a"), false
        ).hasUpdate)
    }

    @Test
    fun stableAndPrereleaseChannelSelectionIsCorrect() {
        val releases = listOf(
            release("v2.4.0-testpre.1"),
            release("v2.3.9", prerelease = false),
            release("v2.3.8", prerelease = false)
        )
        assertEquals("2.3.9", UpdateCheckerManager.selectUpdate(
            releases, "2.3.9-testpre.4", false, listOf("arm64-v8a"), false
        ).latestVersion)
        assertEquals("2.4.0-testpre.1", UpdateCheckerManager.selectUpdate(
            releases, "2.3.9-testpre.4", true, listOf("arm64-v8a"), false
        ).latestVersion)
        assertFalse(UpdateCheckerManager.selectUpdate(
            releases, "2.4.0", true, listOf("arm64-v8a"), false
        ).hasUpdate)
    }

    @Test
    fun choosesExactAbiAndDistributionAndRejectsMissingIntegrityMetadata() {
        val release = release(
            "v2.3.9-testpre.5",
            assets = listOf(
                asset("2.3.9-testpre.5", "x86_64", 64),
                asset("2.3.9-testpre.5", "x86", 86),
                asset("2.3.9-testpre.5", "arm64-v8a", 12, fdroid = true),
                asset("2.3.9-testpre.5", "universal", 99)
            )
        )
        assertEquals(86L, UpdateCheckerManager.selectUpdate(
            listOf(release), "2.3.9-testpre.4", true, listOf("x86"), false
        ).downloadUrl?.substringAfterLast('/')?.toLong())
        assertEquals(12L, UpdateCheckerManager.selectUpdate(
            listOf(release), "2.3.9-testpre.4", true, listOf("arm64-v8a"), true
        ).downloadUrl?.substringAfterLast('/')?.toLong())
        assertEquals(99L, UpdateCheckerManager.selectUpdate(
            listOf(release), "2.3.9-testpre.4", true, listOf("arm64-v8a"), false
        ).downloadUrl?.substringAfterLast('/')?.toLong())

        val unverified = release.copy(assets = listOf(asset("2.3.9-testpre.5", "arm64-v8a", 1).copy(digest = null)))
        assertFalse(UpdateCheckerManager.selectUpdate(
            listOf(unverified), "2.3.9-testpre.4", true, listOf("arm64-v8a"), false
        ).hasUpdate)
    }

    @Test
    fun automaticCheckIsDisabledOrRateLimited() {
        val now = 1_000_000_000L
        assertFalse(UpdateCheckerManager.shouldAutoCheck(false, 0, now))
        assertTrue(UpdateCheckerManager.shouldAutoCheck(true, 0, now))
        assertFalse(UpdateCheckerManager.shouldAutoCheck(true, now - 1_000, now))
        assertTrue(UpdateCheckerManager.shouldAutoCheck(true, now - 6 * 60 * 60 * 1000L, now))
        assertTrue(UpdateCheckerManager.shouldAutoCheck(true, now + 1_000, now))
    }

    @Test
    fun downloadChecksDigestSizeAndUsesBinaryApiHeader() = runBlocking {
        val bytes = "verified apk bytes".toByteArray()
        val acceptHeader = AtomicReference<String?>(null)
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5_000
        val responder = thread(start = true) {
            repeat(3) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Accept:", ignoreCase = true)) {
                            acceptHeader.set(line.substringAfter(':').trim())
                        }
                    }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        try {
            val actualDigest = "sha256:" + MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val update = CheckUpdateResult(
                hasUpdate = true,
                downloadUrl = "http://127.0.0.1:${server.localPort}/asset",
                assetSize = bytes.size.toLong(),
                assetDigest = actualDigest
            )
            val target = UpdateCheckerManager.downloadApk(temporaryFolder.root, update, {})
            assertEquals(bytes.toList(), target.readBytes().toList())
            assertEquals("application/octet-stream", acceptHeader.get())

            var rejected = false
            try {
                UpdateCheckerManager.downloadApk(
                    temporaryFolder.root, update.copy(assetDigest = digest), {}
                )
            } catch (_: IOException) {
                rejected = true
            }
            assertTrue(rejected)
            assertTrue(temporaryFolder.root.resolve("updates").listFiles().orEmpty()
                .none { it.name.startsWith("pending-") })

            rejected = false
            try {
                UpdateCheckerManager.downloadApk(
                    temporaryFolder.root, update.copy(assetSize = bytes.size.toLong() + 1), {}
                )
            } catch (_: IOException) {
                rejected = true
            }
            assertTrue(rejected)
            assertTrue(temporaryFolder.root.resolve("updates").listFiles().orEmpty()
                .none { it.name.startsWith("pending-") })
        } finally {
            server.close()
            responder.join(1_000)
        }
    }

    @Test
    fun activeProxyIsTheOnlyDownloadRouteEvenWhenItFails() = runBlocking {
        val bytes = "apk via selected node".toByteArray()
        val digest = "sha256:" + MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val direct = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val proxy = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        direct.soTimeout = 1_500
        proxy.soTimeout = 5_000
        val directUsed = AtomicBoolean(false)
        val proxyRequests = AtomicInteger(0)
        val directResponder = thread(start = true) {
            try {
                direct.accept().use { socket ->
                    directUsed.set(true)
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes
                    )
                }
            } catch (_: SocketTimeoutException) {
                // No direct request is the expected result.
            }
        }
        val proxyResponder = thread(start = true) {
            repeat(2) {
                proxy.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    val count = proxyRequests.incrementAndGet()
                    socket.getOutputStream().apply {
                        if (count == 1) {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                        } else {
                            write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                        flush()
                    }
                }
            }
        }
        val update = CheckUpdateResult(
            hasUpdate = true,
            downloadUrl = "http://127.0.0.1:${direct.localPort}/asset",
            assetSize = bytes.size.toLong(),
            assetDigest = digest
        )
        try {
            val ready = UpdateCheckerManager.downloadApk(
                temporaryFolder.root, update, {}, activeProxyPort = proxy.localPort
            )
            assertEquals(bytes.toList(), ready.readBytes().toList())
            var failed = false
            try {
                UpdateCheckerManager.downloadApk(
                    temporaryFolder.root, update, {}, activeProxyPort = proxy.localPort
                )
            } catch (_: IOException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals(2, proxyRequests.get())
            directResponder.join(2_000)
            assertFalse(directUsed.get())
            assertEquals(bytes.toList(), ready.readBytes().toList())
            assertTrue(temporaryFolder.root.resolve("updates").listFiles().orEmpty()
                .none { it.name.startsWith("pending-") })
        } finally {
            direct.close()
            proxy.close()
            directResponder.join(1_000)
            proxyResponder.join(1_000)
        }
    }

    private fun release(
        tag: String,
        prerelease: Boolean = true,
        assets: List<GitHubRelease.Asset> = listOf(
            asset(tag.removePrefix("v"), "arm64-v8a", tag.substringAfterLast('.').toLongOrNull() ?: 7)
        )
    ) = GitHubRelease(tag, "", assets, prerelease)

    private fun asset(version: String, abi: String, id: Long, fdroid: Boolean = false) =
        GitHubRelease.Asset(
            id = id,
            name = "v2rayNG_${version}${if (fdroid) "-fdroid" else ""}_${abi}.apk",
            size = 100,
            digest = digest,
            browserDownloadUrl = ""
        )
}
