package com.wangzan101.mika.ui

import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.guard.Severity
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.record.RunHandle
import com.wangzan101.mika.record.RunStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.tool.Verb

class RunTimelineViewModelTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_760_000_000_000L
    private val store by lazy { RunStore(tmp.root.resolve("files"), { null }, 30, { clock }) { it } }

    private fun run(goal: String, block: RunHandle.() -> Unit): String {
        val h = store.open(goal)
        h.block()
        h.close()
        return h.runId
    }

    private fun ready(runId: String) = RunTimelineViewModel(store, runId).load() as TimelineUi.Ready

    @Test fun recordedGoalsKeepTheirConfirmationsAfterTheTaskEnds() {
        val criteria = listOf("确认地址", "确认店名", "给出推荐")
        val id = run("找咖啡馆") {
            event(RunEvent.Criteria(clock, criteria, emptyList(), confirmedIds = setOf(1)))
            event(RunEvent.Criteria(clock, criteria, emptyList(), complete = true, confirmedIds = setOf(1, 2, 3)))
            event(RunEvent.TaskEnded(clock, "succeeded", "推荐拿铁"))
        }
        assertEquals(criteria, ready(id).header.criteria)
        assertEquals(setOf(1, 2, 3), ready(id).header.confirmedIds)
        val old = run("旧任务") { event(RunEvent.Criteria(clock, criteria, emptyList())) }
        assertNull(ready(old).header.confirmedIds)
    }

    /** 任务还在跑、还没有结束事件时算「进行中」:页面据此每秒重读,跑完就停。 */
    @Test fun anUnfinishedRunOfALiveTaskReadsAsRunning() {
        val h = store.open("打开设置")
        store.append(h.runId, RunEvent.ModelReply(clock + 1_000, 1, "", null, null, 500, 10, 0, 900, repliedAtMs = clock + 1_000))
        val live = RunTimelineViewModel(store, h.runId, alive = { it == h.runId }).load() as TimelineUi.Ready
        assertTrue("没结束就算在跑", live.header.running)
        store.append(h.runId, RunEvent.TaskEnded(clock + 2_000, "succeeded", "好了"))
        val done = RunTimelineViewModel(store, h.runId, alive = { it == h.runId }).load() as TimelineUi.Ready
        assertFalse("结束之后不再重读", done.header.running)
    }

    /** 没有结束记录、任务也已经不在了(进程被杀、记录没写完):算已结束,不一直按秒重读。 */
    @Test fun anUnfinishedRunWhoseTaskIsGoneIsNotRunning() {
        val h = store.open("打开设置")
        store.append(h.runId, RunEvent.ModelReply(clock + 1_000, 1, "", null, null, 500, 10, 0, 900, repliedAtMs = clock + 1_000))
        val gone = RunTimelineViewModel(store, h.runId, alive = { false }).load() as TimelineUi.Ready
        assertFalse(gone.header.running)
        val other = RunTimelineViewModel(store, h.runId, alive = { it == "another-run" }).load() as TimelineUi.Ready
        assertFalse("别的任务在跑不算这一次", other.header.running)
    }

    @Test fun usedMemoryAppearsInProcessHeader() {
        val id = run("总结") { event(RunEvent.MemoryUsed(clock, listOf("m1", "m2"))) }
        val ui = RunTimelineViewModel(store, id, resolveMemory = { ids -> ids.map { "记忆 $it" } }).load() as TimelineUi.Ready
        assertEquals(listOf("记忆 m1", "记忆 m2"), ui.header.usedMemory)
    }

    @Test fun headerCarriesRunMetrics() {
        val id = run("查天气") {
            event(RunEvent.TaskStarted(clock, "查天气", "m1"))
            event(RunEvent.ModelReply(clock + 3_000, 1, "", null, null, 1_000, 100, 0, 2_500, repliedAtMs = clock + 3_000))
            event(RunEvent.TaskEnded(clock + 4_000, "succeeded", "晴"))
        }
        val m = ready(id).header.metrics!!
        assertEquals(id, m.runId)
        assertEquals(4_000L, m.totalMs)
        assertEquals(3_000L, m.firstFeedbackMs)
        assertEquals(1, m.modelCalls)
    }

    @Test fun headerCarriesTheStepLimit() {
        val limited = run("查天气") {
            event(RunEvent.TaskStarted(clock, "查天气", "m1", stepLimit = 60))
            event(RunEvent.TaskEnded(clock + 1_000, "succeeded", "晴"))
        }
        assertEquals(60, ready(limited).header.stepLimit)
        val unlimited = run("看电量") {
            event(RunEvent.TaskStarted(clock, "看电量", "m1"))
            event(RunEvent.TaskEnded(clock + 1_000, "succeeded", "85%"))
        }
        assertNull(ready(unlimited).header.stepLimit)
    }

    @Test fun headerCarriesTheTokenBudgetAndUsesAllMeteredCallsWhenAvailable() {
        val id = run("查天气") {
            event(RunEvent.TaskStarted(clock, "查天气", "m1", tokenBudget = 500_000))
            event(RunEvent.ModelReply(clock + 1_000, 1, "", null, null, 100, 20, 0, 900, repliedAtMs = clock + 1_000))
            event(RunEvent.ModelCalls(clock + 2_000, calls = 3, unreported = 0,
                inputTokens = 300, outputTokens = 80, cachedTokens = 40, httpCalls = 3, httpUnreported = 0))
            event(RunEvent.TaskEnded(clock + 3_000, "succeeded", "晴"))
        }
        val header = ready(id).header
        assertEquals(500_000L, header.tokenBudget)
        assertEquals("主循环和旁路调用合计；缓存输入不再加一次", 380L, header.tokensUsed)

        val legacy = run("旧记录") {
            event(RunEvent.TaskStarted(clock, "旧记录", "m1", tokenBudget = 100_000))
            event(RunEvent.ModelReply(clock + 1_000, 1, "", null, null, 100, 20, 0, 900, repliedAtMs = clock + 1_000))
            event(RunEvent.TaskEnded(clock + 2_000, "succeeded", "好了"))
        }
        assertEquals("没有总计事件的旧记录回退主循环 Token", 120L, ready(legacy).header.tokensUsed)
        assertNull(ready(legacy).header.metrics!!.allInputTokens)
    }

    /** 步数用完后的收尾那一轮记作第上限加一步:显示用了几步时不算它,不会出现 4 / 3。 */
    @Test fun stepsUsedNeverExceedsTheLimit() {
        val id = run("查快递") {
            event(RunEvent.TaskStarted(clock, "查快递", "m1", stepLimit = 3))
            (1..4).forEach { step -> event(RunEvent.ModelReply(clock + step * 1_000L, step, "", null, null, 500, 10, 0, 900, repliedAtMs = clock + step * 1_000L)) }
            event(RunEvent.TaskEnded(clock + 5_000, "failed", "已达到 3 步上限"))
        }
        assertEquals(3, ready(id).header.stepsUsed)
        val early = run("看电量") {
            event(RunEvent.TaskStarted(clock, "看电量", "m1", stepLimit = 30))
            event(RunEvent.ModelReply(clock + 1_000, 1, "", null, null, 500, 10, 0, 900, repliedAtMs = clock + 1_000))
            event(RunEvent.ModelReply(clock + 2_000, 2, "85%", null, null, 500, 10, 0, 900, repliedAtMs = clock + 2_000))
            event(RunEvent.TaskEnded(clock + 3_000, "succeeded", "85%"))
        }
        assertEquals(2, ready(early).header.stepsUsed)
    }

    @Test fun groupsEventsIntoSteps() {
        val long = "先".repeat(100)
        val id = run("看电量") {
            event(RunEvent.TaskStarted(clock, "看电量", "m1"))
            event(RunEvent.Criteria(clock, listOf("看到电量百分比"), emptyList()))
            event(RunEvent.ModelReply(clock, 1, long, "phone_launch_app", """{"package":"com.android.settings"}""", 10, 1, null))
            event(RunEvent.ToolResult(clock, 1, "phone_launch_app", """{"ok":true}""", true, null))
            event(RunEvent.ModelReply(clock, 2, "看看", "phone_observe", "{}", 10, 1, null))
            event(RunEvent.ToolResult(clock, 2, "phone_observe", """{"ok":true,"elements":[]}""", null, 1))
            media(1, byteArrayOf(1, 2, 3))
            event(RunEvent.Media(clock, 1, 2, "com.android.settings"))
            event(RunEvent.ModelReply(clock, 3, "", "phone_tap", """{"ref":"@e3"}""", 10, 1, null))
            event(RunEvent.ToolResult(clock, 3, "phone_tap", """{"ok":true}""", false, null))
            event(RunEvent.ModelReply(clock, 4, "", "phone_tap", """{"ref":"@e9"}""", 10, 1, null))
            event(RunEvent.ToolResult(clock, 4, "phone_tap", """{"ok":false,"error":"ref_expired","message":"x"}""", null, null))
            clock += 65_000
            event(RunEvent.TaskEnded(clock, "succeeded", "85%"))
        }
        val ui = ready(id)
        assertEquals(listOf(1, 2, 3, 4), ui.steps.map { it.step })
        val (launch, look, tap, stale) = ui.steps
        assertEquals(long, launch.said)
        assertEquals("打开 App", launch.tool)
        assertEquals("com.android.settings", launch.detail)
        assertEquals("生效", launch.result)
        assertEquals("看屏幕", look.tool)
        assertEquals(1, look.screenshotId)
        assertTrue(store.screen(id, 1)!!.isFile)
        assertEquals("没变化", tap.result)
        assertEquals("出错:界面已经变了,要重新看屏幕", stale.result)
        assertNull(launch.screenshotId)

        val h = ui.header
        assertEquals("看电量", h.goal)
        assertEquals(listOf("看到电量百分比"), h.criteria)
        assertEquals("办成了", h.outcome)
        assertEquals("1 分 5 秒", h.duration)
        assertEquals(4, h.modelCalls)
        assertEquals("没有核查", h.verdict)
    }

    @Test fun rejectedLightReplyCountsAsCallButNotStep() {
        val id = run("看电量") {
            event(RunEvent.ModelReply(clock, 1, "", "phone_observe", "{}", 10, 1, null, model = "light", role = "light_rejected"))
            event(RunEvent.ModelReply(clock, 1, "", "phone_tap", """{"ref":"@e3"}""", 10, 1, null, model = "main", role = "main"))
            event(RunEvent.ToolResult(clock, 1, "phone_tap", """{"ok":true}""", true, null))
            event(RunEvent.TaskEnded(clock, "succeeded", "85%"))
        }
        val ui = ready(id)
        assertEquals(2, ui.header.modelCalls)
        // 改派掉的 observe 不进步骤:这一步显示的是主模型的 tap
        assertEquals(ToolLabels(knownTools()).label("phone_tap"), ui.steps.single().tool)
    }

    @Test fun stepTimingIncludesModelRetryAndToolWithoutCountingBatchChildrenTwice() {
        val id = run("连续操作") {
            event(RunEvent.ModelReply(clock, 1, "", "phone_observe", "{}", null, null, null,
                latencyMs = 1_200, role = "light_rejected"))
            event(RunEvent.ModelReply(clock, 1, "", "do_steps", "{}", null, null, null,
                latencyMs = 2_300, retryWaitMs = 500))
            event(RunEvent.ToolResult(clock, 1, "phone_tap", """{"ok":true}""", true, null,
                durationMs = 700, source = "batch"))
            event(RunEvent.ToolResult(clock, 1, "phone_type_text", """{"ok":true}""", true, null,
                durationMs = 900, source = "batch"))
            event(RunEvent.ToolResult(clock, 1, "do_steps", """{"ok":true}""", true, null,
                durationMs = 2_000, source = "model"))
        }
        val timing = ready(id).steps.single().timing
        assertEquals(4_000L, timing.modelMs)
        assertEquals(2_000L, timing.toolMs)
        assertEquals(500L, timing.retryMs)
        assertEquals(6_000L, timing.totalMs)
        assertEquals("约 6.0 秒 · 模型 4.0 秒 · 操作 2.0 秒 · 含重试 500 毫秒",
            RunTimelineViewModel.timingLabel(timing))
    }

    @Test fun replayTimingAndLegacyMissingTimingAreLabeledHonestly() {
        val replay = run("回放") {
            event(RunEvent.ToolResult(clock, 1, "phone_tap", """{"ok":true}""", true, null,
                durationMs = 850, source = "replay"))
        }
        assertEquals("操作 850 毫秒", RunTimelineViewModel.timingLabel(ready(replay).steps.single().timing))

        val legacy = run("旧记录") {
            event(RunEvent.ModelReply(clock, 1, "", "phone_tap", "{}", null, null, null))
            event(RunEvent.ToolResult(clock, 1, "phone_tap", """{"ok":true}""", true, null))
        }
        assertEquals("耗时未记录", RunTimelineViewModel.timingLabel(ready(legacy).steps.single().timing))
        assertNull(ready(legacy).steps.single().timing.totalMs)
    }

    @Test fun activitiesMergeReadsAndKeepActionsSeparate() {
        val id = run("查页面并操作") {
            event(RunEvent.ModelReply(clock, 1, "", "do_steps", "{}", 10, 1, null))
            repeat(3) { i ->
                event(RunEvent.Activity(clock, 1, "observe", "web_read_page", "example.com", "completed", listOf(i + 1), callId = "r$i"))
            }
            event(RunEvent.Activity(clock, 1, "act", "web_tap", "example.com", "done", callId = "a1"))
            event(RunEvent.Activity(clock, 1, "act", "web_tap", "example.com", "no_effect", callId = "a2"))
        }
        val rows = ready(id).steps.single().activities
        assertEquals(3, rows.size)
        assertEquals(3, rows[0].count)
        assertEquals(listOf(1, 2, 3), rows[0].obsIds)
        assertEquals(listOf("act", "act"), rows.drop(1).map { it.kind })
    }

    /** 外部 MCP 连接器调用的活动单独成行,标的是连接器配置的名字;普通的读照旧合并。 */
    @Test fun mcpCallShowsTheConnectorName() {
        val id = run("查笔记") {
            event(RunEvent.ModelReply(clock, 1, "", "mcp_demo__search", "{}", 10, 1, null))
            event(RunEvent.Activity(clock, 1, "observe", "web_read_page", "example.com", "completed", listOf(1), callId = "r1"))
            event(RunEvent.Activity(clock, 1, "act", "mcp_demo__search", null, "completed", callId = "m1", mcpConnector = "Team notes"))
        }
        val rows = ready(id).steps.single().activities
        assertEquals(2, rows.size)
        assertEquals("observe", rows[0].kind)
        assertEquals("mcp", rows[1].kind)
        assertEquals("已调用 Team notes MCP", rows[1].label)
    }

    @Test fun showsVerifierAndGuardTags() {
        // 和 AgentLoop 一样用守卫自己的序列化拼结果
        fun withGuard(vararg v: GuardVerdict) =
            JsonObject(mapOf("ok" to JsonPrimitive(true), "guard" to JsonArray(v.map { it.toJson() }))).toString()
        val warn = withGuard(GuardVerdict("no_progress", Severity.Warn, buildJsonObject { put("same", 3) }))
        val stop = withGuard(GuardVerdict("repeating_action", Severity.Stop, buildJsonObject { put("code", "x"); put("n", 3) }))
        // 键的顺序换了也认得
        val reordered = """{"ok":true,"guard":[{"evidence":{"a":1},"severity":"warn","code":"too_many_failures"},{"severity":"warn","code":"brand_new"}]}"""
        val id = run("发消息") {
            event(RunEvent.ModelReply(clock, 1, "", "phone_scroll", "{}", null, null, null))
            event(RunEvent.ToolResult(clock, 1, "phone_scroll", warn, false, null))
            event(RunEvent.ModelReply(clock, 2, "", "phone_scroll", "{}", null, null, null))
            // 截短的结果不再是合法 JSON,标签照样认得出
            event(RunEvent.ToolResult(clock, 2, "phone_scroll", RunEvent.clip(stop + " ".repeat(RunEvent.MAX_FIELD)), false, null))
            event(RunEvent.ModelReply(clock, 4, "", "phone_tap", "{}", null, null, null))
            event(RunEvent.ToolResult(clock, 4, "phone_tap", reordered, null, null))
            event(RunEvent.ModelReply(clock, 5, "", "phone_tap", "{}", null, null, null))
            event(RunEvent.ToolResult(clock, 5, "phone_tap", """{"ok":false,"error":"some_new_code"}""", null, null))
            event(RunEvent.ModelReply(clock, 6, "", "memory_save", "{}", null, null, null))
            event(RunEvent.ToolResult(clock, 6, "memory_save", """{"ok":false,"error":"content_not_from_user","message":"m","recoverable":false,"counts_as_failure":false}""", null, null))
            event(RunEvent.ModelReply(clock, 7, "", "skill_save", "{}", null, null, null))
            event(RunEvent.ToolResult(clock, 7, "skill_save", """{"ok":false,"error":"sensitive","message":"m","recoverable":false}""", null, null))
            event(RunEvent.ModelReply(clock, 3, "好了", "finish", """{"answer":"x"}""", null, null, null))
            event(RunEvent.Verifier(clock, "fail", "{}"))
            event(RunEvent.ToolResult(clock, 3, "finish", """{"ok":false,"error":"verification_failed"}""", null, null))
            event(RunEvent.TaskEnded(clock, "failed", "已安全停止"))
        }
        val ui = ready(id)
        assertEquals(listOf(Tag("守卫提醒:画面一直没变化", TagTone.Warn)), ui.steps[0].tags)
        assertEquals(listOf(Tag("守卫停止:同一个操作反复做", TagTone.Bad)), ui.steps[1].tags)
        assertEquals(listOf(Tag("核查没通过", TagTone.Bad)), ui.steps.single { it.step == 3 }.tags)
        val four = ui.steps.single { it.step == 4 }
        assertEquals(listOf(Tag("守卫提醒:操作连续失败", TagTone.Warn), Tag("守卫提醒", TagTone.Warn)), four.tags)
        assertEquals("出错了", ui.steps.single { it.step == 5 }.result)
        // 按规矩没收的不算出错:不带「出错」(界面按这个前缀把一步标成失败),说没收和原因;工具用界面上的名字
        val declined = ui.steps.single { it.step == 6 }
        assertEquals("不是你亲口说的,没有写入", declined.result)
        assertEquals("写入记忆", declined.tool)
        // 同一个错误码,结果里没写不计入失败的(存技能)照旧算出错
        assertEquals("出错了", ui.steps.single { it.step == 7 }.result)
        assertFalse(ui.toString().contains("brand_new") || ui.toString().contains("some_new_code"))
        assertEquals("核查没通过", ui.header.verdict)
        assertEquals("没有完成", ui.header.outcome)
    }

    @Test fun missingRunShowsExpired() {
        assertEquals(TimelineUi.Expired, RunTimelineViewModel(store, "20250101-000000-abcdef").load())
        assertEquals(TimelineUi.Expired, RunTimelineViewModel(store, "../../etc").load())
        val id = run("x") { event(RunEvent.TaskEnded(clock, "cancelled", "")) }
        tmp.root.resolve("files/runs/$id").deleteRecursively()
        assertEquals(TimelineUi.Expired, RunTimelineViewModel(store, id).load())
    }

    @Test fun maskedFieldsStayMasked() {
        val key = "sk-" + "a".repeat(30)
        val id = run("登录 $key") {
            val args = Redactor.args(Verb.TypeText, """{"ref":"@e2","text":"hunter2"}""", secretField = true)
            event(RunEvent.ModelReply(clock, 1, Redactor.text("我输入密码 $key"), "phone_type_text", args, null, null, null))
            event(RunEvent.ToolResult(clock, 1, "phone_type_text", """{"ok":true,"secret":true}""", true, null))
        }
        val ui = ready(id)
        val all = ui.toString()
        assertFalse(all.contains("hunter2"))
        assertFalse(all.contains(key))
        assertEquals(Redactor.MASK, ui.steps.single().detail)
        assertTrue(ui.steps.single().said.contains(Redactor.MASK))
        assertTrue(ui.header.goal.contains(Redactor.MASK))
        assertEquals("没有结束", ui.header.outcome)
    }
}
