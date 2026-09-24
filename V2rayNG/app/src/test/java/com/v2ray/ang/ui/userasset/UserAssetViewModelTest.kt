package com.v2ray.ang.ui.userasset

import android.app.Application
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.AssetUrlCache
import com.v2ray.ang.dto.entities.AssetUrlItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class UserAssetViewModelTest {
    @get:Rule val files = TemporaryFolder()
    private val viewModel = UserAssetViewModel(mock(Application::class.java))

    @Test
    fun builtInIdentitySurvivesReloadAndSourceChange() {
        val first = viewModel.buildAssetList(null, "first/source")
        val reloaded = viewModel.buildAssetList(emptyList(), "second/source")

        assertEquals(3, first.size)
        assertEquals(first.size, first.map { it.guid }.distinct().size)
        assertEquals(first.map { it.guid }, reloaded.map { it.guid })
        assertTrue(reloaded.all { it.assetUrl.locked == true })
        assertEquals(
            AppConfig.GEOIP_ONLY_CN_PRIVATE_URL,
            reloaded.single { it.assetUrl.remarks == AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT }.assetUrl.url,
        )
        assertTrue(reloaded.filter { it.assetUrl.remarks != AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT }
            .all { it.assetUrl.url.startsWith("https://github.com/second/source/") })
    }

    @Test
    fun savedAssetKeepsItsIdentityAndReplacesMatchingBuiltIn() {
        val saved = AssetUrlCache("saved-guid", AssetUrlItem(AppConfig.GEOSITE_DAT, "https://example.invalid/geosite.dat"))
        val custom = AssetUrlCache("custom-guid", AssetUrlItem("custom.dat", "file"))
        val builtIns = viewModel.buildAssetList(emptyList(), "source")
        val rows = viewModel.buildAssetList(listOf(saved, custom), "source")

        assertEquals(4, rows.size)
        assertEquals(saved, rows.single { it.assetUrl.remarks == saved.assetUrl.remarks })
        assertEquals(custom, rows.single { it.guid == custom.guid })
        assertEquals(
            builtIns.filter { it.assetUrl.remarks != AppConfig.GEOSITE_DAT }.map { it.guid },
            rows.filter { it.assetUrl.locked == true }.map { it.guid },
        )
    }

    @Test
    fun geoDownloadUsesOnlyRunningProxyWhenItSucceeds() = runBlocking {
        val attemptedPorts = mutableListOf<Int>()
        val result = viewModel.downloadGeoFiles(
            listOf(AssetUrlCache("asset", AssetUrlItem("geosite.dat", "https://example.com/geosite.dat"))),
            files.root,
            10808,
            "user",
            "password"
        ) { request, file ->
            attemptedPorts += request.httpPort
            assertEquals("user", request.proxyUsername)
            assertEquals("password", request.proxyPassword)
            file.writeText("proxy")
            true
        }

        assertEquals(listOf(10808), attemptedPorts)
        assertEquals(1, result.successCount)
        assertEquals("proxy", File(files.root, "geosite.dat").readText())
        assertTrue(files.root.listFiles().orEmpty().none { it.name.startsWith("asset-") })
    }

    @Test
    fun geoDownloadDoesNotFallBackToDirectWhenRunningProxyFails() = runBlocking {
        val asset = listOf(AssetUrlCache("asset", AssetUrlItem("geoip.dat", "https://example.com/geoip.dat")))
        val target = File(files.root, "geoip.dat").apply { writeText("old") }
        val attemptedPorts = mutableListOf<Int>()
        val failed = viewModel.downloadGeoFiles(asset, files.root, 10808, null, null) { request, file ->
            attemptedPorts += request.httpPort
            file.writeText("partial")
            false
        }

        assertEquals(listOf(10808), attemptedPorts)
        assertEquals(1, failed.failureCount)
        assertEquals("old", target.readText())
        assertTrue(files.root.listFiles().orEmpty().none { it.name.startsWith("asset-") })
    }

    @Test
    fun geoDownloadWithoutActiveProxyUsesDirectConnection() = runBlocking {
        val attemptedPorts = mutableListOf<Int>()
        val result = viewModel.downloadGeoFiles(
            listOf(AssetUrlCache("asset", AssetUrlItem("geoip.dat", "https://example.com/geoip.dat"))),
            files.root,
            0,
            null,
            null
        ) { request, file ->
            attemptedPorts += request.httpPort
            file.writeText("direct")
            true
        }

        assertEquals(listOf(0), attemptedPorts)
        assertEquals(1, result.successCount)
    }

    @Test
    fun downloadsAtMostTwoRemoteAssetsAtOnce() = runBlocking {
        val entered = AtomicInteger()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val firstTwoStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val assets = (1..3).map { index ->
            AssetUrlCache("id$index", AssetUrlItem("asset$index.dat", "https://example.com/$index"))
        }

        val job = async {
            viewModel.downloadGeoFiles(assets, files.root, 10808, null, null) { _, target ->
                val now = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                if (entered.incrementAndGet() == 2) firstTwoStarted.complete(Unit)
                try {
                    release.await()
                    target.writeText("downloaded")
                    true
                } finally {
                    active.decrementAndGet()
                }
            }
        }

        withTimeout(5_000) { firstTwoStarted.await() }
        assertEquals(2, entered.get())
        release.complete(Unit)
        val result = withTimeout(5_000) { job.await() }
        assertEquals(3, result.successCount)
        assertEquals(0, result.failureCount)
        assertEquals(2, peak.get())
    }

    @Test
    fun importedLocalFileIsNotDownloaded() = runBlocking {
        val local = File(files.root, "custom.dat").apply { writeText("local") }
        val attemptedUrls = mutableListOf<String>()
        val result = viewModel.downloadGeoFiles(
            listOf(
                AssetUrlCache("local", AssetUrlItem("custom.dat", "file")),
                AssetUrlCache("remote", AssetUrlItem("geosite.dat", "https://example.com/geosite.dat"))
            ), files.root, 0, null, null
        ) { request, target ->
            attemptedUrls += request.url.orEmpty()
            target.writeText("remote")
            true
        }

        assertEquals(listOf("https://example.com/geosite.dat"), attemptedUrls)
        assertEquals(1, result.successCount)
        assertEquals(0, result.failureCount)
        assertEquals("local", local.readText())
    }

    @Test
    fun cancellationRemovesOnlyTheIncompleteDownload() = runBlocking {
        val target = File(files.root, "geoip.dat").apply { writeText("old") }
        val started = CompletableDeferred<Unit>()
        val job = async {
            viewModel.downloadGeoFiles(
                listOf(AssetUrlCache("asset", AssetUrlItem("geoip.dat", "https://example.com/geoip.dat"))),
                files.root, 10808, null, null
            ) { _, pending ->
                pending.writeText("partial")
                started.complete(Unit)
                awaitCancellation()
            }
        }

        withTimeout(5_000) { started.await() }
        job.cancelAndJoin()
        assertEquals("old", target.readText())
        assertTrue(files.root.listFiles().orEmpty().none { it.name.startsWith("asset-") })
    }
}
