package com.v2ray.ang.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AppManagerUtilTest {
    @Test
    fun loadsApplicationInfoWithoutPermissionDetailsAndKeepsPackageOrder() = runBlocking {
        val packageManager = mock<PackageManager>()
        val context = mock<Context>()
        whenever(context.packageManager).thenReturn(packageManager)
        val first = mock<ApplicationInfo>().apply {
            packageName = "com.example.first"
            flags = ApplicationInfo.FLAG_SYSTEM
        }
        val second = mock<ApplicationInfo>().apply {
            packageName = "com.example.second"
        }
        whenever(first.loadLabel(packageManager)).thenReturn("First")
        whenever(second.loadLabel(packageManager)).thenReturn("Second")
        whenever(packageManager.getInstalledApplications(0)).thenReturn(mutableListOf(first, second))

        val apps = AppManagerUtil.loadNetworkAppList(context)

        verify(packageManager).getInstalledApplications(0)
        assertEquals(listOf("com.example.first", "com.example.second"), apps.map { it.packageName })
        assertEquals(listOf("First", "Second"), apps.map { it.appName })
        assertEquals(listOf(true, false), apps.map { it.isSystemApp })
    }
}
