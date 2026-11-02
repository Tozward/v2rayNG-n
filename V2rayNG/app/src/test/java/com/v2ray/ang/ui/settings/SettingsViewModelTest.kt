package com.v2ray.ang.ui.settings

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.lifecycle.ViewModelStore
import com.tencent.mmkv.MMKV
import com.v2ray.ang.enums.TunBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
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
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun failedBackendWriteRestoresThePersistedSelection() {
        val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        val settings = mock<MMKV>()
        try {
            mockStatic(MMKV::class.java).use { staticMock ->
                staticMock.`when`<MMKV> { MMKV.mmkvWithID("SETTING", MMKV.MULTI_PROCESS_MODE) }.thenReturn(settings)
                val model = SettingsViewModel(mock<Application>(), { true }, {}, TunBackend.HEV) {
                    throw IllegalStateException("write failed")
                }
                assertFalse(model.tunBackendSaveError.value)
                model.setTunBackend(TunBackend.SING)
                runBlocking {
                    withTimeout(5_000) { while (!model.tunBackendSaveError.value) delay(10) }
                }
                assertEquals(TunBackend.HEV, model.tunBackend.value)
            }
        } finally {
            Dispatchers.resetMain()
            mainDispatcher.close()
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
            }
            assertEquals(TunBackend.HEV, model.tunBackend.value)
            model.setTunBackend(TunBackend.SING)
            assertEquals(TunBackend.SING, model.tunBackend.value)
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
            model.setTunBackend(TunBackend.XRAY)
            ViewModelStore().apply { put("settings", model); clear() }
            finishWrite.countDown()
            runBlocking {
                withTimeout(5_000) { while (stored.get() != TunBackend.XRAY) delay(10) }
            }
            val reopened = SettingsViewModel(mock<Application>(), { true }, {}, stored.get()) {}
            assertEquals(TunBackend.XRAY, reopened.tunBackend.value)
        } finally {
            finishWrite.countDown()
            Dispatchers.resetMain()
            mainDispatcher.close()
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
            Dispatchers.resetMain()
            mainDispatcher.close()
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
            })
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
            Dispatchers.resetMain()
            mainDispatcher.close()
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
