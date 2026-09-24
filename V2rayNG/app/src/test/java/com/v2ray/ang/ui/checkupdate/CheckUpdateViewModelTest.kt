package com.v2ray.ang.ui.checkupdate

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModelStore
import com.tencent.mmkv.MMKV
import com.v2ray.ang.R
import com.v2ray.ang.dto.CheckUpdateResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class CheckUpdateViewModelTest {
    private val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        mainDispatcher.close()
    }

    @Test fun checkThenDownloadPublishesProgressAndInstallFile() = runBlocking(mainDispatcher) {
        withSettingsStorage {
            val cache = File(System.getProperty("java.io.tmpdir"), "test-update-cache")
            val apk = File(cache, "update.apk")
            val app = mock<Application>()
            whenever(app.cacheDir).thenReturn(cache)
            val result = CheckUpdateResult(
                hasUpdate = true,
                latestVersion = "2.3.9-testpre.5",
                downloadUrl = "https://api.github.com/example",
                assetSize = 1,
                assetDigest = "sha256:" + "a".repeat(64)
            )
            val model = CheckUpdateViewModel(app, { result }, { _, _, onProgress ->
                onProgress(65)
                apk
            })
            assertNull(model.updateResult.value)
            assertFalse(model.showUpdateDialog.value)
            assertNull(model.pendingApk.value)

            model.checkForUpdates()
            withTimeout(5_000) { model.showUpdateDialog.first { it } }
            assertEquals(result, model.updateResult.value)
            model.startDownload()
            assertEquals(apk, withTimeout(5_000) { model.pendingApk.first { it != null } })
            assertNull(model.downloadProgress.value)
            assertFalse(model.showUpdateDialog.value)
            model.consumePendingApk()
            assertNull(model.pendingApk.value)
        }
    }

    @Test fun noUpdateAndFailureHaveDistinctVisibleStates() = runBlocking(mainDispatcher) {
        withSettingsStorage {
            val app = mock<Application>()
            val latest = CheckUpdateViewModel(
                app, { CheckUpdateResult(hasUpdate = false) }, { _, _, _ -> error("unused") }
            )
            latest.checkForUpdates()
            assertEquals(R.string.update_already_latest_version, withTimeout(5_000) {
                latest.statusMessage.first { it != null }
            })
            assertNull(latest.updateResult.value)

            mockStatic(Log::class.java).use {
                val failing = CheckUpdateViewModel(
                    app, { throw IOException("offline") }, { _, _, _ -> error("unused") }
                )
                failing.checkForUpdates()
                assertEquals(R.string.update_check_failed, withTimeout(5_000) {
                    failing.statusMessage.first { it != null }
                })
                assertFalse(failing.showUpdateDialog.value)
            }
        }
    }

    @Test fun cancellingDownloadClearsProgressAndNeverOffersAnInstallerFile() = runBlocking(mainDispatcher) {
        withSettingsStorage {
            val app = mock<Application>()
            whenever(app.cacheDir).thenReturn(File(System.getProperty("java.io.tmpdir"), "test-update-cache"))
            val release = CheckUpdateResult(hasUpdate = true, latestVersion = "2.3.9-testpre.6")
            val gate = CompletableDeferred<Unit>()
            val model = CheckUpdateViewModel(app, { release }, { _, _, onProgress ->
                onProgress(12)
                gate.await()
                File("unused.apk")
            })

            model.offerUpdate(release)
            assertEquals(12, withTimeout(5_000) { model.downloadProgress.first { it == 12 } })
            model.cancelDownload()
            gate.complete(Unit)
            yield()

            assertNull(model.downloadProgress.value)
            assertNull(model.pendingApk.value)
            assertFalse(model.showUpdateDialog.value)
        }
    }

    @Test fun channelTogglePersistsTheLatestValueAfterLeavingTheScreen() {
        withSettingsStorage {
            val firstWriteStarted = CountDownLatch(1)
            val finishFirstWrite = CountDownLatch(1)
            try {
                val writes = CopyOnWriteArrayList<Boolean>()
                val model = CheckUpdateViewModel(
                    mock<Application>(),
                    { CheckUpdateResult(hasUpdate = false) },
                    { _, _, _ -> error("unused") },
                    { enabled ->
                        if (enabled) {
                            firstWriteStarted.countDown()
                            finishFirstWrite.await(5, TimeUnit.SECONDS)
                        }
                        writes.add(enabled)
                    }
                )
                val store = ViewModelStore().apply { put("update", model) }

                model.toggleCheckPreRelease(true)
                assertTrue(firstWriteStarted.await(5, TimeUnit.SECONDS))
                model.toggleCheckPreRelease(false)
                store.clear()
                finishFirstWrite.countDown()

                runBlocking {
                    withTimeout(5_000) {
                        while (writes.size < 2) kotlinx.coroutines.delay(10)
                    }
                }
                assertEquals(listOf(true, false), writes)
            } finally {
                finishFirstWrite.countDown()
            }
        }
    }

    private inline fun withSettingsStorage(block: () -> Unit) {
        val settings = mock<MMKV>()
        mockStatic(MMKV::class.java).use { staticMock ->
            staticMock.`when`<MMKV> { MMKV.mmkvWithID("SETTING", MMKV.MULTI_PROCESS_MODE) }
                .thenReturn(settings)
            block()
        }
    }
}
