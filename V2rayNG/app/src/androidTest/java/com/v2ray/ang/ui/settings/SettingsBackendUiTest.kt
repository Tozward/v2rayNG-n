package com.v2ray.ang.ui.settings

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI interaction checks restricted to disposable CI emulators. */
@RunWith(AndroidJUnit4::class)
class SettingsBackendUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val title get() = context.getString(R.string.title_pref_tun_backend)
    private val labels get() = listOf(R.string.tun_backend_xray, R.string.tun_backend_hev,
        R.string.tun_backend_sing_native).map(context::getString)
    private val radio = hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Test
    fun backendChoicesSupportTouchKeyboardDpadAndPersistence() {
        assumeTrue(context.packageName == "com.v2ray.ang")
        assumeTrue(InstrumentationRegistry.getArguments().getString("deviceAudit") != "true")
        assumeTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE.startsWith("goldfish"))
        MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.VPN)
        MmkvManager.encodeSettings(AppConfig.PREF_ROOT_MODE_ENABLE, false)
        MmkvManager.encodeSettings(AppConfig.PREF_AUTO_CHECK_UPDATE, false)
        MmkvManager.encodeSettings(AppConfig.PREF_TUN_BACKEND, "sing")
        assertTrue("native label must be final", !labels[2].contains("experiment", ignoreCase = true))

        withSettings {
            openChoices()
            assertChoices(labels[2])
            touchHev()
            openChoices()
            activateKey(KeyEvent.KEYCODE_ENTER, InputDevice.SOURCE_KEYBOARD)
            awaitSaved("sing_native")
            touchHevAfterOpening()
            openChoices()
            activateKey(KeyEvent.KEYCODE_SPACE, InputDevice.SOURCE_KEYBOARD)
            awaitSaved("sing_native")
            touchHevAfterOpening()
            openChoices()
            activateKey(KeyEvent.KEYCODE_DPAD_CENTER, InputDevice.SOURCE_DPAD)
            awaitSaved("sing_native")
        }
        withSettings {
            row().performScrollTo().assertIsDisplayed().assertTextContains(labels[2])
            openChoices()
            assertChoices(labels[2])
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
        report("Not run: TalkBack speech, announcements and spoken focus order; Compose semantics inspected only")
    }

    private fun withSettings(block: () -> Unit) {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            try {
                compose.waitForIdle()
                block()
                compose.waitForIdle()
            } catch (failure: Throwable) {
                runCatching(::dumpNodes).onFailure { report("UI_DIAGNOSTICS unavailable=${it.javaClass.simpleName}") }
                throw failure
            }
        }
    }

    private fun row() = compose.onNode(hasText(title) and hasClickAction())
    private fun option(label: String) = compose.onNode(radio and hasText(label))

    private fun openChoices() {
        // The official scroll action uses Compose's layout/clipping and waits for recomposition.
        // Accessibility isVisibleToUser alone does not guarantee a safe row-center touch point.
        row().performScrollTo().assertIsDisplayed().performTouchInput { click() }
        compose.onAllNodes(radio).assertCountEquals(3)
    }

    private fun touchHevAfterOpening() {
        openChoices()
        touchHev()
    }

    private fun touchHev() {
        option(labels[1]).assertIsDisplayed().performTouchInput { click() }
        awaitSaved("hev")
    }

    private fun assertChoices(selected: String) {
        compose.onAllNodes(radio).assertCountEquals(3)
        for (label in labels) {
            val node = option(label).assertTextEquals(label).assertHasClickAction()
            if (label == selected) node.assertIsSelected() else node.assertIsNotSelected()
            val config = node.fetchSemanticsNode().config
            report("UI_BACKEND label=$label selected=${config[SemanticsProperties.Selected]} " +
                "click=${config.contains(SemanticsActions.OnClick)} focus=${config.contains(SemanticsActions.RequestFocus)}")
        }
    }

    private fun activateKey(key: Int, source: Int) {
        val navigation = if (source == InputDevice.SOURCE_DPAD) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_TAB
        injectKey(navigation, source)
        compose.waitForIdle()
        option(labels[2]).performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        injectKey(key, source)
    }

    private fun injectKey(key: Int, source: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            assertTrue(automation.injectInputEvent(KeyEvent(now, now, action, key, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source), true))
        }
    }

    private fun awaitSaved(key: String) {
        compose.waitUntil(timeoutMillis = 8000) {
            MmkvManager.decodeSettingsString(AppConfig.PREF_TUN_BACKEND) == key &&
                compose.onAllNodes(radio).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
    }

    private fun dumpNodes() {
        val knownLabels = labels + title + listOf(R.string.title_settings, R.string.title_ui_settings,
            R.string.title_vpn_settings).map(context::getString)
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            val raw = listOfNotNull(node.text, node.contentDescription).joinToString(" ")
            val known = knownLabels.filter { raw.contains(it) }.joinToString("|")
            val bounds = Rect().also(node::getBoundsInScreen)
            // Never dump arbitrary settings values, editable text or credentials.
            report("UI_NODE depth=$depth class=${node.className} children=${node.childCount} known=$known " +
                "textLength=${raw.length} clickable=${node.isClickable} checkable=${node.isCheckable} " +
                "visible=${node.isVisibleToUser} focused=${node.isFocused} bounds=$bounds actions=${node.actionList.map { it.id }}")
            if (depth < 12) repeat(node.childCount) { node.getChild(it)?.let { child -> visit(child, depth + 1) } }
        }
        automation.rootInActiveWindow?.let { visit(it, 0) }
    }

    private fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
}
