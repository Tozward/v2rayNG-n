package com.v2ray.ang.ui.settings

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.tencent.mmkv.MMKV
import com.v2ray.ang.enums.TunBackend
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SettingsViewModelTest {
    private val models = mutableListOf<SettingsViewModel>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun clearAndAwaitModels(dispatcher: ExecutorCoroutineDispatcher) {
        try {
            val jobs = models.map { checkNotNull(it.viewModelScope.coroutineContext[Job]) }
            ViewModelStore().apply {
                models.forEachIndexed { index, model -> put("settings-$index", model) }
                clear()
            }
            // Persistence may have updated its sink while still resuming on Main.
            runBlocking { withTimeout(5_000) { jobs.joinAll() } }
        } finally {
            try { Dispatchers.resetMain() } finally { dispatcher.close() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retiredInitialBackendAndFailedWriteKeepTheNativeSelection() {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(dispatcher)
        val settings = mock<MMKV>()
        try {
            mockStatic(MMKV::class.java).use { staticMock ->
                staticMock.`when`<MMKV> { MMKV.mmkvWithID("SETTING", MMKV.MULTI_PROCESS_MODE) }.thenReturn(settings)
                LogUtil.refreshLogLevel()
                val model = SettingsViewModel(mock<Application>(), { true }, {}, TunBackend.SING) {
                    throw IllegalStateException("write failed")
                }.also { models.add(it) }
                assertEquals(TunBackend.SING_NATIVE, model.tunBackend.value)
                model.setTunBackend(TunBackend.HEV)
                runBlocking { withTimeout(5_000) { model.awaitTunBackendSave() } }
                assertTrue(model.tunBackendSaveError.value)
                assertEquals(TunBackend.SING_NATIVE, model.tunBackend.value)
            }
        } finally {
            clearAndAwaitModels(dispatcher)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun failedBackendWriteRestoresThePersistedSelection() {
        val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        val settings = mock<MMKV>()
        try {
            mockStatic(MMKV::class.java).use { staticMock ->
                staticMock.`when`<MMKV> { MMKV.mmkvWithID("SETTING", MMKV.MULTI_PROCESS_MODE) }.thenReturn(settings)
                LogUtil.refreshLogLevel()
                for (backend in listOf(TunBackend.SING, TunBackend.SING_NATIVE)) {
                    val model = SettingsViewModel(mock<Application>(), { true }, {}, TunBackend.HEV) {
                        throw IllegalStateException("write failed")
                    }.also { models.add(it) }
                    assertFalse(model.tunBackendSaveError.value)
                    model.setTunBackend(backend)
                    runBlocking {
                        withTimeout(5_000) { while (!model.tunBackendSaveError.value) delay(10) }
                    }
                    assertEquals(TunBackend.HEV, model.tunBackend.value)
                }
            }
        } finally {
            clearAndAwaitModels(mainDispatcher)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun nativeBackendSelectionSurvivesLeavingSettingsAndReopening() {
        val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        try {
            val stored = AtomicReference(TunBackend.HEV)
            val model = SettingsViewModel(mock<Application>(), { true }, {}, stored.get()) { stored.set(it) }
                .also { models.add(it) }
            model.setTunBackend(TunBackend.SING_NATIVE)
            ViewModelStore().apply { put("settings", model); clear() }
            runBlocking { withTimeout(5_000) { model.awaitTunBackendSave() } }
            assertEquals(TunBackend.SING_NATIVE, stored.get())
            val reopened = SettingsViewModel(mock<Application>(), { true }, {}, stored.get()) {}
            assertEquals(TunBackend.SING_NATIVE, reopened.tunBackend.value)
        } finally {
            clearAndAwaitModels(mainDispatcher)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun backendSelectionSurvivesLeavingSettingsAndReopening() {
        val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        val writeStarted = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        try {
            val stored = AtomicReference(TunBackend.HEV)
            val model = SettingsViewModel(mock<Application>(), { true }, {}, stored.get()) { backend ->
                writeStarted.countDown()
                finishWrite.await(5, TimeUnit.SECONDS)
                stored.set(backend)
            }.also { models.add(it) }
            assertEquals(TunBackend.HEV, model.tunBackend.value)
            model.setTunBackend(TunBackend.SING)
            assertEquals(TunBackend.SING_NATIVE, model.tunBackend.value)
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
            model.setTunBackend(TunBackend.XRAY)
            ViewModelStore().apply { put("settings", model); clear() }
            finishWrite.countDown()
            runBlocking {
                withTimeout(5_000) { model.awaitTunBackendSave() }
            }
            assertEquals(TunBackend.XRAY, stored.get())
            val reopened = SettingsViewModel(mock<Application>(), { true }, {}, stored.get()) {}
            assertEquals(TunBackend.XRAY, reopened.tunBackend.value)
        } finally {
            finishWrite.countDown()
            clearAndAwaitModels(mainDispatcher)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun autoCheckSwitchPersistsAndReopensDisabled() {
        val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        try {
            val stored = AtomicBoolean(true)
            val application = mock<Application>()
            val viewModel = SettingsViewModel(application, { stored.get() }, { stored.set(it) })
                .also { models.add(it) }
            assertTrue(viewModel.autoCheckUpdate.value)
            viewModel.setAutoCheckUpdate(false)
            assertFalse(viewModel.autoCheckUpdate.value)
            runBlocking {
                withTimeout(5_000) {
                    while (stored.get()) delay(10)
                }
            }
            assertFalse(SettingsViewModel(application, { stored.get() }, { stored.set(it) })
                .autoCheckUpdate.value)
        } finally {
            clearAndAwaitModels(mainDispatcher)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun rapidSwitchesPersistTheLastValueEvenAfterLeavingSettings() {
        val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        val firstWriteStarted = CountDownLatch(1)
        val finishFirstWrite = CountDownLatch(1)
        try {
            val writes = CopyOnWriteArrayList<Boolean>()
            val model = SettingsViewModel(mock<Application>(), { true }, { enabled ->
                if (!enabled) {
                    firstWriteStarted.countDown()
                    finishFirstWrite.await(5, TimeUnit.SECONDS)
                }
                writes.add(enabled)
            }).also { models.add(it) }
            val store = ViewModelStore().apply { put("settings", model) }

            model.setAutoCheckUpdate(false)
            assertTrue(firstWriteStarted.await(5, TimeUnit.SECONDS))
            model.setAutoCheckUpdate(true)
            store.clear()
            finishFirstWrite.countDown()

            runBlocking {
                withTimeout(5_000) {
                    while (writes.size < 2) delay(10)
                }
            }
            assertEquals(listOf(false, true), writes)
        } finally {
            finishFirstWrite.countDown()
            clearAndAwaitModels(mainDispatcher)
        }
    }

    @Test
    fun vpnSettingsVisibilityFollowsActivityAvailability() = runBlocking(Dispatchers.IO) {
        val packageManager = mock<PackageManager>()
        val application = mock<Application>()
        whenever(application.packageManager).thenReturn(packageManager)
        var activity: ComponentName? = null

        val settings = mock<MMKV>()
        mockStatic(MMKV::class.java).use { staticMock ->
            staticMock.`when`<MMKV> { MMKV.mmkvWithID("SETTING", MMKV.MULTI_PROCESS_MODE) }
                .thenReturn(settings)
            mockConstruction(Intent::class.java) { intent, context ->
                assertEquals(listOf(Settings.ACTION_VPN_SETTINGS), context.arguments())
                whenever(intent.resolveActivity(packageManager)).thenAnswer { activity }
            }.use {
                val viewModel = SettingsViewModel(application)
                assertFalse(viewModel.systemVpnSettingsAvailable.value)
                viewModel.refreshSystemVpnSettingsAvailability()
                assertFalse(viewModel.systemVpnSettingsAvailable.value)

                activity = mock<ComponentName>()
                viewModel.refreshSystemVpnSettingsAvailability()
                assertTrue(viewModel.systemVpnSettingsAvailable.value)

                activity = null
                viewModel.refreshSystemVpnSettingsAvailability()
                assertFalse(viewModel.systemVpnSettingsAvailable.value)
            }
        }
    }
}
