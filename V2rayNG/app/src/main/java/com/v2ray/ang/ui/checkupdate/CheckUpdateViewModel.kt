package com.v2ray.ang.ui.checkupdate

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class CheckUpdateViewModel internal constructor(
    application: Application,
    private val checker: suspend (Boolean) -> CheckUpdateResult,
    private val downloader: suspend (File, CheckUpdateResult, (Int) -> Unit) -> File
) : BaseViewModel(application) {

    constructor(application: Application) : this(
        application,
        { includePreRelease -> UpdateCheckerManager.checkForUpdate(includePreRelease) },
        UpdateCheckerManager::downloadApk
    )

    private val _checkPreRelease = MutableStateFlow(
        MmkvManager.decodeSettingsBool(
            AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE,
            BuildConfig.VERSION_NAME.contains('-')
        )
    )
    val checkPreRelease: StateFlow<Boolean> = _checkPreRelease.asStateFlow()

    private val _updateResult = MutableStateFlow<CheckUpdateResult?>(null)
    val updateResult: StateFlow<CheckUpdateResult?> = _updateResult.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Int?>(null)
    val downloadProgress: StateFlow<Int?> = _downloadProgress.asStateFlow()

    private val _pendingApk = MutableStateFlow<File?>(null)
    val pendingApk: StateFlow<File?> = _pendingApk.asStateFlow()

    private val _statusMessage = MutableStateFlow<Int?>(null)
    val statusMessage: StateFlow<Int?> = _statusMessage.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    fun toggleCheckPreRelease(enabled: Boolean) {
        _checkPreRelease.value = enabled
        viewModelScope.launch(Dispatchers.IO) {
            MmkvManager.encodeSettings(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, enabled)
        }
    }

    fun checkForUpdates() {
        if (checkJob?.isActive == true || downloadJob?.isActive == true) return
        checkJob = viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = null
            try {
                val result = checker(_checkPreRelease.value)
                if (result.hasUpdate) {
                    _updateResult.value = result
                    _showUpdateDialog.value = true
                } else {
                    _updateResult.value = null
                    _statusMessage.value = R.string.update_already_latest_version
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Manual update check failed", error)
                _statusMessage.value = R.string.update_check_failed
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun offerUpdate(result: CheckUpdateResult) {
        if (!result.hasUpdate) return
        _updateResult.value = result
        _showUpdateDialog.value = false
        _statusMessage.value = null
        startDownload()
    }

    fun startDownload() {
        val result = _updateResult.value ?: return
        if (downloadJob?.isActive == true) return
        _showUpdateDialog.value = false
        downloadJob = viewModelScope.launch {
            _downloadProgress.value = 0
            _statusMessage.value = null
            try {
                _pendingApk.value = downloader(getApplication<Application>().cacheDir, result) { progress ->
                    _downloadProgress.value = progress
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Update APK download failed", error)
                _showUpdateDialog.value = true
                _statusMessage.value = R.string.update_download_failed
            } finally {
                _downloadProgress.value = null
            }
        }
    }

    fun consumePendingApk() {
        _pendingApk.value = null
    }

    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
    }
}
