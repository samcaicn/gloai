package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.execution
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.uistate.IdleScroll
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * App 里的节奏接在执行入口上:单步和一批里的每一步都在动作之前补足停留,停了多久记成这次调用的等待;
 * 「快」不停;限制浏览快慢的 App 换屏到了上限就不做、报 page_limit,打字照常,用户答话后接着做;停留里轻轻滚过只进运行记录。
 */
class AppDwellTest {
    /** 一个 App:每点一下换一整屏(六条不同的内容);记下点、打字、轻轻滚了几下。 */
    private class Pages(private val pkg: String = "com.slow") : ScreenPort by FakeScreen() {
        var page = 0
        val calls = mutableListOf<String>()
        private fun shown() = (1..6).map { i ->
            UiElement("@e$i", "text", "第${page}屏的第${i}条内容", 540, 200 * i, listOf(0, 80 * i, 999, 80 * i + 60), null, true, i == 1, false)
        }
        override suspend fun snapshot() = ScreenSnapshot("s$page", pkg, shown(), 1080, 2400)
        override suspend fun peek() = snapshot()
        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult { calls += "tap"; page++; return ActionResult.Ok(changed = true) }
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult { calls += "type"; return ActionResult.Ok(changed = true) }
        override suspend fun back(): ActionResult { calls += "back"; page++; return ActionResult.Ok(changed = true) }
        override suspend fun idleScroll(fraction: Double): Boolean { calls += "idle"; return true }
    }

    private fun tools(screen: ScreenPort, pacing: AppPacing, idle: IdleScroll? = null): ToolDispatcher =
        toolDispatcher(screen, appPacing = { pacing }, appTempo = { if (it == "com.slow") AppTempo.CAREFUL else AppTempo.NORMAL }, appCapped = { it == "com.slow" }, appIdleScroll = { idle })

    private fun executor(t: ToolDispatcher) = ActionExecutor(t, OperationGuards(), null, gate = {}, recorder = ActionRecorder(RunSink.NONE))

    private suspend fun ActionExecutor.tap(t: ToolDispatcher): ToolExecution {
        val sid = t.execute(Part.ToolCall("o", "phone_observe", "{}")).content["snapshotId"]!!.jsonPrimitive.content
        return run(Part.ToolCall("t", "phone_tap", """{"ref":"@e2","snapshotId":"$sid"}"""), Origin.Model(1)).execution()
    }

    private fun ToolExecution.error() = (content["error"] as? JsonPrimitive)?.content

    @Test fun fastAddsNoDwell() = runTest {
        val screen = Pages()
        val t = tools(screen, OperatingPace.FAST.appPacing())
        val ex = executor(t)
        val start = currentTime
        repeat(5) { assertEquals(0L, ex.tap(t).waitMs) }
        // 屏数也不设上限
        assertEquals(5, screen.page)
        assertEquals(0L, currentTime - start)
    }

    @Test fun perAppFastTempoSkipsDwellEvenWhenOthersWait() = runTest {
        // 停留引擎开着(别的 App 在等),这个 App 覆盖成「快」:里面不停也不看上限
        val screen = Pages("com.quick")
        val t = toolDispatcher(screen, appPacing = { AppPacing.human(Random(3)) }, appTempo = { AppTempo.FAST }, appIdleScroll = { null })
        val ex = executor(t)
        val start = currentTime
        repeat(5) { assertEquals(0L, ex.tap(t).waitMs) }
        assertEquals(5, screen.page)
        assertEquals(0L, currentTime - start)
    }

    @Test fun eachActionWaitsAfterTheLastOneAndTheWaitIsRecorded() = runTest {
        val t = tools(Pages("com.plain"), OperatingPace.STANDARD.appPacing(Random(3)))
        val ex = executor(t)
        assertEquals(0L, ex.tap(t).waitMs)
        val before = currentTime
        val second = ex.tap(t)
        // 上一下换了一屏:新的一屏的范围(标准)
        assertTrue("${second.waitMs}", second.waitMs in AppPacing.Limits().newScreenMs.first..AppPacing.Limits().maxDwellMs)
        assertTrue(currentTime - before >= second.waitMs)
        // 一批里的每一步同样经执行入口停
        val sid = t.execute(Part.ToolCall("o", "phone_observe", "{}")).content["snapshotId"]!!.jsonPrimitive.content
        val typed = ex.run(Part.ToolCall("b1", "phone_type_text", """{"ref":"@e1","snapshotId":"$sid","text":"你好"}"""), Origin.Batch(2)).execution()
        assertTrue("${typed.waitMs}", typed.waitMs > 0)
    }

    @Test fun carefulAppsStopAtTheScreenBudgetUntilTheUserAnswers() = runTest {
        val screen = Pages()
        val t = tools(screen, AppPacing(Random(1), screenCap = 3))
        val ex = executor(t)
        repeat(3) { assertEquals(JsonPrimitive(true), ex.tap(t).content["ok"]) }
        // 已换了三屏:换屏的动作不做,说明为什么停
        val stopped = ex.tap(t)
        assertEquals("page_limit", stopped.error())
        assertTrue(stopped.content["message"].toString(), stopped.content["message"].toString().contains("ask_user"))
        assertTrue(stopped.content["message"].toString().contains("Mika reached its per-task app screen budget"))
        val budget = stopped.content["navigationBudget"] as JsonObject
        assertEquals(JsonPrimitive("app_screens"), budget["scope"])
        assertEquals(JsonPrimitive("com.slow"), budget["target"])
        assertEquals(JsonPrimitive(3), budget["used"])
        assertEquals(JsonPrimitive(3), budget["limit"])
        assertEquals(3, screen.page)
        // 打字照常
        val sid = t.execute(Part.ToolCall("o", "phone_observe", "{}")).content["snapshotId"]!!.jsonPrimitive.content
        assertEquals(JsonPrimitive(true), ex.run(Part.ToolCall("y", "phone_type_text", """{"ref":"@e1","snapshotId":"$sid","text":"a"}"""), Origin.Model(1)).execution().content["ok"])
        // 用户答过话:接着做
        t.noteUserAnswer("还要接着看吗?", "继续")
        assertEquals(JsonPrimitive(true), ex.tap(t).content["ok"])
        assertEquals(4, screen.page)
    }

    @Test fun ordinaryAppsHaveNoBudget() = runTest {
        val screen = Pages("com.plain")
        val t = tools(screen, AppPacing(Random(1), screenCap = 2))
        val ex = executor(t)
        repeat(4) { assertEquals(JsonPrimitive(true), ex.tap(t).content["ok"]) }
    }

    @Test fun idleScrollsHappenOnlyBeforeActionsThatDoNotAimAtAPlaceAndAreOnlyRecorded() = runTest {
        val screen = Pages()
        val t = tools(screen, AppPacing.human(Random(5)), IdleScroll(Random(6)))
        val ex = executor(t)
        ex.tap(t)
        // 点按坐标或编号的动作之前不滚
        ex.tap(t)
        assertTrue(screen.calls.none { it == "idle" })
        // 返回不按屏上的位置:新的一屏上停得够久,停留里轻轻滚几下,只进运行记录
        val back = ex.run(Part.ToolCall("k", "phone_back", "{}"), Origin.Model(1)).execution()
        val idled = screen.calls.count { it == "idle" }
        assertTrue("$idled", idled > 0)
        assertEquals(JsonPrimitive(idled), back.record["idleScrolls"])
        assertTrue(back.content["idleScrolls"] == null)
    }
}
