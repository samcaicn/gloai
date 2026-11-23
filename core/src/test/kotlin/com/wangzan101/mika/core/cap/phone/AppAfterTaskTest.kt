package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.screen.uistate.AfterTaskMarks
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppAfterTaskTest {
    private class MarkingScreen : ScreenPort by FakeScreen() {
        override val afterTask = AfterTaskMarks()
    }

    private fun call(args: String) = Part.ToolCall("c", "phone_app_after_task", args)
    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    @Test fun marksAreRecordedPerCopyAndTheLastOneWins() = runTest {
        val screen = MarkingScreen()
        val d = toolDispatcher(screen)
        val first = d.execute(call("""{"package":"com.example.music","action":"keep","reason":"用户要接着听"}""")).content
        assertEquals("true", first.s("ok"))
        assertNull(first.s("replaces"))
        d.execute(call("""{"package":"com.example.chat","action":"close","reason":"临时登录","user":10}"""))
        val again = d.execute(call("""{"package":"com.example.music","action":"close","reason":"用户说做完就关"}""")).content
        assertEquals("keep", again.s("replaces"))
        assertEquals(listOf("com.example.chat@10", "com.example.music"), screen.afterTask.closeKeys())
        assertEquals(emptyList<String>(), screen.afterTask.keepKeys())
    }

    @Test fun badArgumentsAreRefused() = runTest {
        val d = toolDispatcher(MarkingScreen())
        assertEquals("invalid_arguments", d.execute(call("""{"package":"com.example.music","action":"pause","reason":"x"}""")).content.s("error"))
        assertEquals("invalid_arguments", d.execute(call("""{"package":"not a package","action":"keep","reason":"x"}""")).content.s("error"))
        assertEquals("invalid_arguments", d.execute(call("""{"package":"com.example.music","action":"keep"}""")).content.s("error"))
    }

    @Test fun screensWithoutAnEndOfTaskStepSaySo() = runTest {
        val r = toolDispatcher(FakeScreen()).execute(call("""{"package":"com.example.music","action":"keep","reason":"x"}""")).content
        assertEquals(AppAfterTask.AFTER_TASK_UNAVAILABLE, r.s("error"))
    }
}
