package com.wangzan101.mika.cap.phone.a11y

import android.view.Display
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.TEST_PACKAGE
import com.wangzan101.mika.cap.phone.shell.AsciiTextKeys
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnseenTextKeysDeviceTest {
    @Test
    fun typesLiteralAsciiInTwoHiddenEditorsAfterRejectedPaste() = runBlocking {
        withEditor(failPasteAfter = null) { shell, id, screen ->
            val title = screen.focusEditor("Title")
            val task = shell.topTaskId(id)
            assertEquals(AsciiTextKeys.NOT_SENT, shell.injectAsciiText(Display.DEFAULT_DISPLAY, task, TEST_PACKAGE, "Wrong"))
            assertEquals(AsciiTextKeys.NOT_SENT, shell.injectAsciiText(id, task + 1, TEST_PACKAGE, "Wrong"))
            assertEquals(AsciiTextKeys.NOT_SENT, shell.injectAsciiText(id, task, TEST_PACKAGE, "Partial中文"))
            assertEquals(AsciiTextKeys.NOT_SENT, shell.injectAsciiText(id, task, TEST_PACKAGE, "Wrong\n"))

            val tools = toolDispatcher(screen)
            val first = "A'z %s & 2"
            val firstResult = tools.typeAtTestPoint(title, first, pressEnter = true)
            assertKeyDispatch(firstResult, screen.peek().elements)
            assertEquals(firstResult.toString(), "true", firstResult["enterSkipped"]?.jsonPrimitive?.content)
            screen.awaitEditor("Title: $first")
            val description = screen.focusEditor("Description")
            val second = "SecondValue"
            val secondResult = tools.typeAtTestPoint(description, second)
            assertKeyDispatch(secondResult, screen.peek().elements)
            screen.awaitEditor("Description: $second")
            screen.awaitEditor("Title: $first")
            assertFalse(screen.keyboardUp())
        }
    }

    @Test
    fun doesNotReplayWhenRejectedPasteAlreadyChangedTheField() = runBlocking {
        withEditor(failPasteAfter = "Prefix") { _, _, screen ->
            val title = screen.focusEditor("Title")
            val result = toolDispatcher(screen).typeAtTestPoint(title, "PrefixAndRest", pressEnter = true)
            assertEquals(result.toString(), "unknown", result["verification"]?.jsonPrimitive?.content)
            assertEquals(result.toString(), "true", result["enterSkipped"]?.jsonPrimitive?.content)
            assertTrue(result.toString(), result["hint"]?.jsonPrimitive?.content?.contains("The paste shortcut") == true)
            screen.awaitEditor("Title: Prefix")
        }
    }

    private suspend fun withEditor(
        failPasteAfter: String?, check: suspend (IMikaShell, Int, AndroidScreen) -> Unit,
    ) {
        val shell = DeviceSupport.shell()
        val agent = DeviceSupport.newAgent()
        val id = agent.display.displayId
        try {
            val rejectingPaste = object : IMikaShell by shell {
                override fun injectCtrlKey(displayId: Int, keyCode: Int): Int {
                    // 这个夹具只模拟 V 的结果;A 固定为已投递,避免真实按键的偶发结果改变被测分支。
                    if (keyCode == KeyEvent.KEYCODE_A) return AsciiTextKeys.DISPATCHED
                    if (keyCode != KeyEvent.KEYCODE_V) return shell.injectCtrlKey(displayId, keyCode)
                    if (failPasteAfter != null) {
                        assertEquals(AsciiTextKeys.DISPATCHED,
                            shell.injectAsciiText(id, shell.topTaskId(id), TEST_PACKAGE, failPasteAfter))
                        return AsciiTextKeys.UNCONFIRMED
                    }
                    return AsciiTextKeys.NOT_SENT
                }
            }
            val screen = AndroidScreen(DeviceSupport.service(), rejectingPaste, DeviceSupport.ready(agent.display))
            agent.startEditor(KeyOnlyEditorTestActivity::class.java)
            check(shell, id, screen)
        } finally {
            agent.release()
        }
    }

    private suspend fun AndroidScreen.focusEditor(label: String): UiElement {
        val field = awaitEditor(label)
        // 预备动作只聚焦、不输入文字;点击偶发丢失时可以安全地重试,正文路径仍只执行一次。
        repeat(3) {
            // ScreenPort 直接用像素中心;bounds 的 0–999 坐标只有工具入口才转换。
            tapXY(field.centerX, field.centerY)
            val focused = withTimeoutOrNull(1_500) {
                var match: UiElement? = null
                while (match == null) {
                    match = peek().elements.firstOrNull { it.name == label && it.focused }
                    if (match == null) delay(100)
                }
                match
            }
            if (focused != null) return focused
        }
        error("Editor '$label' did not take focus; current elements: ${peek().elements}")
    }

    private suspend fun AndroidScreen.awaitEditor(label: String): UiElement {
        val found = withTimeoutOrNull(8_000) {
            var match: UiElement? = null
            while (match == null) {
                match = peek().elements.firstOrNull { it.name == label }
                if (match == null) delay(100)
            }
            match
        }
        return checkNotNull(found) { "Editor '$label' not found; current elements: ${peek().elements}" }
            .also { assertFalse(it.editable) }
    }

    private fun assertKeyDispatch(result: JsonObject, elements: List<UiElement>) {
        assertEquals("$result with $elements", "true", result["ok"]?.jsonPrimitive?.content)
        assertEquals(result.toString(), "unknown", result["verification"]?.jsonPrimitive?.content)
        assertTrue(result.toString(), result["hint"]?.jsonPrimitive?.content?.contains("Sent keyboard events") == true)
    }
}
