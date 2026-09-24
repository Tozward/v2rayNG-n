package com.v2ray.ang.ui.perappproxy

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.AppSelection
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.AppManagerUtil
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * ViewModel for PerAppProxy screen.
 * Holds all UI state and business logic.
 */
class PerAppProxyViewModel internal constructor(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val loadAppList: suspend (Context) -> List<AppInfo>
) : BaseViewModel(application) {

    constructor(application: Application, savedStateHandle: SavedStateHandle) :
            this(application, savedStateHandle, AppManagerUtil::loadNetworkAppList)

    // Blacklist (apps to be proxied or bypassed)
    private val _blacklist = MutableStateFlow(loadBlacklist())
    val blacklist: StateFlow<Set<String>> = _blacklist.asStateFlow()

    // UI states
    private val _allApps = MutableStateFlow<List<AppInfo>?>(null)
    val searchQuery: StateFlow<String> = savedStateHandle.getStateFlow(SEARCH_QUERY, "")
    val displayedApps: StateFlow<List<AppInfo>> = combine(_allApps, searchQuery) { apps, query ->
        applyFilter(apps.orEmpty(), query)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _perAppProxyEnabled = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false)
    )
    val perAppProxyEnabled: StateFlow<Boolean> = _perAppProxyEnabled.asStateFlow()

    private val _bypassApps = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS, false)
    )
    val bypassApps: StateFlow<Boolean> = _bypassApps.asStateFlow()

    private var isAppListLoading = false

    // Blacklist operations
    fun toggle(packageName: String) {
        val currentSelection = _blacklist.value
        val newSelection = if (packageName in currentSelection) {
            currentSelection - packageName
        } else {
            currentSelection + packageName
        }
        replaceBlacklist(newSelection)
        SettingsChangeManager.makeRestartService()
    }

    private fun loadBlacklist(): Set<String> {
        return MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)?.toSet() ?: emptySet()
    }

    private fun replaceBlacklist(newBlacklist: Set<String>) {
        if (newBlacklist == _blacklist.value) return

        _blacklist.value = newBlacklist
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, newBlacklist.toMutableSet())
    }

    // Per‑app proxy switch
    fun setPerAppProxyEnabled(enabled: Boolean) {
        if (_perAppProxyEnabled.value != enabled) {
            _perAppProxyEnabled.value = enabled
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, enabled)
        }
    }

    fun setBypassAppsEnabled(enabled: Boolean) {
        if (_bypassApps.value != enabled) {
            _bypassApps.value = enabled
            MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, enabled)
        }
    }

    // Load and filter apps
    fun loadApps(context: Context) {
        if (_allApps.value != null || isAppListLoading) return

        val applicationContext = context.applicationContext
        isAppListLoading = true
        launchLoading {
            try {
                val list = withContext(Dispatchers.IO) {
                    loadAppList(applicationContext)
                }
                val selectedPackages = _blacklist.value
                _allApps.value = withContext(Dispatchers.Default) {
                    sortApps(list, selectedPackages)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.ANG_PACKAGE, "Error loading apps", e)
            } finally {
                isAppListLoading = false
            }
        }
    }

    fun filterApps(query: String) {
        savedStateHandle[SEARCH_QUERY] = query
    }

    private fun applyFilter(apps: List<AppInfo>, query: String): List<AppInfo> {
        if (query.isEmpty()) return apps

        return apps.filter {
            it.appName.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
        }
    }

    private fun sortApps(apps: List<AppInfo>, selectedPackages: Set<String>): List<AppInfo> {
        val collator = Collator.getInstance()
        return apps.sortedWith { p1, p2 ->
            val s1 = p1.packageName in selectedPackages
            val s2 = p2.packageName in selectedPackages
            when {
                s1 && !s2 -> -1
                !s1 && s2 -> 1
                p1.isSystemApp && !p2.isSystemApp -> 1
                !p1.isSystemApp && p2.isSystemApp -> -1
                else -> collator.compare(p1.appName, p2.appName)
            }
        }
    }

    // Bulk actions
    fun selectAll() {
        val visibleApps = displayedApps.value
        val currentSelection = _blacklist.value
        val allSelected = visibleApps.all { it.packageName in currentSelection }
        val newSelection = currentSelection.toMutableSet().apply {
            visibleApps.forEach { app ->
                if (allSelected) remove(app.packageName) else add(app.packageName)
            }
        }
        replaceBlacklist(newSelection)
        enablePerAppProxyAndRestart()
    }

    fun invertSelection() {
        val packageNames = displayedApps.value.map { it.packageName }
        replaceBlacklist(AppSelection.invert(_blacklist.value, packageNames))
        enablePerAppProxyAndRestart()
    }

    fun selectProxyAppAuto(context: Context) {
        val applicationContext = context.applicationContext
        launchLoading {
            val url = AppConfig.ANDROID_PACKAGE_NAME_LIST_URL
            var content = withContext(Dispatchers.IO) {
                HttpUtil.getUrlContent(
                    UrlContentRequest(
                        url = url,
                        timeout = 5000
                    )
                )
            }
            if (content.isNullOrEmpty()) {
                val proxyUsername = SettingsManager.getSocksUsername()
                val proxyPassword = SettingsManager.getSocksPassword()
                val httpPort = SettingsManager.getHttpPort()
                content = withContext(Dispatchers.IO) {
                    HttpUtil.getUrlContent(
                        UrlContentRequest(
                            url = url,
                            timeout = 5000,
                            httpPort = httpPort,
                            proxyUsername = proxyUsername,
                            proxyPassword = proxyPassword
                        )
                    )
                } ?: ""
            }
            val success = applyProxyAppList(
                content = content,
                context = applicationContext,
                forceGoogleApps = true
            )
            if (success) {
                enablePerAppProxyAndRestart()
            }
        }
    }

    fun importProxyApp(content: String?, context: Context) {
        if (content.isNullOrEmpty()) return

        val applicationContext = context.applicationContext
        launchLoading {
            val success = applyProxyAppList(
                content = content,
                context = applicationContext,
                forceGoogleApps = false
            )
            if (success) {
                enablePerAppProxyAndRestart()
            }
        }
    }

    fun exportProxyApp(): String {
        return buildString {
            append(_bypassApps.value)
            _blacklist.value.forEach { packageName ->
                append(System.lineSeparator())
                append(packageName)
            }
        }
    }

    private suspend fun applyProxyAppList(content: String, context: Context, forceGoogleApps: Boolean): Boolean {
        val installedApps = _allApps.value ?: return false

        try {
            val proxyAppList = if (content.isEmpty()) {
                withContext(Dispatchers.IO) {
                    Utils.readTextFromAssets(context, "proxy_package_name")
                }
            } else content
            if (proxyAppList.isNullOrEmpty()) return false

            val bypassApps = _bypassApps.value
            val newBlacklist = withContext(Dispatchers.Default) {
                AppSelection.fromProxyList(
                    packageNames = installedApps.map { it.packageName },
                    proxyAppList = proxyAppList,
                    bypassApps = bypassApps,
                    forceGoogleApps = forceGoogleApps
                )
            }
            replaceBlacklist(newBlacklist)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error selecting proxy app", e)
            return false
        }
        return true
    }

    private fun enablePerAppProxyAndRestart() {
        setPerAppProxyEnabled(true)
        SettingsChangeManager.makeRestartService()
    }

    private companion object {
        const val SEARCH_QUERY = "per_app_search_query"
    }
}
