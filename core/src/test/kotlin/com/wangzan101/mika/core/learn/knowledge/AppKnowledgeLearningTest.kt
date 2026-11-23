package com.wangzan101.mika.core.learn.knowledge

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.assembly.knownTools
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppKnowledgeLearningTest {
    private val store = KnowledgeStore(KnowledgeStore.loadBundled())
    private val app = store.byPackage("com.taobao.idlefish")!!
    private val facts = "- 首页顶部的搜索框进入搜索页,结果分商品和用户两个标签。\n- 「我的」页里的「我发布的」列出自己挂出的商品。"
    private val unreviewed = TaskState.Succeeded("已查看", verified = false, reviewed = false)

    /** [results] 每一步的结果;默认都生效。 */
    private fun events(vararg results: String = Array(5) { """{"ok":true}""" }, extra: List<RunEvent> = emptyList()): List<RunEvent> {
        val out = mutableListOf<RunEvent>()
        val names = listOf("phone_launch_app", "phone_tap", "phone_tap", "phone_scroll", "phone_tap", "phone_back", "phone_back")
        results.forEachIndexed { i, r ->
            val name = names[i]
            out += RunEvent.ModelReply(i.toLong(), i + 1, "", name,
                if (i == 0) """{"package":"com.taobao.idlefish"}""" else """{"x":500,"y":500}""", null, null, null)
            out += RunEvent.ToolResult((i + 20).toLong(), i + 1, name, r, !r.contains("false") && !r.contains("unchanged"), null)
        }
        return out + extra
    }

    private fun model(reply: String) = FakeModel(ModelReply(listOf(Part.Text(reply)), StopReason.EndTurn, null))
    private fun request(m: FakeModel) = m.requests.single().single().parts.filterIsInstance<Part.Text>().single().text

    private fun draft(m: FakeModel, ev: List<RunEvent>, end: TaskState = unreviewed, outcome: RunOutcome = RunOutcome.Done,
        current: AppKnowledgeDraft? = null, goal: String = "查看闲鱼") = runBlocking {
        AppKnowledgeDrafter(m).draft(AppRunInput("r1", goal, end, outcome, ev, knownTools()), app, store.builtIn(app.name), current, "d1", 5)
    }

    @Test fun doneRunAddsFactsWithExistingKnowledgeInRequest() {
        val m = model(facts)
        val d = draft(m, events())!!
        assertEquals(AppKnowledgeStage.Draft, d.stage)
        assertEquals("xianyu", d.appName)
        assertEquals("闲鱼", d.display)
        assertEquals(facts, d.markdown)
        assertFalse(d.lesson)
        assertNull(d.revises)
        val req = request(m)
        assertTrue(req, req.contains("聊一聊")) // 内置说明的正文
        assertTrue(req, req.contains("phone_tap"))
    }

    @Test fun nothingWorthAddingYieldsNothing() {
        assertNull(draft(model("NONE"), events()))
        assertNull(draft(model("  "), events()))
    }

    @Test fun repliesThatAreNotAFactListAreRejected() {
        assertNull(draft(model("---\nname: xianyu\n---\n## 是什么\n闲鱼。"), events()))
        assertNull(draft(model("- 搜索页里点 `phone_fly` 能飞。"), events()))
        assertNull(draft(model((1..20).joinToString("\n") { "- 第 $it 条事实写在这里。" }), events()))
    }

    @Test fun revisionOfCurrentSupplement() {
        val current = AppKnowledgeDraft("d0", "xianyu", "com.taobao.idlefish", "- 旧的一条。", "r0", AppKnowledgeStage.Trial, 1)
        val m = model(facts)
        val d = draft(m, events(), current = current)!!
        assertEquals("d0", d.revises)
        assertTrue(request(m).contains("旧的一条"))
        // 改完和原来一样:不算修订
        assertNull(draft(model("- 旧的一条。"), events(), current = current))
    }

    @Test fun failureReasonIsSentAsIs() {
        val bad = """{"ok":false,"error":"obscured"}"""
        val m = model("NONE")
        draft(m, events("""{"ok":true}""", bad, bad), TaskState.Failed("找不到「我发布的」入口", FailKind.GaveUp), RunOutcome.Unknown)
        assertTrue(request(m), request(m).contains("找不到「我发布的」入口"))
        assertTrue(request(m).contains("not_done"))
    }

    @Test fun verifiedNotesAndAPlainDoneRunAskNothing() {
        val verified = AppKnowledgeDraft("d0", "xianyu", "com.taobao.idlefish", "- 旧的一条。", "r0", AppKnowledgeStage.Verified, 1)
        val m = model(facts)
        assertNull(draft(m, events(), current = verified))
        assertTrue(m.requests.isEmpty())
        // 磕绊的照样请
        val bad = """{"ok":false,"error":"obscured"}"""
        assertTrue(draft(model(facts), events("""{"ok":true}""", bad, bad), current = verified) != null)
    }

    @Test fun olderFullAppMdSupplementCanBeRevised() {
        val old = AppKnowledgeDraft("d0", "xianyu", "com.taobao.idlefish", "---\nname: xianyu\n---\n## 界面\n首页顶部有搜索入口。", "r0", AppKnowledgeStage.Trial, 1)
        assertEquals("- 首页顶部有搜索入口。", AppKnowledgeDrafter.facts(old.markdown))
        val m = model("- 首页顶部有搜索入口。\n- 「我的」页里有「我发布的」。")
        assertEquals("d0", draft(m, events(), current = old)!!.revises)
        assertTrue(request(m).contains("- 首页顶部有搜索入口。"))
    }

    @Test fun revisionOnTrialShadowsThePreviousOne() {
        val v1 = AppKnowledgeDraft("v1", "xianyu", "p", "- a", "r", AppKnowledgeStage.Verified, 1)
        val v2 = AppKnowledgeDraft("v2", "xianyu", "p", "- b", "r", AppKnowledgeStage.Trial, 2, revises = "v1")
        assertEquals("v2", AppKnowledgeTrial.inUse(listOf(v1, v2), "xianyu")?.id)
        assertEquals("v1", AppKnowledgeTrial.inUse(listOf(v1, v2.copy(stage = AppKnowledgeStage.Archived)), "xianyu")?.id)
        assertEquals("v1", AppKnowledgeTrial.inUse(listOf(v1, v2.copy(stage = AppKnowledgeStage.Draft)), "xianyu")?.id)
    }

    @Test fun failedAndBumpyRunsLearnLessons() {
        val bad = """{"ok":false,"error":"obscured"}"""
        val ok = """{"ok":true}"""
        // 没办成:放弃或步数用完的,动过几步就学
        val gaveUp = model(facts)
        val lesson = draft(gaveUp, events(ok, bad, ok), TaskState.Failed("找不到「我发布的」入口", FailKind.GaveUp), RunOutcome.Unknown)!!
        assertTrue(lesson.lesson)
        val req = request(gaveUp)
        assertTrue(req, req.contains("没办成") && req.contains("obscured"))
        assertTrue(draft(model(facts), events(ok, ok, ok), TaskState.Succeeded("只看了一部分", verified = false, reviewed = false), RunOutcome.Partial)!!.lesson)
        // 办成了但磕绊:动作不够数也学
        val bumpy = draft(model(facts), events(ok, bad, bad))!!
        assertTrue(bumpy.lesson)
        assertTrue(draft(model(facts), events(ok, ok, ok, ok, ok, ok, ok), unreviewed)!!.lesson) // 返回了两次
    }

    @Test fun runsThatTeachNothingAboutTheAppAskNoModel() {
        val ok = """{"ok":true}"""
        val cases = listOf(
            Triple(events(), TaskState.Cancelled as TaskState, RunOutcome.Unknown),
            Triple(events(), unreviewed, RunOutcome.Unknown),
            Triple(events(), TaskState.Succeeded("ok", verified = false, note = "x"), RunOutcome.Done), // 核查跑过没通过
            Triple(events(), TaskState.Failed("模型出错", FailKind.Model), RunOutcome.Unknown),
            Triple(events(ok, ok, ok), unreviewed, RunOutcome.Done), // 顺利办成、动作太少
            Triple(events(ok, ok), TaskState.Failed("x", FailKind.GaveUp), RunOutcome.Unknown), // 一两步就停下
            Triple(events(extra = listOf(RunEvent.Takeover(50, "login", "skipped", 10, 0))), unreviewed, RunOutcome.Done),
            Triple(events(extra = listOf(RunEvent.Takeover(50, "payment", "user_finished", 10, 4))), unreviewed, RunOutcome.Done),
            Triple(events(extra = listOf(RunEvent.Replay(50, "r", 1, 3, true, ""))), unreviewed, RunOutcome.Done),
        )
        for ((ev, end, outcome) in cases) {
            val m = model(facts)
            assertNull("$end $outcome", draft(m, ev, end, outcome))
            assertTrue("$end $outcome", m.requests.isEmpty())
        }
    }

    @Test fun touchedAppsAreThoseActedIn() {
        val ev = events() + listOf(
            RunEvent.ModelReply(80, 9, "", "phone_launch_app", """{"package":"com.tencent.mm"}""", null, null, null),
            RunEvent.ToolResult(81, 9, "phone_launch_app", """{"ok":true}""", true, null),
            RunEvent.ModelReply(82, 10, "", "phone_launch_app", """{"package":"com.unknown.app"}""", null, null, null),
            RunEvent.ToolResult(83, 10, "phone_launch_app", """{"ok":true}""", true, null),
        )
        assertEquals(listOf("xianyu", "wechat"), AppKnowledgeDrafter.touched(ev, knownTools(), store).map { it.name })
    }

    @Test fun lessonsNeedTwoBadTrialsInARow() {
        val done = AppKnowledgeDraft("d1", "xianyu", "p", facts, "r", AppKnowledgeStage.Trial, 1)
        assertEquals(AppKnowledgeStage.Verified, AppKnowledgeTrial.next(done, good = true).stage)
        assertEquals(AppKnowledgeStage.Archived, AppKnowledgeTrial.next(done, good = false).stage)
        val lesson = done.copy(lesson = true)
        val once = AppKnowledgeTrial.next(lesson, good = false)
        assertEquals(AppKnowledgeStage.Trial, once.stage)
        assertEquals(AppKnowledgeStage.Archived, AppKnowledgeTrial.next(once, good = false).stage)
        assertEquals(AppKnowledgeStage.Verified, AppKnowledgeTrial.next(once, good = true).stage)
    }
}
