package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS

class ContextCompressorTest {
    private var seq = 0
    private val pad = "x".repeat(400)
    private val head = listOf(
        Message(Role.User, listOf(Part.Text("帮我查天气")), Author.Mika),
        Message(Role.User, listOf(Part.Text(ContextBuilder.envelope(ContextKind.Memory, "记忆", "1. 喜欢摄氏度"))), Author.Mika),
    )

    private fun round(name: String, args: String = "{}", result: String = """{"ok":true,"changed":true,"pad":"$pad"}"""): List<Message> {
        val id = "c${++seq}"
        return listOf(
            Message(Role.Assistant, listOf(Part.ToolCall(id, name, args)), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult(id, result, isError = false)), Author.Mika),
        )
    }

    private fun taps(n: Int) = (1..n).flatMap { round("phone_tap", """{"target":"按钮$it"}""") }

    @Test fun modelOverrideReachesTheCallerAndMovesTheFirstCompaction() = runTest {
        val history = head + (1..40).flatMap { round("phone_tap", result = """{"pad":"${"x".repeat(1_360)}"}""") }
        assertTrue(uncalibrated(history) in 16_001..18_000)
        suspend fun sent(trigger: Int?): List<Message> {
            val transport = FakeModel(answerReply("ok"), contextTokens = 32_000)
            val model = object : ChatModel by transport {
                override val capabilities = transport.capabilities.copy(compactionTriggerTokens = trigger)
            }
            val caller = ModelCaller(model, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, RetryPolicy.NONE, {}, { 0 })
            caller.reset("")
            caller.ask(history, emptyList(), step = 1)
            return transport.requests.single()
        }
        assertEquals(history, sent(null))
        assertTrue(uncalibrated(sent(16_000)) < uncalibrated(history))
    }

    @Test fun overflowUsesTheTargetForTheSameModelOverride() {
        // 32K 窗口，自动目标 12K；手填 16K 触发时目标为 10K。
        val user = Message(Role.User, listOf(Part.Text("x".repeat(26_000))), Author.User)
        val history = listOf(user) + (1..20).flatMap {
            round("phone_tap", """{"target":"ok","note":"${"x".repeat(1_600)}"}""", "{}")
        }
        fun retrySize(trigger: Int?): Int {
            val compressor = ContextCompressor(TOOLS, triggerOverride = trigger)
            assertTrue(compressor.overflow(history, 32_000))
            return uncalibrated(compressor.current(history, 32_000))
        }
        assertTrue(retrySize(null) in 10_001..12_000)
        assertTrue(retrySize(16_000) <= 10_000)
    }
    private fun summary(ms: List<Message>) = ms.firstNotNullOfOrNull { m ->
        m.plainText().takeIf { it.startsWith(ContextCompressor.HEADER) }
    }
    private fun toolRounds(ms: List<Message>) = ms.count { m -> m.role == Role.Assistant && m.parts.any { it is Part.ToolCall } }

    @Test fun underBudgetUntouched() {
        val h = head + taps(20)
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = 128_000)
        // 没越过触发点:原样发,一条不裁
        assertEquals(h, c.messages)
        assertEquals(0, c.foldedSteps)
    }

    /** 轮数触发:大小远没到触发点(1M 窗口),但 40 轮 > keepRecent(12)+foldEvery(16)=28,照样折到最近 12 轮。 */
    @Test fun foldsByRoundCountEvenWhenSizeIsSmall() {
        val h = head + taps(40)
        val c = ContextCompressor(TOOLS, foldEvery = 16).compress(h, contextTokens = 1_000_000)
        assertTrue(c.rewritten)
        assertEquals(28, c.foldedSteps)
        assertEquals(12, toolRounds(c.messages))
        assertTrue(summary(c.messages)!!.contains("Step 28 phone_tap(按钮28) → applied"))
    }

    /** 轮数触发也是锯齿式的:两次折叠之间只追加,不每步都压;折叠数远少于步数。 */
    @Test fun roundCountFoldsInBatchesNotEveryStep() {
        val all = head + taps(120)
        fun h(n: Int) = all.take(head.size + 2 * n)
        val c = ContextCompressor(TOOLS, foldEvery = 16)
        val outs = (1..120).map { c.compress(h(it), contextTokens = 1_000_000) }
        val rewrites = outs.zipWithNext().withIndex().filter { (_, p) -> p.first.messages != p.second.messages.take(p.first.messages.size) }.map { it.index + 2 }
        val folds = outs.zipWithNext().withIndex().filter { (_, p) -> p.first.foldedSteps != p.second.foldedSteps }.map { it.index + 2 }
        assertEquals(folds, rewrites)
        assertTrue("$rewrites", rewrites.isNotEmpty() && rewrites.size <= 10)
        rewrites.zipWithNext().forEach { (a, b) -> assertTrue("$rewrites", b - a > 1) }
    }

    /** 默认不按轮数触发(只按大小),40 轮在 1M 窗口里原样发。 */
    @Test fun withoutFoldEveryRoundCountDoesNotTrigger() {
        val h = head + taps(40)
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = 1_000_000)
        assertEquals(h, c.messages)
        assertEquals(0, c.foldedSteps)
    }

    /** 触发点刚好低于整份请求的窗口:压一次就能回到目标以下,用最轻的一级(留最近 12 次往返)。 */
    private fun justOver(h: List<Message>) = windowFor(uncalibrated(h)) - 1

    @Test fun foldsOldestKeepsRecent12() {
        val h = head + taps(40)
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = justOver(h))
        assertEquals(28, c.foldedSteps)
        assertEquals(12, toolRounds(c.messages))
        // 任务原话与成功标准原样在最前,其后是摘要,再后是最近 12 次往返原样
        assertEquals(head, c.messages.take(2))
        assertTrue(summary(c.messages) == c.messages[2].plainText())
        assertEquals(ContextKind.Notice, ContextBuilder.kindOf(c.messages[2]))
        assertEquals(h.takeLast(24), c.messages.drop(3))
        val s = summary(c.messages)!!
        assertTrue(s, "Step 1 phone_tap(按钮1) → applied" in s && "Step 28 phone_tap(按钮28) → applied" in s && "Step 29" !in s)
    }

    @Test fun keepsTheAskUserRoundEvenWhenATaskProgressCallComesFirst() {
        // #714 lets a round carry a task_progress call ahead of the one action that
        // actually runs. The compressor must judge the round by that action, not
        // by the progress report, or the user's answer is folded away.
        val ask = listOf(
            Message(Role.Assistant, listOf(
                Part.ToolCall("p1", "task_progress", """{"updates":[]}"""),
                Part.ToolCall("a1", "ask_user", """{"question":"Continue?"}"""),
            ), Author.Mika),
            Message(Role.Tool, listOf(
                Part.ToolResult("p1", """{"ok":true}""", isError = false),
                Part.ToolResult("a1", """{"answer":"yes"}""", isError = false),
            ), Author.Mika),
        )
        val h = head + taps(30) + ask + taps(20)
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = justOver(h))
        assertTrue(
            "the ask_user round was folded",
            c.messages.any { m -> m.parts.any { it is Part.ToolCall && it.name == "ask_user" } },
        )
        assertTrue(
            "the user's answer was folded",
            c.messages.any { m -> m.parts.any { it is Part.ToolResult && it.callId == "a1" } },
        )
    }

    @Test fun appendOnlyBetweenCompactions() {
        val all = head + taps(120)
        fun h(n: Int) = all.take(head.size + 2 * n)
        val c = ContextCompressor(TOOLS)
        val ctx = 8000
        val outs = (1..120).map { c.compress(h(it), contextTokens = ctx) }
        val rewrites = outs.zipWithNext().withIndex().filter { (_, p) -> p.first.messages != p.second.messages.take(p.first.messages.size) }.map { it.index + 2 }
        val folds = outs.zipWithNext().withIndex().filter { (_, p) -> p.first.foldedSteps != p.second.foldedSteps }.map { it.index + 2 }
        // 每次改写都是一次压缩(折叠数变了),两次压缩之间只追加;压缩次数远少于步数
        assertEquals(folds, rewrites)
        assertTrue("$rewrites", rewrites.isNotEmpty() && rewrites.size <= 10)
        rewrites.zipWithNext().forEach { (a, b) -> assertTrue("$rewrites", b - a > 1) }
        // 发出的整份请求始终在可用窗口(扣掉输出预留)以内
        outs.forEach { assertTrue(uncalibrated(it.messages) <= ContextLimits.of(ctx).usable) }
    }

    @Test fun oversizedNewestResultIsShrunkToFitTheWindow() {
        val huge = round("run_command", """{"command":"cat big.log"}""",
            """{"ok":true,"exitCode":0,"output":"${"日志".repeat(40_000)}"}""")
        val h = head + taps(3) + huge
        val ctx = 16_000
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = ctx, fixedOverhead = 1000)
        assertTrue("${uncalibrated(c.messages, 1000)}", uncalibrated(c.messages, 1000) <= ContextLimits.of(ctx).usable)
        val last = c.messages.last().parts.filterIsInstance<Part.ToolResult>().single()
        assertTrue(last.content, HistoryPruner.OMITTED_READ in last.content && "\"exitCode\":0" in last.content)
        val calls = c.messages.flatMap { m -> m.parts.filterIsInstance<Part.ToolCall>().map { it.id } }
        val results = c.messages.flatMap { m -> m.parts.filterIsInstance<Part.ToolResult>().map { it.callId } }
        assertEquals(calls, results)
    }

    @Test fun downwardCalibrationCannotLetOneHugeScreenBypassCompaction() = runTest {
        val first = ModelReply(
            listOf(Part.ToolCall("open_settings", "phone_launch_app", """{"package":"com.android.settings"}""")),
            StopReason.ToolUse, Usage(inputTokens = 972, outputTokens = 20),
        )
        val transport = FakeModel(first, answerReply("done"), contextTokens = 32_768)
        val caller = ModelCaller(transport, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, RetryPolicy.NONE, {}, { 0 })
        caller.reset("")
        // 字符数不是 token 数:这份开局原文约 8K 字符;假服务端报 972 输入 token,旧逻辑会把增量估算压到下限 0.5。
        val opening = listOf(Message(Role.User, listOf(Part.Text("x".repeat(8_000))), Author.User))
        caller.ask(opening, emptyList(), step = 1)
        val reply = Message(Role.Assistant, first.parts, Author.Mika)
        caller.noteReply(reply, transport, first.usage)

        val elements = (1..400).joinToString(",") { i ->
            """{"ref":"@e$i","role":"TextView","name":"Setting item $i","center":{"x":500,"y":100},"bounds":[0,0,1080,120],"resourceId":"android:id/title"}"""
        }
        val nodes = (1..400).joinToString(",") { i ->
            """{"role":"TextView","ref":"@e$i","text":"Setting item $i","resourceId":"android:id/title","bounds":[0,0,1080,120]}"""
        }
        val screen = """{"ok":true,"snapshotId":"settings","elements":[$elements],"tree":[{"id":1,"type":"application","nodes":[$nodes]}]}"""
        assertTrue("test fixture chars=${screen.length}", screen.length > 90_000)
        assertTrue(
            "test fixture must reproduce the old trigger bypass",
            972 + 20 + UsageMeter.estimate(TokenEstimate.of(screen), 0.5) < ContextLimits.of(32_768).trigger,
        )
        val history = opening + reply + Message(Role.Tool, listOf(Part.ToolResult("open_settings", screen, false)), Author.Mika)
        caller.ask(history, emptyList(), step = 2)

        val sent = transport.requests[1]
        val result = sent.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().single()
        assertTrue("a single new screen must be capped before sending", HistoryPruner.OMITTED_READ in result.content)
        assertTrue("raw JSON must not be resent at ${screen.length} chars", "android:id/title" !in result.content)
        assertTrue(uncalibrated(sent) <= ContextLimits.of(32_768).usable)
    }

    @Test fun overheadHeavySmallWindowNeverExceedsTheWindow() {
        val all = head + taps(40)
        val ctx = 4000
        val overhead = 1800
        val c = ContextCompressor(TOOLS)
        // 固定开销几乎占满触发点:每次压缩后有回差,不会每步都压;发出的整份请求始终放得下
        var rewrites = 0
        for (n in 1..40) {
            val out = c.compress(all.take(head.size + 2 * n), contextTokens = ctx, fixedOverhead = overhead)
            if (out.rewritten) rewrites++
            assertTrue("$n: ${uncalibrated(out.messages, overhead)}", uncalibrated(out.messages, overhead) <= ctx)
        }
        assertTrue("$rewrites", rewrites in 1..20)
    }

    @Test fun foldedObservationsKeepScreenshotId() {
        val obs = round("phone_observe", "{}", """{"ok":true,"snapshotId":"s1","screenshotId":7,"elements":[],"pad":"$pad"}""")
        val dump = round("phone_list_elements", "{}", """{"ok":true,"snapshotId":"s2","screenshotId":8,"pad":"$pad"}""")
        val h = head + obs + dump + taps(20)
        val s = summary(ContextCompressor(TOOLS).compress(h, contextTokens = 1000).messages)!!
        assertTrue(s, "Step 1 phone_observe #7 → applied" in s && "Step 2 phone_list_elements #8 → applied" in s)
    }

    @Test fun pinnedMessagesKept() {
        val knowledge = Message(Role.User, listOf(Part.Text(ContextBuilder.envelope(ContextKind.AppKnowledge, "天气 App", "…"))), Author.Mika)
        val inputRound = round("ask_user", """{"prompt":"哪个城市?"}""", """{"ok":true}""") +
            Message(Role.User, listOf(Part.Text("北京")), Author.Mika)
        // 追问的回答也可能直接作为工具结果
        val inputAsResult = round("ask_user", """{"prompt":"哪天?"}""", """{"ok":true,"answer":"明天"}""")
        val confirmRound = round("request_confirmation", """{"action":"send"}""", """{"ok":true,"approved":true}""")
        val takeoverRound = round("hand_to_user", """{"reason":"login","message":"请登录"}""", """{"outcome":"complete","ok":true}""")
        val h = head + taps(2) + knowledge + inputRound + taps(2) + takeoverRound + inputAsResult + confirmRound + taps(20)
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = 1000)
        for (m in head + knowledge + inputRound + takeoverRound + inputAsResult + confirmRound) assertTrue("$m", m in c.messages)
        assertTrue(c.foldedSteps > 0)
        val summaries = c.messages.mapNotNull { m -> m.plainText().takeIf { it.startsWith(ContextCompressor.HEADER) } }
        assertTrue(summaries.flatMap { it.lines() }.none { "request_" in it || "hand_to_user" in it })
        // 保留下来的顺序不变
        val kept = c.messages.filter { it in h }
        assertEquals(h.filter { it in kept }, kept)
    }

    @Test fun domainKnowledgeAfterWebRoundStaysPinned() {
        val nav = round("web_navigate", """{"url":"https://shop.example.com/"}""",
            """{"ok":true,"surface":"web","tabId":"t1","url":"https://shop.example.com/","pad":"$pad"}""")
        val siteKnowledge = Message(Role.User, listOf(Part.Text(ContextBuilder.envelope(ContextKind.SiteKnowledge, "好店", "网页做法…"))), Author.Mika)
        val h = head + taps(2) + nav + siteKnowledge + taps(20)
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = 1000)
        assertTrue(c.foldedSteps > 0)
        assertTrue(siteKnowledge in c.messages)
        assertTrue("所在的往返被折叠了", nav.first() !in c.messages)
    }

    @Test fun pairingIntactAfterFold() {
        val skipped = Part.ToolCall("p2", "phone_tap", "{}")
        val parallel = listOf(
            Message(Role.Assistant, listOf(Part.ToolCall("p1", "phone_observe", "{}"), skipped), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("p1", """{"ok":true,"elements":[]}""", false),
                Part.ToolResult("p2", """{"ok":false,"error":"parallel_tool_call_skipped"}""", true)), Author.Mika),
            Message(Role.User, listOf(Part.Text("Current phone screenshot"), Part.Image("aaa")), Author.Mika),
        )
        val h = head + taps(3) + parallel + taps(3) + parallel.map { m ->
            Message(m.role, m.parts.map { p -> when (p) {
                is Part.ToolCall -> p.copy(id = p.id + "b"); is Part.ToolResult -> p.copy(callId = p.callId + "b"); else -> p } }, Author.Mika)
        } + taps(12)
        for (aggressive in listOf(false, true)) {
            val out = ContextCompressor(TOOLS).compress(h, contextTokens = 1000, aggressive = aggressive).messages
            val calls = out.flatMap { m -> m.parts.filterIsInstance<Part.ToolCall>().map { it.id } }
            val results = out.flatMap { m -> m.parts.filterIsInstance<Part.ToolResult>().map { it.callId } }
            assertEquals(calls.sorted(), results.sorted())
            out.forEachIndexed { i, m ->
                m.parts.filterIsInstance<Part.ToolResult>().forEach { r ->
                    val prev = out.subList(0, i).last { it.role == Role.Assistant }
                    assertTrue(prev.parts.any { it is Part.ToolCall && it.id == r.callId })
                }
            }
        }
    }

    @Test fun aggressiveKeeps6() {
        val h = head + taps(20)
        // 模型已说超长:不论估算多少都压
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = 1_000_000, aggressive = true)
        // 折 14 次,留最近 6 次
        assertEquals(14, c.foldedSteps)
        assertEquals(6, toolRounds(c.messages))
        assertEquals(h.takeLast(12), c.messages.takeLast(12))
    }

    @Test fun summaryLineFormat() {
        val h = head +
            round("phone_tap", """{"ref":"@e3","snapshotId":"s1","target":"设置"}""", """{"ok":true,"changed":true}""") +
            round("phone_scroll", """{"direction":"down"}""", """{"ok":true,"changed":false}""") +
            round("phone_launch_app", """{"package":"com.x"}""", """{"ok":false,"error":"app_not_found","message":"…"}""") +
            round("phone_back") +
            round("phone_type_text", """{"text":"${"天气".repeat(30)}"}""", """{"ok":true}""") +
            listOf(Message(Role.Assistant, listOf(Part.Text("我想想")), Author.Mika), Message(Role.User, listOf(Part.Text("nudge")), Author.Mika)) +
            round("phone_observe", "{}", """{"ok":true,"elements":[]}""") +
            taps(12)
        val s = ContextCompressor(TOOLS).compress(h, contextTokens = 100).let { summary(it.messages)!! }
        assertEquals(
            listOf(
                ContextCompressor.HEADER,
                ContextCompressor.SUMMARY_REMINDER,
                "Step 1 phone_tap(设置) → applied",
                "Step 2 phone_scroll(down) → no change",
                "Step 3 phone_launch_app(com.x) → app_not_found",
                "Step 4 phone_back → applied",
                "Step 5 phone_type_text(${"天气".repeat(10)}…) → applied",
                "Step 7 phone_observe → applied",
            ),
            s.lines().take(8),
        )
    }

    @Test fun oneSummaryPerContiguousRun() {
        val takeover = round("hand_to_user", """{"reason":"login"}""", """{"outcome":"complete","ok":true}""")
        val h = head + taps(3) + takeover + taps(2) + taps(12)
        // 模型报过超长:留最近 6 次往返
        val out = ContextCompressor(TOOLS).compress(h, contextTokens = 1_000_000, aggressive = true).messages
        val texts = out.map { it.plainText() }
        // 开头 2 条,第一段摘要,保留的接管往返,第二段摘要,最近 6 次往返
        assertTrue(texts[2]!!.startsWith(ContextCompressor.HEADER) && "Step 3" in texts[2]!! && "Step 5" !in texts[2]!!)
        assertEquals(takeover, out.subList(3, 5))
        assertTrue(texts[5]!!.startsWith(ContextCompressor.HEADER))
        assertEquals(listOf(ContextCompressor.HEADER, ContextCompressor.SUMMARY_REMINDER) + listOf("Step 5 phone_tap(按钮1) → applied", "Step 6 phone_tap(按钮2) → applied") +
            (7..12).map { "Step $it phone_tap(按钮${it - 6}) → applied" },
            texts[5]!!.lines())
        assertEquals(2 + 1 + 2 + 1 + 12, out.size)
    }

    @Test fun resultOutcomes() {
        fun one(result: Part.ToolResult?): String {
            val id = "r${++seq}"
            val r = listOf(Message(Role.Assistant, listOf(Part.ToolCall(id, "phone_tap", "{}")), Author.Mika)) +
                listOfNotNull(result?.let { Message(Role.Tool, listOf(it.copy(callId = id)), Author.Mika) })
            val h = head + r + taps(12)
            return summary(ContextCompressor(TOOLS).compress(h, contextTokens = 100).messages)!!.lines()[2].substringAfter(" → ")
        }
        assertEquals("no result", one(null))
        assertEquals("failed", one(Part.ToolResult("", """{"message":"boom"}""", isError = true)))
        assertEquals("ref_expired", one(Part.ToolResult("", """{"error":"ref_expired"}""", isError = true)))
        assertEquals("timeout", one(Part.ToolResult("", "timeout", isError = true)))
        assertEquals("failed", one(Part.ToolResult("", "出了点问题", isError = true)))
        assertEquals("applied", one(Part.ToolResult("", "done", isError = false)))
    }

    @Test fun fixedOverheadCountsAgainstBudget() {
        val h = head + taps(20)
        val context = windowFor(uncalibrated(h))
        assertEquals(0, ContextCompressor(TOOLS).compress(h, context).foldedSteps)
        assertTrue(ContextCompressor(TOOLS).compress(h, context, fixedOverhead = 100).foldedSteps > 0)
    }

    @Test fun keepsLatestFullReadAndDiffChain() {
        val full = round("phone_observe", "{}", """{"ok":true,"snapshotId":"s1","elements":[{"ref":"@e1"}],"pad":"$pad"}""")
        val diffs = (2..3).flatMap { round("phone_back", "{}", """{"ok":true,"screen":{"snapshotId":"s$it","full":false,"added":[]},"pad":"$pad"}""") }
        val h = head + taps(3) + full + diffs + taps(20)
        val out = ContextCompressor(TOOLS,diffScreens = true).compress(h, contextTokens = 1000).messages
        val kept = out.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().map { it.content }
        assertTrue(kept.any { "\"s1\"" in it })
        // 之前的普通往返照常折叠
        assertTrue(summary(out)!!.contains("tap(按钮1)"))
        // 狠压缩时不保留
        val hard = ContextCompressor(TOOLS,diffScreens = true).compress(h, contextTokens = 1000, aggressive = true).messages
        assertTrue(hard.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().none { "\"s1\"" in it.content })
    }

    // #263:只折到留 12 轮那一档时,留下的往返里的屏幕自己看得懂(第一份是全量 s9),边界前那份全量 s1 照常折叠;
    // s9 是接在 s3 后面的差异时 s1 照旧留着(沿用原来的做法;s3 已换成占位,s1 并不足以看懂 s9)
    @Test fun dropsEarlierFullReadWhenKeptScreensAreSelfContained() {
        val (folded, kept) = compressWithLatest("""{"snapshotId":"s9","full":true,"elements":[{"ref":"@e1"}]}""")
        assertTrue(kept.any { "\"s9\"" in it } && kept.none { "\"s1\"" in it })
        val (foldedWithBase, keptWithBase) = compressWithLatest("""{"snapshotId":"s9","base":"s3","full":false,"added":[]}""")
        assertTrue(keptWithBase.any { "\"s9\"" in it } && keptWithBase.any { "\"s1\"" in it })
        // 两次都停在留 12 轮那一档:前 30 次点按、两次差异和(第一次)s1 折掉
        assertEquals(listOf(38, 37), listOf(folded, foldedWithBase))
    }

    @Test fun keepsTableObservationAsBaseForLaterDiff() {
        val (_, kept) = compressWithLatest("""{"snapshotId":"s9","base":"s1","full":false,"added":[]}""",
            rows = """"elementTables":[{"columns":["ref"],"rows":[["@e1"]]}]""")
        assertTrue(kept.any { "elementTables" in it && "\"s1\"" in it })
    }

    // 手机和网页各有各的差异链:留下的往返里先有网页的全量,手机的差异仍接在边界前手机那份全量后面,它不能折
    @Test fun keepsPhoneBaseWhenKeptRoundsStartWithAWebFullScreen() {
        val web = round("web_interact", "{}", """{"ok":true,"screen":{"snapshotId":"w1","full":true,"elements":[{"ref":"@w1"}]},"pad":"$pad"}""")
        val (folded, kept) = compressWithLatest("""{"snapshotId":"p2","base":"s3","full":false,"added":[]}""", before = web)
        assertTrue(kept.any { "\"w1\"" in it } && kept.any { "\"s1\"" in it })
        assertEquals(37, folded)
    }

    /** 边界前:30 次点按、全量 s1、两次差异、5 次点按;留下的 12 次:[before]、带 [screen] 的一次返回、其余点按。返回折了几步和留下的工具结果。 */
    private fun compressWithLatest(screen: String, before: List<Message> = emptyList(), rows: String = """"elements":[{"ref":"@e1"}]"""): Pair<Int, List<String>> {
        val full = round("phone_observe", "{}", """{"ok":true,"snapshotId":"s1",$rows,"pad":"$pad"}""")
        val diffs = (2..3).flatMap { round("phone_back", "{}", """{"ok":true,"screen":{"snapshotId":"s$it","base":"s${it - 1}","full":false,"added":[]},"pad":"$pad"}""") }
        val latest = round("phone_back", "{}", """{"ok":true,"screen":$screen,"pad":"$pad"}""")
        val h = head + taps(30) + full + diffs + taps(5) + before + latest + taps(11 - before.size / 2)
        val c = ContextCompressor(TOOLS, diffScreens = true).compress(h, contextTokens = 10_000)
        return c.foldedSteps to c.messages.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().map { it.content }
    }
}
