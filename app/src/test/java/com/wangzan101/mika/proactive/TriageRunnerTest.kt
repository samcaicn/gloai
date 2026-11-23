package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.proactive.NoticeInbox
import com.wangzan101.mika.core.proactive.NoticeRecord
import com.wangzan101.mika.core.proactive.TargetedModel
import com.wangzan101.mika.core.proactive.TriageConsent
import com.wangzan101.mika.core.proactive.TriageDecision
import com.wangzan101.mika.core.proactive.TriageMark
import com.wangzan101.mika.core.proactive.TriagePlanner
import com.wangzan101.mika.core.proactive.TriageTarget
import com.wangzan101.mika.core.model.usage.ProactiveBudget
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TriageRunnerTest {
    /** 每次调用都等测试放行([release]),或直接按 [reply] 回。 */
    private class Fake(var reply: () -> String = { "" }) : ChatModel {
        override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
        val calls = mutableListOf<List<Message>>()
        val tools = mutableListOf<List<ToolSpec>>()
        var gate: CompletableDeferred<Unit>? = null
        var cancelled = 0
        /** 设了就每次调用都抛它(连不上模型之类)。 */
        var error: Exception? = null
        /** 设了就在下一次调用里不理取消:等它放行后抛「预算用完」(只用一次)。 */
        var late: CompletableDeferred<Unit>? = null
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            calls += messages
            this.tools += tools
            error?.let { throw it }
            late?.let { d ->
                late = null
                withContext(NonCancellable) { d.await() }
                throw ModelError.ProactiveBudgetExceeded(1, 1)
            }
            try {
                gate?.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled++
                throw e
            }
            return ModelReply(listOf(Part.Text(reply())), StopReason.EndTurn, null)
        }
        fun packages() = calls.map { m -> Regex("App package: (\\S+)").find((m.single().parts.single() as Part.Text).text)!!.groupValues[1] }
    }

    private val target = TriageTarget("p1", "Provider", "m1")

    private inner class Env(scope: TestScope) {
        val inbox = NoticeInbox(clock = { scope.testScheduler.currentTime }, zone = { ZoneOffset.UTC })
        val locked = MutableStateFlow(false)
        val busy = MutableStateFlow(false)
        var on = true
        var consent: TriageConsent? = TriageConsent.of(target)
        var effective: TriageTarget? = target
        /** 建模型那一次读到的目标(null 时同 [effective]):模拟两次读配置之间被改了。 */
        var built: TriageTarget? = null
        val model = Fake()
        var tokens = 0L
        var calls = 0L
        var limit = ProactiveBudget.DEFAULT_DAILY_LIMIT
        var models = 0
        var modelError: Exception? = null
        /** 读「现在配的是哪个模型」这一步出错(配置一时读不出来)。 */
        var targetError: Exception? = null
        /** 读锁屏状态这一步出错。 */
        var lockReadFails = false
        val runner = TriageRunner(
            inbox,
            TriageHooks(
                target = { targetError?.let { throw it }; effective },
                model = { call ->
                    models++
                    assertEquals("判定建模型时说的是判定", com.wangzan101.mika.core.proactive.ProactiveCall.Triage, call)
                    modelError?.let { throw it }
                    (built ?: effective)?.let { TargetedModel(it, model) }
                },
                tokensToday = { tokens }, callsToday = { calls },
                usageVersion = { MutableStateFlow(0L) }, busy = busy,
            ),
            on = { on }, consent = { consent }, budgetLimit = { limit },
            language = { "English" }, clock = { scope.testScheduler.currentTime },
            planner = TriagePlanner(zone = { ZoneOffset.UTC }),
        )

        private var watching: kotlinx.coroutines.Job? = null
        private val scope0 = scope

        /** 停下看守再开始(开关关了又开、回到前台)。 */
        fun restart() {
            watching?.cancel()
            scope0.runCurrent()
            watching = scope0.backgroundScope.launch { runner.watch(locked) { if (lockReadFails) throw IllegalStateException("lock state unavailable"); locked.value } }
            scope0.runCurrent()
        }

        init {
            restart()
        }

        fun post(key: String, pkg: String = "com.chat", at: Long = 1) {
            inbox.add(NoticeRecord(key, pkg, at, "title $key", "text $key"))
        }
    }

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun ok(vararg ids: String) =
        """{"items":[${ids.joinToString(",") { """{"id":"$it","decision":"remember","reason":"r"}""" }}]}"""

    @Test fun batchIsSentAfterTheWindowWithNoTools() = runTest {
        val e = Env(this)
        e.model.reply = { ok("n1", "n2") }
        e.post("a"); e.post("b", at = 2)
        runCurrent()
        assertEquals(TriageStatus.Waiting, e.runner.state.value.status)
        assertTrue(e.runner.timerActive)
        wait(TriagePlanner.WINDOW_MS - 1)
        assertTrue(e.model.calls.isEmpty())
        wait(1)
        assertEquals(1, e.model.calls.size)
        assertTrue(e.model.tools.single().isEmpty())
        val marks = e.runner.state.value.marks
        assertEquals(TriageDecision.Remember, (marks.getValue("a") as TriageMark.Done).verdict.decision)
        assertEquals(TriageStatus.Idle, e.runner.state.value.status)
        assertFalse(e.runner.timerActive)
        // 没新东西就不再发
        wait(3_600_000)
        assertEquals(1, e.model.calls.size)
    }

    @Test fun lockedHasNoTimerAndSendsNothing() = runTest {
        val e = Env(this)
        e.locked.value = true
        runCurrent()
        e.post("a")
        runCurrent()
        assertEquals(TriageStatus.Locked, e.runner.state.value.status)
        assertFalse(e.runner.timerActive)
        wait(24 * 3_600_000L)
        assertTrue(e.model.calls.isEmpty())
        assertEquals(0, e.models)
        // 解锁后才发(早已过了凑批的时间,马上发)
        e.locked.value = false
        runCurrent()
        assertEquals(1, e.model.calls.size)
    }

    @Test fun lockCancelsTheTimer() = runTest {
        val e = Env(this)
        e.post("a")
        runCurrent()
        assertTrue(e.runner.timerActive)
        e.locked.value = true
        runCurrent()
        assertFalse("一锁屏就取消计时", e.runner.timerActive)
        wait(TriagePlanner.WINDOW_MS * 10)
        assertTrue(e.model.calls.isEmpty())
    }

    @Test fun lockDuringRequestCancelsItAndKeepsItemsPending() = runTest {
        val e = Env(this)
        e.model.gate = CompletableDeferred()
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        assertTrue(e.runner.requestActive)
        assertEquals(TriageStatus.Sending, e.runner.state.value.status)
        e.locked.value = true
        runCurrent()
        assertEquals(1, e.model.cancelled)
        assertFalse(e.runner.requestActive)
        val s = e.runner.state.value
        assertEquals(TriageStatus.Locked, s.status)
        assertEquals("照旧待判定,不算失败", 1, s.counts.pending)
        assertEquals(0, s.counts.failed)
        assertNull(s.marks["a"])
        // 解锁后再发一次
        e.model.gate = null
        e.model.reply = { ok("n1") }
        e.locked.value = false
        runCurrent()
        assertEquals(2, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.judged)
    }

    // 锁屏取消了请求,模型却不理取消、晚些时候才回来;这期间解锁后新的一批已经在发:晚回来的那次什么都不能动
    @Test fun staleRequestReturningLateDoesNotTouchTheNewerBatch() = runTest {
        val e = Env(this)
        e.model.reply = { ok("n1") }
        val late = CompletableDeferred<Unit>()
        e.model.late = late
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertTrue(e.runner.requestActive)
        e.locked.value = true
        runCurrent()
        assertFalse(e.runner.requestActive)
        e.model.gate = CompletableDeferred()
        e.locked.value = false
        runCurrent()
        assertEquals(2, e.model.calls.size)
        assertTrue(e.runner.requestActive)
        // 第一次请求这时才回来(按「预算用完」)
        late.complete(Unit)
        runCurrent()
        assertTrue("新的一批还在发", e.runner.requestActive)
        assertEquals(TriageStatus.Sending, e.runner.state.value.status)
        assertEquals(1, e.runner.state.value.counts.sending)
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertEquals(TriageDecision.Remember, (e.runner.state.value.marks.getValue("a") as TriageMark.Done).verdict.decision)
        assertEquals("晚回来的「预算用完」不算数", TriageStatus.Idle, e.runner.state.value.status)
    }

    @Test fun busyWaitsWithoutPollingThenSendsWhenTheTaskEnds() = runTest {
        val e = Env(this)
        e.busy.value = true
        e.post("a")
        wait(TriagePlanner.WINDOW_MS * 5)
        assertTrue(e.model.calls.isEmpty())
        assertEquals(TriageStatus.Busy, e.runner.state.value.status)
        assertFalse("任务在跑时不计时、不轮询", e.runner.timerActive)
        e.busy.value = false
        runCurrent()
        assertEquals(1, e.model.calls.size)
    }

    @Test fun failedBatchIsNotRetriedUntilAsked() = runTest {
        val e = Env(this)
        e.model.reply = { "sorry, I cannot" }
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.failed)
        wait(24 * 3_600_000L)
        assertEquals("不自动重试", 1, e.model.calls.size)
        e.runner.retryFailed()
        runCurrent()
        assertEquals("点一次只多试一次", 2, e.model.calls.size)
        wait(24 * 3_600_000L)
        assertEquals(2, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.failed)
    }

    @Test fun budgetExceededStopsQuietly() = runTest {
        val e = Env(this)
        e.post("a")
        e.model.reply = { throw ModelError.ProactiveBudgetExceeded(10, 10) }
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        val s = e.runner.state.value
        assertEquals("请求时被拒、账本看不出哪项:按 Token 算", TriageStatus.TokensUsedUp, s.status)
        assertEquals(1, s.counts.pending)
        assertEquals(0, s.counts.failed)
        e.post("b", at = 5)
        wait(TriagePlanner.WINDOW_MS * 3)
        assertEquals("当天不再发", 1, e.model.calls.size)
    }

    @Test fun ledgerBudgetUsedUpSendsNothing() = runTest {
        val e = Env(this)
        e.calls = ProactiveBudget.callLimit(e.limit)
        e.post("a")
        wait(TriagePlanner.WINDOW_MS * 3)
        assertEquals(0, e.models)
        assertEquals(TriageStatus.CallsUsedUp, e.runner.state.value.status)
    }

    @Test fun consentMismatchStopsTriage() = runTest {
        val e = Env(this)
        e.effective = target.copy(model = "m2")
        e.post("a")
        wait(TriagePlanner.WINDOW_MS * 3)
        assertEquals(TriageStatus.NeedsConsent, e.runner.state.value.status)
        assertEquals(0, e.models)
        // 重新确认后才发
        e.consent = TriageConsent.of(e.effective!!)
        e.runner.poke()
        runCurrent()
        assertEquals(1, e.model.calls.size)
    }

    // 门口查同意时是同意过的模型,建模型时配置已被改成别的:不发,照旧待判定,等用户重新确认
    @Test fun targetChangedBetweenTheGateAndTheBuildSendsNothing() = runTest {
        val e = Env(this)
        e.model.reply = { ok("n1") }
        e.built = target.copy(model = "m2")
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.models)
        assertTrue("文字没发出去", e.model.calls.isEmpty())
        assertFalse(e.runner.requestActive)
        assertEquals(TriageStatus.NeedsConsent, e.runner.state.value.status)
        assertEquals(1, e.runner.state.value.counts.pending)
        assertTrue(e.runner.state.value.marks.isEmpty())
        // 配置回到同意过的那个:照常发
        e.built = null
        e.runner.poke()
        runCurrent()
        assertEquals(1, e.model.calls.size)
        assertTrue(e.runner.state.value.marks.getValue("a") is TriageMark.Done)
    }

    @Test fun consentChangedDuringRequestDropsTheResult() = runTest {
        val e = Env(this)
        e.model.gate = CompletableDeferred()
        e.model.reply = { ok("n1") }
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        e.effective = target.copy(providerId = "p2")
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertNull("换了服务商:回来的结果不记", e.runner.state.value.marks["a"])
        assertEquals(TriageStatus.NeedsConsent, e.runner.state.value.status)
    }

    @Test fun noModelSendsNothing() = runTest {
        val e = Env(this)
        e.effective = null
        e.post("a")
        wait(TriagePlanner.WINDOW_MS * 3)
        assertEquals(TriageStatus.NoModel, e.runner.state.value.status)
        assertEquals(0, e.models)
    }

    @Test fun switchedOffSendsNothing() = runTest {
        val e = Env(this)
        e.on = false
        e.runner.poke()
        e.post("a")
        wait(TriagePlanner.WINDOW_MS * 3)
        assertEquals(TriageStatus.Off, e.runner.state.value.status)
        assertEquals(0, e.models)
    }

    @Test fun appsNeverShareARequestAndOnlyOneIsInFlight() = runTest {
        val e = Env(this)
        e.model.gate = CompletableDeferred()
        e.model.reply = { ok("n1", "n2") }
        e.post("a1", "com.a", 1); e.post("b1", "com.b", 2); e.post("a2", "com.a", 3)
        wait(TriagePlanner.WINDOW_MS)
        assertEquals("一次只发一批", 1, e.model.calls.size)
        e.model.gate!!.complete(Unit)
        e.model.gate = null
        runCurrent()
        assertEquals(2, e.model.calls.size)
        assertEquals(listOf("com.a", "com.b"), e.model.packages())
        assertEquals(3, e.runner.state.value.counts.judged)
    }

    // 建模型出错(比如解不开 Key):这一批算失败,看守不被带垮,之后的照常处理
    @Test fun modelConstructionErrorFailsTheBatchAndKeepsRunning() = runTest {
        val e = Env(this)
        e.modelError = IllegalStateException("key")
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.runner.state.value.counts.failed)
        e.modelError = null
        e.model.reply = { ok("n1") }
        e.post("b", at = 5)
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.judged)
    }

    @Test fun eightPendingAreSentWithoutWaiting() = runTest {
        val e = Env(this)
        repeat(8) { e.post("k$it", at = it.toLong()) }
        runCurrent()
        assertEquals(1, e.model.calls.size)
    }

    @Test fun updateDuringTheCooldownSendsNothingUntilItEnds() = runTest {
        val e = Env(this)
        e.model.reply = { ok("n1") }
        e.post("a", at = 1)
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.judged)
        val judgedAt = currentTime
        // 聊天接着说:同一个键每 20 秒重发一次,冷却(2 分钟)期间一个请求都不发
        e.post("a", at = 50)
        runCurrent()
        assertEquals(1, e.runner.state.value.counts.cooling)
        assertEquals(TriageStatus.Waiting, e.runner.state.value.status)
        assertTrue("冷却到点要靠计时(解锁时)", e.runner.timerActive)
        e.post("a", at = 60)
        wait(60_000)
        e.post("a", at = 70)
        wait(judgedAt + 120_000 - currentTime - 1)
        assertEquals(1, e.model.calls.size)
        assertNull("正在显示的是旧结果", e.runner.state.value.marks["a"]?.takeIf { it.postedAtMs == 70L })
        // 到点后照常凑批,一次请求带上攒下的
        wait(1 + TriagePlanner.WINDOW_MS)
        assertEquals(2, e.model.calls.size)
        assertEquals(0, e.runner.state.value.counts.cooling)
        assertEquals(1, e.runner.state.value.counts.judged)
        wait(3_600_000)
        assertEquals("没新内容就不再发", 2, e.model.calls.size)
    }

    @Test fun chattyAppCostsAFewRequestsNotOneEveryTwentySeconds() = runTest {
        val e = Env(this)
        e.model.reply = { ok("n1") }
        // 十分钟里每 5 秒一句;第一批发出去后让请求拖 3 秒,期间正好来一句
        var at = 1L
        e.post("a", at = at)
        wait(TriagePlanner.WINDOW_MS - 1)
        e.model.gate = CompletableDeferred()
        wait(1)
        assertEquals(1, e.model.calls.size)
        e.post("a", at = ++at)
        runCurrent()
        e.model.gate!!.complete(Unit)
        e.model.gate = null
        runCurrent()
        assertEquals("请求期间的更新也进冷却", 1, e.runner.state.value.counts.cooling)
        val end = currentTime + 600_000
        while (currentTime < end) {
            e.post("a", at = ++at)
            wait(5_000)
        }
        // 判定 0s+20s;冷却 2 分钟+凑批 20 秒、5 分钟+20 秒,第三次在 ~8 分钟时,之后冷却 15 分钟
        assertEquals(3, e.model.calls.size)
    }

    @Test fun lockedCooldownHasNoTimerAndIsSentAfterUnlock() = runTest {
        val e = Env(this)
        e.model.reply = { ok("n1") }
        e.post("a", at = 1)
        wait(TriagePlanner.WINDOW_MS)
        e.post("a", at = 2)
        runCurrent()
        assertTrue(e.runner.timerActive)
        e.locked.value = true
        runCurrent()
        assertFalse("锁着不为冷却计时", e.runner.timerActive)
        wait(3_600_000)
        assertEquals(1, e.model.calls.size)
        e.locked.value = false
        runCurrent()
        assertEquals("解锁时早已到点:马上发", 2, e.model.calls.size)
    }

    @Test fun updateAfterAFailureWaitsForTheHold() = runTest {
        val e = Env(this)
        e.model.reply = { "sorry, I cannot" }
        e.post("a", at = 1)
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        val failedAt = currentTime
        // 模型坏着,聊天每 20 秒一句:停着的 2 分钟里一个请求都不发
        var at = 1L
        while (currentTime < failedAt + 120_000 - 20_000) {
            e.post("a", at = ++at)
            wait(20_000)
        }
        wait(failedAt + 120_000 - currentTime - 1)
        assertEquals(1, e.model.calls.size)
        assertEquals("停着的算失败", 1, e.runner.state.value.counts.failed)
        assertEquals(0, e.runner.state.value.counts.pending)
        // 到点后凑批,只发一次
        wait(1 + TriagePlanner.WINDOW_MS)
        assertEquals(2, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.failed)
        // 再失败:没有新内容,不再自动发
        wait(3_600_000)
        assertEquals(2, e.model.calls.size)
    }

    @Test fun lockedFailureHoldHasNoTimer() = runTest {
        val e = Env(this)
        e.model.reply = { "sorry, I cannot" }
        e.post("a", at = 1)
        wait(TriagePlanner.WINDOW_MS)
        e.post("a", at = 2)
        runCurrent()
        assertTrue(e.runner.timerActive)
        e.locked.value = true
        runCurrent()
        assertFalse(e.runner.timerActive)
        wait(3_600_000)
        assertEquals(1, e.model.calls.size)
        e.model.reply = { ok("n1") }
        e.locked.value = false
        runCurrent()
        assertEquals("解锁时早已停完:马上发", 2, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.judged)
    }

    @Test fun manualRetryIgnoresTheFailureHold() = runTest {
        val e = Env(this)
        e.model.reply = { "sorry, I cannot" }
        e.post("a", at = 1)
        wait(TriagePlanner.WINDOW_MS)
        e.post("a", at = 2)
        runCurrent()
        e.model.reply = { ok("n1") }
        e.runner.retryFailed()
        runCurrent()
        assertEquals(2, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.judged)
    }

    @Test fun triageStopsAtItsShareOfTokensAndResumesWhenTheLimitIsRaised() = runTest {
        val e = Env(this)
        e.model.reply = { ok("n1") }
        e.tokens = ProactiveBudget.triageShare(e.limit) - 1
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals("八成之前照常发", 1, e.model.calls.size)
        e.tokens = ProactiveBudget.triageShare(e.limit)
        e.post("b", at = 5)
        wait(TriagePlanner.WINDOW_MS * 3)
        assertEquals("到判定那份(整份还剩两成)就停", 1, e.model.calls.size)
        assertEquals(TriageStatus.TokensUsedUp, e.runner.state.value.status)
        assertFalse(e.runner.timerActive)
        // 用户调高上限:接着判
        e.limit = 2 * ProactiveBudget.DEFAULT_DAILY_LIMIT
        e.runner.budgetChanged()
        runCurrent()
        assertEquals(2, e.model.calls.size)
    }

    @Test fun triageStopsAtItsShareOfRequests() = runTest {
        val e = Env(this)
        e.calls = ProactiveBudget.triageShare(ProactiveBudget.callLimit(e.limit))
        assertEquals(640L, e.calls)
        e.post("a")
        wait(TriagePlanner.WINDOW_MS * 3)
        assertEquals(0, e.models)
        assertEquals(TriageStatus.CallsUsedUp, e.runner.state.value.status)
        e.calls--
        e.runner.poke()
        runCurrent()
        assertEquals(1, e.model.calls.size)
    }

    // 看一遍时读配置出了一次错:在发的请求不动(它已经算过钱,取消了回头还得再发一遍),只是这一回不发新的;
    // 配置读得出来之后照常
    @Test fun aFailedConfigurationReadLeavesTheRequestInFlightAlone() = runTest {
        val e = Env(this)
        e.model.gate = CompletableDeferred()
        e.model.reply = { ok("n1") }
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertTrue(e.runner.requestActive)
        e.targetError = IllegalStateException("temporary configuration read failure")
        e.runner.poke()
        e.post("b", pkg = "com.other", at = 2)
        wait(TriagePlanner.WINDOW_MS)
        assertTrue(e.runner.requestActive)
        assertEquals(0, e.model.cancelled)
        assertEquals(1, e.model.calls.size)
        assertEquals(TriageStatus.Sending, e.runner.state.value.status)
        e.targetError = null
        e.model.gate!!.complete(Unit)
        e.model.gate = null
        runCurrent()
        wait(TriagePlanner.WINDOW_MS)
        // 原来那一个没有重发;出错期间来的那条随后照常判
        assertEquals(listOf("com.chat", "com.other"), e.model.packages())
        assertEquals(0, e.runner.state.value.counts.failed)
    }

    // 锁屏状态读不出来的时候不能留着请求:锁屏不发是硬规矩,不知道锁没锁就当锁了
    @Test fun aFailedLockReadStopsTheRequest() = runTest {
        val e = Env(this)
        e.model.gate = CompletableDeferred()
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertTrue(e.runner.requestActive)
        e.lockReadFails = true
        e.runner.poke()
        runCurrent()
        assertFalse(e.runner.requestActive)
        assertEquals(1, e.model.cancelled)
    }

    // 连不上模型时整个停一阵:只按会话各自冷却的话,每来一个新会话都会再连一次,来得多时就是不停地重连。
    // 那一批不算失败,照旧待判定;连续连不上停得越久;连上了照常判
    @Test fun anUnreachableModelPausesTriageAsAWhole() = runTest {
        val e = Env(this)
        e.model.error = ModelError.Network()
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        assertEquals(0, e.runner.state.value.counts.failed)
        assertEquals(1, e.runner.state.value.counts.pending)
        // 又来了四个新会话(别的 App 的也一样):都不再连
        e.post("b", at = 2); e.post("c", at = 3); e.post("d", pkg = "com.other", at = 4); e.post("e", pkg = "com.mail", at = 5)
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        assertEquals(TriageStatus.Unreachable, e.runner.state.value.status)
        // 停着的时候锁屏:到点再看的那个计时取消(息屏不留计时),解锁后接着停
        assertTrue(e.runner.timerActive)
        e.locked.value = true
        runCurrent()
        assertFalse(e.runner.timerActive)
        e.locked.value = false
        runCurrent()
        assertEquals(TriageStatus.Unreachable, e.runner.state.value.status)
        assertEquals(1, e.model.calls.size)
        // 停够第一级:再连一次,还是连不上,这回停第二级那么久
        wait(TriagePlanner.BACKOFF_MS[0])
        assertEquals(2, e.model.calls.size)
        wait(TriagePlanner.BACKOFF_MS[0])
        assertEquals(2, e.model.calls.size)
        // 网络好了:到点接着判,一条都没丢、没有记成失败
        e.model.error = null
        e.model.reply = { ok("n1", "n2", "n3") }
        wait(TriagePlanner.BACKOFF_MS[1])
        wait(3 * TriagePlanner.WINDOW_MS)
        assertEquals(0, e.runner.state.value.counts.pending)
        assertEquals(0, e.runner.state.value.counts.failed)
        assertEquals(5, e.runner.state.value.counts.judged)
    }

    // 别的失败(模型回了错、回复读不懂)照旧只记在那一批上,不让整个停下
    @Test fun otherFailuresDoNotPauseTriageAsAWhole() = runTest {
        val e = Env(this)
        e.model.error = ModelError.Server(500)
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.runner.state.value.counts.failed)
        e.model.error = null
        e.model.reply = { ok("n1") }
        e.post("b", pkg = "com.other", at = 2)
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(2, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.judged)
    }

    // 连不上而停着的时候用户点了「再试一次」:马上再连
    @Test fun retryingByHandEndsTheUnreachablePause() = runTest {
        val e = Env(this)
        e.model.error = ModelError.Network()
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        e.model.error = null
        e.model.reply = { ok("n1") }
        e.runner.retryFailed()
        runCurrent()
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(2, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.judged)
    }

    // 连上了却等到超时也没回应(模型忙、很慢):也让整个停一阵,不接着一条条地问。
    // 超时的那一批记成失败(可能是这一批本身的问题,不自动重发),页面上「再试一次」点得到
    @Test fun aTimeoutAlsoPausesTriageAsAWhole() = runTest {
        val e = Env(this)
        e.model.gate = CompletableDeferred()
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        wait(com.wangzan101.mika.core.proactive.NoticeTriage.TIMEOUT_MS)
        assertEquals(1, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.failed)
        e.post("b", pkg = "com.other", at = 2)
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        assertEquals(TriageStatus.Unreachable, e.runner.state.value.status)
        e.model.gate = null
        e.model.reply = { ok("n1") }
        wait(TriagePlanner.BACKOFF_MS[0])
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(2, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.judged)
    }

    // 「换没换模型」按服务商 id 和模型名认:两次读配置给的不是同一个对象、显示名改了,都不算换,照样停着
    @Test fun theUnreachablePauseHoldsWhileTheSameModelIsConfigured() = runTest {
        val e = Env(this)
        // 建模型那一次读到的是另一个对象,显示名也不一样
        e.built = TriageTarget(target.providerId, "Renamed", target.model)
        e.model.error = ModelError.Network()
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        e.effective = TriageTarget(target.providerId, "Renamed again", target.model)
        e.post("b", pkg = "com.other", at = 2)
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        assertEquals(TriageStatus.Unreachable, e.runner.state.value.status)
    }

    // 因为连不上而停着,不该比原因活得久:换了服务商或模型、重新开始看守,都马上再试
    @Test fun theUnreachablePauseEndsWhenTheModelChangesOrTheWatchRestarts() = runTest {
        val e = Env(this)
        e.model.error = ModelError.Network()
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(TriageStatus.Unreachable, e.runner.state.value.status)
        // 换了模型(同意也跟着换)
        val other = TriageTarget("p2", "Other", "m2")
        e.effective = other
        e.consent = TriageConsent.of(other)
        e.model.error = null
        e.model.reply = { ok("n1") }
        e.runner.poke()
        runCurrent()
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(2, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.judged)

        // 重新开始看守
        val again = Env(this)
        again.model.error = ModelError.Network()
        again.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(TriageStatus.Unreachable, again.runner.state.value.status)
        again.model.error = null
        again.model.reply = { ok("n1") }
        again.restart()
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(2, again.model.calls.size)
        assertEquals(1, again.runner.state.value.counts.judged)
    }
}
