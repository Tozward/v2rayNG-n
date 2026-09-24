package com.v2ray.ang.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.v2ray.ang.dto.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

object AppManagerUtil {
    private val labelDispatcher = Dispatchers.IO.limitedParallelism(4)

    /**
     * Load the list of network applications.
     *
     * @param context The context to use.
     * @return A list of AppInfo objects representing the network applications.
     */
    suspend fun loadNetworkAppList(context: Context): ArrayList<AppInfo> =
        withContext(Dispatchers.IO) {
            val packageManager = context.packageManager
            val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                getInstalledApplicationsLegacy(packageManager)
            }
            val apps = coroutineScope {
                installedApps.map { applicationInfo ->
                    async(labelDispatcher) {
                        val appName = applicationInfo.loadLabel(packageManager).toString()
                        AppInfo(
                            appName,
                            applicationInfo.packageName,
                            applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM > 0,
                            0
                        )
                    }
                }.awaitAll()
            }
            ArrayList(apps)
        }

    // The int overload is the only installed-app query on Android 12L and earlier.
    // Remove this fallback when the minimum SDK reaches 33.
    @Suppress("DEPRECATION")
    private fun getInstalledApplicationsLegacy(packageManager: PackageManager): List<ApplicationInfo> =
        packageManager.getInstalledApplications(0)

    fun getLastUpdateTime(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

}
