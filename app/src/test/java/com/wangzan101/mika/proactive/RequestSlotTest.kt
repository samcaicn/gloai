package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.proactive.LearnTuning
import com.wangzan101.mika.core.proactive.NoticeInbox
import com.wangzan101.mika.core.proactive.NoticeRecord
import com.wangzan101.mika.core.proactive.TargetedModel
import com.wangzan101.mika.core.proactive.TopicPoolData
import com.wangzan101.mika.core.proactive.TriageConsent
import com.wangzan101.mika.core.proactive.TriagePlanner
import com.wangzan101.mika.core.proactive.TriageTarget
import com.wangzan101.mika.core.model.usage.ProactiveBudget
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 判定和归纳共用一个请求位:主动能力的后台请求一次只发一个,两边都轮得到。 */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestSlotTest {
    /** 两边各一个模型,在途数记在一起;每次调用都等测试放行。 */
    private class Gauge {
        var inFlight = 0
        var most = 0
        val order = ArrayList<String>()
    }

    private class Held(private val who: String, private val gauge: Gauge, private val reply: String) : ChatModel {
        override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
        val gates = ArrayList<CompletableDeferred<Unit>>()
        val calls get() = gates.size
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            val gate = CompletableDeferred<Unit>().also { gates += it }
            gauge.order += who
            gauge.inFlight++
            gauge.most = maxOf(gauge.most, gauge.inFlight)
            try {
                gate.await()
            } finally {
                gauge.inFlight--
            }
            return ModelReply(listOf(Part.Text(reply)), StopReason.EndTurn, null)
        }
        fun finish(i: Int = gates.lastIndex) = gates[i].complete(Unit)
    }

    private class MemStore : TopicPoolStore {
        var data = TopicPoolData()
        override fun load() = data
        override fun save(data: TopicPoolData) { this.data = data }
        override fun dropBroken() {}
        override fun wipe() { data = TopicPoolData() }
    }

    private val target = TriageTarget("p1", "Provider", "m1")

    private inner class Env(val scope: TestScope, shared: Boolean = true, watch: Boolean = true) {
        val gauge = Gauge()
        val judge = Held("triage", gauge, """[{"id":"n1","decision":"ignore","reason":"r"}]""")
        val distil = Held("learn", gauge, """{"signals":[]}""")
        val locked = MutableStateFlow(false)
        val inbox = NoticeInbox(clock = { scope.testScheduler.currentTime }, zone = { ZoneOffset.UTC })
        private val slot = RequestSlot()
        private fun hooks(m: ChatModel) = TriageHooks(
            target = { target }, model = { TargetedModel(target, m) },
            tokensToday = { 0 }, callsToday = { 0 }, usageVersion = { MutableStateFlow(0L) }, busy = MutableStateFlow(false),
        )
        val triage = TriageRunner(
            inbox, hooks(judge), on = { true }, consent = { TriageConsent.of(target) },
            budgetLimit = { ProactiveBudget.DEFAULT_DAILY_LIMIT }, language = { "English" },
            clock = { scope.testScheduler.currentTime }, planner = TriagePlanner(zone = { ZoneOffset.UTC }, windowMs = 0),
            slot = if (shared) slot else RequestSlot(),
        )
        val learn = LearnRunner(
            hooks(distil), on = { true }, consent = { TriageConsent.of(target) },
            budgetLimit = { ProactiveBudget.DEFAULT_DAILY_LIMIT }, language = { "English" },
            eligible = { true }, slot = if (shared) slot else RequestSlot(), sensitive = { false }, store = MemStore(),
            clock = { scope.testScheduler.currentTime }, zone = { ZoneOffset.UTC },
        )

        var watches: List<kotlinx.coroutines.Job> = emptyList()

        /** 开始看守(和 ProactiveRuntime 一样,两边在同一个作业下)。 */
        fun start() {
            watches = listOf(
                scope.backgroundScope.launch { triage.watch(locked) { locked.value } },
                scope.backgroundScope.launch { learn.watch(locked) { locked.value } },
            )
            scope.runCurrent()
        }

        /** 停止看守(关掉功能、完全退出)。 */
        fun stop() {
            watches.forEach { it.cancel() }
            scope.runCurrent()
        }

        init {
            if (watch) start()
        }

        private var n = 0
        fun record(key: String = "k${++n}") = NoticeRecord(key, "com.chat", scope.testScheduler.currentTime + n, "title $key", "text $key")
    }

    // 两边同时到点:一次只发一个(不是各发各的、同时两个请求),判定先发,发完归纳接着发,两边都轮得到
    @Test fun onlyOneBackgroundRequestAtATimeAndBothGetATurn() = runTest {
        val e = Env(this)
        // 归纳先攒够一批(还没到判定的那一条)
        val batch = (1..LearnTuning.DUE_COUNT).map { e.record() }
        // 同一刻:归纳攒够了,判定也有一条到点
        e.inbox.add(batch.first())
        batch.forEach(e.learn::capture)
        runCurrent()
        assertEquals(1, e.gauge.inFlight)
        assertEquals(listOf("triage"), e.gauge.order)
        assertTrue(e.triage.requestActive)
        assertFalse(e.learn.requestActive)
        // 判定发完:归纳接着发
        e.judge.finish()
        runCurrent()
        assertEquals(listOf("triage", "learn"), e.gauge.order)
        assertTrue(e.learn.requestActive)
        e.distil.finish()
        runCurrent()
        assertEquals(0, e.gauge.inFlight)
        assertEquals(1, e.gauge.most)
        assertEquals(0, e.learn.state.value.pending)
        assertEquals(1, e.triage.state.value.counts.judged)
    }

    // 归纳在发的时候判定到点:等着,不抢;归纳发完,判定先发,归纳的下一批排在后面
    @Test fun triageWaitsForLearningAndThenGoesFirst() = runTest {
        val e = Env(this)
        repeat(2 * LearnTuning.DUE_COUNT) { e.learn.capture(e.record()) }
        runCurrent()
        assertEquals(listOf("learn"), e.gauge.order)
        e.inbox.add(e.record("late"))
        runCurrent()
        assertFalse(e.triage.requestActive)
        assertEquals(0, e.judge.calls)
        assertEquals(1, e.gauge.inFlight)
        e.distil.finish()
        runCurrent()
        assertEquals(listOf("learn", "triage"), e.gauge.order)
        e.judge.finish()
        runCurrent()
        assertEquals(listOf("learn", "triage", "learn"), e.gauge.order)
        e.distil.finish()
        runCurrent()
        assertEquals(1, e.gauge.most)
    }

    // 在发的被取消(锁屏):位子放开;解锁后照常,一次还是只发一个
    @Test fun aCancelledRequestFreesTheSlot() = runTest {
        val e = Env(this)
        val batch = (1..LearnTuning.DUE_COUNT).map { e.record() }
        e.inbox.add(batch.first())
        batch.forEach(e.learn::capture)
        runCurrent()
        assertTrue(e.triage.requestActive)
        e.locked.value = true
        runCurrent()
        assertFalse(e.triage.requestActive)
        assertFalse(e.learn.requestActive)
        assertEquals(0, e.gauge.inFlight)
        e.locked.value = false
        runCurrent()
        assertEquals(1, e.gauge.inFlight)
        e.judge.finish()
        runCurrent()
        e.distil.finish()
        runCurrent()
        assertEquals(1, e.gauge.most)
        assertEquals(0, e.learn.state.value.pending)
    }

    // 对照:各用各的位子时,两边会同时各发一个——共用一个位子防的就是这个
    @Test fun withoutASharedSlotBothWouldSendAtOnce() = runTest {
        val e = Env(this, shared = false)
        val batch = (1..LearnTuning.DUE_COUNT).map { e.record() }
        e.inbox.add(batch.first())
        batch.forEach(e.learn::capture)
        runCurrent()
        assertEquals(2, e.gauge.most)
    }

    // 位子空着时两边同时到点:谁先看到谁先发(这里是归纳先攒够),另一边等它发完——一次还是只有一个
    @Test fun atATieWhoeverLooksFirstSendsFirst() = runTest {
        val e = Env(this)
        val batch = (1..LearnTuning.DUE_COUNT).map { e.record() }
        batch.forEach(e.learn::capture)
        e.inbox.add(batch.first())
        runCurrent()
        assertEquals(listOf("learn"), e.gauge.order)
        assertFalse(e.triage.requestActive)
        e.distil.finish()
        runCurrent()
        assertEquals(listOf("learn", "triage"), e.gauge.order)
        e.judge.finish()
        runCurrent()
        assertEquals(1, e.gauge.most)
    }

    // 看守停下(关掉功能、完全退出)时判定在发、归纳等着:两边都收住,不趁机多发一个;再开始看守,两边照常各轮一次
    @Test fun stoppingAndRestartingTheWatchKeepsOneAtATime() = runTest {
        val e = Env(this)
        val batch = (1..LearnTuning.DUE_COUNT).map { e.record() }
        e.inbox.add(batch.first())
        batch.forEach(e.learn::capture)
        runCurrent()
        assertEquals(listOf("triage"), e.gauge.order)
        e.stop()
        assertEquals(listOf("triage"), e.gauge.order)
        assertEquals(0, e.gauge.inFlight)
        assertFalse(e.triage.requestActive)
        assertFalse(e.learn.requestActive)
        assertEquals(LearnTuning.DUE_COUNT, e.learn.state.value.pending)
        e.start()
        assertEquals(1, e.gauge.inFlight)
        // 两边都发完:各轮到一次,没有同时在发的
        repeat(4) {
            e.judge.gates.lastOrNull()?.complete(Unit)
            e.distil.gates.lastOrNull()?.complete(Unit)
            runCurrent()
        }
        assertEquals(0, e.gauge.inFlight)
        assertEquals(1, e.gauge.most)
        assertEquals(0, e.learn.state.value.pending)
        assertEquals(1, e.triage.state.value.counts.judged)
        assertTrue("learn" in e.gauge.order.drop(1) && "triage" in e.gauge.order.drop(1))
    }
}
