package com.wangzan101.mika.core.learn.replay

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.safety.gate.ConfirmationGate
import com.wangzan101.mika.core.safety.guard.OperationGuards
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.screen.map.ScreenGraph
import com.wangzan101.mika.core.screen.map.ScreenNode
import com.wangzan101.mika.core.screen.map.Transition
import com.wangzan101.mika.core.screen.map.TransitionEffect
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.assembly.toolDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayerScreenMapTest {
    private val graph = ScreenGraph("com.notes", mapOf(
        "home" to ScreenNode("home", "com.notes", anchors = mapOf("备忘录" to 1, "全部笔记" to 1)),
        "editor" to ScreenNode("editor", "com.notes", anchors = mapOf("新笔记" to 1, "完成" to 1)),
        "settings" to ScreenNode("settings", "com.notes", anchors = mapOf("设置" to 1),
            transitions = mapOf("s-h" to Transition("tap", "返回", TransitionEffect.NAVIGATED, "home", 1))),
    ))

    private fun replayer(s: ScreenPort, sink: RunSink = Events(), gate: suspend () -> Unit = {}) =
        toolDispatcher(s).also { it.captureTargets = true }.let { d ->
            Replayer(d, ActionExecutor(d, OperationGuards(), ConfirmationGate(), gate, ActionRecorder(sink)), gate = gate, sink = sink, graph = graph)
        }

    @Test fun completesWhenLandingOnExpectedScreen() = runTest {
        val s = Notes.screen()
        val out = replayer(s).run(RouteChoice(Notes.route(), listOf("今天天气不错")))
        assertEquals(ReplayOutcome.Completed(3), out)
    }

    @Test fun divergesWhenLandingOnWrongScreenAfterRetry() = runTest {
        val s = Notes.screen()
        // 点了「新建」却去了设置页（changed=true，但落错屏）
        s.pages["settings"] = listOf(ui("@e1", "text", "设置", listOf(0, 0, 999, 60), clickable = false))
        s.links["home" to "新建"] = "settings"
        val out = replayer(s).run(RouteChoice(Notes.route(), listOf("x")))
        assertTrue(out is ReplayOutcome.Diverged)
        val d = out as ReplayOutcome.Diverged
        assertEquals(1, d.done) // 第 2 步（点「新建」）对不上
        assertTrue(d.executed) // 这一步真的执行过
        assertNotNull(d.screenMap)
        assertTrue(d.screenMap!!.contains("设置"))
        assertTrue(d.screenMap!!.contains("从这屏可走")) // 交还文案含「落在哪屏 + 从这走」
    }
}
