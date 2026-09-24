package com.v2ray.ang.ui.perappproxy

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.tencent.mmkv.MMKV
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.handler.SettingsChangeManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class PerAppProxyViewModelTest {
    private val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mainDispatcher.close()
    }

    @Test
    fun queryEnteredBeforeLoadingFinishesFiltersTheFirstPublishedList() = runBlocking(mainDispatcher) {
        withSettingsStorage {
            val pendingApps = CompletableDeferred<List<AppInfo>>()
            val savedState = SavedStateHandle()
            val model = PerAppProxyViewModel(mock<Application>(), savedState) { pendingApps.await() }
            val context = appContext()
            val emissions = mutableListOf<List<AppInfo>>()
            val observer = launch { model.displayedApps.collect { emissions.add(it) } }

            assertTrue(model.displayedApps.value.isEmpty())
            model.loadApps(context)
            withTimeout(5_000) { model.isLoading.first { it } }
            model.filterApps("stale")
            model.filterApps("target")
            pendingApps.complete(sampleApps())

            val matching = withTimeout(5_000) {
                model.displayedApps.first { it.size == 1 && it.single().packageName == "com.example.target" }
            }
            assertEquals("Target App", matching.single().appName)
            assertEquals("target", model.searchQuery.value)
            assertEquals("target", savedState.get<String>("per_app_search_query"))
            assertFalse(emissions.any { it.size == 2 })

            model.selectAll()
            assertEquals(setOf("com.example.target"), model.blacklist.value)
            model.invertSelection()
            assertTrue(model.blacklist.value.isEmpty())
            SettingsChangeManager.consumeRestartService()

            model.filterApps("com.example.other")
            assertEquals("com.example.other", withTimeout(5_000) {
                model.displayedApps.first { it.size == 1 && it.single().packageName == "com.example.other" }
            }.single().packageName)

            model.filterApps("missing")
            withTimeout(5_000) { model.displayedApps.first { it.isEmpty() } }
            model.filterApps("")
            assertEquals(2, withTimeout(5_000) { model.displayedApps.first { it.size == 2 } }.size)
            observer.cancel()
        }
    }

    @Test
    fun savedQueryFiltersAfterViewModelRecreation() = runBlocking(mainDispatcher) {
        withSettingsStorage {
            val savedState = SavedStateHandle(mapOf("per_app_search_query" to "target"))
            val model = PerAppProxyViewModel(mock<Application>(), savedState) { sampleApps() }

            assertEquals("target", model.searchQuery.value)
            model.loadApps(appContext())
            assertEquals(
                "com.example.target",
                withTimeout(5_000) {
                    model.displayedApps.first { it.size == 1 }
                }.single().packageName
            )
        }
    }

    @Test
    fun bulkActionsUseTheCurrentQueryBeforeAsyncFilteringPublishes() = runBlocking(mainDispatcher) {
        withSettingsStorage {
            val model = PerAppProxyViewModel(mock<Application>(), SavedStateHandle()) { sampleApps() }
            model.loadApps(appContext())
            withTimeout(5_000) { model.displayedApps.first { it.size == 2 } }

            model.filterApps("target")
            model.selectAll()
            assertEquals(setOf("com.example.target"), model.blacklist.value)

            model.filterApps("other")
            model.invertSelection()
            assertEquals(setOf("com.example.target", "com.example.other"), model.blacklist.value)
            SettingsChangeManager.consumeRestartService()
        }
    }

    @Test
    fun failedLoadKeepsTheListEmptyAndAllowsRetry() = runBlocking(mainDispatcher) {
        withSettingsStorage {
            mockStatic(Log::class.java).use {
                var attempts = 0
                val loadStarted = CompletableDeferred<Unit>()
                val failLoad = CompletableDeferred<Unit>()
                val model = PerAppProxyViewModel(mock<Application>(), SavedStateHandle()) {
                    if (attempts++ == 0) {
                        loadStarted.complete(Unit)
                        failLoad.await()
                        throw IllegalStateException("package scan failed")
                    }
                    sampleApps()
                }
                val context = appContext()

                model.loadApps(context)
                withTimeout(5_000) { loadStarted.await() }
                assertTrue(model.isLoading.value)
                failLoad.complete(Unit)
                withTimeout(5_000) { model.isLoading.first { !it } }
                assertTrue(model.displayedApps.value.isEmpty())

                model.loadApps(context)
                assertEquals(2, withTimeout(5_000) { model.displayedApps.first { it.size == 2 } }.size)
            }
        }
    }

    private fun appContext(): Context = mock<Context>().also {
        whenever(it.applicationContext).thenReturn(it)
    }

    private fun sampleApps() = listOf(
        AppInfo("Target App", "com.example.target", false, 0),
        AppInfo("Other App", "com.example.other", false, 0)
    )

    private inline fun withSettingsStorage(block: () -> Unit) {
        val settings = mock<MMKV>()
        mockStatic(MMKV::class.java).use { staticMock ->
            staticMock.`when`<MMKV> { MMKV.mmkvWithID("SETTING", MMKV.MULTI_PROCESS_MODE) }
                .thenReturn(settings)
            block()
        }
    }
}
