package com.v2ray.ang.ui.settings

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeoutException

/** UI interaction checks restricted to disposable CI emulators. */
@RunWith(AndroidJUnit4::class)
class SettingsBackendUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val title get() = context.getString(R.string.title_pref_tun_backend)
    private val labels get() = listOf(R.string.tun_backend_xray, R.string.tun_backend_hev,
        R.string.tun_backend_sing_native).map(context::getString)

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
            touch(option(labels[1]))
            awaitSaved("hev")
            openChoices()
            activateKey(option(labels[2]), KeyEvent.KEYCODE_ENTER, InputDevice.SOURCE_KEYBOARD)
            awaitSaved("sing_native")
            openChoices()
            touch(option(labels[1]))
            awaitSaved("hev")
            openChoices()
            activateKey(option(labels[2]), KeyEvent.KEYCODE_DPAD_CENTER, InputDevice.SOURCE_DPAD)
            awaitSaved("sing_native")
        }
        withSettings {
            val row = settingsRow()
            assertTrue("reopened summary", textOf(row).contains(labels[2]))
            touch(row)
            assertChoices(labels[2])
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
        report("Not run: TalkBack speech, announcements and spoken focus order; accessibility nodes inspected only")
    }

    private fun withSettings(block: () -> Unit) {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            try { block() } catch (failure: Throwable) {
                runCatching(::dumpNodes).onFailure { report("UI_DIAGNOSTICS unavailable=${it.javaClass.simpleName}") }
                throw failure
            }
        }
    }

    private fun openChoices() {
        touch(settingsRow())
        await("backend dialog") { nodes().count { it.isCheckable && it.isClickable } == 3 }
    }

    private fun settingsRow(): AccessibilityNodeInfo {
        await("settings accessibility content") {
            nodes().any { it.packageName == context.packageName && it.isScrollable }
        }
        repeat(16) { attempt ->
            labelledRow(title)?.let { row ->
                if (row.isVisibleToUser) return row
                row.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                waitForIdle()
                labelledRow(title)?.takeIf { it.isVisibleToUser }?.let { return it }
            }
            val scroller = nodes().firstOrNull { it.isScrollable && it.isVisibleToUser &&
                it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
            report("UI_SCROLL attempt=$attempt accepted=${scroller?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)}")
            waitForIdle()
        }
        error("TUN setting not reachable by scrolling")
    }

    // Compose exposes Text children separately from the row's input/accessibility actions.
    private fun labelledRow(label: String): AccessibilityNodeInfo? = nodes().firstNotNullOfOrNull { leaf ->
        if (leaf.text?.contains(label) != true && leaf.contentDescription?.contains(label) != true) null
        else generateSequence(leaf) { it.parent }.take(8).firstOrNull { it.isClickable }
    }

    private fun option(label: String): AccessibilityNodeInfo =
        nodes().first { it.isCheckable && it.isClickable && textOf(it) == label }

    private fun textOf(node: AccessibilityNodeInfo): String = nodes(node).flatMap {
        listOfNotNull(it.text?.toString(), it.contentDescription?.toString())
    }.filter { it.isNotBlank() }.distinct().joinToString("\n")

    private fun assertChoices(selected: String) {
        await("backend dialog choices") { nodes().count { it.isCheckable && it.isClickable } == 3 }
        val choices = nodes().filter { it.isCheckable && it.isClickable }
        assertEquals(labels.toSet(), choices.map(::textOf).toSet())
        for (node in choices) {
            val label = textOf(node)
            val checked = AccessibilityNodeInfoCompat.wrap(node).checked == AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE
            assertEquals("selected $label", label == selected, checked)
            assertTrue("backend row must expose activation", node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
            report("UI_BACKEND label=$label checked=$checked focusable=${node.isFocusable} actions=${node.actionList.map { it.id }}")
        }
    }

    private fun activateKey(node: AccessibilityNodeInfo, key: Int, source: Int) {
        val label = textOf(node)
        // Enter non-touch input mode before requesting input focus, as a
        // keyboard/remote user does. Navigation does not activate an option.
        val navigation = if (source == InputDevice.SOURCE_DPAD) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_TAB
        val navigationTime = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            assertTrue(automation.injectInputEvent(KeyEvent(navigationTime, navigationTime, action,
                navigation, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source), true))
        }
        assertTrue("backend input focus", node.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        await("backend input focus state") { option(label).isFocused }
        val now = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            assertTrue(automation.injectInputEvent(KeyEvent(now, now, action, key, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source), true))
        }
    }

    private fun touch(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        assertTrue("visible touch target", node.isVisibleToUser && !bounds.isEmpty)
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }

    private fun nodes(root: AccessibilityNodeInfo? = automation.rootInActiveWindow): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result.add(node)
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        root?.let(::visit)
        return result
    }

    private fun waitForIdle() {
        try { automation.waitForIdle(150, 1500) } catch (_: TimeoutException) {
            report("UI_WAIT accessibility event stream still active")
        }
    }

    private fun dumpNodes() {
        val knownLabels = labels + title + listOf(R.string.title_settings, R.string.title_ui_settings,
            R.string.title_vpn_settings).map(context::getString)
        nodes().take(160).forEachIndexed { index, node ->
            val raw = listOfNotNull(node.text, node.contentDescription).joinToString("\n")
            val known = knownLabels.filter { raw.contains(it) }.joinToString("|")
            val bounds = Rect().also(node::getBoundsInScreen)
            // Never dump arbitrary settings values, editable text or credentials.
            report("UI_NODE index=$index class=${node.className} children=${node.childCount} known=$known " +
                "textLength=${raw.length} clickable=${node.isClickable} checkable=${node.isCheckable} " +
                "visible=${node.isVisibleToUser} focused=${node.isFocused} bounds=$bounds actions=${node.actionList.map { it.id }}")
        }
    }

    private fun awaitSaved(key: String) = await("persisted $key") {
        MmkvManager.decodeSettingsString(AppConfig.PREF_TUN_BACKEND) == key
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        error("Timed out: $description")
    }

    private fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
}
