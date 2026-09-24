package com.v2ray.ang.ui.checkupdate

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.ui.compose.VersionInfoBlock
import com.v2ray.ang.ui.compose.verticalScrollbar
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

class CheckUpdateActivity : BaseComponentActivity() {

    companion object {
        private const val EXTRA_VERSION = "update_version"
        private const val EXTRA_NOTES = "update_notes"
        private const val EXTRA_URL = "update_url"
        private const val EXTRA_SIZE = "update_size"
        private const val EXTRA_DIGEST = "update_digest"
        private const val EXTRA_PRE_RELEASE = "update_pre_release"

        fun installIntent(context: android.content.Context, update: CheckUpdateResult): Intent =
            Intent(context, CheckUpdateActivity::class.java).apply {
                putExtra(EXTRA_VERSION, update.latestVersion)
                putExtra(EXTRA_NOTES, update.releaseNotes)
                putExtra(EXTRA_URL, update.downloadUrl)
                putExtra(EXTRA_SIZE, update.assetSize)
                putExtra(EXTRA_DIGEST, update.assetDigest)
                putExtra(EXTRA_PRE_RELEASE, update.isPreRelease)
            }
    }

    private val viewModel: CheckUpdateViewModel by viewModels()
    private var installAttempted = false

    private val installPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val apk = viewModel.pendingApk.value ?: return@registerForActivityResult
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                packageManager.canRequestPackageInstalls()
            ) {
                launchInstaller(apk)
            } else {
                toastError(R.string.update_install_permission_required)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installAttempted = savedInstanceState?.getBoolean("install_attempted") ?: false
        if (savedInstanceState == null || viewModel.updateResult.value == null) {
            val update = intent.getStringExtra(EXTRA_VERSION)?.let { version ->
                CheckUpdateResult(
                    hasUpdate = true,
                    latestVersion = version,
                    releaseNotes = intent.getStringExtra(EXTRA_NOTES),
                    downloadUrl = intent.getStringExtra(EXTRA_URL),
                    assetSize = intent.getLongExtra(EXTRA_SIZE, 0),
                    assetDigest = intent.getStringExtra(EXTRA_DIGEST),
                    isPreRelease = intent.getBooleanExtra(EXTRA_PRE_RELEASE, false)
                )
            }
            if (update != null) viewModel.offerUpdate(update) else viewModel.checkForUpdates()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.pendingApk.collect { apk ->
                    if (apk == null) {
                        installAttempted = false
                    } else if (!installAttempted) {
                        installAttempted = true
                        requestInstall(apk)
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("install_attempted", installAttempted)
        super.onSaveInstanceState(outState)
    }

    private fun requestInstall(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            try {
                installPermissionLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (error: RuntimeException) {
                LogUtil.e(AppConfig.TAG, "Cannot request update install permission", error)
                toastError(R.string.update_install_permission_required)
            }
            return
        }
        launchInstaller(apk)
    }

    private fun launchInstaller(apk: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.cache", apk)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            viewModel.consumePendingApk()
        } catch (error: RuntimeException) {
            LogUtil.e(AppConfig.TAG, "Could not open APK installer", error)
            toastError(R.string.update_install_unavailable)
        }
    }

    @Composable
    override fun ScreenContent() {
        CheckUpdateScreen(
            viewModel = viewModel,
            onBackClick = { finish() },
            onInstallReady = { apk ->
                installAttempted = true
                requestInstall(apk)
            }
        )
    }
}

@Composable
fun CheckUpdateScreen(
    viewModel: CheckUpdateViewModel,
    onBackClick: () -> Unit,
    onInstallReady: (File) -> Unit
) {
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val checkPreRelease by viewModel.checkPreRelease.collectAsStateWithLifecycle()
    val showUpdateDialog by viewModel.showUpdateDialog.collectAsStateWithLifecycle()
    val updateResult by viewModel.updateResult.collectAsStateWithLifecycle()
    val progress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val pendingApk by viewModel.pendingApk.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    val libVersion = remember { CoreNativeManager.getLibVersion() }
    val versionText = "v${BuildConfig.VERSION_NAME} ($libVersion)"

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.update_check_for_update),
                onBackClick = onBackClick,
                isLoading = isLoading
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSwitchItem(
                icon = painterResource(R.drawable.ic_source_code_24dp),
                title = stringResource(R.string.update_check_pre_release),
                checked = checkPreRelease,
                onCheckedChange = { viewModel.toggleCheckPreRelease(it) }
            )
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_check_update_24dp),
                title = stringResource(R.string.update_check_for_update),
                onClick = { viewModel.checkForUpdates() }
            )
            statusMessage?.let { message ->
                Text(
                    text = stringResource(message),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            if (progress != null) {
                val percentage = (progress ?: 0).coerceIn(0, 100)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = stringResource(R.string.update_downloading, percentage),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            LinearProgressIndicator(
                                progress = { percentage / 100f },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                gapSize = 0.dp,
                                drawStopIndicator = {}
                            )
                            TextButton(
                                onClick = viewModel::cancelDownload,
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    }
                }
            }
            pendingApk?.let { apk ->
                SettingsMenuItem(
                    icon = painterResource(R.drawable.ic_check_update_24dp),
                    title = stringResource(R.string.update_install_downloaded),
                    onClick = { onInstallReady(apk) }
                )
            }
            VersionInfoBlock(versionText = versionText)
            NavigationBarsSpacer()
        }
    }

    if (showUpdateDialog && updateResult != null) {
        UpdateOfferDialog(
            result = updateResult!!,
            onConfirm = viewModel::startDownload,
            onDismiss = viewModel::dismissUpdateDialog
        )
    }
}

@Composable
fun UpdateOfferDialog(
    result: CheckUpdateResult,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_new_version_found, result.latestVersion ?: "")) },
        text = {
            val scrollState = rememberScrollState()
            Text(
                text = result.releaseNotes.orEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .verticalScrollbar(scrollState)
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.update_now))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}
