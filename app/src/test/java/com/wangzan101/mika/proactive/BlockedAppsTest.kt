package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.proactive.LearnTuning
import com.wangzan101.mika.core.proactive.NoticeHints
import com.wangzan101.mika.core.proactive.NoticeInbox
import com.wangzan101.mika.core.proactive.NoticeRecord
import com.wangzan101.mika.core.proactive.NoticeTriage
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

/** 「应用访问」里禁用一个 App 之后:它已收的记录、待判定、提醒、待归纳的事件都去掉,在发的请求取消,不再发它的。 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlockedAppsTest {
    private class Fake(var reply: String) : ChatModel {
        override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
        val packages = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var cancelled = 0
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            val text = (messages.single().parts.single() as Part.Text).text
            packages += Regex("App package: (\\S+)").find(text)!!.groupValues[1]
            try {
                gate?.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled++
                throw e
            }
            return ModelReply(listOf(Part.Text(reply)), StopReason.EndTurn, null)
        }
    }

    private class MemStore : TopicPoolStore {
        var data = TopicPoolData()
        override fun load() = data
        override fun save(data: TopicPoolData) { this.data = data }
        override fun dropBroken() {}
        override fun wipe() { data = TopicPoolData() }
    }

    private val target = TriageTarget("p1", "Provider", "m1")

    private inner class Env(val scope: TestScope) {
        val allowed = setOf("com.chat", "com.other")
        val blocked = HashSet<String>()
        /** 「应用访问」读不读得出来。 */
        var known = true
        val gate get() = AppGate(known, blocked.toSet())
        val locked = MutableStateFlow(false)
        val busy = MutableStateFlow(false)
        val inbox = NoticeInbox(clock = { scope.testScheduler.currentTime }, zone = { ZoneOffset.UTC })
        val hints = NoticeHints()
        val judge = Fake("""{"items":[{"id":"n1","decision":"surface","reason":"r"},{"id":"n2","decision":"surface","reason":"r"}]}""")
        val distil = Fake("""{"signals":[]}""")
        private fun hooks(m: ChatModel) = TriageHooks(
            target = { target }, model = { TargetedModel(target, m) },
            tokensToday = { 0 }, callsToday = { 0 }, usageVersion = { MutableStateFlow(0L) }, busy = busy,
        )
        val triage = TriageRunner(
            inbox, hooks(judge), on = { true }, consent = { TriageConsent.of(target) },
            budgetLimit = { ProactiveBudget.DEFAULT_DAILY_LIMIT }, language = { "English" },
            clock = { scope.testScheduler.currentTime }, planner = TriagePlanner(zone = { ZoneOffset.UTC }),
            suspended = { !known },
        )
        val learn = LearnRunner(
            hooks(distil), on = { true }, consent = { TriageConsent.of(target) },
            budgetLimit = { ProactiveBudget.DEFAULT_DAILY_LIMIT }, language = { "English" },
            eligible = { BlockedApps.learnEligible(it, allowed, emptySet(), blocked) },
            suspended = { !known },
            sensitive = { false }, store = MemStore(),
            clock = { scope.testScheduler.currentTime }, zone = { ZoneOffset.UTC },
        )

        init {
            scope.backgroundScope.launch { triage.watch(locked) { locked.value } }
            scope.backgroundScope.launch { learn.watch(locked) { locked.value } }
            scope.runCurrent()
        }

        private var n = 0
        /** 照 ProactiveRuntime.admit 那样收一条:进收件箱,也进归纳的缓冲。 */
        fun post(pkg: String) {
            n++
            val r = NoticeRecord("$pkg-$n", pkg, scope.testScheduler.currentTime + n, "title $n", "text $n")
            if (BlockedApps.readable(pkg, allowed, gate) && BlockedApps.admits(r, allowed, gate)) {
                inbox.add(r)
                learn.capture(r)
            }
            scope.runCurrent()
        }

        fun syncHints() = hints.sync(inbox.state.value.records, triage.state.value.marks, true)

        fun block(pkg: String) {
            blocked += pkg
            changed()
        }

        /** 「应用访问」读不出来了 / 又读得出来了。 */
        fun access(known: Boolean) {
            this.known = known
            changed()
        }

        /** 照 ProactiveRuntime 收到「应用访问变了」那样做。 */
        private fun changed() {
            BlockedApps.accessChanged(gate, inbox, triage, learn)
            scope.runCurrent()
        }
    }

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test fun learnEligibilityHonoursTheBlockedSet() {
        assertTrue(BlockedApps.learnEligible("com.chat", setOf("com.chat"), emptySet(), emptySet()))
        assertFalse(BlockedApps.learnEligible("com.chat", setOf("com.chat"), emptySet(), setOf("com.chat")))
        assertFalse(BlockedApps.learnEligible("com.chat", setOf("com.chat"), setOf("com.chat"), emptySet()))
        assertFalse(BlockedApps.learnEligible("com.chat", emptySet(), emptySet(), emptySet()))
        assertFalse(BlockedApps.learnEligible("com.wangzan101.mika", setOf("com.wangzan101.mika"), emptySet(), emptySet()))
    }

    @Test fun blockingPurgesQueuedRecordsHintsAndEvents() = runTest {
        val e = Env(this)
        // 先有一条判成值得提醒、在提醒列表里
        e.post("com.chat")
        wait(TriagePlanner.WINDOW_MS)
        e.syncHints()
        assertEquals(1, e.hints.state.value.list.size)
        // 再来几条待判定、待归纳的
        e.post("com.chat"); e.post("com.other")
        assertEquals(2, e.triage.state.value.counts.pending)
        assertEquals(3, e.learn.state.value.pending)
        e.block("com.chat")
        assertTrue(e.inbox.state.value.records.none { it.packageName == "com.chat" })
        assertEquals(1, e.triage.state.value.counts.pending)
        assertTrue(e.triage.state.value.marks.keys.none { it.startsWith("com.chat") })
        e.syncHints()
        assertTrue(e.hints.state.value.list.isEmpty())
        assertEquals(1, e.learn.state.value.pending)
        assertEquals(1, e.learn.state.value.buffered)
        // 凑批到点、归纳到期:只发别的 App 的
        wait(TriagePlanner.WINDOW_MS)
        wait(LearnTuning.DUE_AGE_MS)
        e.post("com.other")
        assertEquals(listOf("com.chat", "com.other"), e.judge.packages)
        assertTrue(e.distil.packages.none { it == "com.chat" })
    }

    @Test fun blockingCancelsRequestsInFlightForThatApp() = runTest {
        val e = Env(this)
        e.judge.gate = CompletableDeferred()
        e.distil.gate = CompletableDeferred()
        repeat(LearnTuning.DUE_COUNT) { e.post("com.chat") }
        assertTrue(e.learn.requestActive)
        wait(TriagePlanner.WINDOW_MS)
        assertTrue(e.triage.requestActive)
        e.block("com.chat")
        assertFalse(e.triage.requestActive)
        assertFalse(e.learn.requestActive)
        assertEquals(1, e.judge.cancelled)
        assertEquals(1, e.distil.cancelled)
        assertEquals(0, e.learn.state.value.buffered)
        assertTrue(e.inbox.state.value.records.isEmpty())
        // 放行也没有结果、也不再发
        e.judge.gate!!.complete(Unit)
        e.distil.gate!!.complete(Unit)
        wait(24 * 3_600_000L)
        assertTrue(e.triage.state.value.marks.isEmpty())
        assertEquals(listOf("com.chat"), e.judge.packages)
        assertEquals(listOf("com.chat"), e.distil.packages)
    }

    @Test fun blockingAnotherAppLeavesTheRequestAlone() = runTest {
        val e = Env(this)
        e.judge.gate = CompletableDeferred()
        e.post("com.chat")
        wait(TriagePlanner.WINDOW_MS)
        assertTrue(e.triage.requestActive)
        e.block("com.other")
        assertTrue(e.triage.requestActive)
        e.judge.gate!!.complete(Unit)
        runCurrent()
        assertEquals(1, e.triage.state.value.marks.size)
    }

    // ---- 「应用访问」那份设置读不出来:不知道哪些 App 不让碰,整个停着,攒下的不动 ----

    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()

    @Test fun unreadableAppAccessReadsNoNotifications() {
        val broken = java.io.File(tmp.root, "broken.json").apply { writeText("{broken") }
        val state = com.wangzan101.mika.settings.AppAccessStore(broken).current()
        assertFalse(state.available)
        // 读不出来时那份「禁用的」是空的:只看它会当成一个都没禁用
        assertTrue(state.blocked.isEmpty())
        val unknown = AppGate.of(state)
        assertFalse(unknown.known)
        val r = NoticeRecord("k", "com.chat", 1L, "title", "text")
        assertFalse(BlockedApps.readable("com.chat", setOf("com.chat"), unknown))
        assertFalse(BlockedApps.admits(r, setOf("com.chat"), unknown))
        // 没有那份文件是读得出来的(一个都没禁用)
        val fresh = com.wangzan101.mika.settings.AppAccessStore(java.io.File(tmp.root, "none.json")).current()
        val known = AppGate.of(fresh)
        assertTrue(known.known)
        assertTrue(BlockedApps.readable("com.chat", setOf("com.chat"), known))
        assertTrue(BlockedApps.admits(r, setOf("com.chat"), known))
        assertFalse(BlockedApps.readable("com.chat", setOf("com.chat"), known.copy(blocked = setOf("com.chat"))))
    }

    @Test fun unreadableAppAccessStopsRequestsAndKeepsWhatIsQueued() = runTest {
        val e = Env(this)
        // 这一批有 DUE_COUNT 条:判定的回复给每一条都判上(默认的假回复只判前两条,其余的会算失败再发)
        e.judge.reply = (1..LearnTuning.DUE_COUNT).joinToString(",", """{"items":[""", "]}") { """{"id":"n$it","decision":"ignore","reason":"r"}""" }
        e.judge.gate = CompletableDeferred()
        e.distil.gate = CompletableDeferred()
        repeat(LearnTuning.DUE_COUNT) { e.post("com.chat") }
        wait(TriagePlanner.WINDOW_MS)
        assertTrue(e.triage.requestActive)
        assertTrue(e.learn.requestActive)
        val records = e.inbox.state.value.records.size
        assertEquals(LearnTuning.DUE_COUNT, records)
        val buffered = e.learn.state.value.buffered

        e.access(known = false)
        // 在发的两个请求都停了;收件箱里的、待归纳的一条没少
        assertFalse(e.triage.requestActive)
        assertFalse(e.learn.requestActive)
        assertEquals(1, e.judge.cancelled)
        assertEquals(1, e.distil.cancelled)
        assertEquals(TriageStatus.Suspended, e.triage.state.value.status)
        assertEquals(LearnStatus.Suspended, e.learn.state.value.status)
        assertEquals(records, e.inbox.state.value.records.size)
        assertEquals(buffered, e.learn.state.value.buffered)
        assertEquals(buffered, e.learn.state.value.pending)
        // 这期间来的通知不收;等多久都不发
        e.post("com.chat"); e.post("com.other")
        wait(24 * 3_600_000L)
        assertEquals(records, e.inbox.state.value.records.size)
        assertEquals(buffered, e.learn.state.value.buffered)
        assertEquals(1, e.judge.packages.size)
        assertEquals(1, e.distil.packages.size)

        // 读得出来了:接着做,原来那些重新发
        e.judge.gate = null
        e.distil.gate = null
        e.access(known = true)
        wait(TriagePlanner.WINDOW_MS)
        // 停下的那次不算失败,也没有别的重发:归纳重发那一批;判定一批最多 MAX_BATCH 条,这些分两批发完
        assertTrue(LearnTuning.DUE_COUNT in NoticeTriage.MAX_BATCH + 1..2 * NoticeTriage.MAX_BATCH)
        assertEquals(listOf("com.chat", "com.chat", "com.chat"), e.judge.packages)
        assertEquals(listOf("com.chat", "com.chat"), e.distil.packages)
        val c = e.triage.state.value.counts
        assertEquals(0, c.pending)
        assertEquals(0, c.failed)
        assertEquals(records, c.judged)
        assertEquals(0, e.learn.state.value.pending)
        assertEquals(0, e.learn.state.value.feed.getValue("com.chat").failed)
    }

    // 读不出来的那一阵里用户把它重置、又禁用了一个 App:读得出来时先清掉它的,再接着做,不会把它的发出去
    @Test fun whenAppAccessComesBackTheBlockedAppIsPurgedBeforeAnythingIsSent() = runTest {
        val e = Env(this)
        // 还没凑够一批、没到点:都在排队
        e.post("com.chat"); e.post("com.chat"); e.post("com.other")
        e.access(known = false)
        wait(24 * 3_600_000L)
        assertTrue(e.judge.packages.isEmpty())
        assertTrue(e.distil.packages.isEmpty())
        assertEquals(3, e.inbox.state.value.records.size)
        e.blocked += "com.chat"
        e.access(known = true)
        wait(TriagePlanner.WINDOW_MS)
        wait(LearnTuning.DUE_AGE_MS)
        e.post("com.other")
        assertTrue(e.inbox.state.value.records.none { it.packageName == "com.chat" })
        assertEquals(listOf("com.other"), e.judge.packages.distinct())
        assertEquals(listOf("com.other"), e.distil.packages.distinct())
    }
}
