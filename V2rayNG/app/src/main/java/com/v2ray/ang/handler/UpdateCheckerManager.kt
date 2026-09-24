package com.v2ray.ang.handler

import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object UpdateCheckerManager {
    private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private val versionPattern = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)((?:\\.\\d+)*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")
    private val digestPattern = Regex("^sha256:[0-9a-fA-F]{64}$")

    fun shouldAutoCheck(enabled: Boolean, lastAttemptAt: Long, now: Long): Boolean =
        enabled && (lastAttemptAt <= 0 || now < lastAttemptAt || now - lastAttemptAt >= AUTO_CHECK_INTERVAL_MS)

    suspend fun checkForUpdate(
        includePreRelease: Boolean,
        activeProxyPort: Int? = null,
        quick: Boolean = false
    ): CheckUpdateResult {
        val response = withContext(Dispatchers.IO) {
            val url = "${AppConfig.APP_API_URL}?per_page=20"
            val timeout = if (quick) 3500 else 5000
            if (activeProxyPort != null && activeProxyPort !in 1..65535) {
                throw IOException("Invalid running proxy port")
            }
            val proxyRequest = activeProxyPort?.let { port ->
                UrlContentRequest(
                    url = url,
                    timeout = timeout,
                    httpPort = port,
                    proxyUsername = SettingsManager.getSocksUsername(),
                    proxyPassword = SettingsManager.getSocksPassword()
                )
            }
            val request = proxyRequest ?: UrlContentRequest(url = url, timeout = timeout)
            HttpUtil.getUrlContent(request)
                ?: throw IOException("Could not load release list")
        }
        return withContext(Dispatchers.Default) {
            val releases = JsonUtil.fromJsonSafe(response, Array<GitHubRelease>::class.java)
                ?: throw IOException("Invalid release list")
            selectUpdate(
                releases.asList(),
                BuildConfig.VERSION_NAME,
                includePreRelease,
                Build.SUPPORTED_ABIS.asList(),
                BuildConfig.APPLICATION_ID.endsWith(".fdroid")
            )
        }
    }

    internal fun selectUpdate(
        releases: List<GitHubRelease>,
        currentVersion: String,
        includePreRelease: Boolean,
        supportedAbis: List<String>,
        fdroid: Boolean
    ): CheckUpdateResult {
        val current = parseVersion(currentVersion) ?: throw IllegalStateException("Invalid app version")
        val selected = releases.asSequence()
            .filter { includePreRelease || !it.prerelease }
            .mapNotNull { release ->
                val version = parseVersion(release.tagName) ?: return@mapNotNull null
                if (version <= current) return@mapNotNull null
                val asset = findAsset(release, supportedAbis, fdroid) ?: return@mapNotNull null
                Triple(release, version, asset)
            }
            .maxWithOrNull { first, second -> first.second.compareTo(second.second) }
            ?: return CheckUpdateResult(hasUpdate = false)
        val (release, _, asset) = selected
        return CheckUpdateResult(
            hasUpdate = true,
            latestVersion = release.tagName.removePrefix("v"),
            releaseNotes = release.body,
            downloadUrl = "${AppConfig.APP_API_URL}/assets/${asset.id}",
            assetSize = asset.size,
            assetDigest = asset.digest,
            isPreRelease = release.prerelease
        )
    }

    private fun findAsset(
        release: GitHubRelease,
        supportedAbis: List<String>,
        fdroid: Boolean
    ): GitHubRelease.Asset? {
        val variant = if (fdroid) "-fdroid" else ""
        val version = release.tagName.removePrefix("v")
        val prefix = "v2rayNG_${version}${variant}_"
        val abis = supportedAbis + "universal"
        return abis.firstNotNullOfOrNull { abi ->
            release.assets.firstOrNull { asset ->
                asset.name == "$prefix$abi.apk" && asset.id > 0 && asset.size > 0 &&
                    asset.digest?.matches(digestPattern) == true
            }
        }
    }

    private data class Version(val numbers: List<Int>, val preRelease: List<String>?) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            for (index in 0 until maxOf(numbers.size, other.numbers.size)) {
                val comparison = numbers.getOrElse(index) { 0 }.compareTo(other.numbers.getOrElse(index) { 0 })
                if (comparison != 0) return comparison
            }
            if (preRelease == null) return if (other.preRelease == null) 0 else 1
            if (other.preRelease == null) return -1
            for (index in 0 until minOf(preRelease.size, other.preRelease.size)) {
                val left = preRelease[index]
                val right = other.preRelease[index]
                val leftNumber = left.toLongOrNull()
                val rightNumber = right.toLongOrNull()
                val comparison = when {
                    leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                    leftNumber != null -> -1
                    rightNumber != null -> 1
                    else -> left.compareTo(right)
                }
                if (comparison != 0) return comparison
            }
            return preRelease.size.compareTo(other.preRelease.size)
        }
    }

    private fun parseVersion(raw: String): Version? {
        val match = versionPattern.matchEntire(raw) ?: return null
        val numbers = (1..3).map { match.groupValues[it].toIntOrNull() ?: return null } +
            match.groupValues[4].split('.').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: return null }
        val suffix = match.groupValues[5].takeIf { it.isNotEmpty() }
        return Version(numbers, suffix?.split('.'))
    }

    suspend fun downloadApk(
        cacheDir: File,
        update: CheckUpdateResult,
        onProgress: (Int) -> Unit,
        activeProxyPort: Int? = null
    ): File = withContext(Dispatchers.IO) {
        val url = update.downloadUrl ?: throw IOException("Missing release asset")
        val expectedDigest = update.assetDigest?.takeIf { it.matches(digestPattern) }
            ?: throw IOException("Missing release digest")
        val expectedSize = update.assetSize.takeIf { it > 0 }
            ?: throw IOException("Missing release size")
        if (activeProxyPort != null && activeProxyPort !in 1..65535) {
            throw IOException("Invalid running proxy port")
        }
        val updateDir = File(cacheDir, "updates")
        if (!updateDir.exists() && !updateDir.mkdirs()) throw IOException("Could not create update cache")
        val pending = File.createTempFile("pending-", ".apk", updateDir)
        val ready = File(updateDir, "update.apk")
        try {
            val client = downloadClient(activeProxyPort ?: 0)
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "v2rayNG/${BuildConfig.VERSION_NAME}")
                .build()
            currentCoroutineContext().ensureActive()
            onProgress(0)
            val hash = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var lastProgress = -1
            val call = client.newCall(request)
            val cancellationWatcher = launch(Dispatchers.Default) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Release download returned ${response.code}")
                    response.body.byteStream().use { input ->
                        pending.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                hash.update(buffer, 0, count)
                                received += count
                                if (received > expectedSize) throw IOException("Release size exceeded")
                                val progress = (received * 100 / expectedSize).toInt()
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }
            } finally {
                cancellationWatcher.cancel()
            }
            currentCoroutineContext().ensureActive()
            if (received != expectedSize) throw IOException("Release size mismatch")
            val actualDigest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (!expectedDigest.substringAfter(':').equals(actualDigest, ignoreCase = true)) {
                throw IOException("Release digest mismatch")
            }
            if (ready.exists() && !ready.delete()) throw IOException("Could not replace cached update")
            if (!pending.renameTo(ready)) throw IOException("Could not finish update download")
            ready
        } finally {
            pending.delete()
        }
    }

    private fun downloadClient(port: Int = 0): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.MINUTES)
            .followRedirects(true)
        if (port > 0) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(AppConfig.LOOPBACK, port)))
            builder.proxyAuthenticator { _, response ->
                val username = SettingsManager.getSocksUsername()
                val password = SettingsManager.getSocksPassword()
                if (response.request.header("Proxy-Authorization") != null ||
                    username.isNullOrBlank() || password.isNullOrBlank()
                ) null
                else response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(username, password))
                    .build()
            }
        }
        return builder.build()
    }
}
