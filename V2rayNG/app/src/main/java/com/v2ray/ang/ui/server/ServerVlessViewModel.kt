package com.v2ray.ang.ui.server

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.v2ray.ang.dto.VlessTestpre
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ServerVlessViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
    companion object {
        private const val TESTPRE_INPUT = "testpreInput"
    }

    // getStateFlow inserts its default value, so check for restored input before creating it.
    private val hasRestoredTestpreInput = savedStateHandle.contains(TESTPRE_INPUT)
    private var initialized = false
    val testpreInput = savedStateHandle.getStateFlow(TESTPRE_INPUT, "")

    private val _testpreError = MutableStateFlow(false)
    val testpreError = _testpreError.asStateFlow()

    fun initialize(testpre: Int?) {
        if (!initialized && !hasRestoredTestpreInput) {
            savedStateHandle[TESTPRE_INPUT] = testpre?.toString() ?: ""
        }
        initialized = true
    }

    fun updateTestpre(input: String) {
        savedStateHandle[TESTPRE_INPUT] = input
        _testpreError.value = false
    }

    fun validateTestpre(flow: String): Boolean {
        val input = testpreInput.value.trim()
        _testpreError.value = VlessTestpre.supportsFlow(flow) &&
            input.isNotEmpty() && input != "0" && VlessTestpre.parse(input) == null
        return !_testpreError.value
    }

    fun testpreFor(flow: String): Int? =
        VlessTestpre.forOutbound(flow, VlessTestpre.parse(testpreInput.value.trim()))
}
