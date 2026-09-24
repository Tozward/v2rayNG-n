package com.v2ray.ang.util

import android.content.Context
import android.content.pm.ApplicationInfo
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
            val installedApps = packageManager.getInstalledApplications(0)
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

    fun getLastUpdateTime(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

}
