package com.v2ray.ang.handler

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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

    internal fun releaseUrl(includePreRelease: Boolean): String =
        if (includePreRelease) "${AppConfig.APP_API_URL}?per_page=20"
        else "${AppConfig.APP_API_URL}/latest"

    internal fun parseReleases(response: String, includePreRelease: Boolean): List<GitHubRelease> {
        val releases = if (includePreRelease) {
            JsonUtil.fromJsonSafe(response, Array<GitHubRelease>::class.java)?.asList()
        } else {
            JsonUtil.fromJsonSafe(response, GitHubRelease::class.java)?.let { listOf(it) }
        }
        return releases ?: throw IOException("Invalid release response")
    }

    suspend fun checkForUpdate(
        includePreRelease: Boolean,
        activeProxyPort: Int? = null,
        quick: Boolean = false
    ): CheckUpdateResult {
        val response = withContext(Dispatchers.IO) {
            val url = releaseUrl(includePreRelease)
            val timeout = if (quick) 3500 else 5000
            if (activeProxyPort != null && activeProxyPort !in 1..65535) {
                throw IOException("Invalid running proxy port")
            }
            val request = Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "v2rayNG/${BuildConfig.VERSION_NAME}")
                .build()
            // Keep proxy credentials on the proxy's authentication exchange, never
            // on the tunneled GitHub request. Both metadata and APK IO cancel promptly.
            executeRequest(updateClient(activeProxyPort ?: 0, timeout.toLong()), request) { result ->
                if (!result.isSuccessful) throw IOException("Release check returned ${result.code}")
                result.body.string()
            }
        }
        return withContext(Dispatchers.Default) {
            val releases = parseReleases(response, includePreRelease)
            selectUpdate(
                releases,
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

    /** Called once after this package is replaced, never while handing a URI to the installer. */
    suspend fun cleanupInstalledApks(context: Context) = withContext(Dispatchers.IO) {
        // Bound archive parsing to part of goAsync's broadcast execution window.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        cleanupInstalledApks(context.cacheDir, context.packageName, BuildConfig.VERSION_NAME,
            readArchive = { file ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0))
                } else {
                    legacyArchiveInfo(context.packageManager, file)
                }
            },
            hasTime = { System.nanoTime() < deadline })
    }

    // API 24–32 have no PackageInfoFlags overload; remove when minSdk reaches 33.
    @Suppress("DEPRECATION")
    private fun legacyArchiveInfo(packageManager: PackageManager, file: File): PackageInfo? =
        packageManager.getPackageArchiveInfo(file.path, 0)

    internal fun cleanupInstalledApks(
        cacheDir: File,
        packageName: String,
        installedVersion: String,
        readArchive: (File) -> PackageInfo?,
        hasTime: () -> Boolean = { true }
    ) {
        val current = parseVersion(installedVersion) ?: return
        for (file in File(cacheDir, "updates").listFiles().orEmpty()) {
            if (!hasTime()) return
            if (!file.isFile || !(file.name == "update.apk" ||
                        (file.name.startsWith("update-") && file.name.endsWith(".apk")))) continue
            val archive = readArchive(file) ?: continue
            if (archive.packageName != packageName) continue
            val version = archive.versionName?.let(::parseVersion) ?: continue
            // ABI/distribution offsets make versionCode unsuitable for release ordering.
            // Keep future/unknown archives and all pending downloads intact.
            if (version <= current && !file.delete()) {
                LogUtil.w(AppConfig.TAG, "Update cache: could not remove installed archive ${file.name}")
            }
        }
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
        // A returned FileProvider URI must keep referring to these verified bytes
        // even if another activity downloads a different release before installation.
        val ready = File(updateDir, pending.name.replaceFirst("pending-", "update-"))
        try {
            val client = updateClient(activeProxyPort ?: 0)
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
            executeRequest(client, request) { response ->
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
            currentCoroutineContext().ensureActive()
            if (received != expectedSize) throw IOException("Release size mismatch")
            val actualDigest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (!expectedDigest.substringAfter(':').equals(actualDigest, ignoreCase = true)) {
                throw IOException("Release digest mismatch")
            }
            if (ready.exists() || !pending.renameTo(ready)) throw IOException("Could not finish update download")
            ready
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            pending.delete()
        }
    }

    private suspend fun <T> executeRequest(
        client: OkHttpClient,
        request: Request,
        consume: suspend (Response) -> T
    ): T = coroutineScope {
        val call = client.newCall(request)
        val cancellationWatcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            ensureActive()
            call.execute().use { consume(it) }
        } catch (error: IOException) {
            ensureActive()
            throw error
        } finally {
            cancellationWatcher.cancel()
        }
    }

    private fun updateClient(port: Int, timeoutMillis: Long? = null): OkHttpClient = updateClient(port, timeoutMillis) {
        SettingsManager.getSocksUsername() to SettingsManager.getSocksPassword()
    }

    internal fun updateClient(port: Int, username: String?, password: String?, timeoutMillis: Long? = null): OkHttpClient =
        updateClient(port, timeoutMillis) { username to password }

    private fun updateClient(port: Int, timeoutMillis: Long?, credentials: () -> Pair<String?, String?>): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.MINUTES)
            .followRedirects(true)
        if (timeoutMillis != null) {
            builder.connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        }
        if (port > 0) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(AppConfig.LOOPBACK, port)))
            builder.proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null ||
                    (response.request.url.isHttps && response.request.method != "CONNECT")
                ) null
                else {
                    val (username, password) = credentials()
                    if (username.isNullOrBlank() || password.isNullOrBlank()) null
                    else response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(username, password))
                        .build()
                }
            }
        }
        return builder.build()
    }
}
