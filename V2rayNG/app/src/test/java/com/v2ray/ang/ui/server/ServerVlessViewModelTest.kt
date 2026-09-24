package com.v2ray.ang.ui.server

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerVlessViewModelTest {
    @Test
    fun initialAndDisabledValuesDoNotEnablePreconnections() {
        val model = ServerVlessViewModel(SavedStateHandle())
        model.initialize(null)
        assertEquals("", model.testpreInput.value)
        assertTrue(model.validateTestpre("xtls-rprx-vision"))
        assertNull(model.testpreFor("xtls-rprx-vision"))

        model.updateTestpre("0")
        assertTrue(model.validateTestpre("xtls-rprx-vision"))
        assertNull(model.testpreFor("xtls-rprx-vision"))
    }

    @Test
    fun validInputPersistsThroughSavedStateAndOnlyWorksWithVision() {
        val state = SavedStateHandle()
        val model = ServerVlessViewModel(state)
        model.initialize(2)
        model.updateTestpre("5")
        assertTrue(model.validateTestpre("xtls-rprx-vision"))
        assertEquals(5, model.testpreFor("xtls-rprx-vision"))
        assertNull(model.testpreFor(""))

        val restored = ServerVlessViewModel(SavedStateHandle(mapOf("testpreInput" to state.get<String>("testpreInput"))))
        restored.initialize(2)
        assertEquals("5", restored.testpreInput.value)
    }

    @Test
    fun invalidInputShowsErrorAndEditingClearsIt() {
        val model = ServerVlessViewModel(SavedStateHandle())
        model.initialize(null)
        model.updateTestpre("17")
        assertFalse(model.validateTestpre("xtls-rprx-vision"))
        assertTrue(model.testpreError.value)
        model.updateTestpre("3")
        assertFalse(model.testpreError.value)
        assertTrue(model.validateTestpre("xtls-rprx-vision"))
        assertEquals(3, model.testpreFor("xtls-rprx-vision"))

        model.updateTestpre("invalid")
        assertTrue(model.validateTestpre(""))
        assertNull(model.testpreFor(""))
    }
}
