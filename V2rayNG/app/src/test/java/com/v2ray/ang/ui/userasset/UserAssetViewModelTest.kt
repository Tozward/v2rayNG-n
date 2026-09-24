package com.v2ray.ang.ui.userasset

import android.app.Application
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.AssetUrlCache
import com.v2ray.ang.dto.entities.AssetUrlItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import java.io.File

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
    fun geoDownloadUsesOnlyRunningProxyWhenItSucceeds() {
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
        assertFalse(File(files.root, "geosite.dat_temp").exists())
    }

    @Test
    fun geoDownloadDoesNotFallBackToDirectWhenRunningProxyFails() {
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
        assertFalse(File(files.root, "geoip.dat_temp").exists())
    }

    @Test
    fun geoDownloadWithoutActiveProxyUsesDirectConnection() {
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
}
