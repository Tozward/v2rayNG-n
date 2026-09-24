package com.v2ray.ang.handler

import com.sun.net.httpserver.HttpServer
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
import java.net.InetSocketAddress
import java.security.MessageDigest

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
        var acceptHeader: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { exchange ->
            acceptHeader = exchange.requestHeaders.getFirst("Accept")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val actualDigest = "sha256:" + MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val update = CheckUpdateResult(
                hasUpdate = true,
                downloadUrl = "http://127.0.0.1:${server.address.port}/asset",
                assetSize = bytes.size.toLong(),
                assetDigest = actualDigest
            )
            val target = UpdateCheckerManager.downloadApk(temporaryFolder.root, update) {}
            assertEquals(bytes.toList(), target.readBytes().toList())
            assertEquals("application/octet-stream", acceptHeader)

            var rejected = false
            try {
                UpdateCheckerManager.downloadApk(
                    temporaryFolder.root, update.copy(assetDigest = digest)
                ) {}
            } catch (_: IOException) {
                rejected = true
            }
            assertTrue(rejected)
            assertFalse(temporaryFolder.root.resolve("updates/pending.apk").exists())
        } finally {
            server.stop(0)
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
