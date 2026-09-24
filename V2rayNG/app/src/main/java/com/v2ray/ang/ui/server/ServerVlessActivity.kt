package com.v2ray.ang.ui.server

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.dto.VlessTestpre
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField

class ServerVlessActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.VLESS
    private val viewModel: ServerVlessViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initialize(initialConfig.testpre)
    }

    @Composable
    override fun ScreenContent() {
        val options = rememberFieldOptions()
        val scope = rememberCoroutineScope()
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(
                initialConfig = initialConfig
            )
        }.apply {
            configType = EConfigType.VLESS
        }
        val flowOptions = stringArrayResource(R.array.flows).toList()
        val testpreInput by viewModel.testpreInput.collectAsStateWithLifecycle()
        val testpreError by viewModel.testpreError.collectAsStateWithLifecycle()

        ServerEditorScaffold(
            title = serverConfigType.toString(),
            onSaveClick = {
                if (viewModel.validateTestpre(uiState.flow)) {
                    saveServer(uiState) { it.copy(testpre = viewModel.testpreFor(uiState.flow)) }
                }
            }
        ) {
            CommonBasicFields(uiState)
            VlessProtocolFields(uiState, flowOptions, testpreInput, testpreError)
            CommonNetworkFields(uiState, options)
            CommonStreamSecurityFields(
                state = uiState,
                options = options,
                scope = scope,
                buildProfileItem = { uiState.toProfileItem(initialConfig) }
            )
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        if (config.password.isNullOrBlank()) {
            return false
        }
        return true
    }

    @Composable
    private fun VlessProtocolFields(
        state: ServerUiState,
        flowOptions: List<String>,
        testpreInput: String,
        testpreError: Boolean
    ) {
        FormTextField(
            label = stringResource(R.string.server_lab_id),
            value = state.password,
            onValueChange = { state.password = it },
            isError = state.isPasswordError
        )
        FormTextField(
            stringResource(R.string.server_lab_encryption),
            state.encryption,
            { state.encryption = it }
        )
        FormDropdownField(
            stringResource(R.string.server_lab_flow),
            state.flow,
            flowOptions,
            { state.flow = it }
        )
        if (VlessTestpre.supportsFlow(state.flow)) {
            FormTextField(
                label = stringResource(R.string.server_lab_testpre),
                value = testpreInput,
                onValueChange = viewModel::updateTestpre,
                keyboardType = KeyboardType.Number,
                isError = testpreError,
                supportingText = stringResource(R.string.server_lab_testpre_hint)
            )
        }
    }
}
