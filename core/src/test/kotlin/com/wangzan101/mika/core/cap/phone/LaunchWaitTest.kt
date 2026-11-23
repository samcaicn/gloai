package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.screen.uistate.APP_IN_USE_BY_OTHER_TASK
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.AppWait
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** phone_launch_app 的 wait:另一个任务用着这个 App 时,模型可以要求等它放开再开。 */
class LaunchWaitTest {
    /** 第一次打开报被占用,之后照 [busyTimes] 次数一直被占;[wait] 是等的结果。 */
    private class BusyScreen(private val wait: AppWait, private var busyTimes: Int = 1) : ScreenPort by FakeScreen() {
        val calls = mutableListOf<String>()
        override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
            calls += "open $packageName"
            return if (busyTimes-- > 0) ActionResult.Failed(APP_IN_USE_BY_OTHER_TASK) else ActionResult.Ok()
        }
        override suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait {
            calls += "wait $packageName $timeoutMs"
            return wait
        }
    }

    private suspend fun launch(screen: ScreenPort, args: String) =
        toolDispatcher(screen).execute(Part.ToolCall("l", "phone_launch_app", args))

    private fun error(r: com.wangzan101.mika.core.tool.ToolExecution) = r.content["error"]?.jsonPrimitive?.content

    @Test fun withoutWaitTheInUseResultComesBackAtOnce() = runTest {
        val screen = BusyScreen(AppWait.Free)
        val r = launch(screen, """{"package":"com.example.chat"}""")
        assertEquals(APP_IN_USE_BY_OTHER_TASK, error(r))
        assertEquals(listOf("open com.example.chat"), screen.calls)
        // 说明里告诉模型可以要求等
        assertTrue(r.content["message"]!!.jsonPrimitive.content.contains("wait"))
    }

    @Test fun waitOpensTheAppOnceItIsFree() = runTest {
        val screen = BusyScreen(AppWait.Free)
        val r = launch(screen, """{"package":"com.example.chat","wait":30}""")
        assertEquals("true", r.content["ok"]!!.jsonPrimitive.content)
        assertEquals(listOf("open com.example.chat", "wait com.example.chat 30000", "open com.example.chat"), screen.calls)
    }

    @Test fun mutualWaitFailsFastWithItsOwnCode() = runTest {
        val screen = BusyScreen(AppWait.WouldDeadlock)
        val r = launch(screen, """{"package":"com.example.chat","wait":60}""")
        assertEquals(LaunchApp.WAIT_WOULD_DEADLOCK, error(r))
        assertEquals(listOf("open com.example.chat", "wait com.example.chat 60000"), screen.calls)
    }

    @Test fun waitingTooLongSaysItIsStillInUse() = runTest {
        val screen = BusyScreen(AppWait.TimedOut)
        assertEquals(LaunchApp.WAIT_TIMED_OUT, error(launch(screen, """{"package":"com.example.chat","wait":5}""")))
    }

    /** 没被占着时 wait 不起作用;超过上限的等待是参数错,不打开。 */
    @Test fun waitIsBoundedAndOnlyUsedWhenBusy() = runTest {
        val free = BusyScreen(AppWait.Free, busyTimes = 0)
        assertEquals("true", launch(free, """{"package":"com.example.chat","wait":10}""").content["ok"]!!.jsonPrimitive.content)
        assertEquals(listOf("open com.example.chat"), free.calls)
        val tooLong = BusyScreen(AppWait.Free)
        val r = launch(tooLong, """{"package":"com.example.chat","wait":${LaunchApp.MAX_WAIT_S + 1}}""")
        assertEquals("invalid_arguments", error(r))
        assertEquals(emptyList<String>(), tooLong.calls)
    }
}
