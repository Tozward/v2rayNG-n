package com.v2ray.ang.ui.settings

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SettingsViewModelTest {
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
