package com.wangzan101.mika.cap.phone.a11y

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.cap.phone.shell.AsciiTextKeys
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.runAction
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnseenClipboardDeviceTest {
    @Test
    fun chineseUsesAppPasteMenuAfterFocusChangesWithoutIme() = runBlocking {
        withEditor(rejectPasteShortcut = true) { screen ->
            val field = screen.awaitEditor("Description")
            val text = "中文测试"
            val result = toolDispatcher(screen).typeAtTestPoint(field, text, pressEnter = true)
            assertEquals(result.toString(), "true", result["ok"]?.jsonPrimitive?.content)
            assertEquals(result.toString(), "unknown", result["verification"]?.jsonPrimitive?.content)
            assertEquals(result.toString(), "true", result["enterSkipped"]?.jsonPrimitive?.content)
            assertTrue(result.toString(), result["hint"]?.jsonPrimitive?.content?.contains("Used the editor's Paste menu") == true)
            screen.awaitEditor("Description: $text")
            assertFalse(screen.keyboardUp())
        }
    }

    @Test
    fun selectionRepaintDoesNotCutOffAnAsynchronousClipboardRead() = runBlocking {
        withEditor { screen ->
            val field = screen.awaitEditor("Shortcut")
            val text = "延迟读取"
            val result = toolDispatcher(screen).typeAtTestPoint(field, text)
            assertEquals(result.toString(), "true", result["ok"]?.jsonPrimitive?.content)
            screen.awaitEditor("Shortcut: $text")
            assertFalse(screen.keyboardUp())
        }
    }

    private suspend fun withEditor(rejectPasteShortcut: Boolean = false, check: suspend (AndroidScreen) -> Unit) {
        val agent = DeviceSupport.newAgent()
        try {
            val screen = if (rejectPasteShortcut) {
                val shell = DeviceSupport.shell()
                val rejectingPaste = object : IMikaShell by shell {
                    override fun injectCtrlKey(displayId: Int, keyCode: Int): Int = when (keyCode) {
                        KeyEvent.KEYCODE_A -> AsciiTextKeys.DISPATCHED
                        KeyEvent.KEYCODE_V -> AsciiTextKeys.NOT_SENT
                        else -> shell.injectCtrlKey(displayId, keyCode)
                    }
                }
                AndroidScreen(DeviceSupport.service(), rejectingPaste, DeviceSupport.ready(agent.display))
            } else agent.screen
            agent.startEditor(ClipboardEditorTestActivity::class.java)
            check(screen)
        } finally {
            agent.release()
        }
    }

    private suspend fun AndroidScreen.awaitEditor(label: String): UiElement = withTimeout(8_000) {
        var found: UiElement? = null
        while (found == null) {
            found = peek().elements.firstOrNull { it.name == label }
            if (found == null) delay(100)
        }
        assertFalse(found.editable)
        found
    }
}

internal fun DeviceSupport.Agent.startEditor(activity: Class<out Activity>) {
    val component = "${DeviceSupport.TEST_PACKAGE}/${activity.name}"
    val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
    val output = DeviceSupport.sh(
        "am start -W -f $flags --display ${display.displayId} -n $component",
    )
    check("Status: ok" in output) { output }
}

internal suspend fun ToolDispatcher.typeAtTestPoint(
    field: UiElement, text: String, pressEnter: Boolean = false,
): JsonObject {
    val args = buildJsonObject {
        put("x", (field.bounds[0] + field.bounds[2]) / 2)
        put("y", (field.bounds[1] + field.bounds[3]) / 2)
        put("text", text)
        put("pressEnter", pressEnter)
    }
    return runAction(Part.ToolCall("type_${field.ref}", "phone_type_text", args.toString())).content
}
