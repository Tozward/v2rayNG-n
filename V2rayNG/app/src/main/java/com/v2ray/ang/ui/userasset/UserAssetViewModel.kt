package com.v2ray.ang.ui.userasset

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.AssetUrlCache
import com.v2ray.ang.dto.entities.AssetUrlItem
import com.v2ray.ang.extension.concatUrl
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException

internal data class AssetFileMetadata(val length: Long, val lastModified: Long)

internal data class UserAssetUiState(
    val assets: List<AssetUrlCache> = emptyList(),
    val fileMetadata: Map<String, AssetFileMetadata> = emptyMap()
)

class UserAssetViewModel(application: Application) : BaseViewModel(application) {
    private val builtInGeoFiles = listOf(AppConfig.GEOSITE_DAT, AppConfig.GEOIP_DAT, AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT)

    private val _uiState = MutableStateFlow(UserAssetUiState())
    internal val uiState: StateFlow<UserAssetUiState> = _uiState.asStateFlow()
    private var reloadJob: Job? = null

    fun reload(geoFilesSource: String, extDir: File): Job {
        reloadJob?.cancel()
        return viewModelScope.launch(Dispatchers.IO) {
            val snapshot = buildAssetList(MmkvManager.decodeAssetUrls(), geoFilesSource)
            val files = extDir.listFiles().orEmpty().associateBy { it.name }
            val metadata = snapshot.mapNotNull { asset ->
                files[asset.assetUrl.remarks]?.let { file ->
                    asset.guid to AssetFileMetadata(file.length(), file.lastModified())
                }
            }.toMap()
            ensureActive()
            _uiState.value = UserAssetUiState(snapshot, metadata)
        }.also { reloadJob = it }
    }

    internal fun buildAssetList(
        decodedAssets: List<AssetUrlCache>?,
        geoFilesSource: String
    ): List<AssetUrlCache> {
        val savedAssets = decodedAssets ?: emptyList()
        val builtInItems = builtInGeoFiles
            .filter { geoFile -> savedAssets.none { it.assetUrl.remarks == geoFile } }
            .map {
                AssetUrlCache(
                    // Built-in rows have no persisted GUID; keep their UI identity across reloads.
                    "builtin:$it",
                    AssetUrlItem(
                        it,
                        String.format(AppConfig.GITHUB_DOWNLOAD_URL, geoFilesSource).concatUrl(it),
                        locked = true
                    )
                )
            }
        // Force update URL for geoip-only-cn-private.dat
        return (builtInItems + savedAssets).map { cache ->
            if (cache.assetUrl.remarks == AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT) {
                cache.copy(
                    assetUrl = cache.assetUrl.copy(
                        url = AppConfig.GEOIP_ONLY_CN_PRIVATE_URL
                    )
                )
            } else {
                cache
            }
        }
    }

    suspend fun downloadGeoFiles(
        extDir: File,
        httpPort: Int,
        proxyUsername: String? = null,
        proxyPassword: String? = null
    ): GeoDownloadResult {
        val downloader = HttpUtil.newFileDownloader(15000, httpPort, proxyUsername, proxyPassword)
        return downloadGeoFiles(
            uiState.value.assets,
            extDir,
            httpPort,
            proxyUsername,
            proxyPassword,
            downloader::downloadToFile
        )
    }

    internal suspend fun downloadGeoFiles(
        snapshot: List<AssetUrlCache>,
        extDir: File,
        httpPort: Int,
        proxyUsername: String?,
        proxyPassword: String?,
        downloadToFile: suspend (UrlContentRequest, File) -> Boolean
    ): GeoDownloadResult = coroutineScope {
        val slots = Semaphore(2)
        val results = snapshot
            .filter { it.assetUrl.url != "file" }
            .map { cache ->
                async(Dispatchers.IO) {
                    slots.withPermit {
                        cache.assetUrl.remarks to tryDownload(
                            cache.assetUrl,
                            extDir,
                            httpPort,
                            proxyUsername,
                            proxyPassword,
                            downloadToFile
                        )
                    }
                }
            }.awaitAll()
        val failures = results.filterNot { it.second }.map { it.first }
        GeoDownloadResult(results.size - failures.size, failures.size, failures)
    }

    private suspend fun tryDownload(
        item: AssetUrlItem,
        extDir: File,
        httpPort: Int,
        proxyUsername: String? = null,
        proxyPassword: String? = null,
        downloadToFile: suspend (UrlContentRequest, File) -> Boolean
    ): Boolean {
        val targetTemp = try {
            File.createTempFile("asset-", ".tmp", extDir)
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "Could not create temporary geo file: ${item.remarks}", e)
            return false
        }
        val target = File(extDir, item.remarks)
        try {
            if (
                downloadToFile(
                    UrlContentRequest(
                        url = item.url,
                        timeout = 15000,
                        httpPort = httpPort,
                        proxyUsername = proxyUsername,
                        proxyPassword = proxyPassword
                    ),
                    targetTemp
                )
            ) {
                currentCoroutineContext().ensureActive()
                if (targetTemp.renameTo(target)) return true
                throw IOException("Could not replace downloaded geo file: ${item.remarks}")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to download geo file: ${item.remarks}", e)
        } finally {
            if (targetTemp.exists() && !targetTemp.delete()) {
                LogUtil.w(AppConfig.TAG, "Could not remove incomplete geo file: ${item.remarks}")
            }
        }
        return false
    }

    data class GeoDownloadResult(
        val successCount: Int,
        val failureCount: Int,
        val failedAssets: List<String>
    )
}
