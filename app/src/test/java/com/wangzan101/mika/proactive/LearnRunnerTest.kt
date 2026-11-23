package com.wangzan101.mika.proactive

import android.service.notification.NotificationListenerService
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.proactive.ContactDrop
import com.wangzan101.mika.core.proactive.ContactPoolData
import com.wangzan101.mika.core.proactive.ContactRelation
import com.wangzan101.mika.core.proactive.DigestData
import com.wangzan101.mika.core.proactive.DayDigest
import com.wangzan101.mika.core.proactive.DigestLine
import com.wangzan101.mika.core.proactive.DigestNote
import com.wangzan101.mika.core.proactive.LearnLevel
import com.wangzan101.mika.core.proactive.SourceTier
import com.wangzan101.mika.core.proactive.SpanKind
import com.wangzan101.mika.core.proactive.TopicPool
import com.wangzan101.mika.core.proactive.LearnDrop
import com.wangzan101.mika.core.proactive.DigestPool
import com.wangzan101.mika.core.proactive.SpanDigest
import com.wangzan101.mika.core.proactive.DigestLevel
import com.wangzan101.mika.core.proactive.LearnTuning
import com.wangzan101.mika.core.proactive.NoticeJournal
import com.wangzan101.mika.core.proactive.NoticeEntry
import com.wangzan101.mika.core.proactive.ProactiveCall
import com.wangzan101.mika.core.proactive.NoticeJournalData
import com.wangzan101.mika.core.proactive.NoticeMessage
import com.wangzan101.mika.core.proactive.NoticeRecord
import com.wangzan101.mika.core.proactive.ObservedContact
import com.wangzan101.mika.core.proactive.ObservedTopic
import com.wangzan101.mika.core.proactive.TopicKind
import com.wangzan101.mika.core.proactive.TargetedModel
import com.wangzan101.mika.core.proactive.TopicPoolData
import com.wangzan101.mika.core.proactive.TriageConsent
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LearnRunnerTest {
    private class Fake(var reply: () -> String = { """{"signals":[]}""" }) : ChatModel {
        override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
        val calls = mutableListOf<List<Message>>()
        val tools = mutableListOf<List<ToolSpec>>()
        var gate: CompletableDeferred<Unit>? = null
        var cancelled = 0
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
            // 分段归纳(当日消息归纳的第 1 级)的请求另给回复
            val text = (messages.single().parts.single() as Part.Text).text
            val out = when {
                text.startsWith("Language for notes") -> notesReply()
                isSpan(text, "Week") -> weekReply()
                isSpan(text, "Month") -> monthReply()
                text.startsWith("Language for the digest") -> dayReply()
                else -> reply()
            }
            return ModelReply(listOf(Part.Text(out)), StopReason.EndTurn, null)
        }
        var notesReply: () -> String = { """{"notes":[]}""" }
        /** 当日归纳(第 2 级)的请求的回复。 */
        var dayReply: () -> String = { """{"lines":[]}""" }
        /** 周归纳(第 3 级)、月归纳(第 4 级)的请求的回复。 */
        var weekReply: () -> String = { """{"lines":[]}""" }
        var monthReply: () -> String = { """{"lines":[]}""" }
        /** 周、月归纳的输入第二行写着是哪一周、哪个月。 */
        private fun isSpan(text: String, what: String) = text.startsWith("Language for the digest") && text.lineSequence().drop(1).firstOrNull()?.startsWith("$what: ") == true
        /** 第 [i] 次请求是哪一种:learn(归纳话题)、notes(分段归纳)、day(当日归纳)、week、month。 */
        fun kind(i: Int) = when {
            input(i).startsWith("Language for notes") -> "notes"
            isSpan(input(i), "Week") -> "week"
            isSpan(input(i), "Month") -> "month"
            input(i).startsWith("Language for the digest") -> "day"
            else -> "learn"
        }
        val kinds get() = calls.indices.map(::kind)
        fun isNotes(i: Int) = input(i).startsWith("Language for notes")
        fun input(i: Int) = (calls[i].single().parts.single() as Part.Text).text
        fun packages() = calls.indices.map { Regex("App package: (\\S+)").find(input(it))!!.groupValues[1] }
    }

    private class MemStore : TopicPoolStore {
        var data = TopicPoolData()
        var saves = 0
        var failLoad = false
        var failSave = false
        /** 另存的旧文件有几份、整份删过几次。 */
        var brokenCopies = 0
        var wipes = 0
        override fun load(): TopicPoolData {
            if (failLoad) throw java.security.KeyStoreException("busy")
            return data
        }
        var onSave: () -> Unit = {}
        override fun save(data: TopicPoolData) {
            if (failSave) throw java.io.IOException("disk")
            this.data = data; saves++; onSave()
        }
        override fun dropBroken() { brokenCopies = 0 }
        var failWipe = false
        override fun wipe() {
            if (failWipe) throw java.io.IOException("could not delete")
            data = TopicPoolData(); brokenCopies = 0; wipes++
        }
    }

    private class MemJournal : NoticeJournalStore {
        var data: NoticeJournalData? = null
        var saves = 0
        var wipes = 0
        var failLoad = false
        var failSave = false
        var failWipe = false
        override fun load(): NoticeJournalData? {
            if (failLoad) throw java.security.KeyStoreException("busy")
            return data
        }
        override fun save(data: NoticeJournalData) {
            if (failSave) throw java.io.IOException("disk")
            this.data = data; saves++
        }
        override fun wipe() {
            if (failWipe) throw java.io.IOException("could not delete")
            data = null; wipes++
        }
        val texts get() = data?.entries.orEmpty().map { it.text }
    }

    private class MemDigest : DigestStore {
        var data = DigestData()
        var saves = 0
        var wipes = 0
        var failLoad = false
        /** 存盘这一步出错(存储满了之类);[saveAttempts] 连没存上的也数。 */
        var failSave = false
        var saveAttempts = 0
        override fun load(): DigestData {
            if (failLoad) throw java.security.KeyStoreException("busy")
            return data
        }
        override fun save(data: DigestData) {
            saveAttempts++
            if (failSave) throw java.io.IOException("disk")
            this.data = data; saves++
        }
        var failWipe = false
        override fun wipe() {
            if (failWipe) throw java.io.IOException("could not delete")
            data = DigestData(); wipes++
        }
        val texts get() = data.notes.map { it.text }
    }

    private class MemPurges : LearnPurges {
        /** 写过几次(每写一次就是一次偏好落盘)。 */
        var writes = 0
        /** 话题那份下一次写上之后要做的事(只做一次):模拟写完这一笔进程就没了。 */
        var afterTopicWrite: (() -> Unit)? = null
        override var topicPurges: Set<String> = emptySet()
            set(v) { field = v; writes++; afterTopicWrite?.also { afterTopicWrite = null }?.invoke() }
        override var contactPurges: Set<String> = emptySet()
            set(v) { field = v; writes++ }
        override var digestPurges: Set<String> = emptySet()
            set(v) { field = v; writes++ }
        override var journalPurges: Set<String> = emptySet()
            set(v) { field = v; writes++ }
    }

    private class MemContacts : ContactPoolStore {
        var data = ContactPoolData()
        var saves = 0
        var failLoad = false
        var failSave = false
        var brokenCopies = 0
        var wipes = 0
        override fun load(): ContactPoolData {
            if (failLoad) throw java.security.KeyStoreException("busy")
            return data
        }
        var onSave: () -> Unit = {}
        override fun save(data: ContactPoolData) {
            if (failSave) throw java.io.IOException("disk")
            this.data = data; saves++; onSave()
        }
        override fun dropBroken() { brokenCopies = 0 }
        var failWipe = false
        override fun wipe() {
            if (failWipe) throw java.io.IOException("could not delete")
            data = ContactPoolData(); brokenCopies = 0; wipes++
        }
    }

    private val target = TriageTarget("p1", "Provider", "m1")
    private val start = 20_000 * LearnTuning.DAY_MS

    private inner class Env(val scope: TestScope) {
        val locked = MutableStateFlow(false)
        val busy = MutableStateFlow(false)
        var on = true
        var sensitive = false
        val contacts = MemContacts()
        var consent: TriageConsent? = TriageConsent.of(target)
        var effective: TriageTarget? = target
        /** 建模型那一次读到的目标(null 时同 [effective])。 */
        var built: TriageTarget? = null
        val model = Fake()
        val store = MemStore()
        var calls = 0L
        /** 建模型(要解密 Key)这一步出错。 */
        var buildFails = false
        /** 读「现在配的是哪个模型」这一步出错(配置一时读不出来)。 */
        var targetFails = false
        /** 读锁屏状态这一步出错。 */
        var lockReadFails = false
        var builds = 0
        /** 每次建模型时说的是要发哪一种请求(回复上限跟着它定)。 */
        val asked = mutableListOf<ProactiveCall>()
        val purges = MemPurges()
        /** 「当日消息归纳」开关。 */
        var digest = false
        val journal = MemJournal()
        val digestStore = MemDigest()
        val optOut = HashSet<String>()
        /** 「应用访问」读不出来。 */
        var suspended = false
        val runner = LearnRunner(
            TriageHooks(
                target = { if (targetFails) throw IllegalStateException("temporary configuration read failure"); effective },
                model = {
                    builds++
                    asked += it
                    if (buildFails) throw java.security.KeyStoreException("busy")
                    (built ?: effective)?.let { TargetedModel(it, model) }
                },
                tokensToday = { 0 }, callsToday = { calls },
                usageVersion = { MutableStateFlow(0L) }, busy = busy,
            ),
            on = { on }, consent = { consent }, budgetLimit = { ProactiveBudget.DEFAULT_DAILY_LIMIT },
            language = { "English" },
            eligible = { it !in optOut },
            suspended = { suspended },
            sensitive = { sensitive },
            store = store,
            contactStore = contacts, purges = purges,
            digest = { digest }, journalStore = journal, digestStore = digestStore,
            appLabel = { "App ${it.substringAfterLast('.')}" },
            clock = { now() }, zone = { ZoneOffset.UTC },
        )

        fun now() = start + scope.testScheduler.currentTime

        init {
            scope.backgroundScope.launch { runner.watch(locked) { if (lockReadFails) throw IllegalStateException("lock state unavailable"); locked.value } }
            scope.runCurrent()
        }

        private var n = 0
        fun post(pkg: String = "com.shop", count: Int = 1) {
            repeat(count) {
                n++
                runner.capture(NoticeRecord("k$n", pkg, now(), "title $n", "text $n"))
            }
            scope.runCurrent()
        }

        /** 聊天 App 里一个会话发来 [count] 条:同一个键(同一个 tag 重发)、同一个标题。 */
        fun chat(key: String, title: String, count: Int = 1, pkg: String = "com.chat") {
            repeat(count) {
                n++
                runner.capture(NoticeRecord(key, pkg, now(), title, "message $n"))
            }
            scope.runCurrent()
        }
    }

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun newTopic(vararg ids: String, label: String = "Hiking") =
        """{"signals":[{"ids":[${ids.joinToString(",") { "\"$it\"" }}],"new":{"kind":"interest","label":"$label"}}]}"""

    @Test fun notDueSendsNothingAndThereIsNoTimer() = runTest {
        val e = Env(this)
        e.post(count = LearnTuning.DUE_COUNT - 1)
        assertEquals(LearnStatus.Waiting, e.runner.state.value.status)
        // 过了 30 分钟、几个小时,没有任何事发生:不发(没有定时器)
        wait(5 * 3_600_000L)
        assertTrue(e.model.calls.isEmpty())
        // 有事发生(又来一条别的 App 的)时才看一遍:这时最旧的早过了 30 分钟
        e.post(pkg = "com.other")
        assertEquals(1, e.model.calls.size)
        assertEquals(listOf("com.shop"), e.model.packages())
    }

    @Test fun twelveEventsMakeOneRequestWithNoTools() = runTest {
        val e = Env(this)
        e.model.reply = { newTopic("n1", "n2") }
        e.post(count = LearnTuning.DUE_COUNT)
        assertEquals(1, e.model.calls.size)
        assertTrue(e.model.tools.single().isEmpty())
        assertEquals(LearnTuning.BATCH_MAX, Regex("\\[n\\d+]").findAll(e.model.input(0)).count())
        val s = e.runner.state.value
        assertEquals(LearnStatus.Idle, s.status)
        assertEquals(0, s.pending)
        assertEquals(e.now(), s.lastDistilledAtMs)
        val f = s.feed.getValue("com.shop")
        assertEquals(LearnFeed(captured = 12, distilled = 12, batches = 1, accepted = 1), f)
        assertEquals(1, e.store.saves)
        assertEquals(listOf("Hiking"), e.store.data.topics.map { it.label })
        assertEquals(setOf("com.shop"), e.store.data.topics.single().evidence.keys)
        assertEquals(listOf("Hiking"), e.runner.topics.value.map { it.label })
    }

    @Test fun lockedSendsNothingCancelsInFlightAndKeepsEvents() = runTest {
        val e = Env(this)
        e.locked.value = true
        runCurrent()
        e.post(count = 12)
        assertEquals(LearnStatus.Locked, e.runner.state.value.status)
        assertTrue(e.model.calls.isEmpty())
        // 解锁后发;发的途中锁屏:取消,事件留着
        e.model.gate = CompletableDeferred()
        e.locked.value = false
        runCurrent()
        assertEquals(1, e.model.calls.size)
        assertTrue(e.runner.requestActive)
        e.locked.value = true
        runCurrent()
        assertFalse(e.runner.requestActive)
        assertEquals(1, e.model.cancelled)
        assertEquals(12, e.runner.state.value.pending)
        assertEquals(0, e.runner.state.value.feed.getValue("com.shop").failed)
        // 再解锁:重新发同一批
        e.model.gate = null
        e.model.reply = { newTopic("n1") }
        e.locked.value = false
        runCurrent()
        assertEquals(2, e.model.calls.size)
        assertEquals(0, e.runner.state.value.pending)
    }

    // 锁屏取消了请求,模型却不理取消、晚些时候才回来;这期间解锁后新的一批已经在发:晚回来的那次什么都不能动
    @Test fun staleRequestReturningLateDoesNotTouchTheNewerBatch() = runTest {
        val e = Env(this)
        e.model.reply = { newTopic("n1") }
        val late = CompletableDeferred<Unit>()
        e.model.late = late
        e.post(count = LearnTuning.DUE_COUNT)
        assertTrue(e.runner.requestActive)
        e.locked.value = true
        runCurrent()
        assertFalse(e.runner.requestActive)
        e.model.gate = CompletableDeferred()
        e.locked.value = false
        runCurrent()
        assertEquals(2, e.model.calls.size)
        assertTrue(e.runner.requestActive)
        late.complete(Unit)
        runCurrent()
        assertTrue("新的一批还在发", e.runner.requestActive)
        assertEquals(LearnStatus.Sending, e.runner.state.value.status)
        assertEquals(0, e.runner.state.value.pending)
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("Hiking"), e.store.data.topics.map { it.label })
        assertEquals("晚回来的「预算用完」不算数", LearnStatus.Idle, e.runner.state.value.status)
    }

    @Test fun busyWaits() = runTest {
        val e = Env(this)
        e.busy.value = true
        runCurrent()
        e.post(count = 12)
        assertEquals(LearnStatus.Busy, e.runner.state.value.status)
        assertTrue(e.model.calls.isEmpty())
        e.busy.value = false
        runCurrent()
        assertEquals(1, e.model.calls.size)
    }

    // 模型回了、但回复读不懂:这批丢掉不重试;下一批要停一段才发(不定时,到点后等下一件事发生)
    @Test fun unreadableReplyDropsTheBatchAndHoldsTheNextOne() = runTest {
        val e = Env(this)
        e.model.reply = { "I cannot help with that." }
        e.post(count = 12)
        assertEquals(1, e.model.calls.size)
        assertEquals(0, e.runner.state.value.buffered)
        assertEquals(1, e.runner.state.value.feed.getValue("com.shop").failed)
        // 马上又攒够一批:停着,不发
        e.post(count = 12)
        assertEquals(1, e.model.calls.size)
        assertEquals(LearnStatus.Waiting, e.runner.state.value.status)
        assertEquals(12, e.runner.state.value.pending)
        e.busy.value = true; runCurrent(); e.busy.value = false; runCurrent()
        assertEquals(1, e.model.calls.size)
        // 到点了也没有定时器;有事发生时才发
        wait(LearnTuning.failureHoldMs(1))
        assertEquals(1, e.model.calls.size)
        e.model.reply = { newTopic("n1") }
        e.post(pkg = "com.other")
        assertEquals(2, e.model.calls.size)
        assertEquals(listOf("com.shop", "com.shop"), e.model.packages())
        // 成了一次就不再停:别的 App 攒够了马上发
        e.post(pkg = "com.other", count = 11)
        assertEquals(3, e.model.calls.size)
    }

    // 模型一出问题,到期的各个 App 不会一批接一批地发出去、失败
    @Test fun oneFailureDoesNotCascadeAcrossApps() = runTest {
        val e = Env(this)
        e.model.error = ModelError.Server(500)
        e.locked.value = true
        runCurrent()
        e.post(pkg = "com.a", count = 12)
        e.post(pkg = "com.b", count = 12)
        e.post(pkg = "com.c", count = 12)
        e.locked.value = false
        runCurrent()
        assertEquals(1, e.model.calls.size)
        assertEquals(24, e.runner.state.value.pending)
        assertEquals(LearnStatus.Waiting, e.runner.state.value.status)
        // 连续失败,一级一级停得更久:2、5、15、30 分钟,之后都是 30 分钟
        var expected = 1
        for (hold in listOf(2, 5, 15, 30, 30)) {
            wait(hold * 60_000L - 1)
            e.busy.value = true; runCurrent(); e.busy.value = false; runCurrent()
            assertEquals("停满 $hold 分钟之前不发", expected, e.model.calls.size)
            wait(1)
            e.busy.value = true; runCurrent(); e.busy.value = false; runCurrent()
            if (expected < 3) expected++
            assertEquals(expected, e.model.calls.size)
        }
        // 三个 App 各丢了一批,之后没有东西可发
        assertEquals(0, e.runner.state.value.buffered)
    }

    // 根本没连上模型(模型只在家里的网络上、人出门了):这批留着,停一段再试,连上了就归纳
    @Test fun unreachableModelKeepsTheBatchAndRetriesLater() = runTest {
        val e = Env(this)
        e.model.error = ModelError.Network()
        e.post(count = 12)
        assertEquals(1, e.model.calls.size)
        assertEquals(12, e.runner.state.value.pending)
        assertEquals(1, e.runner.state.value.feed.getValue("com.shop").failed)
        assertEquals(LearnStatus.Waiting, e.runner.state.value.status)
        assertFalse(e.runner.requestActive)
        e.post()
        assertEquals(1, e.model.calls.size)
        wait(LearnTuning.failureHoldMs(1))
        e.post()
        assertEquals(2, e.model.calls.size)
        assertEquals(14, e.runner.state.value.pending)
        // 第二次失败停 5 分钟
        wait(LearnTuning.failureHoldMs(1))
        e.post()
        assertEquals(2, e.model.calls.size)
        // 连上了:同一批(最旧的 12 条)发出去,归纳完
        e.model.error = null
        e.model.reply = { newTopic("n1") }
        wait(LearnTuning.failureHoldMs(2))
        e.post()
        assertEquals(3, e.model.calls.size)
        assertEquals(e.model.input(0).substringAfter("Notifications"), e.model.input(2).substringAfter("Notifications"))
        assertEquals(listOf("Hiking"), e.store.data.topics.map { it.label })
        assertEquals(4, e.runner.state.value.pending)
        assertEquals(12, e.runner.state.value.feed.getValue("com.shop").distilled)
    }

    // 建模型(要解密 Key)出错:不是这批的问题,留着;也停一段,不会每来一条通知就再出一次错
    @Test fun modelBuildFailureKeepsTheBatchAndHolds() = runTest {
        val e = Env(this)
        e.buildFails = true
        e.post(count = 12)
        assertEquals(1, e.builds)
        assertTrue(e.model.calls.isEmpty())
        assertEquals(12, e.runner.state.value.pending)
        assertEquals(1, e.runner.state.value.feed.getValue("com.shop").failed)
        assertEquals(LearnStatus.Waiting, e.runner.state.value.status)
        e.post(count = 5)
        assertEquals(1, e.builds)
        e.buildFails = false
        e.model.reply = { newTopic("n1") }
        wait(LearnTuning.failureHoldMs(1))
        e.post()
        assertEquals(2, e.builds)
        assertEquals(1, e.model.calls.size)
        assertEquals(6, e.runner.state.value.pending)
    }

    // 用户打开归纳页:不再停着,有到期的马上再试一次(多半是刚改了设置)
    @Test fun openingThePageLiftsTheHold() = runTest {
        val e = Env(this)
        e.model.error = ModelError.Network()
        e.post(count = 12)
        assertEquals(1, e.model.calls.size)
        e.model.error = null
        e.model.reply = { newTopic("n1") }
        e.runner.open()
        runCurrent()
        assertEquals(2, e.model.calls.size)
        assertEquals(0, e.runner.state.value.pending)
        assertEquals(LearnStatus.Idle, e.runner.state.value.status)
    }

    // 关掉归纳再打开:从头算,不带着上次的停顿
    @Test fun switchingOffForgetsTheHold() = runTest {
        val e = Env(this)
        e.model.error = ModelError.Server(500)
        e.post(count = 12)
        assertEquals(1, e.model.calls.size)
        e.on = false
        e.runner.clear(); e.runner.poke()
        e.on = true
        e.runner.poke()
        e.model.error = null
        e.post(count = 12)
        assertEquals(2, e.model.calls.size)
    }

    @Test fun timeoutCountsAsFailure() = runTest {
        val e = Env(this)
        e.model.gate = CompletableDeferred()
        e.post(count = 12)
        wait(LearnTuning.TIMEOUT_MS)
        assertEquals(1, e.runner.state.value.feed.getValue("com.shop").failed)
        assertEquals(0, e.runner.state.value.buffered)
        assertEquals(1, e.model.calls.size)
    }

    @Test fun budgetExhaustedIsQuietAndKeepsEvents() = runTest {
        val e = Env(this)
        e.model.error = ModelError.ProactiveBudgetExceeded(1, 1)
        e.post(count = 12)
        assertEquals(1, e.model.calls.size)
        assertEquals(LearnStatus.BudgetUsedUp, e.runner.state.value.status)
        assertEquals(12, e.runner.state.value.pending)
        // 当天不再发
        e.post()
        e.busy.value = true; runCurrent(); e.busy.value = false; runCurrent()
        assertEquals(1, e.model.calls.size)
        // 账本那边到上限也不发
        val f = Env(this)
        // 请求次数的上限跟着 Token 上限走;归纳按整份算
        f.calls = ProactiveBudget.callLimit(ProactiveBudget.DEFAULT_DAILY_LIMIT)
        f.post(count = 12)
        assertTrue(f.model.calls.isEmpty())
        assertEquals(LearnStatus.BudgetUsedUp, f.runner.state.value.status)
    }

    @Test fun consentMismatchStops() = runTest {
        val e = Env(this)
        e.effective = TriageTarget("p2", "Other", "m2")
        e.post(count = 12)
        assertEquals(LearnStatus.NeedsConsent, e.runner.state.value.status)
        assertTrue(e.model.calls.isEmpty())
        // 发的途中换了模型:结果不用,事件留着
        e.effective = target
        e.model.gate = CompletableDeferred()
        e.runner.poke(); runCurrent()
        assertEquals(1, e.model.calls.size)
        e.effective = TriageTarget("p2", "Other", "m2")
        e.model.reply = { newTopic("n1") }
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(e.store.data.topics.isEmpty())
        assertEquals(12, e.runner.state.value.pending)
        assertEquals(LearnStatus.NeedsConsent, e.runner.state.value.status)
    }

    // 门口查同意时是同意过的模型,建模型时配置已被改成别的:不发,事件留着待归纳
    @Test fun targetChangedBetweenTheGateAndTheBuildSendsNothing() = runTest {
        val e = Env(this)
        e.built = target.copy(providerId = "p2")
        e.post(count = LearnTuning.DUE_COUNT)
        assertTrue("文字没发出去", e.model.calls.isEmpty())
        assertFalse(e.runner.requestActive)
        val s = e.runner.state.value
        assertEquals(LearnStatus.NeedsConsent, s.status)
        assertEquals(LearnTuning.DUE_COUNT, s.pending)
        assertEquals(0, s.feed.getValue("com.shop").batches)
        e.built = null
        e.runner.poke()
        runCurrent()
        assertEquals(1, e.model.calls.size)
    }

    @Test fun optedOutAppIsNeverSent() = runTest {
        val e = Env(this)
        e.optOut += "com.chat"
        e.post(pkg = "com.chat", count = 20)
        assertEquals(0, e.runner.state.value.buffered)
        e.post(pkg = "com.shop", count = 5)
        // 先收下、再关掉:已收的去掉
        e.optOut += "com.shop"
        e.runner.poke(); runCurrent()
        assertEquals(0, e.runner.state.value.buffered)
        wait(3_600_000)
        e.post(pkg = "com.chat")
        assertTrue(e.model.calls.isEmpty())
    }

    @Test fun switchOffClearsAndCapturesNothing() = runTest {
        val e = Env(this)
        e.post(count = 3)
        e.on = false
        e.runner.clear(); e.runner.poke(); runCurrent()
        assertEquals(LearnStatus.Off, e.runner.state.value.status)
        assertEquals(0, e.runner.state.value.buffered)
        e.post(count = 12)
        assertEquals(0, e.runner.state.value.buffered)
        assertTrue(e.model.calls.isEmpty())
    }

    // 池子里的话题比给模型看的位置多:这一批的 App 自己印证过的那个哪怕弱也给模型看(它再推同样的东西时模型才引用得上,
    // 不然只能另起一个说法差不多的);别的 App 的一批还是只按强度挑,轮不到它
    @Test fun theAppsOwnTopicsAreShownToTheModelEvenWhenWeak() = runTest {
        val e = Env(this)
        val today = 20_000L
        val old = start - 5 * LearnTuning.DAY_MS
        e.store.data = TopicPoolData(topics =
            (0 until LearnTuning.EXISTING_MAX + 10).map {
                ObservedTopic("kn$it", TopicKind.Interest, "News $it", start, start, mapOf("com.news" to listOf(today - 2, today - 1, today)))
            } + ObservedTopic("kshop", TopicKind.Shopping, "Tents", old, old, mapOf("com.shop" to listOf(today - 5))))
        e.post(count = 12)
        val lines = e.model.input(0).lines()
        assertEquals(LearnTuning.EXISTING_MAX, lines.count { it.contains("] kind: ") })
        // 排在第一个(话题名在那一行的下一行,不可信的块里)
        assertEquals("Tents", lines[lines.indexOfFirst { it.contains("] kind: ") } + 1])
        e.post(pkg = "com.other", count = 12)
        assertEquals(listOf("com.shop", "com.other"), e.model.packages())
        assertFalse(e.model.input(1).contains("Tents"))
    }

    @Test fun lateOutcomeReachesTheTopic() = runTest {
        val e = Env(this)
        e.model.reply = { newTopic("n1") }
        e.post(count = 12)
        e.runner.removed("k1", NotificationListenerService.REASON_CLICK)
        e.runner.removed("k2", NotificationListenerService.REASON_CANCEL_ALL)
        assertEquals(1, e.store.data.topics.single().opened)
        assertEquals(2, e.store.saves)
    }

    // 「敏感类推断」关掉之后,早先归到敏感话题上的通知才被点开:不往上补
    @Test fun lateOutcomeSkipsSensitiveTopicsWhenTheSwitchIsOff() = runTest {
        val e = Env(this)
        e.sensitive = true
        e.model.reply = { """{"signals":[{"ids":["n1","n2"],"new":{"kind":"interest","label":"Diabetes","sensitive":true}}]}""" }
        e.post(count = 12)
        assertTrue(e.store.data.topics.single().sensitive)
        e.sensitive = false
        val saves = e.store.saves
        e.runner.removed("k1", NotificationListenerService.REASON_CLICK)
        assertEquals(0, e.store.data.topics.single().opened)
        assertEquals(saves, e.store.saves)
        // 再打开开关:之后点开的照常补
        e.sensitive = true
        e.runner.removed("k2", NotificationListenerService.REASON_CLICK)
        assertEquals(1, e.store.data.topics.single().opened)
    }

    @Test fun unreadablePoolIsNotOverwritten() = runTest {
        val e = Env(this)
        e.store.failLoad = true
        e.post(count = 12)
        assertEquals(LearnStatus.StoreUnavailable, e.runner.state.value.status)
        assertTrue(e.model.calls.isEmpty())
        assertEquals(0, e.store.saves)
        assertEquals(12, e.runner.state.value.pending)
        e.store.failLoad = false
        e.post()
        assertEquals(1, e.model.calls.size)
    }

    @Test fun sensitiveSwitch() = runTest {
        val e = Env(this)
        val flagged = """{"signals":[{"ids":["n1"],"new":{"kind":"interest","label":"Diabetes","sensitive":true}}]}"""
        e.model.reply = { flagged }
        e.post(count = 12)
        assertTrue(e.model.input(0).contains("Sensitive topics: not allowed"))
        assertTrue(e.store.data.topics.isEmpty())
        e.sensitive = true
        e.post(count = 12)
        assertTrue(e.model.input(1).contains("Sensitive topics: allowed"))
        assertTrue(e.store.data.topics.single().sensitive)
        // 关掉并选删除:删掉并存盘
        e.sensitive = false
        val saves = e.store.saves
        e.runner.deleteSensitive()
        assertTrue(e.store.data.topics.isEmpty())
        assertEquals(saves + 1, e.store.saves)
    }

    // ---- 联系人与关系 ----

    private val aliceReply =
        """{"signals":[{"ids":["n1","n2"],"new":{"kind":"interest","label":"Hiking"}}],"contacts":[{"ids":["n1","n2","n3"],"type":"person","relation":"friend","name":"Mallory"}]}"""

    @Test fun contactsRideOnTheSameSingleRequest() = runTest {
        val e = Env(this)
        e.model.reply = { aliceReply }
        e.chat("0|com.chat|7|null|1", "Alice", count = 6)
        e.chat("0|com.chat|8|null|1", "Promo", count = 6)
        // 和第一部分一样:12 条只发一次请求
        assertEquals(1, e.model.calls.size)
        assertTrue(e.model.input(0).lines().contains("Contacts: on"))
        val c = e.contacts.data.contacts.single()
        // 名字取自通知标题,不取模型给的
        assertEquals("Alice", c.name)
        assertEquals("com.chat", c.packageName)
        assertEquals(listOf(e.store.data.topics.single().id), c.topics)
        val f = e.runner.state.value.feed.getValue("com.chat")
        assertEquals(1, f.contactsAccepted)
        assertEquals(0, f.contactDroppedTotal)
        assertEquals(listOf("Alice"), e.runner.contactsView.value.contacts.map { it.name })
        // 只有键和标题的哈希,没有原始的键
        assertFalse(c.id.contains("com.chat") || c.id.contains("Alice"))
    }

    // 先存话题再存联系人:联系人存下去的那一刻,它连着的话题已经在存好的话题池里了
    @Test fun topicsAreSavedBeforeTheContactsThatLinkToThem() = runTest {
        val e = Env(this)
        val order = ArrayList<String>()
        var danglingAtSave: Set<String>? = null
        e.store.onSave = { order += "topics" }
        e.contacts.onSave = {
            order += "contacts"
            val saved = e.store.data.topics.map { it.id }.toSet()
            danglingAtSave = e.contacts.data.contacts.flatMap { it.topics }.toSet() - saved
        }
        e.model.reply = { aliceReply }
        e.chat("0|com.chat|7|null|1", "Alice", count = 6)
        e.chat("0|com.chat|8|null|1", "Promo", count = 6)
        assertEquals(listOf("topics", "contacts"), order)
        assertEquals(emptySet<String>(), danglingAtSave)
        assertEquals(1, e.contacts.data.contacts.single().topics.size)
    }

    @Test fun optedOutAppRecordsNoContacts() = runTest {
        val e = Env(this)
        e.optOut += "com.chat"
        e.model.reply = { aliceReply }
        e.chat("a", "Alice", count = 20)
        wait(3_600_000)
        e.post(pkg = "com.chat")
        assertTrue(e.model.calls.isEmpty())
        assertEquals(0, e.contacts.saves)
    }

    @Test fun consentMismatchDiscardsContactsToo() = runTest {
        val e = Env(this)
        e.model.gate = CompletableDeferred()
        e.model.reply = { aliceReply }
        e.chat("a", "Alice", count = 12)
        e.effective = TriageTarget("p2", "Other", "m2")
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(e.contacts.data.contacts.isEmpty())
        assertTrue(e.store.data.topics.isEmpty())
        assertEquals(12, e.runner.state.value.pending)
    }

    @Test fun pageActionsOnContacts() = runTest {
        val e = Env(this)
        e.model.reply = { aliceReply }
        e.chat("a", "Alice", count = 12)
        val id = e.contacts.data.contacts.single().id
        e.runner.setContactRelation(id, ContactRelation.Family)
        assertEquals(ContactRelation.Family, e.contacts.data.contacts.single().userRelation)
        // 删掉话题:联系人上的连接也去掉
        e.runner.deleteTopic(e.store.data.topics.single().id)
        assertTrue(e.contacts.data.contacts.single().topics.isEmpty())
        // 删掉联系人:同一个会话不再记
        e.runner.deleteContact(id)
        assertTrue(e.contacts.data.contacts.isEmpty())
        assertEquals(1, e.runner.contactsView.value.blocked)
        e.chat("a", "Alice", count = 12)
        assertTrue(e.contacts.data.contacts.isEmpty())
        assertEquals(1, e.runner.state.value.feed.getValue("com.chat").contactDropped[ContactDrop.Blocked])
        e.runner.allowDeletedContacts()
        assertEquals(0, e.runner.contactsView.value.blocked)
        e.chat("a", "Alice", count = 12)
        assertEquals(1, e.contacts.data.contacts.size)
        // 清掉某个 App 的、清空全部:联系人也去掉,但不挡
        e.runner.clearApp("com.chat")
        assertTrue(e.contacts.data.contacts.isEmpty())
        assertEquals(0, e.runner.contactsView.value.blocked)
        e.chat("a", "Alice", count = 12)
        assertEquals(1, e.contacts.data.contacts.size)
        e.runner.clearAll()
        assertTrue(e.contacts.data.contacts.isEmpty())
        assertTrue(e.store.data.topics.isEmpty())
        assertEquals(0, e.runner.contactsView.value.blocked)
    }

    // 话题页上把一个话题并进另一个:存下来只剩并进去的那个,证据并过去;联系人上的连接改指它;早先归到并掉的那个上的通知
    // 之后才被点开,算到它上面;模型再新起并掉的那个名字,不另起。并掉不是删掉:不进「不再记录」,不记待删
    @Test fun mergingATopicMovesItsEvidenceLinksAndName() = runTest {
        val e = Env(this)
        e.model.reply = { aliceReply }
        repeat(12) { e.runner.capture(NoticeRecord("a$it", "com.chat", e.now(), "Alice", "message $it")) }
        runCurrent()
        val hiking = e.store.data.topics.single().id
        assertEquals(listOf(hiking), e.contacts.data.contacts.single().topics)
        e.model.reply = { newTopic("n1", label = "Trails") }
        e.post(count = 12)
        val trails = e.store.data.topics.single { it.label == "Trails" }.id
        e.runner.mergeTopic(hiking, trails)
        val merged = e.store.data.topics.single()
        assertEquals(trails, merged.id)
        assertEquals("Trails", merged.label)
        assertEquals(setOf("com.chat", "com.shop"), merged.evidence.keys)
        assertEquals(listOf(trails), e.runner.topics.value.map { it.id })
        assertEquals(listOf(trails), e.contacts.data.contacts.single().topics)
        assertEquals(listOf(trails), e.runner.contactsView.value.contacts.single().topics)
        assertTrue(e.store.data.blocked.isEmpty())
        assertEquals(0, e.runner.topicsBlocked.value)
        // 两份都存上了:没有记着要补做的
        assertTrue(e.purges.topicPurges.isEmpty())
        assertTrue(e.purges.contactPurges.isEmpty())
        // 归到「Hiking」上的那条聊天通知这时才被点开
        e.runner.removed("a0", NotificationListenerService.REASON_CLICK)
        assertEquals(1, e.store.data.topics.single().opened)
        // 模型再新起「Hiking」:算到「Trails」上
        e.model.reply = { newTopic("n1") }
        e.post(count = 12)
        assertEquals(listOf("Trails"), e.store.data.topics.map { it.label })
        assertFalse(LearnDrop.Blocked in e.runner.state.value.feed.getValue("com.shop").dropped)
        // 换一个进程:存下来的就是并好的样子,名字也还认
        assertEquals(1, e.store.data.merged.size)
        val again = newProcess(e)
        again.open()
        assertEquals(listOf(trails), again.topics.value.map { it.id })
        // 同一个、已经没有的:什么都不做,也不存
        val saves = e.store.saves
        e.runner.mergeTopic(trails, trails)
        e.runner.mergeTopic(hiking, trails)
        assertEquals(saves, e.store.saves)
    }

    /** 两个话题「Hiking」(聊天 App 里 Alice 的会话连着它)和「Trails」。返回两个的 id。 */
    private fun TestScope.hikingAndTrails(e: Env): Pair<String, String> {
        e.model.reply = { aliceReply }
        repeat(12) { e.runner.capture(NoticeRecord("a$it", "com.chat", e.now(), "Alice", "message $it")) }
        runCurrent()
        e.model.reply = { newTopic("n1", label = "Trails") }
        e.post(count = 12)
        val hiking = e.store.data.topics.single { it.label == "Hiking" }.id
        assertEquals(listOf(hiking), e.contacts.data.contacts.single().topics)
        return hiking to e.store.data.topics.single { it.label == "Trails" }.id
    }

    // 合并没存上(存储满了之类),用户接着把并好的那个删了,也没存上,进程没了。下次读出来:文件里两个话题都还在,
    // 合并和删除都得补做——只补删除的话,并掉的那个带着它的证据回来了,名字也不在「不再记录」里
    @Test fun anUnsavedMergeIsRedoneBeforeTheDeleteThatFollowedIt() = runTest {
        val e = Env(this)
        val (hiking, trails) = hikingAndTrails(e)
        e.store.failSave = true
        e.runner.mergeTopic(hiking, trails)
        e.runner.deleteTopic(trails)
        assertEquals(2, e.store.data.topics.size)
        assertEquals(2, e.purges.topicPurges.size)
        e.store.failSave = false
        val again = newProcess(e)
        again.open()
        assertTrue(again.topics.value.isEmpty())
        assertTrue(e.store.data.topics.isEmpty())
        // 两个名字都进了「不再记录」
        assertEquals(setOf(TopicPool.fingerprint("Hiking"), TopicPool.fingerprint("Trails")), e.store.data.blocked.toSet())
        assertTrue(e.purges.topicPurges.isEmpty())
        // 联系人上不再连着它们
        assertTrue(e.contacts.data.contacts.single().topics.isEmpty())
    }

    // 只是合并没存上、进程没了:下次读出来补做,不用用户再并一次;并掉的名字照样认
    @Test fun anUnsavedMergeIsRedoneWhenThePoolLoadsAgain() = runTest {
        val e = Env(this)
        val (hiking, trails) = hikingAndTrails(e)
        e.store.failSave = true
        e.runner.mergeTopic(hiking, trails)
        assertEquals(2, e.store.data.topics.size)
        e.store.failSave = false
        val again = newProcess(e)
        again.open()
        assertEquals(listOf(trails), e.store.data.topics.map { it.id })
        assertEquals(setOf("com.chat", "com.shop"), e.store.data.topics.single().evidence.keys)
        assertEquals(mapOf(TopicPool.fingerprint("Hiking") to trails), e.store.data.merged)
        assertTrue(e.store.data.blocked.isEmpty())
        assertEquals(listOf(trails), e.contacts.data.contacts.single().topics)
        assertTrue(e.purges.topicPurges.isEmpty())
        // 存上了就不再记着:再换一个进程不会重做,也不多存
        val saves = e.store.saves
        newProcess(e).open()
        assertEquals(saves, e.store.saves)
    }

    // 合并的那一刻联系人那份读不出来(密钥库一时不可用),话题那份照常并了、存了。联系人读得出来之后:
    // 连着并掉的那个话题的改连并进去的那个,不被当成指着空处的连接去掉
    @Test fun contactLinksFollowAMergeMadeWhileContactsWereUnreadable() = runTest {
        val e = Env(this)
        val (hiking, trails) = hikingAndTrails(e)
        e.contacts.failLoad = true
        val later = newProcess(e)
        later.open()
        later.mergeTopic(hiking, trails)
        assertEquals(listOf(trails), e.store.data.topics.map { it.id })
        assertEquals(listOf(hiking), e.contacts.data.contacts.single().topics)
        assertEquals(1, e.purges.contactPurges.size)
        e.contacts.failLoad = false
        later.open()
        assertEquals(listOf(trails), e.contacts.data.contacts.single().topics)
        assertEquals(listOf(trails), later.contactsView.value.contacts.single().topics)
        assertTrue(e.purges.contactPurges.isEmpty())
    }

    // 联系人那份改了却没存上、进程没了:下次读出来补做
    @Test fun contactLinksFollowAMergeWhoseContactSaveFailed() = runTest {
        val e = Env(this)
        val (hiking, trails) = hikingAndTrails(e)
        e.contacts.failSave = true
        e.runner.mergeTopic(hiking, trails)
        assertEquals(listOf(trails), e.store.data.topics.map { it.id })
        assertEquals(listOf(hiking), e.contacts.data.contacts.single().topics)
        e.contacts.failSave = false
        val again = newProcess(e)
        again.open()
        assertEquals(listOf(trails), e.contacts.data.contacts.single().topics)
        assertTrue(e.purges.contactPurges.isEmpty())
    }

    // 一次合并要记到话题、联系人两份上。记完进程就没了(两份文件都还没改):下次读出来两份都补做,联系人上的连接跟着改指
    @Test fun aMergeRecordedJustBeforeTheProcessDiedIsRedoneOnBothStores() = runTest {
        class Died : Error("process death")
        val e = Env(this)
        val (hiking, trails) = hikingAndTrails(e)
        e.purges.afterTopicWrite = { throw Died() }
        try {
            e.runner.mergeTopic(hiking, trails)
            org.junit.Assert.fail("the injected death should interrupt the merge")
        } catch (_: Died) {}
        assertEquals(2, e.store.data.topics.size)
        assertEquals(listOf(hiking), e.contacts.data.contacts.single().topics)
        val again = newProcess(e)
        again.open()
        assertEquals(listOf(trails), e.store.data.topics.map { it.id })
        assertEquals(listOf(trails), e.contacts.data.contacts.single().topics)
        assertTrue(e.purges.topicPurges.isEmpty())
        assertTrue(e.purges.contactPurges.isEmpty())
    }

    // 同一个名字上「删掉」和「并进别的」是后做的算数,没存上、进程没了之后补做也一样:
    // 先删了「Tea」(兴趣),后把同名的另一个(在买的)并进「Drinks」→「Tea」算并掉的,不在「不再记录」里
    @Test fun unsavedDeletesAndMergesAreRedoneInTheOrderTheUserMadeThem() = runTest {
        fun topic(id: String, kind: TopicKind, label: String) = ObservedTopic(id, kind, label, start, start, mapOf("com.shop" to listOf(20_000L)))
        val tea = TopicPool.fingerprint("Tea")
        val e = Env(this)
        e.store.data = TopicPoolData(topics = listOf(
            topic("k1", TopicKind.Interest, "Tea"), topic("k3", TopicKind.Shopping, "Tea"), topic("k4", TopicKind.Routine, "Drinks")))
        e.runner.open()
        e.store.failSave = true
        e.runner.deleteTopic("k1")
        e.runner.mergeTopic("k3", "k4")
        assertEquals(0, e.runner.topicsBlocked.value)
        e.store.failSave = false
        newProcess(e).open()
        assertEquals(listOf("k4"), e.store.data.topics.map { it.id })
        assertEquals("k4", e.store.data.merged[tea])
        assertFalse(tea in e.store.data.blocked)
        // 反过来:先把一个并掉,后把同名的另一个删了 →「Tea」不再记
        val f = Env(this)
        f.store.data = TopicPoolData(topics = listOf(
            topic("k1", TopicKind.Interest, "Tea"), topic("k3", TopicKind.Shopping, "Tea"), topic("k4", TopicKind.Routine, "Drinks")))
        f.runner.open()
        f.store.failSave = true
        f.runner.mergeTopic("k3", "k4")
        f.runner.deleteTopic("k1")
        f.store.failSave = false
        newProcess(f).open()
        assertEquals(listOf("k4"), f.store.data.topics.map { it.id })
        assertTrue(f.store.data.merged.isEmpty())
        assertTrue(tea in f.store.data.blocked)
    }

    // 两个同名的话题先后并进两个不同的目标:名字算在后并的那个目标上,正着做、反着做补出来都得对
    @Test fun theLaterOfTwoSameNameMergesWinsAfterAReplay() = runTest {
        fun topic(id: String, kind: TopicKind, label: String) = ObservedTopic(id, kind, label, start, start, mapOf("com.shop" to listOf(20_000L)))
        for (reverse in listOf(false, true)) {
            val e = Env(this)
            e.store.data = TopicPoolData(topics = listOf(
                topic("k1", TopicKind.Interest, "Tea"), topic("k2", TopicKind.Interest, "Coffee"),
                topic("k3", TopicKind.Shopping, "Tea"), topic("k4", TopicKind.Shopping, "Drinks")))
            e.runner.open()
            e.store.failSave = true
            val ops = listOf("k1" to "k2", "k3" to "k4").let { if (reverse) it.reversed() else it }
            ops.forEach { (from, into) -> e.runner.mergeTopic(from, into) }
            e.store.failSave = false
            newProcess(e).open()
            assertEquals("reverse=$reverse", ops.last().second, e.store.data.merged[TopicPool.fingerprint("Tea")])
        }
    }

    // 目标是刚归纳出来、还没存上的话题(存储满了之类):这时不并。记号里只有两个 id,进程一没目标不在文件里,
    // 这次合并补不回来;并了的话,用户看到并好了,重启后并掉的那个却回来了、目标没了
    @Test fun aMergeIntoATopicThatIsNotSavedYetIsNotDone() = runTest {
        val e = Env(this)
        e.model.reply = { newTopic("n1") }
        e.post(count = 12)
        val hiking = e.store.data.topics.single().id
        e.store.failSave = true
        e.model.reply = { newTopic("n1", label = "Trails") }
        e.post(count = 12)
        val trails = e.runner.topics.value.single { it.label == "Trails" }.id
        assertEquals(listOf(hiking), e.store.data.topics.map { it.id })
        e.runner.mergeTopic(hiking, trails)
        // 没并:两个都还在,什么都没记
        assertEquals(setOf(hiking, trails), e.runner.topics.value.map { it.id }.toSet())
        assertTrue(e.purges.topicPurges.isEmpty())
        assertTrue(e.purges.contactPurges.isEmpty())
        // 存得上了:先把话题池存上,再并
        e.store.failSave = false
        e.runner.mergeTopic(hiking, trails)
        assertEquals(listOf(trails), e.store.data.topics.map { it.id })
        assertEquals(listOf(trails), e.runner.topics.value.map { it.id })
        assertTrue(e.purges.topicPurges.isEmpty())
    }

    // 连着并了两回都没存上:补做时照先后来,一路跟到最后并进去的那个
    @Test fun unsavedMergesInARowEndUpOnTheLastTopic() = runTest {
        val e = Env(this)
        val (hiking, trails) = hikingAndTrails(e)
        e.model.reply = { newTopic("n1", label = "Camping") }
        e.post(count = 12)
        val camping = e.store.data.topics.single { it.label == "Camping" }.id
        e.store.failSave = true
        e.contacts.failSave = true
        e.runner.mergeTopic(hiking, trails)
        e.runner.mergeTopic(trails, camping)
        assertEquals(3, e.store.data.topics.size)
        e.store.failSave = false
        e.contacts.failSave = false
        val again = newProcess(e)
        again.open()
        assertEquals(listOf(camping), e.store.data.topics.map { it.id })
        assertEquals(mapOf(TopicPool.fingerprint("Hiking") to camping, TopicPool.fingerprint("Trails") to camping), e.store.data.merged)
        assertEquals(listOf(camping), e.contacts.data.contacts.single().topics)
        assertTrue(e.purges.topicPurges.isEmpty())
        assertTrue(e.purges.contactPurges.isEmpty())
    }

    // 请求在途时用户把它要引用的话题并掉了:回复引用的还是并掉的那个,算到并进去的那个上,不当成「被删了」丢掉
    @Test fun aReplyCitingATopicMergedMeanwhileCountsOnTheOther() = runTest {
        val e = Env(this)
        e.model.reply = { newTopic("n1") }
        e.post(count = 12)
        e.model.reply = { newTopic("n1", label = "Trails") }
        e.post(count = 12)
        val hiking = e.store.data.topics.single { it.label == "Hiking" }.id
        val trails = e.store.data.topics.single { it.label == "Trails" }.id
        wait(LearnTuning.DAY_MS)
        e.model.gate = CompletableDeferred()
        e.post(count = 12)
        assertEquals(3, e.model.calls.size)
        // 这次给模型看的话题里「Hiking」是第几个
        val lines = e.model.input(2).lines()
        val ref = lines[lines.indexOf("Hiking") - 1].substringAfter("[").substringBefore("]")
        e.model.reply = { """{"signals":[{"ids":["n1"],"topic":"$ref"}]}""" }
        e.runner.mergeTopic(hiking, trails)
        e.model.gate!!.complete(Unit)
        runCurrent()
        val t = e.store.data.topics.single()
        assertEquals(trails, t.id)
        assertEquals(2, t.days)
        assertFalse(LearnDrop.NotApplied in e.runner.state.value.feed.getValue("com.shop").dropped)
    }

    @Test fun unreadableContactStoreDoesNotBlockTopics() = runTest {
        val e = Env(this)
        e.contacts.failLoad = true
        e.model.reply = { aliceReply }
        e.chat("a", "Alice", count = 12)
        assertEquals(1, e.model.calls.size)
        assertTrue(e.model.input(0).lines().contains("Contacts: off"))
        assertEquals(1, e.store.data.topics.size)
        assertEquals(0, e.contacts.saves)
        e.runner.open()
        assertEquals(0, e.contacts.saves)
    }

    private fun seededTopics() = TopicPoolData(topics = listOf(
        ObservedTopic("ka", TopicKind.Interest, "Hiking", 1L, start, mapOf("com.shop" to listOf(20_000L), "com.chat" to listOf(20_000L))),
        ObservedTopic("kb", TopicKind.Interest, "Diabetes", 1L, start, mapOf("com.chat" to listOf(20_000L)), sensitive = true),
    ))

    private fun seededContacts() = ContactPoolData(contacts = listOf(
        ObservedContact("c1", "com.chat", "Alice", 1L, start, relationVotes = mapOf(ContactRelation.Partner to listOf(20_000L)), days = listOf(20_000L)),
        ObservedContact("c2", "com.mail", "Bob", 1L, start, days = listOf(20_000L)),
    ))

    // 「清空全部」时四份都读不出来:连文件一起删掉,不能什么都没做(读不出来的存盘只有这一条出路)
    @Test fun clearAllWorksWhenNothingCanBeRead() = runTest {
        val e = Env(this)
        e.digest = true
        e.store.data = seededTopics(); e.store.brokenCopies = 1
        e.contacts.data = seededContacts(); e.contacts.brokenCopies = 2
        e.digestStore.data = DigestData(days = listOf(DayDigest(20_000, start, listOf(DigestLine("一句", apps = listOf("com.shop"))))))
        e.journal.data = NoticeJournalData(sinceMs = start, entries = listOf(NoticeEntry("com.shop", start, "t", text = "x")))
        e.store.failLoad = true; e.contacts.failLoad = true; e.digestStore.failLoad = true; e.journal.failLoad = true
        e.purges.topicPurges = setOf("com.old"); e.purges.contactPurges = setOf("com.old")
        e.runner.clearAll()
        assertEquals(1, e.store.wipes)
        assertEquals(1, e.contacts.wipes)
        assertEquals(1, e.digestStore.wipes)
        assertEquals(1, e.journal.wipes)
        assertTrue(e.digestStore.data.days.isEmpty())
        assertEquals(null, e.journal.data)
        assertEquals(0, e.store.brokenCopies)
        assertEquals(0, e.contacts.brokenCopies)
        assertTrue(e.store.data.topics.isEmpty())
        assertTrue(e.contacts.data.contacts.isEmpty())
        // 都清空了,记着的补删也不用做了
        assertEquals(emptySet<String>(), e.purges.topicPurges)
        assertEquals(emptySet<String>(), e.purges.contactPurges)
        // 之后照常能用:不用等读得出来
        e.model.reply = { newTopic("n1") }
        e.post(count = 12)
        assertEquals(listOf("Hiking"), e.store.data.topics.map { it.label })
    }

    // 「清空全部」读得出来、却存不上:文件里还是旧的,下次启动会读回来——把文件删掉
    @Test fun clearAllDeletesTheFileWhenTheEmptyPoolCannotBeSaved() = runTest {
        val e = Env(this)
        e.store.data = seededTopics()
        e.contacts.data = seededContacts()
        e.runner.open()
        e.store.failSave = true; e.contacts.failSave = true
        e.runner.clearAll()
        assertEquals(1, e.store.wipes)
        assertEquals(1, e.contacts.wipes)
        assertTrue(e.store.data.topics.isEmpty())
        assertTrue(e.contacts.data.contacts.isEmpty())
        assertTrue(e.runner.topics.value.isEmpty())
    }

    // 关掉「敏感类推断」并删除时读不出来:记下来,读得出来时先补删(进程没了也还记着:记在 LearnPurges 里)
    @Test fun sensitiveDeleteIsMadeUpWhenTheStoresCanBeReadAgain() = runTest {
        val e = Env(this)
        e.store.data = seededTopics()
        e.contacts.data = seededContacts()
        e.store.failLoad = true; e.contacts.failLoad = true
        e.runner.deleteSensitive()
        assertEquals(2, e.store.data.topics.size)
        assertEquals(setOf(LearnRunner.PURGE_SENSITIVE), e.purges.topicPurges)
        assertEquals(setOf(LearnRunner.PURGE_SENSITIVE), e.purges.contactPurges)
        // 换一个进程(新的 LearnRunner、同一份记录):一读出来就补删,还没等到任何别的操作
        e.store.failLoad = false; e.contacts.failLoad = false
        val again = LearnRunner(
            TriageHooks(target = { target }, model = { null }, tokensToday = { 0 }, callsToday = { 0 },
                usageVersion = { MutableStateFlow(0L) }, busy = MutableStateFlow(false)),
            on = { true }, consent = { null }, budgetLimit = { 1 }, language = { "English" }, eligible = { true }, sensitive = { false },
            store = e.store, contactStore = e.contacts, purges = e.purges, clock = { e.now() },
        )
        again.open()
        assertEquals(listOf("Hiking"), e.store.data.topics.map { it.label })
        assertTrue(e.contacts.data.contacts.none { ContactRelation.Partner in it.relationVotes })
        assertEquals(2, e.contacts.data.contacts.size)
        assertEquals(emptySet<String>(), e.purges.topicPurges)
        assertEquals(emptySet<String>(), e.purges.contactPurges)
    }

    // 「清掉这个 App 的」也一样;只有一份读不出来时,读得出来的那份当场清
    @Test fun clearAppIsMadeUpForTheStoreThatCouldNotBeRead() = runTest {
        val e = Env(this)
        e.store.data = seededTopics()
        e.contacts.data = seededContacts()
        e.contacts.failLoad = true
        e.runner.clearApp("com.chat")
        assertEquals(mapOf("Hiking" to setOf("com.shop")), e.store.data.topics.associate { it.label to it.evidence.keys })
        assertEquals(emptySet<String>(), e.purges.topicPurges)
        assertEquals(setOf("com.chat"), e.purges.contactPurges)
        assertEquals(2, e.contacts.data.contacts.size)
        e.contacts.failLoad = false
        e.runner.open()
        assertEquals(listOf("Bob"), e.contacts.data.contacts.map { it.name })
        assertEquals(emptySet<String>(), e.purges.contactPurges)
    }

    // 删了却没存上:还记着(这时进程没了,下次读出来会补做)
    @Test fun aPurgeThatCouldNotBeSavedStaysPending() = runTest {
        val e = Env(this)
        e.store.data = seededTopics()
        e.contacts.data = seededContacts()
        e.store.failSave = true; e.contacts.failSave = true
        e.runner.clearApp("com.chat")
        assertEquals(setOf("com.chat"), e.purges.topicPurges)
        assertEquals(setOf("com.chat"), e.purges.contactPurges)
        assertEquals(listOf("Hiking"), e.runner.topics.value.map { it.label })
        assertEquals(listOf("Bob"), e.runner.contactsView.value.contacts.map { it.name })
        // 文件里还是旧的
        assertEquals(2, e.store.data.topics.size)
        assertEquals(2, e.contacts.data.contacts.size)
    }

    // 删了、当时没存上、后来存上了:不再记着。否则下次启动会再删一遍,把这之后新攒的也删掉
    @Test fun aPurgeIsNotRepeatedOnceItReachedTheDisk() = runTest {
        val e = Env(this)
        e.store.data = seededTopics()
        e.contacts.data = seededContacts()
        e.store.failSave = true; e.contacts.failSave = true
        e.runner.clearApp("com.chat")
        // 存盘好了;这个 App 之后又攒出了新的话题和联系人
        e.store.failSave = false; e.contacts.failSave = false
        e.model.reply = { aliceReply }
        e.chat("a", "Alice", count = 12)
        assertEquals(emptySet<String>(), e.purges.topicPurges)
        assertEquals(emptySet<String>(), e.purges.contactPurges)
        assertEquals(setOf("com.shop", "com.chat"), e.store.data.topics.single().evidence.keys)
        assertEquals(setOf("Alice", "Bob"), e.contacts.data.contacts.map { it.name }.toSet())
        // 换一个进程再打开:新攒的还在
        val again = LearnRunner(
            TriageHooks(target = { target }, model = { null }, tokensToday = { 0 }, callsToday = { 0 },
                usageVersion = { MutableStateFlow(0L) }, busy = MutableStateFlow(false)),
            on = { true }, consent = { null }, budgetLimit = { 1 }, language = { "English" }, eligible = { true }, sensitive = { false },
            store = e.store, contactStore = e.contacts, purges = e.purges, clock = { e.now() },
        )
        again.open()
        assertEquals(setOf("com.shop", "com.chat"), e.store.data.topics.single().evidence.keys)
        assertEquals(setOf("Alice", "Bob"), e.contacts.data.contacts.map { it.name }.toSet())
    }

    // 同一个进程里对同一个 App 再点一次「清掉」:照样再删(上一次之后内存里可能又攒了新的)
    @Test fun askingAgainPurgesAgain() = runTest {
        val e = Env(this)
        e.runner.open()
        e.model.reply = { aliceReply }
        e.chat("a", "Alice", count = 12)
        e.runner.clearApp("com.chat")
        assertTrue(e.store.data.topics.isEmpty())
        e.chat("a", "Alice", count = 12)
        assertEquals(1, e.store.data.topics.size)
        e.runner.clearApp("com.chat")
        assertTrue(e.store.data.topics.isEmpty())
        assertTrue(e.contacts.data.contacts.isEmpty())
        assertEquals(emptySet<String>(), e.purges.topicPurges)
    }

    // 用户删了东西:读不出来时另存的旧文件(里面可能还有刚删的)一并删掉
    @Test fun deletionsDropTheSetAsideCopies() = runTest {
        val e = Env(this)
        e.store.data = seededTopics()
        e.contacts.data = seededContacts()
        fun copies() { e.store.brokenCopies = 1; e.contacts.brokenCopies = 1 }
        fun gone() = e.store.brokenCopies == 0 && e.contacts.brokenCopies == 0
        copies(); e.runner.open(); assertFalse("只是打开页面不删", gone())
        e.runner.deleteTopic("ka"); assertTrue(gone())
        copies(); e.runner.deleteContact("c2"); assertTrue(gone())
        copies(); e.runner.clearApp("com.chat"); assertTrue(gone())
        copies(); e.runner.deleteSensitive(); assertTrue(gone())
        copies(); e.runner.clearAll(); assertTrue(gone())
    }

    // ---- 通知日志(通知原文,「当日消息归纳」用) ----

    private fun TestScope.lockAndUnlock(e: Env) {
        e.locked.value = true; runCurrent(); e.locked.value = false; runCurrent()
    }

    // 只有「当日消息归纳」开着才记,而且只记归纳收的那些 App
    @Test fun journalTakesOnlyWhatLearningTakesAndOnlyWhenDigestIsOn() = runTest {
        val e = Env(this)
        e.post(pkg = "com.shop")
        assertEquals(0, e.runner.state.value.journal)
        e.digest = true
        e.optOut += "com.bank"
        e.post(pkg = "com.shop", count = 2)
        e.post(pkg = "com.bank")
        assertEquals(2, e.runner.state.value.journal)
        // 归纳关着时一条都不记
        e.on = false
        e.post(pkg = "com.shop")
        e.on = true
        lockAndUnlock(e)
        assertEquals(listOf("text 2", "text 3"), e.journal.texts)
    }

    // 不定时存盘:锁屏时存;攒够 20 段存一次;不再看守时存
    @Test fun journalIsSavedOnLockAndEveryTwentyEntries() = runTest {
        val e = Env(this)
        e.digest = true
        e.post(pkg = "com.a", count = 3)
        assertEquals(0, e.journal.saves)
        e.locked.value = true; runCurrent()
        assertEquals(1, e.journal.saves)
        assertEquals(3, e.journal.texts.size)
        e.locked.value = false; runCurrent()
        // 没有新的,解锁不重复存
        assertEquals(1, e.journal.saves)
        // 一个 App 攒不到 12 条就不会发请求,换着 App 来,只看存盘
        (1..LearnRunner.JOURNAL_FLUSH_EVERY).forEach { e.post(pkg = "com.b${it % 4}") }
        assertEquals(2, e.journal.saves)
        assertEquals(3 + LearnRunner.JOURNAL_FLUSH_EVERY, e.journal.texts.size)
    }

    // 到期的要删,没有新通知来也一样(下一次锁屏或解锁时)
    @Test fun journalExpiresWithoutNewNotifications() = runTest {
        val e = Env(this)
        e.digest = true
        e.post(pkg = "com.a", count = 2)
        lockAndUnlock(e)
        assertEquals(2, e.journal.texts.size)
        wait(NoticeJournal.KEEP_MS + 60_000)
        assertEquals(2, e.journal.texts.size)
        lockAndUnlock(e)
        assertEquals(0, e.journal.texts.size)
        assertEquals(0, e.runner.state.value.journal)
    }

    // 换一个进程:接着上次存下的;同一条通知原样重发不再记一遍
    @Test fun journalSurvivesARestartWithoutDuplicates() = runTest {
        val e = Env(this)
        e.digest = true
        e.runner.capture(NoticeRecord("k", "com.chat", e.now(), "家长群", "明天开会", sender = "王老师", group = true))
        e.runner.capture(NoticeRecord("g", "com.gone", e.now(), "别的", "别的"))
        lockAndUnlock(e)
        val again = LearnRunner(
            TriageHooks(target = { target }, model = { null }, tokensToday = { 0 }, callsToday = { 0 },
                usageVersion = { MutableStateFlow(0L) }, busy = MutableStateFlow(false)),
            on = { true }, consent = { TriageConsent.of(target) }, budgetLimit = { 1 }, language = { "English" }, eligible = { true }, sensitive = { false },
            store = e.store, digest = { true }, journalStore = e.journal, clock = { e.now() },
        )
        again.capture(NoticeRecord("k", "com.chat", e.now() + 5, "家长群", "明天开会", sender = "王老师", group = true))
        again.capture(NoticeRecord("k", "com.chat", e.now() + 9, "家长群", "收到", sender = "李老师", group = true))
        assertEquals(3, again.state.value.journal)
        // 清掉另一个 App 的:有改动,存一次盘(这个实例没在看守,没有锁屏来触发存盘)
        again.clearApp("com.gone")
        assertEquals(listOf("王老师" to "明天开会", "李老师" to "收到"), e.journal.data!!.entries.map { it.sender to it.text })
    }

    /** 换一个进程:同样的文件、同样记着的待删,新的实例。 */
    private fun newProcess(e: Env) = LearnRunner(
        TriageHooks(target = { target }, model = { null }, tokensToday = { 0 }, callsToday = { 0 },
            usageVersion = { MutableStateFlow(0L) }, busy = MutableStateFlow(false)),
        on = { true }, consent = { TriageConsent.of(target) }, budgetLimit = { 1 }, language = { "English" },
        eligible = { it !in e.optOut }, sensitive = { false },
        store = e.store, contactStore = e.contacts, purges = e.purges,
        digest = { true }, journalStore = e.journal, digestStore = e.digestStore, clock = { e.now() }, zone = { ZoneOffset.UTC },
    )

    private fun rawApps(e: Env) = e.journal.data?.entries.orEmpty().map { it.packageName }

    // 不再从这个 App 归纳时原文那份正好读不出来:记下来,读得出来时先补删。不然之后这个 App 又被允许,旧原文就又回来了
    @Test fun revokedAppsRawTextIsRemovedOnceTheJournalLoadsAgain() = runTest {
        val e = Env(this)
        e.journal.data = NoticeJournalData(sinceMs = e.now() - 2_000, entries = listOf(
            NoticeEntry("com.a", e.now() - 1_000, "旧会话", text = "撤销之前的原文"),
            NoticeEntry("com.b", e.now() - 900, "别的", text = "别的 App 的"),
        ))
        e.digest = true
        e.journal.failLoad = true
        e.optOut += "com.a"
        e.runner.forgetApps(setOf("com.a"))
        assertEquals(setOf("com.a"), e.purges.journalPurges)
        assertEquals("读不出来不整份删", 0, e.journal.wipes)
        // 之后读得出来了,这个 App 也重新被允许
        e.journal.failLoad = false
        e.optOut -= "com.a"
        val view = e.runner.notices(e.now() - 10_000, e.now() + 1, LearnLevel.Balanced) { SourceTier.High }!!
        assertEquals(listOf("com.b"), view.entries.map { it.packageName })
        assertEquals(listOf("com.b"), rawApps(e))
        assertTrue("存上了就不再记着", e.purges.journalPurges.isEmpty())
        // 重新允许之后新来的照常记、照常留着:换一个进程也不会被上次的待删带走
        e.runner.capture(NoticeRecord("n1", "com.a", e.now(), "新会话", "重新允许之后的"))
        lockAndUnlock(e)
        val again = newProcess(e)
        assertEquals(listOf("com.b", "com.a"), again.notices(e.now() - 10_000, e.now() + 1, LearnLevel.Balanced) { SourceTier.High }!!.entries.map { it.packageName })
    }

    // 删了却没存上、进程又没了:文件里还是旧的。记着的待删在下次读出来时补做
    @Test fun revokedAppsRawTextStaysDeletedAfterAFailedSaveAndARestart() = runTest {
        val e = Env(this)
        e.digest = true
        e.post(pkg = "com.a", count = 2)
        e.post(pkg = "com.b", count = 1)
        lockAndUnlock(e)
        assertEquals(3, rawApps(e).size)
        e.journal.failSave = true
        e.optOut += "com.a"
        e.runner.forgetApps(setOf("com.a"))
        assertEquals("没存上:文件里还是三条", 3, rawApps(e).size)
        assertEquals(setOf("com.a"), e.purges.journalPurges)
        e.journal.failSave = false
        e.optOut -= "com.a"
        val again = newProcess(e)
        assertEquals(listOf("com.b"), again.notices(e.now() - 10_000, e.now() + 1, LearnLevel.Balanced) { SourceTier.High }!!.entries.map { it.packageName })
        assertEquals(listOf("com.b"), rawApps(e))
        assertTrue(e.purges.journalPurges.isEmpty())
    }

    // 「清掉这个 App 的」时原文读不出来:整份删;连删也删不掉的话记着这个 App,读得出来时补做
    @Test fun clearingAnAppWhileTheJournalCanNeitherBeReadNorDeletedIsReplayedLater() = runTest {
        val e = Env(this)
        e.journal.data = NoticeJournalData(sinceMs = e.now() - 2_000, entries = listOf(
            NoticeEntry("com.a", e.now() - 1_000, "旧会话", text = "要清掉的"),
            NoticeEntry("com.b", e.now() - 900, "别的", text = "别的 App 的"),
        ))
        e.digest = true
        e.journal.failLoad = true
        e.journal.failWipe = true
        e.runner.clearApp("com.a")
        // 读不出来时本来是整份删;删不掉,记着的就是「全清」
        assertEquals(setOf(":all"), e.purges.journalPurges)
        e.journal.failLoad = false
        e.journal.failWipe = false
        assertTrue(newProcess(e).notices(e.now() - 10_000, e.now() + 1, LearnLevel.Balanced) { SourceTier.High }!!.entries.isEmpty())
        assertTrue(e.journal.data!!.entries.isEmpty())
        assertTrue(e.purges.journalPurges.isEmpty())
        // 删得掉的时候整份删了,就没有要补做的
        val e2 = Env(this)
        e2.journal.data = NoticeJournalData(sinceMs = e2.now() - 2_000, entries = listOf(NoticeEntry("com.a", e2.now() - 1_000, "旧会话", text = "要清掉的")))
        e2.digest = true
        e2.journal.failLoad = true
        e2.runner.clearApp("com.a")
        assertEquals(1, e2.journal.wipes)
        assertTrue(e2.purges.journalPurges.isEmpty())
    }

    // 页面要知道还留着哪些 App 的通知原文(只有包名):只有原文、还没归纳出东西的 App 也得能单独清掉
    @Test fun stateListsTheAppsThatStillHaveRawText() = runTest {
        val e = Env(this)
        e.digest = true
        e.post(pkg = "com.a", count = 2)
        e.post(pkg = "com.b", count = 1)
        assertEquals(setOf("com.a", "com.b"), e.runner.state.value.journalApps)
        e.runner.clearApp("com.a")
        assertEquals(setOf("com.b"), e.runner.state.value.journalApps)
        // 换一个进程、没在看守:打开内容页时把原文读出来,页面上照样列得出
        lockAndUnlock(e)
        val again = newProcess(e)
        assertEquals(emptySet<String>(), again.state.value.journalApps)
        again.open()
        assertEquals(setOf("com.b"), again.state.value.journalApps)
    }

    // 清空全部时,有一份既读不出来、文件也删不掉:不能当成清掉了。记一个「全清」,读得出来时照它清空
    @Test fun clearAllThatCouldNotWipeAFileIsFinishedWhenItLoadsAgain() = runTest {
        val e = Env(this)
        e.digest = true
        e.store.data = TopicPoolData(topics = listOf(ObservedTopic("k1", TopicKind.Interest, "徒步", 1L, start).on("com.a" to listOf(20_000L, 19_999L))))
        e.contacts.data = ContactPoolData(contacts = listOf(ObservedContact("c1", "com.a", "张三", 1L, start, days = listOf(20_000L))))
        e.journal.data = NoticeJournalData(sinceMs = e.now() - 2_000, entries = listOf(NoticeEntry("com.a", e.now() - 1_000, "会话", text = "原文")))
        e.digestStore.data = DigestData(days = listOf(DayDigest(20_000, start, listOf(DigestLine("一句", apps = listOf("com.a"))))))
        e.store.failLoad = true; e.store.failWipe = true
        e.contacts.failLoad = true; e.contacts.failWipe = true
        e.journal.failLoad = true; e.journal.failWipe = true
        e.digestStore.failLoad = true; e.digestStore.failWipe = true
        e.runner.clearAll()
        assertEquals(setOf(":all"), e.purges.topicPurges)
        assertEquals(setOf(":all"), e.purges.contactPurges)
        assertEquals(setOf(":all"), e.purges.journalPurges)
        assertEquals(setOf(":all"), e.purges.digestPurges)
        // 存储好了,换一个进程:读出来时都清空,并且存上、不再记着
        e.store.failLoad = false; e.store.failWipe = false
        e.contacts.failLoad = false; e.contacts.failWipe = false
        e.journal.failLoad = false; e.journal.failWipe = false
        e.digestStore.failLoad = false; e.digestStore.failWipe = false
        val again = newProcess(e)
        again.open()
        assertTrue(again.topics.value.isEmpty())
        assertTrue(again.contactsView.value.contacts.isEmpty())
        assertTrue(again.digestDays.value.isEmpty())
        assertEquals(0, again.state.value.journal)
        assertTrue(e.store.data.topics.isEmpty())
        assertTrue(e.contacts.data.contacts.isEmpty())
        assertTrue(e.journal.data!!.entries.isEmpty())
        assertTrue(e.digestStore.data.days.isEmpty())
        for (left in listOf(e.purges.topicPurges, e.purges.contactPurges, e.purges.journalPurges, e.purges.digestPurges)) assertTrue(left.isEmpty())
    }

    // 单删一个联系人没存上(内存里已经空了,文件里还在),再清空全部:内存是空的也得把文件清掉,不能只把待删划掉
    @Test fun clearAllAfterAnUnsavedContactDeleteStillCleansTheFile() = runTest {
        fun seeded() = Env(this).also {
            it.contacts.data = ContactPoolData(contacts = listOf(ObservedContact("c1", "com.a", "张三", 1L, start, days = listOf(20_000L))))
        }
        // 清空时存储已经好了:存上空的,不再记着
        seeded().let { e ->
            e.contacts.failSave = true
            e.runner.deleteContact("c1")
            assertEquals(setOf(":contact:c1"), e.purges.contactPurges)
            assertEquals("没存上,文件里还在", 1, e.contacts.data.contacts.size)
            e.contacts.failSave = false
            e.runner.clearAll()
            assertTrue(e.contacts.data.contacts.isEmpty())
            assertTrue(e.purges.contactPurges.isEmpty())
            val again = newProcess(e)
            again.open()
            assertTrue(again.contactsView.value.contacts.isEmpty())
        }
        // 清空时还是存不上:退到删文件
        seeded().let { e ->
            e.contacts.failSave = true
            e.runner.deleteContact("c1")
            e.runner.clearAll()
            assertEquals(1, e.contacts.wipes)
            assertTrue(e.contacts.data.contacts.isEmpty())
            assertTrue(e.purges.contactPurges.isEmpty())
        }
        // 清空时既存不上也删不掉:待删不能丢,记成「全清」;存储好了之后补清
        seeded().let { e ->
            e.contacts.failSave = true
            e.runner.deleteContact("c1")
            e.contacts.failWipe = true
            e.runner.clearAll()
            assertEquals(setOf(":all"), e.purges.contactPurges)
            assertEquals(1, e.contacts.data.contacts.size)
            e.contacts.failSave = false
            e.contacts.failWipe = false
            val again = newProcess(e)
            again.open()
            assertTrue(again.contactsView.value.contacts.isEmpty())
            assertTrue(e.contacts.data.contacts.isEmpty())
            assertTrue(e.purges.contactPurges.isEmpty())
        }
    }

    // 第一次清空全部时联系人既没存上也没删掉,存储好了之后在同一个进程里再清一次:内存早就空了,这一次也要真的把文件清掉
    @Test fun aSecondClearAllFinishesWhatTheFirstCouldNot() = runTest {
        val e = Env(this)
        e.contacts.data = ContactPoolData(contacts = listOf(ObservedContact("c1", "com.a", "张三", 1L, start, days = listOf(20_000L))))
        e.contacts.failSave = true
        e.contacts.failWipe = true
        e.runner.clearAll()
        assertEquals(setOf(":all"), e.purges.contactPurges)
        assertEquals(1, e.contacts.data.contacts.size)
        e.contacts.failSave = false
        e.contacts.failWipe = false
        e.runner.clearAll()
        assertTrue(e.contacts.data.contacts.isEmpty())
        assertTrue(e.purges.contactPurges.isEmpty())
    }

    // 以前按通知键存下来的联系人:读出来时同名的并成一条并存上;记着的、没存上的删除(记的是以前的 id)先补做,删掉的不借着合并回来
    @Test fun storedContactsAreMergedByConversationNameWhenLoaded() = runTest {
        fun old(key: String, title: String) = com.wangzan101.mika.core.proactive.ContactIdentity.of(
            "com.chat", key, title, com.wangzan101.mika.core.proactive.ContactIdStrategy.KeyAndTitle)!!.id
        val e = Env(this)
        e.contacts.data = ContactPoolData(contacts = listOf(
            ObservedContact(old("k1", "家长群"), "com.chat", "家长群", 1L, start, days = listOf(19_998L)),
            ObservedContact(old("k2", "家长群"), "com.chat", "家长群", 1L, start, days = listOf(19_999L)),
            ObservedContact(old("k3", "家长群"), "com.chat", "家长群", 1L, start, days = listOf(20_000L)),
            ObservedContact(old("k4", "删掉的群"), "com.chat", "删掉的群", 1L, start, days = listOf(20_000L)),
            ObservedContact(old("k5", "删掉的群"), "com.chat", "删掉的群", 1L, start, days = listOf(19_999L)),
        ))
        e.purges.contactPurges = setOf(":contact:" + old("k4", "删掉的群"))
        e.runner.open()
        val shown = e.runner.contactsView.value.contacts
        assertEquals(listOf("家长群"), shown.map { it.name })
        assertEquals(listOf(19_998L, 19_999L, 20_000L), shown.single().days)
        assertTrue(shown.single().established)
        // 存上了:文件里也是并好的一条,记着的删除划掉了
        assertEquals(1, e.contacts.data.contacts.size)
        assertTrue(e.contacts.data.contacts.single().id.startsWith("t"))
        assertTrue(e.purges.contactPurges.isEmpty())
        // 换一个进程再读:不再动
        val saves = e.contacts.saves
        val again = newProcess(e)
        again.open()
        assertEquals(1, again.contactsView.value.contacts.size)
        assertEquals(saves, e.contacts.saves)
    }

    // 换身份之后没存上(存储满了之类),用户删掉了换好的那一条,也没存上,进程没了。下次读出来的还是换之前的文件:
    // 记着的删除要作数,同名的那几条不能再换一遍身份就回来
    @Test fun aDeleteMadeAfterAnUnsavedMigrationStillHolds() = runTest {
        fun old(key: String, title: String) = com.wangzan101.mika.core.proactive.ContactIdentity.of(
            "com.chat", key, title, com.wangzan101.mika.core.proactive.ContactIdStrategy.KeyAndTitle)!!.id
        val e = Env(this)
        e.contacts.data = ContactPoolData(contacts = listOf(
            ObservedContact(old("k1", "家长群"), "com.chat", "家长群", 1L, start, days = listOf(19_999L)),
            ObservedContact(old("k2", "家长群"), "com.chat", "家长群", 1L, start, days = listOf(20_000L)),
        ))
        e.contacts.failSave = true
        e.runner.open()
        val merged = e.runner.contactsView.value.contacts.single()
        assertTrue(merged.id.startsWith("t"))
        e.runner.deleteContact(merged.id)
        assertEquals(2, e.contacts.data.contacts.size)
        assertEquals(1, e.purges.contactPurges.size)
        e.contacts.failSave = false
        val again = newProcess(e)
        again.open()
        assertTrue(again.contactsView.value.contacts.isEmpty())
        assertTrue(e.contacts.data.contacts.isEmpty())
        assertTrue(merged.id in e.contacts.data.blocked)
        assertTrue(e.purges.contactPurges.isEmpty())
    }

    // 清空全部正常做完:没有记着的待删(以前记着的也一并划掉)
    @Test fun clearAllLeavesNoPendingDeletionsWhenItWorked() = runTest {
        val e = Env(this)
        e.digest = true
        e.post(pkg = "com.a", count = 2)
        e.purges.topicPurges = setOf(":topic:k9")
        e.purges.digestPurges = setOf("com.gone")
        e.purges.journalPurges = setOf("com.gone")
        e.runner.clearAll()
        for (left in listOf(e.purges.topicPurges, e.purges.contactPurges, e.purges.journalPurges, e.purges.digestPurges)) assertTrue(left.toString(), left.isEmpty())
    }

    // 一个通知键被认成反复重发的状态显示(导航播报之类):它刚收进来、还没归纳的那几条去掉,通知原文里的也去掉;别的键不动
    @Test fun aKeyRecognisedAsAStatusDisplayLosesWhatItJustAdded() = runTest {
        val e = Env(this)
        e.digest = true
        // 同一个键上一小时前的一条正常通知(很多 App 所有推送共用一个通知键):不能跟着丢
        e.runner.capture(NoticeRecord("nav", "com.maps", e.now(), "常去的地方有新的评价", "点开看看"))
        wait(3_600_000L)
        val burst = e.now()
        repeat(3) { i -> e.runner.capture(NoticeRecord("nav", "com.maps", burst + i * 2_000L, "${300 - i * 10} 米后右转", "某某路")) }
        e.runner.capture(NoticeRecord("promo", "com.maps", burst + 9, "周边优惠", "满减券到账"))
        runCurrent()
        assertEquals(5, e.runner.state.value.journal)
        e.runner.dropLive("nav", "com.maps", burst + 6_000L - 60_000L)
        assertEquals(listOf("常去的地方有新的评价", "周边优惠"), e.journal.data!!.entries.map { it.title })
        assertEquals(2, e.runner.state.value.journal)
        // 没有这个键的东西时什么都不做
        val saves = e.journal.saves
        e.runner.dropLive("nothing", "com.maps", 0)
        assertEquals(saves, e.journal.saves)
    }

    // 认出一个键是状态显示时,正在给这个 App 写分段归纳的请求取消:不然回复回来,照着刚去掉的那几条原文写的句子还是进了归纳。
    // 别的 App 的请求不动
    @Test fun aNotesRequestInFlightIsCancelledWhenItsAppTurnsOutToPostAStatusDisplay() = runTest {
        val e = Env(this)
        e.digest = true
        e.post(pkg = "com.maps", count = 19)
        e.model.gate = CompletableDeferred()
        e.model.notesReply = { oneNote }
        e.post(pkg = "com.maps")
        assertTrue(e.runner.requestActive)
        e.runner.dropLive("x", "com.other", 0)
        assertTrue(e.runner.requestActive)
        e.runner.dropLive("k20", "com.maps", e.now() - 60_000L)
        assertFalse(e.runner.requestActive)
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(e.digestStore.data.notes.isEmpty())
        assertEquals(19, e.runner.state.value.journal)
    }

    // 认出状态显示时,归纳话题的请求正带着它刚收进来的那几条在途:取消,那几条去掉。不然回复回来,照着它们归纳出的话题还是存下了;
    // 锁屏再取消一次的话,它们回到待归纳,解锁后又发给模型
    @Test fun aLearnRequestInFlightIsCancelledWhenItCarriesAStatusDisplay() = runTest {
        val e = Env(this)
        repeat(9) { i -> e.runner.capture(NoticeRecord("normal-$i", "com.maps", e.now(), "普通通知 $i", "普通内容 $i")) }
        e.model.gate = CompletableDeferred()
        e.model.reply = { """{"signals":[{"ids":["n10"],"new":{"kind":"routine","label":"导航播报"}}]}""" }
        repeat(3) { i -> e.runner.capture(NoticeRecord("nav", "com.maps", e.now() + i * 2_000L, "导航", "NAV_TEXT_$i")) }
        runCurrent()
        assertEquals(1, e.model.calls.size)
        assertTrue(e.runner.requestActive)
        assertTrue(e.model.input(0).contains("NAV_TEXT_0"))
        e.runner.dropLive("nav", "com.maps", e.now() - 60_000L)
        runCurrent()
        assertFalse(e.runner.requestActive)
        assertEquals(1, e.model.cancelled)
        assertEquals(9, e.runner.state.value.pending)
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(e.store.data.topics.isEmpty())
        // 剩下的照常再发(到期之后),里面没有那几条
        e.model.gate = null
        e.model.reply = { """{"signals":[]}""" }
        wait(LearnTuning.DUE_AGE_MS + 1)
        e.post(pkg = "com.other")
        val resent = e.model.calls.indices.first { it > 0 && e.model.packages()[it] == "com.maps" }
        assertTrue(e.model.input(resent).contains("普通内容 0"))
        assertFalse(e.model.input(resent).contains("NAV_TEXT"))
        // 别的键、这个键更早的事件在途时不取消
        val f = Env(this)
        f.model.gate = CompletableDeferred()
        f.post(pkg = "com.maps", count = 12)
        assertTrue(f.runner.requestActive)
        f.runner.dropLive("nav", "com.maps", f.now() - 60_000L)
        f.runner.dropLive("k1", "com.maps", f.now() + 1)
        assertTrue(f.runner.requestActive)
    }

    // 认出状态显示的那一刻通知原文读不出来(密钥库一时不可用):记下来,读得出来时先去掉那一阵的再选批。
    // 不然它们还算没归纳过,下一次分段归纳又发给模型。同一个键上之后的正常通知不动
    @Test fun aStatusDisplaysRawTextIsRemovedOnceTheJournalLoadsAgain() = runTest {
        val e = Env(this)
        e.digest = true
        val since = start - 60_000L
        val keyId = com.wangzan101.mika.core.proactive.NoticeDedup.idOf("nav")
        e.journal.data = NoticeJournalData(sinceMs = start - 3_600_000L, entries = listOf(
            NoticeEntry("com.maps", start - 3_000_000L, "更早的正常通知", text = "同一个键", keyId = keyId),
            NoticeEntry("com.maps", start - 30_000L, "导航", text = "300 米后右转", keyId = keyId),
            NoticeEntry("com.maps", start - 20_000L, "导航", text = "200 米后右转", keyId = keyId),
            NoticeEntry("com.maps", start - 10_000L, "周边优惠", text = "另一个键", keyId = "other"),
            NoticeEntry("com.maps", start + 600_000L, "之后的正常通知", text = "同一个键", keyId = keyId),
        ))
        e.journal.failLoad = true
        e.runner.dropLive("nav", "com.maps", since)
        assertEquals(1, e.purges.journalPurges.size)
        assertEquals(5, e.journal.data!!.entries.size)
        e.journal.failLoad = false
        e.runner.open()
        assertEquals(listOf("更早的正常通知", "周边优惠", "之后的正常通知"), e.journal.data!!.entries.map { it.title })
        assertTrue(e.purges.journalPurges.isEmpty())
    }

    // 原文去掉了却没存上、进程没了:文件里还是旧的,下次读出来补做
    @Test fun aStatusDisplaysRawTextRemovalSurvivesAFailedSave() = runTest {
        val e = Env(this)
        e.digest = true
        wait(60_000L)
        repeat(3) { i -> e.runner.capture(NoticeRecord("nav", "com.maps", e.now() - 6_000L + i * 2_000L, "${300 - i * 10} 米后右转", "某某路")) }
        e.runner.capture(NoticeRecord("promo", "com.maps", e.now() - 9, "周边优惠", "满减券到账"))
        runCurrent()
        lockAndUnlock(e)
        assertEquals(4, e.journal.data!!.entries.size)
        e.journal.failSave = true
        e.runner.dropLive("nav", "com.maps", e.now() - 60_000L)
        assertEquals(4, e.journal.data!!.entries.size)
        assertEquals(1, e.purges.journalPurges.size)
        e.journal.failSave = false
        val again = newProcess(e)
        again.open()
        assertEquals(listOf("周边优惠"), e.journal.data!!.entries.map { it.title })
        assertTrue(e.purges.journalPurges.isEmpty())
    }

    // 每一条清除路径都要把通知原文带走(和「禁用 App 后要清掉排着队的」是同一类)
    @Test fun journalFollowsEveryPurgePath() = runTest {
        fun Env.seed(): Env {
            digest = true
            post(pkg = "com.a", count = 2)
            post(pkg = "com.b", count = 1)
            return this
        }
        fun apps(e: Env) = e.journal.data?.entries.orEmpty().map { it.packageName }
        // 取消勾选、在「应用访问」里禁用、「不从它归纳」:都是这个 App 不再归纳,然后重新看一遍
        Env(this).seed().let { e ->
            e.optOut += "com.a"
            e.runner.forgetApps(setOf("com.a"))
            assertEquals(listOf("com.b"), apps(e))
            assertEquals(1, e.runner.state.value.journal)
        }
        // 清掉这个 App 的
        Env(this).seed().let { e ->
            e.runner.clearApp("com.a")
            assertEquals(listOf("com.b"), apps(e))
        }
        // 清空全部
        Env(this).seed().let { e ->
            lockAndUnlock(e)
            assertEquals(3, apps(e).size)
            e.runner.clearAll()
            assertEquals(emptyList<String>(), apps(e))
            assertEquals(0, e.runner.state.value.journal)
        }
        // 关掉「当日消息归纳」
        Env(this).seed().let { e ->
            lockAndUnlock(e)
            e.digest = false
            e.runner.poke()
            assertEquals(emptyList<String>(), apps(e))
            assertTrue(e.journal.wipes > 0)
            assertEquals(0, e.runner.state.value.journal)
        }
        // 关掉归纳、关掉主动能力(都走 clear)
        Env(this).seed().let { e ->
            lockAndUnlock(e)
            e.on = false
            e.runner.clear()
            assertEquals(emptyList<String>(), apps(e))
            assertEquals(0, e.runner.state.value.journal)
        }
    }

    // 日志一时读不出来:不记、不存(不拿空的去盖);「清空全部」「清掉这个 App 的」照样把文件删掉
    @Test fun unreadableJournalIsNeverOverwrittenButCanStillBeWiped() = runTest {
        val e = Env(this)
        e.digest = true
        e.post(pkg = "com.a", count = 2)
        lockAndUnlock(e)
        val saved = e.journal.data
        // 换一个进程,这时读不出来
        e.journal.failLoad = true
        val again = LearnRunner(
            TriageHooks(target = { target }, model = { null }, tokensToday = { 0 }, callsToday = { 0 },
                usageVersion = { MutableStateFlow(0L) }, busy = MutableStateFlow(false)),
            on = { true }, consent = { TriageConsent.of(target) }, budgetLimit = { 1 }, language = { "English" }, eligible = { true }, sensitive = { false },
            store = e.store, digest = { true }, journalStore = e.journal, clock = { e.now() },
        )
        val saves = e.journal.saves
        again.capture(NoticeRecord("x", "com.a", e.now(), "t", "new"))
        again.poke()
        assertEquals(saves, e.journal.saves)
        assertEquals(saved, e.journal.data)
        again.clearApp("com.a")
        assertEquals(null, e.journal.data)
        e.journal.data = saved
        again.clearAll()
        assertEquals(null, e.journal.data)
    }

    // ---- 当日消息归纳的第 1 级:分段归纳 ----

    private val oneNote = """{"notes":[{"ids":["e1","e2"],"text":"买的东西发货了"}]}"""

    // 一个 App 攒够 20 段没归纳过的:发一批(只有这个 App、没有工具),记下句子和它的依据,这一批不再发
    @Test fun notesBatchIsSentWhenDueAndStoredWithItsGrounds() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { oneNote }
        e.post(pkg = "com.shop", count = LearnTuning.NOTES_DUE_COUNT - 1)
        // 归纳话题的那一批(12 条)先发过了;分段归纳还差一段
        assertEquals(listOf(false), e.model.calls.indices.map(e.model::isNotes))
        e.post(pkg = "com.shop")
        assertEquals(listOf(false, true), e.model.calls.indices.map(e.model::isNotes))
        assertTrue(e.model.tools.last().isEmpty())
        assertEquals(LearnTuning.NOTES_BATCH_MAX, Regex("\\[e\\d+] time:").findAll(e.model.input(1)).count())
        val note = e.digestStore.data.notes.single()
        assertEquals("买的东西发货了", note.text)
        assertEquals("com.shop", note.packageName)
        assertEquals(2, note.count)
        assertEquals(listOf("买的东西发货了"), e.runner.digestNotes.value.map { it.text })
        // 这 20 段记成做过了(原文还留着),不再发
        assertEquals(LearnTuning.NOTES_DUE_COUNT, e.journal.data!!.entries.count { it.noted })
        e.busy.value = true; runCurrent(); e.busy.value = false; runCurrent()
        assertEquals(2, e.model.calls.size)
        assertEquals(LearnStatus.Waiting, e.runner.state.value.status)
    }

    // 攒不够 20 段的,最旧的一段过了两小时也发(有事发生时才看,不定时)
    @Test fun notesBatchIsSentWhenItsOldestEntryIsOldEnough() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { oneNote }
        e.post(pkg = "com.a", count = 3)
        wait(LearnTuning.NOTES_DUE_AGE_MS)
        assertTrue(e.model.calls.none { e.model.isNotes(e.model.calls.indexOf(it)) })
        // 归纳话题的那一批(过了 30 分钟)先发,回来之后接着发分段归纳
        e.post(pkg = "com.other")
        assertEquals(listOf("com.a" to false, "com.a" to true), e.model.calls.indices.map { e.model.packages()[it] to e.model.isNotes(it) })
        assertEquals(1, e.digestStore.data.notes.size)
    }

    // 回复读不懂:这一批算做过(不再发同一批),停一段;根本没连上:留着,停一段之后再发同一批
    @Test fun notesFailuresFollowTheSameRulesAsLearning() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.reply = { """{"signals":[]}""" }
        e.model.notesReply = { "I cannot do that." }
        e.post(pkg = "com.a", count = 20)
        assertEquals(1, e.model.calls.indices.count(e.model::isNotes))
        assertEquals(20, e.journal.data!!.entries.count { it.noted })
        assertTrue(e.digestStore.data.notes.isEmpty())
        assertEquals(LearnStatus.Waiting, e.runner.state.value.status)
        // 没连上
        val f = Env(this)
        f.digest = true
        f.model.reply = { """{"signals":[]}""" }
        f.post(pkg = "com.a", count = 19)
        f.model.error = ModelError.Network()
        f.post(pkg = "com.a")
        val notesCalls = { f.model.calls.indices.count(f.model::isNotes) }
        assertEquals(1, notesCalls())
        assertEquals(0, f.journal.data?.entries.orEmpty().count { it.noted })
        f.model.error = null
        f.model.notesReply = { oneNote }
        f.busy.value = true; runCurrent(); f.busy.value = false; runCurrent()
        assertEquals("停着的时候不发", 1, notesCalls())
        wait(LearnTuning.failureHoldMs(1))
        f.busy.value = true; runCurrent(); f.busy.value = false; runCurrent()
        assertEquals(2, notesCalls())
        assertEquals(listOf("买的东西发货了"), f.digestStore.texts)
    }

    // 锁屏:正在发的分段归纳取消,那一批留着,解锁后重发
    @Test fun lockCancelsTheNotesRequestAndKeepsTheBatch() = runTest {
        val e = Env(this)
        e.digest = true
        e.post(pkg = "com.a", count = 19)
        e.model.gate = CompletableDeferred()
        e.model.notesReply = { oneNote }
        e.post(pkg = "com.a")
        assertTrue(e.runner.requestActive)
        e.locked.value = true; runCurrent()
        assertFalse(e.runner.requestActive)
        assertTrue(e.digestStore.data.notes.isEmpty())
        e.model.gate = null
        e.locked.value = false; runCurrent()
        assertEquals(listOf("买的东西发货了"), e.digestStore.texts)
    }

    // 清除的几条路都带走攒下的句子;关开关不带走(只删通知原文);请求在途时清空的,结果回来不能把清掉的带回来
    @Test fun notesFollowEveryPurgePath() = runTest {
        fun seeded(): Env = Env(this).also { e ->
            e.digest = true
            e.model.notesReply = { oneNote }
            e.post(pkg = "com.a", count = 20)
            e.post(pkg = "com.b", count = 20)
            assertEquals(listOf("com.a", "com.b"), e.digestStore.data.notes.map { it.packageName })
        }
        fun apps(e: Env) = e.digestStore.data.notes.map { it.packageName }
        seeded().let { e -> e.optOut += "com.a"; e.runner.forgetApps(setOf("com.a")); assertEquals(listOf("com.b"), apps(e)); assertEquals(1, e.runner.digestNotes.value.size) }
        seeded().let { e -> e.runner.clearApp("com.a"); assertEquals(listOf("com.b"), apps(e)) }
        seeded().let { e -> e.runner.clearAll(); assertEquals(emptyList<String>(), apps(e)); assertTrue(e.runner.digestNotes.value.isEmpty()) }
        // 关掉「当日消息归纳」、关掉总开关:通知原文删掉,已经写下的句子留着(页面上还看得到、删得掉)
        seeded().let { e ->
            e.digest = false; e.runner.poke()
            assertEquals(listOf("com.a", "com.b"), apps(e)); assertEquals(0, e.digestStore.wipes); assertEquals(null, e.journal.data)
            assertEquals(2, e.runner.digestNotes.value.size)
        }
        seeded().let { e ->
            e.on = false; e.runner.clear()
            assertEquals(listOf("com.a", "com.b"), apps(e)); assertEquals(0, e.digestStore.wipes); assertEquals(null, e.journal.data)
        }
        seeded().let { e ->
            val id = e.digestStore.data.notes.first().id
            e.runner.deleteNote(id)
            assertEquals(listOf("com.b"), apps(e))
        }
        // 在途时清空
        val e = Env(this)
        e.digest = true
        e.post(pkg = "com.a", count = 19)
        e.model.gate = CompletableDeferred()
        e.model.notesReply = { oneNote }
        e.post(pkg = "com.a")
        assertTrue(e.runner.requestActive)
        e.runner.clearAll()
        assertFalse(e.runner.requestActive)
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(e.digestStore.data.notes.isEmpty())
        assertEquals(null, e.journal.data)
    }

    // 页面上点开一句分段归纳:给出它那段时间、那个 App 还存着的通知;过了两天的没有了;关掉「当日消息归纳」之后说的是「关着」
    @Test fun aNotesRawNotificationsCanBeLookedUpWhileTheyLast() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { oneNote }
        e.post(pkg = "com.shop", count = 20)
        val note = e.digestStore.data.notes.single()
        val found = e.runner.rawFor(note) as RawNotices.Found
        assertTrue(found.entries.isNotEmpty())
        assertTrue(found.entries.all { it.packageName == "com.shop" && it.atMs in note.fromMs..note.toMs })
        wait(NoticeJournal.KEEP_MS + 1)
        assertEquals(RawNotices.Found(emptyList()), e.runner.rawFor(note))
        e.digest = false
        assertEquals(RawNotices.Off, e.runner.rawFor(note))
        e.digest = true
        e.on = false
        assertEquals(RawNotices.Off, e.runner.rawFor(note))
    }

    // ---- 当日消息归纳的第 2 级:当日归纳 ----

    private val oneLine = """{"lines":[{"ids":["s1"],"text":"买的东西发货了,今天到"}]}"""
    private fun TestScope.nudge(e: Env) { e.busy.value = true; runCurrent(); e.busy.value = false; runCurrent() }

    /** 今天有一句分段归纳的环境。 */
    private fun TestScope.withANote(): Env = Env(this).also { e ->
        e.digest = true
        e.model.notesReply = { oneNote }
        e.model.dayReply = { oneLine }
        e.post(pkg = "com.shop", count = 20)
        assertEquals(listOf("learn", "notes"), e.model.kinds)
    }

    // 一天过完之后(有事发生时才看)把那一天的句子合成一份;放进请求的只有句子和 App 的名字;做过了不重做
    @Test fun dayDigestIsMadeOnceTheDayIsOver() = runTest {
        val e = withANote()
        nudge(e)
        assertFalse("当天不自动做", "day" in e.model.kinds)
        wait(LearnTuning.DAY_MS)
        assertFalse("没有定时器", "day" in e.model.kinds)
        nudge(e)
        assertEquals("day", e.model.kinds.last())
        val input = e.model.input(e.model.calls.lastIndex)
        assertTrue(input, "[s1] app: App shop" in input && "买的东西发货了" in input)
        assertFalse("没有通知原文", "text 1" in input || "title 1" in input)
        assertTrue(e.model.tools.last().isEmpty())
        val d = e.digestStore.data.days.single()
        assertEquals(20_000L, d.day)
        assertFalse(d.partial)
        assertEquals(listOf("买的东西发货了,今天到"), d.lines.map { it.text })
        assertEquals(listOf("com.shop"), d.lines.single().apps)
        assertEquals(listOf(20_000L), e.runner.digestDays.value.map { it.day })
        val calls = e.model.calls.size
        nudge(e)
        assertEquals(calls, e.model.calls.size)
    }

    // 模型和句子一起给的一句概述存下来给页面用;任务拿不到它(读通知的工具、带进任务的那一段都只从句子里取)
    @Test fun theBriefIsStoredForThePageAndNeverGivenToTasks() = runTest {
        val e = withANote()
        e.model.dayReply = { """{"lines":[{"ids":["s1"],"text":"买的东西发货了"}],"brief":"概述只给页面看"}""" }
        e.runner.digestToday()
        runCurrent()
        assertEquals("概述只给页面看", e.digestStore.data.days.single().brief)
        assertEquals("概述只给页面看", e.runner.digestDays.value.single().brief)
        val tier = { _: String -> SourceTier.High }
        val given = e.runner.digests(DigestLevel.Day, LearnLevel.Aggressive, tier)!!
        assertEquals(listOf("买的东西发货了"), given.digests.single().lines.map { it.text })
        assertFalse("概述" in e.runner.observed(LearnLevel.Aggressive, tier).orEmpty())
        // 重做时模型没给:不留着上一份的
        e.model.dayReply = { """{"lines":[{"ids":["s1"],"text":"买的东西发货了"}]}""" }
        e.runner.digestToday()
        runCurrent()
        assertEquals("", e.digestStore.data.days.single().brief)
    }

    private val fiveNotes = """{"notes":[""" + (1..5).joinToString(",") { """{"ids":["e$it"],"text":"第 $it 件事"}""" } + "]}"

    // 今天的句子攒够了,不用用户去点:有事发生时自动做一份「到现在为止」的;没有新句子、离上一份不够久都不再做;过完这一天重做完整的
    @Test fun todaysDigestIsMadeWithoutBeingAskedOnceEnoughNotesHaveGathered() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { fiveNotes }
        e.model.dayReply = { oneLine }
        e.post(pkg = "com.shop", count = 20)
        assertEquals("没有人点过,自己做了", "day", e.model.kinds.last())
        val d = e.digestStore.data.days.single()
        assertTrue(d.partial)
        assertEquals(20_000L, d.day)
        // 没有新句子:再有事也不重做(哪怕离上一份已经够久)
        nudge(e)
        wait(LearnTuning.TODAY_AUTO_INTERVAL_MS)
        nudge(e)
        assertEquals(e.model.kinds.toString(), 1, e.model.kinds.count { it == "day" })
        // 过完这一天:重做完整的
        wait(LearnTuning.DAY_MS)
        nudge(e)
        assertEquals("day", e.model.kinds.last())
        assertFalse(e.digestStore.data.days.single { it.day == 20_000L }.partial)
    }

    // 今天自动做的几次回复都读不懂:不再自动试;但这不算在「这一天完整的那一份」头上,过完这一天照样做
    @Test fun unreadableAutomaticDigestsDoNotUseUpTheFullDigestsTries() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { fiveNotes }
        e.model.dayReply = { "I cannot do that." }
        e.post(pkg = "com.shop", count = 20)
        repeat(8) {
            nudge(e)
            wait(LearnTuning.FAILURE_HOLD_MS.last())
        }
        assertEquals(LearnTuning.DAY_TRIES_MAX, e.model.kinds.count { it == "day" })
        assertTrue(e.digestStore.data.days.isEmpty())
        e.model.dayReply = { oneLine }
        wait(LearnTuning.DAY_MS)
        nudge(e)
        val d = e.digestStore.data.days.single()
        assertEquals(20_000L, d.day)
        assertFalse("完整的那一份照样做了", d.partial)
    }

    // 用户删掉了今天的那一份:不自动做回来(哪怕又来了新句子)
    @Test fun aDeletedTodayIsNotMadeAgainByItself() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { fiveNotes }
        e.model.dayReply = { oneLine }
        e.post(pkg = "com.shop", count = 20)
        assertTrue(e.digestStore.data.days.single().partial)
        e.runner.deleteDay(20_000L)
        runCurrent()
        val days = e.model.kinds.count { it == "day" }
        wait(LearnTuning.TODAY_AUTO_INTERVAL_MS)
        e.post(pkg = "com.shop", count = 20)
        nudge(e)
        assertEquals(days, e.model.kinds.count { it == "day" })
    }

    // 建模型时说清要发哪一种请求:回复长的(分段、当日、周、月归纳)不能和判定共用一个短上限
    @Test fun everyRequestSaysWhichKindItIsWhenTheModelIsBuilt() = runTest {
        val e = withANote()
        assertEquals(listOf(ProactiveCall.Learn, ProactiveCall.Notes), e.asked)
        e.model.weekReply = { weekLine }
        e.model.monthReply = { monthLine }
        e.runner.digestMonth()
        runCurrent()
        assertEquals(listOf("day", "week", "month"), e.model.kinds.takeLast(3))
        assertEquals(listOf(ProactiveCall.Day, ProactiveCall.Span, ProactiveCall.Span), e.asked.takeLast(3))
    }

    // 用户点「归纳今天到现在的」:马上做一份标着「到现在为止」的;这一天过完后重做完整的
    @Test fun todayOnDemandIsPartialAndIsRedoneAfterTheDay() = runTest {
        val e = withANote()
        e.runner.digestToday()
        runCurrent()
        assertEquals("day", e.model.kinds.last())
        assertTrue(e.digestStore.data.days.single().partial)
        // 再点一次才再做;今天没有句子时点了不发请求
        val calls = e.model.calls.size
        nudge(e)
        assertEquals(calls, e.model.calls.size)
        e.model.dayReply = { """{"lines":[{"ids":["s1"],"text":"完整的一份"}]}""" }
        wait(LearnTuning.DAY_MS)
        nudge(e)
        val d = e.digestStore.data.days.single()
        assertFalse(d.partial)
        assertEquals(listOf("完整的一份"), d.lines.map { it.text })
        val empty = Env(this)
        empty.digest = true
        empty.runner.digestToday()
        runCurrent()
        assertTrue(empty.model.calls.isEmpty())
    }

    // 那一天还有没做过分段归纳的通知:先做分段归纳,再做当日归纳
    @Test fun dayDigestWaitsForTheDaysNotes() = runTest {
        val e = withANote()
        e.post(pkg = "com.mail", count = 3)
        wait(LearnTuning.DAY_MS)
        nudge(e)
        val after = e.model.kinds.drop(2)
        assertEquals("day", after.last())
        assertTrue(after.toString(), after.indexOf("notes") in 0 until after.lastIndex)
        assertTrue("[s2]" in e.model.input(e.model.calls.lastIndex))
    }

    // 回复总读不懂:同一天在一个进程里最多试 3 次;没连上模型的不算次数
    @Test fun unreadableDayDigestIsTriedAFewTimesOnly() = runTest {
        val e = withANote()
        e.model.dayReply = { "I cannot do that." }
        wait(LearnTuning.DAY_MS)
        repeat(8) {
            nudge(e)
            wait(LearnTuning.FAILURE_HOLD_MS.last())
        }
        assertEquals(LearnTuning.DAY_TRIES_MAX, e.model.kinds.count { it == "day" })
        assertTrue(e.digestStore.data.days.isEmpty())
    }

    // 清除路径:清掉这个 App 的、清空全部都带走当日归纳;关掉「当日消息归纳」不带走;请求在途时清空的不会写回来;可以单删一天
    @Test fun dayDigestFollowsThePurgePaths() = runTest {
        fun TestScope.made(): Env = withANote().also { e ->
            wait(LearnTuning.DAY_MS)
            nudge(e)
            assertEquals(1, e.digestStore.data.days.single().lines.size)
        }
        made().let { e -> e.runner.clearApp("com.shop"); assertTrue(e.digestStore.data.days.single().lines.isEmpty()) }
        made().let { e -> e.digest = false; e.runner.poke(); assertEquals(1, e.digestStore.data.days.single().lines.size); assertEquals(1, e.runner.digestDays.value.size) }
        made().let { e -> e.runner.clearAll(); assertTrue(e.digestStore.data.days.isEmpty()) }
        // (同一个测试里几个环境共用一个虚拟时钟,这一份的那一天不是第一天)
        made().let { e ->
            e.runner.deleteDay(e.digestStore.data.days.single().day)
            // 留下的只是「删掉了」的记号:没有句子,读的人看不到
            assertTrue(e.runner.digestDays.value.isEmpty())
            assertTrue(e.digestStore.data.days.single().let { it.removed && it.lines.isEmpty() })
        }
        val e = withANote()
        e.model.gate = CompletableDeferred()
        e.runner.digestToday()
        runCurrent()
        assertTrue(e.runner.requestActive)
        e.runner.clearAll()
        assertFalse(e.runner.requestActive)
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(e.digestStore.data.days.isEmpty() && e.digestStore.data.notes.isEmpty())
    }

    // 昨天的归纳已经做了,昨晚的通知之后才补收进来:写成句子之后,昨天重做一份,只重做一次
    @Test fun aDayIsRedoneOnceWhenLateNotesArrive() = runTest {
        val e = withANote()
        wait(LearnTuning.DAY_MS)
        nudge(e)
        assertEquals(1, e.model.kinds.count { it == "day" })
        e.model.dayReply = { """{"lines":[{"ids":["s1","s2"],"text":"连同后来补收到的"}]}""" }
        // 过了一会儿才补收进来:发出时间是昨天
        wait(60_000)
        repeat(20) { i -> e.runner.capture(NoticeRecord("late$i", "com.mail", e.now() - LearnTuning.DAY_MS + i, "title", "late $i")) }
        runCurrent()
        nudge(e)
        assertEquals(2, e.model.kinds.count { it == "day" })
        assertEquals(listOf("连同后来补收到的"), e.digestStore.data.days.single { it.day == 20_000L }.lines.map { it.text })
        val calls = e.model.calls.size
        nudge(e)
        assertEquals(calls, e.model.calls.size)
    }

    // 当日归纳在途时,其中一个 App 不再归纳:请求取消,那个 App 的句子不会出现在任何一句里;之后按剩下的重做
    @Test fun purgingAnAppCancelsTheDayRequestInFlight() = runTest {
        val e = withANote()
        e.post(pkg = "com.mail", count = 20)
        assertEquals(listOf("com.shop", "com.mail"), e.digestStore.data.notes.map { it.packageName })
        e.model.dayReply = { """{"lines":[{"ids":["s1","s2"],"text":"两个 App 合在一起的一句"}]}""" }
        e.model.gate = CompletableDeferred()
        e.runner.digestToday()
        runCurrent()
        assertTrue(e.runner.requestActive)
        e.optOut += "com.mail"
        e.runner.forgetApps(setOf("com.mail"))
        assertFalse(e.runner.requestActive)
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(e.digestStore.data.days.isEmpty())
        // 再要一次:只剩一个 App 的句子,合并句里引用的第二句已经不存在,整句不要
        e.model.gate = null
        e.runner.digestToday()
        runCurrent()
        val last = e.model.input(e.model.calls.lastIndex)
        assertTrue("[s1]" in last && "[s2]" !in last)
        assertTrue(e.digestStore.data.days.single().lines.isEmpty())
    }

    // ---- 带进任务的那一段:按激进程度和来源档位挑 ----

    private fun ObservedTopic.on(vararg apps: Pair<String, List<Long>>) = copy(evidence = apps.toMap())

    // 早该淡忘的话题、联系人(很久没有新归纳、也没开过内容页,还没被整理掉)不带进任务;用户自己定过关系的联系人照带
    @Test fun observedLeavesOutWhatShouldAlreadyBeForgotten() = runTest {
        val e = Env(this)
        val long = start - 70L * LearnTuning.DAY_MS
        val oldDays = listOf(19_928L, 19_929L, 19_930L)
        e.store.data = TopicPoolData(topics = listOf(
            ObservedTopic("old", TopicKind.Interest, "很久以前的话题", long, long).on("com.high" to oldDays),
            ObservedTopic("new", TopicKind.Interest, "最近的话题", 1L, start).on("com.high" to listOf(20_000L, 19_999L)),
        ))
        e.contacts.data = ContactPoolData(contacts = listOf(
            ObservedContact("c1", "com.high", "很久没联系的人", long, long, days = oldDays),
            ObservedContact("c2", "com.high", "用户定过的人", long, long, days = oldDays, userRelation = ContactRelation.Friend),
            ObservedContact("c3", "com.high", "最近的人", 1L, start, days = listOf(19_998L, 19_999L, 20_000L)),
        ))
        assertTrue(e.store.data.topics.first().retention(e.now()) < LearnTuning.FORGET_BELOW)
        assertTrue(e.contacts.data.contacts.first().retention(e.now()) < LearnTuning.FORGET_BELOW)
        val text = e.runner.observed(LearnLevel.Balanced) { SourceTier.High }!!
        for (yes in listOf("最近的话题", "最近的人", "用户定过的人")) assertTrue(yes, yes in text)
        for (no in listOf("很久以前的话题", "很久没联系的人")) assertFalse(no, no in text)
        // 只是不带:池里的没动,也没有为此存盘(整理照旧在收下新归纳、打开内容页时做)
        assertEquals(2, e.store.data.topics.size)
        assertEquals(0, e.store.saves)
        // 只剩该淡忘的:整段没有
        e.store.data = TopicPoolData(topics = e.store.data.topics.take(1))
        e.contacts.data = ContactPoolData(contacts = e.contacts.data.contacts.take(1))
        val alone = Env(this).also { it.store.data = e.store.data; it.contacts.data = e.contacts.data }
        assertEquals(null, alone.runner.observed(LearnLevel.Balanced) { SourceTier.High })
    }

    @Test fun observedFollowsTheLevelAndTheSourceTier() = runTest {
        val e = Env(this)
        e.digest = true
        e.store.data = TopicPoolData(topics = listOf(
            ObservedTopic("k1", TopicKind.Interest, "徒步", 1L, start).on("com.high" to listOf(20_000L, 19_999L)),
            ObservedTopic("k2", TopicKind.Shopping, "跑鞋", 1L, start).on("com.mid" to listOf(20_000L, 19_999L)),
            ObservedTopic("k3", TopicKind.Follow, "只有一天", 1L, start).on("com.high" to listOf(20_000L)),
            ObservedTopic("k4", TopicKind.Interest, "体检", 1L, start, sensitive = true).on("com.high" to listOf(20_000L, 19_999L)),
            ObservedTopic("k5", TopicKind.Service, "不归纳的", 1L, start).on("com.low" to listOf(20_000L, 19_999L)),
        ))
        e.contacts.data = ContactPoolData(contacts = listOf(
            ObservedContact("c1", "com.high", "张三", 1L, start, days = listOf(19_998L, 19_999L, 20_000L), userRelation = ContactRelation.Friend),
            ObservedContact("c2", "com.mid", "李四", 1L, start, days = listOf(19_998L, 19_999L, 20_000L)),
            ObservedContact("c3", "com.high", "还没确立", 1L, start, days = listOf(20_000L)),
        ))
        e.digestStore.data = DigestData(days = listOf(
            DayDigest(20_000, start, listOf(DigestLine("高档的一句", apps = listOf("com.high")), DigestLine("中档的一句", apps = listOf("com.mid")),
                DigestLine("两档合在一起的", apps = listOf("com.high", "com.mid")))),
            DayDigest(19_999, start, listOf(DigestLine("昨天高档的", apps = listOf("com.high"))), leftOut = 9),
        ))
        e.optOut += "com.low"
        val tier: (String) -> SourceTier = { when (it) { "com.high" -> SourceTier.High; "com.low" -> SourceTier.Low; else -> SourceTier.Mid } }

        // 最保守的一档:什么都不带
        assertEquals(null, e.runner.observed(LearnLevel.Conservative, tier))

        // 平衡:只有来源是「高」的;话题要够天数;敏感的在开关关着时不带
        val balanced = e.runner.observed(LearnLevel.Balanced, tier)!!
        for (yes in listOf("高档的一句", "昨天高档的", "徒步", "张三 (friend, set by the user)")) assertTrue(yes, yes in balanced)
        // 昨天那一份做的时候有 9 句放不下:标题里说出来;今天那一份没有漏的,不说
        assertTrue("Yesterday (9 notes did not fit when this was written, so it is not everything):" in balanced)
        assertFalse("Today so far (" in balanced && "did not fit" in balanced.substringBefore("Yesterday"))
        for (no in listOf("中档的一句", "两档合在一起的", "跑鞋", "只有一天", "体检", "不归纳的", "李四", "还没确立")) assertFalse(no, no in balanced)

        // 激进:「中」也带,话题一天就算;「低」的(不归纳)仍然不带
        val aggressive = e.runner.observed(LearnLevel.Aggressive, tier)!!
        for (yes in listOf("中档的一句", "两档合在一起的", "跑鞋", "只有一天", "李四")) assertTrue(yes, yes in aggressive)
        for (no in listOf("体检", "不归纳的", "还没确立")) assertFalse(no, no in aggressive)

        // 「敏感类推断」开着才带敏感的
        e.sensitive = true
        assertTrue("体检" in e.runner.observed(LearnLevel.Balanced, tier)!!)

        // 「当日消息归纳」关着:不带归纳的句子,话题和联系人照带;归纳关着:什么都不带
        e.digest = false
        val noDigest = e.runner.observed(LearnLevel.Balanced, tier)!!
        assertFalse("高档的一句" in noDigest)
        assertTrue("徒步" in noDigest)
        e.on = false
        assertEquals(null, e.runner.observed(LearnLevel.Aggressive, tier))
    }

    // 「应用访问」读不出来:原文、句子、话题都还在,但不收新的、不发、不往任务里带;读得出来了照旧
    @Test fun suspendedKeepsEverythingButCollectsSendsAndCarriesNothing() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { oneNote }
        e.store.data = TopicPoolData(topics = listOf(
            ObservedTopic("k1", TopicKind.Interest, "徒步", 1L, start).on("com.high" to listOf(20_000L, 19_999L)),
        ))
        e.digestStore.data = DigestData(days = listOf(
            DayDigest(19_999, start, listOf(DigestLine("昨天的一句", apps = listOf("com.high")))),
        ))
        e.post(pkg = "com.high", count = 20)
        val tier: (String) -> SourceTier = { SourceTier.High }
        val from = start
        val to = start + LearnTuning.DAY_MS
        assertTrue("昨天的一句" in e.runner.observed(LearnLevel.Aggressive, tier)!!)
        assertEquals(20, e.runner.notices(from, to, LearnLevel.Aggressive, tier)!!.entries.size)
        assertTrue(e.runner.digests(DigestLevel.Day, LearnLevel.Aggressive, tier)!!.digests.isNotEmpty())
        val journal = e.journal.texts
        val notes = e.digestStore.texts
        val calls = e.model.calls.size

        e.suspended = true
        e.runner.poke()
        runCurrent()
        assertEquals(LearnStatus.Suspended, e.runner.state.value.status)
        assertEquals(null, e.runner.observed(LearnLevel.Aggressive, tier))
        assertEquals(null, e.runner.notices(from, to, LearnLevel.Aggressive, tier))
        assertEquals(null, e.runner.digests(DigestLevel.Day, LearnLevel.Aggressive, tier))
        // 这期间来的不收(原文也不记),手动要一份归纳也不发
        e.post(pkg = "com.high", count = 20)
        e.runner.digestToday()
        wait(LearnTuning.DAY_MS / 2)
        assertEquals(calls, e.model.calls.size)
        assertEquals(journal, e.journal.texts)
        assertEquals(notes, e.digestStore.texts)
        assertEquals(0, e.journal.wipes)
        assertEquals(0, e.digestStore.wipes)
        assertEquals(0, e.store.wipes)
        assertEquals(1, e.store.data.topics.size)

        e.suspended = false
        e.runner.poke()
        runCurrent()
        // 接着做的只有本来就排着的(没凑够一批、这时到期了的那几条去归纳话题);
        // 停着的时候点的「归纳今天到现在的」不作数,读得出来了也不补发
        assertEquals(listOf("learn"), e.model.kinds.drop(calls))
        assertTrue("昨天的一句" in e.runner.observed(LearnLevel.Aggressive, tier)!!)
        assertEquals(journal.size, e.runner.notices(from - LearnTuning.DAY_MS, to + LearnTuning.DAY_MS, LearnLevel.Aggressive, tier)!!.entries.size)
    }

    // 任务读通知:只给档位够格的 App 的原文和句子;「当日消息归纳」关着、最保守的一档时没有
    @Test fun noticesForTasksFollowTheLevelAndTier() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { oneNote }
        e.post(pkg = "com.high", count = 20)
        e.post(pkg = "com.mid", count = 20)
        val tier: (String) -> SourceTier = { if (it == "com.high") SourceTier.High else SourceTier.Mid }
        val from = start
        val to = start + LearnTuning.DAY_MS
        assertEquals(null, e.runner.notices(from, to, LearnLevel.Conservative, tier))
        val balanced = e.runner.notices(from, to, LearnLevel.Balanced, tier)!!
        assertEquals(setOf("com.high"), balanced.entries.map { it.packageName }.toSet())
        assertEquals(20, balanced.entries.size)
        assertEquals(listOf("com.high"), balanced.notes.map { it.packageName })
        // 另一个 App 也有通知,只是档位不够:给个数,好让读的人说「还有一个 App 的没看」
        assertEquals(1, balanced.withheldApps)
        val aggressive = e.runner.notices(from, to, LearnLevel.Aggressive, tier)!!
        assertEquals(40, aggressive.entries.size)
        assertEquals(2, aggressive.notes.size)
        assertEquals(0, aggressive.withheldApps)
        assertFalse(aggressive.notesUnavailable)
        // 时间范围之外的不给
        assertTrue(e.runner.notices(to, to + LearnTuning.DAY_MS, LearnLevel.Aggressive, tier)!!.entries.isEmpty())
        // 归纳那份这会儿读不出来(换一个进程):原文照给,句子是空的,并且说得出「读不出来」
        e.digestStore.failLoad = true
        val unreadable = restarted(e).notices(from, to, LearnLevel.Aggressive, tier)!!
        assertEquals(40, unreadable.entries.size)
        assertTrue(unreadable.notes.isEmpty())
        assertTrue(unreadable.notesUnavailable)
        e.digestStore.failLoad = false
        e.digest = false
        assertEquals(null, e.runner.notices(from, to, LearnLevel.Aggressive, tier))
    }

    // ---- 当日消息归纳的第 3 级(周)、第 4 级(月) ----
    // 测试的第一天(纪元日 20000)是 2024-10-04 周五:这一周从 9-30(19996)到 10-06,算十月的;下周一是 20003;11-01 是 20028

    private val weekLine = """{"lines":[{"ids":["d1"],"text":"10 月 4 日买的东西发货了"}],"brief":"这一周:买的东西发货"}"""
    private val monthLine = """{"lines":[{"ids":["d1"],"text":"十月初买的东西到了"}],"brief":"这个月:东西到了"}"""

    /** 周五有一句分段归纳、周六做了周五的当日归纳的环境。 */
    private fun TestScope.withADay(): Env = withANote().also { e ->
        e.model.weekReply = { weekLine }
        e.model.monthReply = { monthLine }
        wait(LearnTuning.DAY_MS)
        nudge(e)
        assertEquals("day", e.model.kinds.last())
    }

    // 一周过完之后(有事发生时才看)把这一周各天的当日归纳合成一份:放进请求的只有当日归纳的句子,每句写着是哪一天的;做过了不重做
    @Test fun weekDigestIsMadeOnceTheWeekIsOver() = runTest {
        val e = withADay()
        wait(LearnTuning.DAY_MS)
        nudge(e)
        assertFalse("周日:这一周还没过完", "week" in e.model.kinds)
        wait(LearnTuning.DAY_MS)
        assertFalse("没有定时器", "week" in e.model.kinds)
        nudge(e)
        assertEquals("week", e.model.kinds.last())
        val input = e.model.input(e.model.calls.lastIndex)
        assertTrue(input, "Week: 2024-09-30 (Mon) to 2024-10-06 (Sun)" in input)
        assertTrue(input, "[d1] date: 2024-10-04 (Fri), apps: App shop" in input && "买的东西发货了,今天到" in input)
        assertFalse("没有通知原文", "text 1" in input || "title 1" in input)
        assertTrue(e.model.tools.last().isEmpty())
        val w = e.digestStore.data.weeks.single()
        assertEquals(19_996L, w.start)
        assertFalse(w.partial)
        // 模型一起给的一句概述也存下了
        assertEquals("这一周:买的东西发货", w.brief)
        val line = w.lines.single()
        assertEquals("10 月 4 日买的东西发货了", line.text)
        assertEquals(listOf(20_000L), line.days)
        assertEquals(listOf("com.shop"), line.apps)
        assertEquals(e.digestStore.data.notes.map { it.id }, line.notes)
        assertEquals(listOf(19_996L), e.runner.digestWeeks.value.map { it.start })
        val calls = e.model.calls.size
        nudge(e)
        assertEquals(calls, e.model.calls.size)
        assertTrue(e.digestStore.data.months.isEmpty())
    }

    // 一个月过完之后把结束在这个月的各周的周归纳合成一份;输入只有周归纳的句子
    @Test fun monthDigestIsMadeOnceTheMonthIsOver() = runTest {
        val e = withADay()
        wait(2 * LearnTuning.DAY_MS)
        nudge(e)
        assertEquals("week", e.model.kinds.last())
        wait(20 * LearnTuning.DAY_MS)
        nudge(e)
        assertFalse("十月还没过完", "month" in e.model.kinds)
        wait(5 * LearnTuning.DAY_MS)
        nudge(e)
        assertEquals("month", e.model.kinds.last())
        val input = e.model.input(e.model.calls.lastIndex)
        assertTrue(input, "Month: 2024-10" in input)
        assertTrue(input, "[d1] week: 2024-09-30 (Mon) to 2024-10-06 (Sun), apps: App shop" in input && "10 月 4 日买的东西发货了" in input)
        assertFalse("没有当日归纳的句子", "今天到" in input)
        val m = e.digestStore.data.months.single()
        assertEquals("这个月:东西到了", m.brief)
        assertEquals(19_997L, m.start)
        assertFalse(m.partial)
        assertEquals(listOf(19_996L), m.lines.single().weeks)
        assertEquals(listOf(20_000L), m.lines.single().days)
        assertEquals(listOf(19_997L), e.runner.digestMonths.value.map { it.start })
        val calls = e.model.calls.size
        nudge(e)
        assertEquals(calls, e.model.calls.size)
    }

    // 用户点「归纳本周到现在的」:今天有新句子就先把今天到现在的做了,再做这一周的;两份都标着「到现在为止」,过完之后各自重做完整的
    @Test fun weekOnDemandDoesTodayFirstAndIsRedoneAfterTheWeek() = runTest {
        val e = withANote()
        e.model.weekReply = { weekLine }
        e.runner.digestWeek()
        runCurrent()
        assertEquals(listOf("day", "week"), e.model.kinds.takeLast(2))
        assertTrue(e.digestStore.data.days.single().partial)
        assertTrue(e.digestStore.data.weeks.single().partial)
        // 再点一次:今天没有新句子,只重做这一周的
        e.runner.digestWeek()
        runCurrent()
        assertEquals(listOf("day", "week", "week"), e.model.kinds.takeLast(3))
        // 没点的时候不自己做
        val calls = e.model.calls.size
        nudge(e)
        assertEquals(calls, e.model.calls.size)
        wait(3 * LearnTuning.DAY_MS)
        nudge(e)
        assertEquals(listOf("day", "week"), e.model.kinds.takeLast(2))
        assertFalse(e.digestStore.data.days.single().partial)
        assertFalse(e.digestStore.data.weeks.single().partial)
        // 这一周什么都没有时点了不发请求
        val empty = Env(this)
        empty.digest = true
        empty.runner.digestWeek()
        empty.runner.digestMonth()
        runCurrent()
        assertTrue(empty.model.calls.isEmpty())
    }

    // 用户点「归纳本月到现在的」:今天、本周、本月依次做,都标着「到现在为止」
    @Test fun monthOnDemandDoesTodayAndTheWeekFirst() = runTest {
        val e = withANote()
        e.model.weekReply = { weekLine }
        e.model.monthReply = { monthLine }
        e.runner.digestMonth()
        runCurrent()
        assertEquals(listOf("day", "week", "month"), e.model.kinds.takeLast(3))
        val m = e.digestStore.data.months.single()
        assertTrue(m.partial)
        assertEquals(19_997L, m.start)
        assertEquals("十月初买的东西到了", m.lines.single().text)
    }

    // 这一周的当日归纳都没有值得说的:不问模型,直接记成做过了(不然上面一级一直等它);月也一样
    @Test fun aWeekWithNothingToMergeIsRecordedWithoutAModelCall() = runTest {
        val e = withANote()
        e.model.dayReply = { """{"lines":[]}""" }
        wait(LearnTuning.DAY_MS)
        nudge(e)
        assertTrue(e.digestStore.data.days.single().lines.isEmpty())
        wait(2 * LearnTuning.DAY_MS)
        nudge(e)
        assertFalse("week" in e.model.kinds)
        val w = e.digestStore.data.weeks.single()
        assertTrue(w.lines.isEmpty() && !w.partial)
        wait(25 * LearnTuning.DAY_MS)
        nudge(e)
        assertFalse("month" in e.model.kinds)
        assertTrue(e.digestStore.data.months.single().lines.isEmpty())
    }

    // 这一周里还有通知没做过分段归纳:先做分段归纳、再做那一天的当日归纳,最后才做这一周的
    @Test fun weekDigestWaitsForTheWeeksNotesAndDays() = runTest {
        val e = withADay()
        // 周六来了几条,还没攒够一批
        e.post(pkg = "com.mail", count = 3)
        e.model.dayReply = { """{"lines":[{"ids":["s1"],"text":"周六的邮件"}]}""" }
        e.model.weekReply = { """{"lines":[{"ids":["d1","d2"],"text":"两天的事"}]}""" }
        wait(2 * LearnTuning.DAY_MS)
        nudge(e)
        assertEquals(listOf("notes", "day", "week"), e.model.kinds.takeLast(3))
        val input = e.model.input(e.model.calls.lastIndex)
        assertTrue(input, "[d1] date: 2024-10-04 (Fri)" in input && "[d2] date: 2024-10-05 (Sat)" in input)
        assertEquals(listOf(20_000L, 20_001L), e.digestStore.data.weeks.single().lines.single().days)
    }

    // 周一一早先做了上周的周归纳,周日夜里的通知之后才补收进来:周日的当日归纳做出来之后,上周跟着重做一次
    @Test fun aWeekIsRedoneOnceWhenOneOfItsDaysIsDoneLater() = runTest {
        val e = withADay()
        wait(2 * LearnTuning.DAY_MS)
        nudge(e)
        assertEquals(1, e.model.kinds.count { it == "week" })
        e.model.dayReply = { """{"lines":[{"ids":["s1"],"text":"周日夜里补收到的"}]}""" }
        e.model.weekReply = { """{"lines":[{"ids":["d1","d2"],"text":"重做的一周"}]}""" }
        wait(60_000)
        // 发出时间是周日
        repeat(20) { i -> e.runner.capture(NoticeRecord("late$i", "com.mail", e.now() - LearnTuning.DAY_MS + i, "title", "late $i")) }
        runCurrent()
        nudge(e)
        assertEquals(listOf("notes", "day", "week"), e.model.kinds.filter { it != "learn" }.takeLast(3))
        val w = e.digestStore.data.weeks.single()
        assertEquals(listOf("重做的一周"), w.lines.map { it.text })
        assertEquals(listOf(20_000L, 20_002L), w.lines.single().days)
        val calls = e.model.calls.size
        nudge(e)
        assertEquals(calls, e.model.calls.size)
    }

    // 回复总读不懂:同一周在一个进程里最多试 3 次,之后不再试
    @Test fun unreadableWeekDigestIsTriedAFewTimesOnly() = runTest {
        val e = withADay()
        e.model.weekReply = { "I cannot do that." }
        wait(2 * LearnTuning.DAY_MS)
        repeat(8) {
            nudge(e)
            wait(LearnTuning.FAILURE_HOLD_MS.last())
        }
        assertEquals(LearnTuning.SPAN_TRIES_MAX, e.model.kinds.count { it == "week" })
        assertTrue(e.digestStore.data.weeks.isEmpty())
    }

    // 清除路径:清掉这个 App 的、关掉「当日消息归纳」、清空全部都带走周、月归纳;可以单删一周、一个月;删一句分段归纳,各级里依据它的句子都没了
    @Test fun spanDigestsFollowThePurgeAndDeletePaths() = runTest {
        fun TestScope.made(): Env = withANote().also { e ->
            e.model.weekReply = { weekLine }
            e.model.monthReply = { monthLine }
            e.runner.digestMonth()
            runCurrent()
            assertEquals(1, e.digestStore.data.weeks.single().lines.size)
            assertEquals(1, e.digestStore.data.months.single().lines.size)
        }
        made().let { e ->
            e.runner.clearApp("com.shop")
            assertTrue(e.digestStore.data.weeks.single().lines.isEmpty() && e.digestStore.data.months.single().lines.isEmpty())
        }
        // 关掉「当日消息归纳」:周、月归纳留着
        made().let { e ->
            e.digest = false
            e.runner.poke()
            assertEquals(1, e.digestStore.data.weeks.single().lines.size)
            assertEquals(1, e.digestStore.data.months.single().lines.size)
            assertEquals(1, e.runner.digestWeeks.value.size)
        }
        made().let { e -> e.runner.clearAll(); assertTrue(e.digestStore.data.weeks.isEmpty() && e.digestStore.data.months.isEmpty()) }
        made().let { e ->
            e.runner.deleteNote(e.digestStore.data.notes.single().id)
            assertTrue(e.digestStore.data.days.single().lines.isEmpty())
            assertTrue(e.digestStore.data.weeks.single().lines.isEmpty() && e.digestStore.data.months.single().lines.isEmpty())
        }
        made().let { e ->
            val week = e.digestStore.data.weeks.single().start
            e.runner.deleteSpan(SpanKind.Week, week)
            assertTrue(e.runner.digestWeeks.value.isEmpty())
            assertTrue(e.digestStore.data.weeks.single().let { it.removed && it.lines.isEmpty() })
            assertTrue("月归纳里依据这一周的句子也去掉", e.digestStore.data.months.single().lines.isEmpty())
            val month = e.digestStore.data.months.single().start
            e.runner.deleteSpan(SpanKind.Month, month)
            assertTrue(e.runner.digestMonths.value.isEmpty())
            assertEquals(setOf(SpanKind.Week to week, SpanKind.Month to month), e.runner.digestRemoved.value)
        }
    }

    // 周归纳在途时:清空全部,结果不写回来;其中一个 App 不再归纳,请求取消
    @Test fun purgesCancelASpanRequestInFlight() = runTest {
        val e = withANote()
        e.model.weekReply = { weekLine }
        e.runner.digestToday()
        runCurrent()
        e.model.gate = CompletableDeferred()
        e.runner.digestWeek()
        runCurrent()
        assertEquals("week", e.model.kinds.last())
        assertTrue(e.runner.requestActive)
        e.optOut += "com.shop"
        e.runner.forgetApps(setOf("com.shop"))
        assertFalse(e.runner.requestActive)
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(e.digestStore.data.weeks.isEmpty())
        val f = withANote()
        f.model.weekReply = { weekLine }
        f.runner.digestToday()
        runCurrent()
        f.model.gate = CompletableDeferred()
        f.runner.digestWeek()
        runCurrent()
        assertTrue(f.runner.requestActive)
        f.runner.clearAll()
        assertFalse(f.runner.requestActive)
        f.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(f.digestStore.data.weeks.isEmpty() && f.digestStore.data.days.isEmpty())
    }

    // 「本月到现在的」:今天所在的这一周要到下个月才结束时,这一周算下个月的,不放进这个月——只把今天到现在的做了,不做周、月
    @Test fun monthOnDemandLeavesOutAWeekThatEndsNextMonth() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { oneNote }
        e.model.dayReply = { oneLine }
        e.model.weekReply = { weekLine }
        e.model.monthReply = { monthLine }
        // 10-30 周三:这一周在 11-03 结束
        wait(26 * LearnTuning.DAY_MS)
        e.post(pkg = "com.shop", count = 20)
        e.runner.digestMonth()
        runCurrent()
        assertEquals("day", e.model.kinds.last())
        assertFalse("week" in e.model.kinds || "month" in e.model.kinds)
        assertTrue(e.digestStore.data.days.single().partial)
        assertTrue(e.digestStore.data.weeks.isEmpty() && e.digestStore.data.months.isEmpty())
        // 没有东西可做的那次点按不留着:之后也不会自己冒出一份本月的
        nudge(e)
        assertFalse("week" in e.model.kinds || "month" in e.model.kinds)
    }

    // 带进任务的那一段:刚过完的那一周、那个月的归纳(完整的那份)也带,同样只带依据的 App 都够格的句子;「到现在为止」的不当成上周
    @Test fun observedCarriesLastWeekAndLastMonth() = runTest {
        val e = Env(this)
        e.digest = true
        // 今天是 20000(2024-10-04 周五):上周从 19989(9-23)起,上个月是九月(管 8-26 到 9-29)
        val lastWeek = 19_989L
        val sept = java.time.LocalDate.of(2024, 9, 1).toEpochDay()
        e.digestStore.data = DigestData(
            weeks = listOf(
                SpanDigest(lastWeek, start, listOf(DigestLine("上周高档的一句", apps = listOf("com.high")), DigestLine("上周中档的一句", apps = listOf("com.mid")))),
                SpanDigest(19_996L, start, listOf(DigestLine("本周到现在的", apps = listOf("com.high"))), partial = true),
            ),
            months = listOf(SpanDigest(sept, start, listOf(DigestLine("九月高档的一句", apps = listOf("com.high"))))),
        )
        val tier: (String) -> SourceTier = { if (it == "com.high") SourceTier.High else SourceTier.Mid }
        val balanced = e.runner.observed(LearnLevel.Balanced, tier)!!
        assertTrue(balanced, "Last week (2024-09-23 to 2024-09-29):" in balanced && "上周高档的一句" in balanced)
        assertTrue(balanced, "Last month (2024-08-26 to 2024-09-29):" in balanced && "九月高档的一句" in balanced)
        for (no in listOf("上周中档的一句", "本周到现在的")) assertFalse(no, no in balanced)
        assertTrue("上周中档的一句" in e.runner.observed(LearnLevel.Aggressive, tier)!!)
        // 上周那份还只是「到现在为止」的(过完之后还没重做):不带
        e.runner.clearAll()
        e.digestStore.data = DigestData(weeks = listOf(SpanDigest(lastWeek, start, listOf(DigestLine("半截的", apps = listOf("com.high"))), partial = true)))
        val f = Env(this).also { it.digest = true; it.digestStore.data = e.digestStore.data }
        assertEquals(null, f.runner.observed(LearnLevel.Balanced, tier))
    }

    // 任务读存着的归纳:各级新的在前,只给依据的 App 都够格的句子,给不出句子的那份不列,没给的句子有个数;条件和读通知原文一样
    @Test fun digestsForTasksFollowTheLevelAndTier() = runTest {
        val e = Env(this)
        e.digest = true
        val oct = java.time.LocalDate.of(2024, 10, 1).toEpochDay()
        e.digestStore.data = DigestData(
            days = listOf(
                DayDigest(19_999, start, listOf(DigestLine("昨天高档的", apps = listOf("com.high")), DigestLine("昨天两档合的", apps = listOf("com.high", "com.mid"))), leftOut = 4),
                DayDigest(20_000, start, listOf(DigestLine("今天中档的", apps = listOf("com.mid"))), partial = true),
            ),
            weeks = listOf(SpanDigest(19_989, start, listOf(DigestLine("上周高档的", apps = listOf("com.high"))))),
            months = listOf(SpanDigest(oct, start, listOf(DigestLine("十月高档的", apps = listOf("com.high"))), partial = true)),
        )
        val tier: (String) -> SourceTier = { if (it == "com.high") SourceTier.High else SourceTier.Mid }
        assertEquals(null, e.runner.digests(DigestLevel.Day, LearnLevel.Conservative, tier))
        val days = e.runner.digests(DigestLevel.Day, LearnLevel.Balanced, tier)!!
        assertEquals(listOf(19_999L), days.digests.map { it.start })
        assertEquals(listOf("昨天高档的"), days.digests.single().lines.map { it.text })
        // 做的时候漏了几句也带给读的人
        assertEquals(4, days.digests.single().leftOut)
        assertEquals(2, days.withheldLines)
        val all = e.runner.digests(DigestLevel.Day, LearnLevel.Aggressive, tier)!!
        assertEquals(listOf(20_000L, 19_999L), all.digests.map { it.start })
        assertEquals(listOf(true, false), all.digests.map { it.partial })
        assertEquals(0, all.withheldLines)
        val week = e.runner.digests(DigestLevel.Week, LearnLevel.Balanced, tier)!!.digests.single()
        assertEquals(19_989L to 19_995L, week.start to week.end)
        // 月归纳:给的是它实际管到的日子(2024 年 10 月管 9-30 到 10-27)
        val month = e.runner.digests(DigestLevel.Month, LearnLevel.Balanced, tier)!!.digests.single()
        assertEquals(java.time.LocalDate.of(2024, 9, 30).toEpochDay() to java.time.LocalDate.of(2024, 10, 27).toEpochDay(), month.start to month.end)
        assertTrue(month.partial)
        e.digest = false
        assertEquals(null, e.runner.digests(DigestLevel.Week, LearnLevel.Aggressive, tier))
    }

    // ---- 删了就不再自动做 ----

    // 删掉昨天的当日归纳:不再做回来——那天还有句子、之后又补收到那天的通知也一样;周一也不会拿它去做周归纳
    @Test fun aDeletedDayDigestIsNotMadeAgain() = runTest {
        val e = withADay()
        e.runner.deleteDay(20_000)
        assertTrue(e.runner.digestDays.value.isEmpty())
        val days = e.model.kinds.count { it == "day" }
        nudge(e)
        assertEquals(days, e.model.kinds.count { it == "day" })
        // 又补收到周五的通知(发出时间是周五),写成了句子:周五的当日归纳仍然不做
        repeat(20) { i -> e.runner.capture(NoticeRecord("late$i", "com.mail", start + 20 * 3_600_000L + i, "title", "late $i")) }
        runCurrent()
        nudge(e)
        assertEquals("notes", e.model.kinds.filter { it != "learn" }.last())
        assertEquals(days, e.model.kinds.count { it == "day" })
        wait(2 * LearnTuning.DAY_MS)
        nudge(e)
        assertFalse("week" in e.model.kinds)
        assertTrue(e.runner.digestWeeks.value.isEmpty())
    }

    // 删掉上周的周归纳:不再做回来——之后周日夜里的通知补收进来、周日的当日归纳做了出来,也不带着它重做;月归纳也不会拿它当输入
    @Test fun aDeletedWeekDigestIsNotMadeAgain() = runTest {
        val e = withADay()
        wait(2 * LearnTuning.DAY_MS)
        nudge(e)
        assertEquals(1, e.model.kinds.count { it == "week" })
        e.runner.deleteSpan(SpanKind.Week, 19_996)
        assertTrue(e.runner.digestWeeks.value.isEmpty())
        assertEquals(setOf(SpanKind.Week to 19_996L), e.runner.digestRemoved.value)
        nudge(e)
        e.model.dayReply = { """{"lines":[{"ids":["s1"],"text":"周日夜里补收到的"}]}""" }
        wait(60_000)
        repeat(20) { i -> e.runner.capture(NoticeRecord("late$i", "com.mail", e.now() - LearnTuning.DAY_MS + i, "title", "late $i")) }
        runCurrent()
        nudge(e)
        assertEquals("day", e.model.kinds.filter { it != "learn" }.last())
        assertEquals(1, e.model.kinds.count { it == "week" })
        wait(26 * LearnTuning.DAY_MS)
        nudge(e)
        assertFalse("month" in e.model.kinds)
        assertTrue(e.digestStore.data.months.isEmpty())
    }

    // 删掉本周「到现在为止」的那份:不会自己再出现,这一周过完也不自动做;再点一次才重新做,之后照常在周末补成完整的
    @Test fun aDeletedCurrentWeekComesBackOnlyWhenAskedAgain() = runTest {
        val e = withANote()
        e.model.weekReply = { weekLine }
        e.runner.digestWeek()
        runCurrent()
        assertEquals(1, e.model.kinds.count { it == "week" })
        e.runner.deleteSpan(SpanKind.Week, 19_996)
        nudge(e)
        wait(3 * LearnTuning.DAY_MS)
        nudge(e)
        assertEquals(1, e.model.kinds.count { it == "week" })
        assertTrue(e.runner.digestWeeks.value.isEmpty())
        // 这时已经是下一周:删掉的那一周没有按钮可点了。另起一个环境看「再点一次」
        val f = withANote()
        f.model.weekReply = { weekLine }
        val thisWeek = DigestPool.weekOf(f.now() / LearnTuning.DAY_MS)
        f.runner.digestWeek()
        runCurrent()
        f.runner.deleteSpan(SpanKind.Week, thisWeek)
        assertEquals(setOf(SpanKind.Week to thisWeek), f.runner.digestRemoved.value)
        f.runner.digestWeek()
        runCurrent()
        assertEquals(2, f.model.kinds.count { it == "week" })
        assertTrue(f.runner.digestRemoved.value.isEmpty())
        assertTrue(f.runner.digestWeeks.value.single().partial)
    }

    // 刚点了「归纳本周到现在的」还没发出去就删:那一次点按作废,不会过后冒出来
    @Test fun deletingClearsAQueuedTap() = runTest {
        val e = withANote()
        e.model.weekReply = { weekLine }
        e.model.monthReply = { monthLine }
        e.runner.digestToday()
        runCurrent()
        e.busy.value = true
        runCurrent()
        e.runner.digestMonth()
        runCurrent()
        assertFalse("week" in e.model.kinds || "month" in e.model.kinds)
        e.runner.deleteSpan(SpanKind.Week, 19_996)
        e.runner.deleteSpan(SpanKind.Month, 19_997)
        e.busy.value = false
        runCurrent()
        assertFalse("week" in e.model.kinds || "month" in e.model.kinds)
    }

    // 用户单独删掉的话题:模型之后再新起同名的不收,页面上有个数,丢弃原因里记着;重新允许之后可以再学。清空全部不算
    @Test fun aDeletedTopicIsNotLearnedAgain() = runTest {
        val e = Env(this)
        e.model.reply = { newTopic("n1", "n2") }
        e.post(count = LearnTuning.DUE_COUNT)
        e.runner.deleteTopic(e.store.data.topics.single().id)
        assertEquals(1, e.runner.topicsBlocked.value)
        assertEquals(1, e.store.data.blocked.size)
        e.post(count = LearnTuning.DUE_COUNT)
        assertEquals(2, e.model.calls.size)
        assertTrue(e.store.data.topics.isEmpty())
        assertEquals(1, e.runner.state.value.feed.getValue("com.shop").dropped[LearnDrop.Blocked])
        e.runner.allowDeletedTopics()
        assertEquals(0, e.runner.topicsBlocked.value)
        assertTrue(e.store.data.blocked.isEmpty())
        e.post(count = LearnTuning.DUE_COUNT)
        assertEquals(listOf("Hiking"), e.store.data.topics.map { it.label })
        // 清空全部是重来:不记
        e.runner.clearAll()
        assertEquals(0, e.runner.topicsBlocked.value)
        e.post(count = LearnTuning.DUE_COUNT)
        assertEquals(listOf("Hiking"), e.store.data.topics.map { it.label })
    }

    // 本周的归纳被删掉之后点「归纳本月到现在的」:不顺带把本周的做回来;这个月没有别的周可用,也就不做
    @Test fun monthOnDemandDoesNotBringBackADeletedWeek() = runTest {
        val e = withANote()
        e.model.weekReply = { weekLine }
        e.model.monthReply = { monthLine }
        e.runner.digestWeek()
        runCurrent()
        assertEquals(1, e.model.kinds.count { it == "week" })
        e.runner.deleteSpan(SpanKind.Week, 19_996)
        e.runner.digestMonth()
        runCurrent()
        assertEquals(1, e.model.kinds.count { it == "week" })
        assertFalse("month" in e.model.kinds)
        assertEquals(setOf(SpanKind.Week to 19_996L), e.runner.digestRemoved.value)
        assertTrue(e.runner.digestWeeks.value.isEmpty() && e.runner.digestMonths.value.isEmpty())
        // 今天的当日归纳被删掉之后点「归纳本周到现在的」:同样不把今天的做回来
        val f = withANote()
        f.model.weekReply = { weekLine }
        f.runner.digestToday()
        runCurrent()
        val today = f.now() / LearnTuning.DAY_MS
        f.runner.deleteDay(today)
        val days = f.model.kinds.count { it == "day" }
        f.runner.digestWeek()
        runCurrent()
        assertEquals(days, f.model.kinds.count { it == "day" })
        assertFalse("week" in f.model.kinds)
        assertTrue(f.runner.digestDays.value.isEmpty())
    }

    // ---- 开关关着时:已经归纳出的东西还留着,清除、删除、到期照样做 ----

    /** 有句子、当日、周、月归纳各一份的环境。 */
    private fun TestScope.withEveryLevel(): Env = withANote().also { e ->
        e.model.weekReply = { weekLine }
        e.model.monthReply = { monthLine }
        e.runner.digestMonth()
        runCurrent()
        assertEquals(1, e.digestStore.data.months.single().lines.size)
    }

    // 关掉总开关(同意随之没了):请求取消,通知原文和没处理完的清掉;句子和各级归纳留着,也不再发任何请求
    @Test fun turningTheFeatureOffKeepsWhatWasDistilled() = runTest {
        val e = withEveryLevel()
        e.post(pkg = "com.shop", count = 3)
        assertTrue(e.journal.data!!.entries.isNotEmpty())
        e.on = false
        e.consent = null
        e.runner.clear()
        assertEquals(null, e.journal.data)
        assertEquals(1, e.digestStore.data.notes.size)
        assertEquals(1, e.digestStore.data.days.single().lines.size)
        assertEquals(1, e.digestStore.data.weeks.single().lines.size)
        assertEquals(1, e.digestStore.data.months.single().lines.size)
        assertEquals(0, e.digestStore.wipes)
        val calls = e.model.calls.size
        e.runner.digestToday(); e.runner.digestWeek(); e.runner.digestMonth()
        e.post(pkg = "com.shop", count = 20)
        nudge(e)
        assertEquals(calls, e.model.calls.size)
        // 关着的时候也带不进任务、工具也读不到
        val high: (String) -> SourceTier = { SourceTier.High }
        assertEquals(null, e.runner.observed(LearnLevel.Aggressive, high))
        assertEquals(null, e.runner.digests(DigestLevel.Day, LearnLevel.Aggressive, high))
    }

    // 关着的时候(总开关关着,或「当日消息归纳」关着):清掉某个 App、把它调成不再归纳、单删、清空全部,都照样去掉各级里的句子
    @Test fun purgesAndDeletesWorkWhileSwitchedOff() = runTest {
        for (off in listOf<(Env) -> Unit>({ it.on = false; it.consent = null; it.runner.clear() }, { it.digest = false; it.runner.poke() })) {
            withEveryLevel().let { e ->
                off(e)
                e.runner.clearApp("com.shop")
                assertTrue(e.digestStore.data.notes.isEmpty())
                assertTrue(e.digestStore.data.days.single().lines.isEmpty())
                assertTrue(e.digestStore.data.weeks.single().lines.isEmpty() && e.digestStore.data.months.single().lines.isEmpty())
            }
            withEveryLevel().let { e ->
                off(e)
                e.optOut += "com.shop"
                e.runner.forgetApps(setOf("com.shop"))
                assertTrue(e.digestStore.data.notes.isEmpty())
                assertTrue(e.digestStore.data.weeks.single().lines.isEmpty() && e.digestStore.data.months.single().lines.isEmpty())
            }
            withEveryLevel().let { e ->
                off(e)
                e.runner.deleteNote(e.digestStore.data.notes.single().id)
                assertTrue(e.digestStore.data.days.single().lines.isEmpty())
                assertTrue(e.digestStore.data.weeks.single().lines.isEmpty() && e.digestStore.data.months.single().lines.isEmpty())
            }
            withEveryLevel().let { e ->
                off(e)
                e.runner.deleteSpan(SpanKind.Week, e.digestStore.data.weeks.single().start)
                assertTrue(e.runner.digestWeeks.value.isEmpty())
                assertTrue(e.digestStore.data.months.single().lines.isEmpty())
            }
            withEveryLevel().let { e ->
                off(e)
                e.runner.clearAll()
                assertTrue(e.digestStore.data.notes.isEmpty() && e.digestStore.data.days.isEmpty() && e.digestStore.data.weeks.isEmpty() && e.digestStore.data.months.isEmpty())
            }
        }
    }

    // 关着的时候期限照算:打开页面时,过了期的句子和归纳照样删
    @Test fun expiryStillRunsWhileSwitchedOff() = runTest {
        val e = withEveryLevel()
        e.digest = false
        e.runner.poke()
        wait((LearnTuning.NOTES_KEEP_DAYS + 2) * LearnTuning.DAY_MS)
        e.runner.open()
        assertTrue(e.digestStore.data.notes.isEmpty())
        assertEquals(1, e.digestStore.data.days.size)
        wait(LearnTuning.DAYS_KEEP * LearnTuning.DAY_MS)
        e.runner.open()
        assertTrue(e.digestStore.data.days.isEmpty())
        assertEquals(1, e.digestStore.data.weeks.size)
    }

    // 总开关开着、但用户还没同意过把通知发给模型(没配模型时打开的):归纳这边什么都不攒,原文也不落盘;
    // 同意之后才开始收。同意过、只是模型变了在等重新确认的,照收不发
    @Test fun nothingIsGatheredForLearningBeforeConsent() = runTest {
        val e = Env(this)
        e.digest = true
        e.consent = null
        e.post(pkg = "com.shop", count = 20)
        assertEquals(0, e.runner.state.value.pending)
        assertEquals(null, e.journal.data)
        assertTrue(e.model.calls.isEmpty())
        e.consent = TriageConsent.of(target)
        e.runner.poke()
        e.model.notesReply = { oneNote }
        e.post(pkg = "com.shop", count = 20)
        assertEquals(listOf("learn", "notes"), e.model.kinds)
        // 模型变了:同意的记录还在,对不上现在的模型。不发,但接着收
        val f = Env(this)
        f.digest = true
        f.effective = TriageTarget("other", "Other", "m2")
        f.post(pkg = "com.shop", count = 20)
        assertTrue(f.model.calls.isEmpty())
        assertEquals(20, f.runner.state.value.pending)
        assertEquals(LearnStatus.NeedsConsent, f.runner.state.value.status)
    }

    // ---- 清除要彻底,读不出来的时候不能误删 ----

    // 读哪些 App 的名单读不出来时,每个 App 都「不够格」:不再收、不再发,但存着的原文和各级归纳一条不删。
    // 用户随后只把一个 App 勾回来,别的 App 攒下的照样留着——只有点了名的(取消勾选、调成低、被禁用)才删
    @Test fun onlyNamedAppsAreForgotten() = runTest {
        val e = withEveryLevel()
        e.post(pkg = "com.shop", count = 3)
        lockAndUnlock(e)
        val journal = e.journal.data!!.entries.size
        // 名单读不出来:com.shop 一下子「不够格」了
        e.optOut += "com.shop"
        e.runner.poke()
        assertEquals(1, e.digestStore.data.notes.size)
        assertEquals(1, e.digestStore.data.days.single().lines.size)
        assertEquals(1, e.digestStore.data.weeks.single().lines.size)
        assertEquals(1, e.digestStore.data.months.single().lines.size)
        assertEquals(journal, e.journal.data!!.entries.size)
        val calls = e.model.calls.size
        e.post(pkg = "com.shop", count = 20)
        assertEquals(calls, e.model.calls.size)
        // 用户把另一个 App 勾了回来(名单从此读得出来,里面没有 com.shop):com.shop 的归纳还在
        e.runner.forgetApps(emptySet())
        e.runner.poke()
        assertEquals(1, e.digestStore.data.months.single().lines.size)
        // 点了名的才删
        e.runner.forgetApps(setOf("com.shop"))
        assertTrue(e.digestStore.data.notes.isEmpty())
        assertTrue(e.digestStore.data.days.single().lines.isEmpty())
        assertTrue(e.digestStore.data.weeks.single().lines.isEmpty() && e.digestStore.data.months.single().lines.isEmpty())
        assertTrue(e.journal.data!!.entries.isEmpty())
        // 归纳那份读不出来时点名:记下来,读得出来时补做
        val f = Env(this)
        f.digestStore.data = DigestData(days = listOf(DayDigest(19_990, start, listOf(DigestLine("购物的一句", apps = listOf("com.shop"))))))
        f.digestStore.failLoad = true
        f.runner.forgetApps(setOf("com.shop"))
        assertEquals(setOf("com.shop"), f.purges.digestPurges)
        f.digestStore.failLoad = false
        f.runner.open()
        assertTrue(f.digestStore.data.days.single().lines.isEmpty())
        assertTrue(f.purges.digestPurges.isEmpty())
    }

    // 「清空来自某 App 的」:它正在发的那一批取消,回复回来不把话题加回去;缓冲里它还没发的也去掉,过后不会再发给模型
    @Test fun clearingAnAppStopsItsBatchAndDropsItsPendingEvents() = runTest {
        val e = Env(this)
        e.model.reply = { newTopic("n1", "n2") }
        e.model.gate = CompletableDeferred()
        e.post(pkg = "com.shop", count = LearnTuning.DUE_COUNT + 5)
        e.post(pkg = "com.other", count = 2)
        assertTrue(e.runner.requestActive)
        assertEquals(listOf("com.shop"), e.model.packages())
        e.runner.clearApp("com.shop")
        assertFalse(e.runner.requestActive)
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(e.store.data.topics.isEmpty())
        // 剩下的只有另一个 App 的那两条
        assertEquals(2, e.runner.state.value.pending)
        e.model.gate = null
        wait(LearnTuning.DUE_AGE_MS + 1)
        e.post(pkg = "com.other")
        assertEquals(listOf("com.shop", "com.other"), e.model.packages())
        assertFalse(e.model.input(1).contains("com.shop"))
    }

    // 「清空全部」:正在发的那一批取消、结果不写回,缓冲清空
    @Test fun clearingEverythingStopsTheBatchAndEmptiesTheBuffer() = runTest {
        val e = Env(this)
        e.model.reply = { newTopic("n1", "n2") }
        e.model.gate = CompletableDeferred()
        e.post(pkg = "com.shop", count = LearnTuning.DUE_COUNT + 5)
        assertTrue(e.runner.requestActive)
        e.runner.clearAll()
        assertFalse(e.runner.requestActive)
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(e.store.data.topics.isEmpty())
        assertEquals(0, e.runner.state.value.pending)
        e.model.gate = null
        wait(LearnTuning.DUE_AGE_MS + 1)
        nudge(e)
        assertEquals(1, e.model.calls.size)
    }

    // 「清空来自某 App 的」时归纳那份读不出来(密钥库一时不可用):不整份删(里面有留两年的月归纳),记下来;
    // 读得出来时补做,做完、存上了就不再记着——之后新攒的不受影响
    @Test fun clearingAnAppWhileDigestsAreUnreadableIsDoneLaterNotByWipingEverything() = runTest {
        val e = Env(this)
        e.digest = true
        e.digestStore.data = DigestData(
            days = listOf(DayDigest(19_990, start, listOf(DigestLine("购物的一句", apps = listOf("com.shop")), DigestLine("聊天的一句", apps = listOf("com.chat"))))),
            months = listOf(SpanDigest(19_967, start, listOf(DigestLine("两个合起来的", apps = listOf("com.shop", "com.chat")), DigestLine("只有聊天的", apps = listOf("com.chat"))))),
        )
        e.digestStore.failLoad = true
        e.runner.clearApp("com.shop")
        assertEquals(0, e.digestStore.wipes)
        assertEquals(setOf("com.shop"), e.purges.digestPurges)
        assertEquals(2, e.digestStore.data.days.single().lines.size)
        e.digestStore.failLoad = false
        e.runner.open()
        assertEquals(listOf("聊天的一句"), e.digestStore.data.days.single().lines.map { it.text })
        assertEquals(listOf("只有聊天的"), e.digestStore.data.months.single().lines.map { it.text })
        assertTrue(e.purges.digestPurges.isEmpty())
        // 清空全部时,记着的也一并清掉
        val f = Env(this)
        f.digestStore.failLoad = true
        f.runner.clearApp("com.shop")
        assertEquals(setOf("com.shop"), f.purges.digestPurges)
        f.runner.clearAll()
        assertTrue(f.purges.digestPurges.isEmpty())
    }

    // ---- 删了却没存上:记着,下次读出来时补做;存上了才不再记 ----

    /** 同一份存盘、同一份记着的删除,换一个进程。 */
    private fun restarted(e: Env) = LearnRunner(
        TriageHooks(target = { target }, model = { null }, tokensToday = { 0 }, callsToday = { 0 },
            usageVersion = { MutableStateFlow(0L) }, busy = e.busy),
        on = { true }, consent = { TriageConsent.of(target) }, budgetLimit = { ProactiveBudget.DEFAULT_DAILY_LIMIT },
        language = { "English" }, eligible = { true }, sensitive = { false }, store = e.store,
        contactStore = e.contacts, purges = e.purges, digest = { true }, journalStore = e.journal,
        digestStore = e.digestStore, clock = { e.now() }, zone = { ZoneOffset.UTC },
    )

    private fun seedDigests(e: Env) {
        val at = e.now()
        e.digestStore.data = DigestData(
            notes = listOf(
                DigestNote("d1", "com.shop", 20_000L, at, at, 1, text = "店里的一句", madeAtMs = at),
                DigestNote("d2", "com.mail", 20_000L, at, at, 1, text = "邮件的一句", madeAtMs = at),
            ),
            days = listOf(
                DayDigest(20_000, at, listOf(DigestLine("今天店里的", notes = listOf("d1"), apps = listOf("com.shop")), DigestLine("今天邮件的", notes = listOf("d2"), apps = listOf("com.mail")))),
                DayDigest(19_999, at, listOf(DigestLine("昨天邮件的", apps = listOf("com.mail")))),
            ),
            weeks = listOf(SpanDigest(19_992, at, listOf(DigestLine("上周邮件的", apps = listOf("com.mail"))))),
            months = listOf(SpanDigest(19_967, at, listOf(DigestLine("上月邮件的", apps = listOf("com.mail"))))),
        )
        e.runner.open()
    }

    // 清掉某个 App 的、调成不读或低:归纳读得出来、删了却没存上,也要记着(不是只在读不出来时才记)
    @Test fun purgingAnAppIsRememberedWhenTheDigestCannotBeSaved() = runTest {
        for (viaTier in listOf(false, true)) {
            val e = Env(this)
            seedDigests(e)
            e.digestStore.failSave = true
            if (viaTier) e.runner.forgetApps(setOf("com.shop")) else e.runner.clearApp("com.shop")
            assertTrue("$viaTier", "com.shop" in e.purges.digestPurges)
            // 这次运行里已经看不到了;文件里还是旧的
            assertEquals(listOf("邮件的一句"), e.runner.digestNotes.value.map { it.text })
            assertEquals(2, e.digestStore.data.notes.size)
            // 换一个进程:读出来时补做,存上了就不再记
            e.digestStore.failSave = false
            val again = restarted(e)
            again.open()
            assertEquals(listOf("邮件的一句"), again.digestNotes.value.map { it.text })
            assertEquals(listOf("邮件的一句"), e.digestStore.data.notes.map { it.text })
            assertTrue(e.digestStore.data.days.none { d -> d.lines.any { "com.shop" in it.apps } })
            assertFalse("com.shop" in e.purges.digestPurges)
        }
    }

    // 单删一句、某一天、某一周、某个月的归纳:没存上、进程又没了,下次启动不能回来
    @Test fun singleDigestDeletesSurviveAFailedSaveAndARestart() = runTest {
        val e = Env(this)
        seedDigests(e)
        e.digestStore.failSave = true
        e.runner.deleteNote("d1")
        e.runner.deleteDay(19_999)
        e.runner.deleteSpan(SpanKind.Week, 19_992)
        e.runner.deleteSpan(SpanKind.Month, 19_967)
        assertEquals(setOf(":note:d1", ":day:19999", ":week:19992", ":month:19967"), e.purges.digestPurges)
        assertEquals(listOf("d2"), e.runner.digestNotes.value.map { it.id })
        // 文件里一样都没少
        assertEquals(2, e.digestStore.data.notes.size)
        assertEquals(1, e.digestStore.data.weeks.single().lines.size)

        e.digestStore.failSave = false
        val again = restarted(e)
        again.open()
        assertEquals(listOf("d2"), again.digestNotes.value.map { it.id })
        assertEquals(listOf(20_000L), again.digestDays.value.map { it.day })
        // 依据过那一句的当日归纳的句子也跟着去掉了(和当场删一样)
        assertEquals(listOf("今天邮件的"), again.digestDays.value.single().lines.map { it.text })
        assertTrue(again.digestWeeks.value.isEmpty())
        assertTrue(again.digestMonths.value.isEmpty())
        // 删掉的那一天、那一周、那个月留着「删过」的记号:不会被自动做回来
        assertTrue(e.digestStore.data.days.single { it.day == 19_999L }.removed)
        assertTrue(e.digestStore.data.weeks.single().removed)
        assertTrue(e.digestStore.data.months.single().removed)
        assertEquals(emptySet<String>(), e.purges.digestPurges)
    }

    // 没存上之后,这个进程里下一次存上了:不再记着——过后不会把这之后新攒的也删掉
    @Test fun aLaterSuccessfulSaveSettlesTheRememberedDeletes() = runTest {
        val e = Env(this)
        seedDigests(e)
        e.digestStore.failSave = true
        e.runner.clearApp("com.shop")
        e.runner.deleteNote("d2")
        assertEquals(setOf("com.shop", ":note:d2"), e.purges.digestPurges)
        // 再点一次同样的删除(已经没东西可删):只是再存一次
        e.digestStore.failSave = false
        e.runner.deleteNote("d2")
        assertEquals(emptySet<String>(), e.purges.digestPurges)
        assertTrue(e.digestStore.data.notes.isEmpty())
        // 之后这个 App 又攒了新的:换一个进程也不会被删
        e.digestStore.data = e.digestStore.data.copy(notes = listOf(DigestNote("d9", "com.shop", 20_000L, e.now(), e.now(), 1, text = "新的一句", madeAtMs = e.now())))
        val again = restarted(e)
        again.open()
        assertEquals(listOf("d9"), again.digestNotes.value.map { it.id })
    }

    // 没有可删的就不记、不存:启动时把「应用访问」里禁用的 App 整份再清一遍,不该每次都写偏好、写文件
    @Test fun forgettingAppsWithNothingStoredWritesNothing() = runTest {
        val e = Env(this)
        seedDigests(e)
        val writes = e.purges.writes
        val attempts = e.digestStore.saveAttempts
        e.runner.forgetApps(setOf("com.blocked", "com.other"))
        assertEquals(writes, e.purges.writes)
        assertEquals(attempts, e.digestStore.saveAttempts)
    }

    // 单删一个话题、一个联系人:没存上、进程又没了,下次启动不能回来;话题的名字照样记进「不再学」
    @Test fun singleTopicAndContactDeletesSurviveAFailedSaveAndARestart() = runTest {
        val e = Env(this)
        e.store.data = seededTopics()
        e.contacts.data = seededContacts()
        e.runner.open()
        val topic = e.runner.topics.value.first()
        val contact = e.runner.contactsView.value.contacts.first()
        e.store.failSave = true
        e.contacts.failSave = true
        e.runner.deleteTopic(topic.id)
        e.runner.deleteContact(contact.id)
        assertEquals(setOf(":topic:${topic.id}"), e.purges.topicPurges)
        assertEquals(setOf(":contact:${contact.id}"), e.purges.contactPurges)
        assertTrue(e.store.data.topics.any { it.id == topic.id })
        assertTrue(e.contacts.data.contacts.any { it.id == contact.id })

        e.store.failSave = false
        e.contacts.failSave = false
        val again = restarted(e)
        again.open()
        assertTrue(again.topics.value.none { it.id == topic.id })
        assertTrue(again.contactsView.value.contacts.none { it.id == contact.id })
        assertTrue(e.store.data.topics.none { it.id == topic.id })
        assertTrue(e.contacts.data.contacts.none { it.id == contact.id })
        assertEquals(1, e.store.data.blocked.size)
        assertEquals(1, e.contacts.data.blocked.size)
        assertEquals(emptySet<String>(), e.purges.topicPurges)
        assertEquals(emptySet<String>(), e.purges.contactPurges)
        // 别的话题、联系人都还在
        assertEquals(seededTopics().topics.size - 1, e.store.data.topics.size)
    }

    // ---- 分段归纳的句子没存上:原文不能先标成「做过了」 ----

    // 句子没存上时,原文照样排着队(标成「做过了」并存下去的话:重启后句子没有、原文也不再归纳,这一批就丢了);
    // 内存里的句子退回去;一直存不上就不再发请求;存得上了再归纳一次,这回留得住
    @Test fun notesAreNotMarkedDoneUntilTheyAreSaved() = runTest {
        val e = Env(this)
        e.digest = true
        // 请求回来的那一刻存盘开始出错
        e.model.notesReply = { e.digestStore.failSave = true; oneNote }
        fun notesCalls() = e.model.kinds.count { it == "notes" }

        e.post(count = LearnTuning.NOTES_DUE_COUNT)
        assertEquals(1, notesCalls())
        // 文件里没有句子;原文一条都没标成做过;内存里也没留下这一批的句子
        assertTrue(e.digestStore.data.notes.isEmpty())
        assertEquals(0, e.journal.data!!.entries.count { it.noted })
        assertTrue(e.runner.digestNotes.value.isEmpty())
        assertEquals(LearnTuning.NOTES_DUE_COUNT, e.runner.state.value.journal)

        // 一直存不上:停着的那一阵过了也不再发(先补存,存不上就不发)
        wait(LearnTuning.FAILURE_HOLD_MS.last() + 1)
        e.runner.poke()
        runCurrent()
        wait(LearnTuning.NOTES_DUE_AGE_MS)
        e.runner.poke()
        runCurrent()
        assertEquals(1, notesCalls())
        assertEquals(LearnStatus.StoreUnavailable, e.runner.state.value.status)
        assertEquals(0, e.journal.data!!.entries.count { it.noted })

        // 存得上了:把这一批归纳一次,这回句子和「做过了」都落了盘
        e.model.notesReply = { oneNote }
        e.digestStore.failSave = false
        e.runner.poke()
        runCurrent()
        assertEquals(2, notesCalls())
        assertEquals(listOf("买的东西发货了"), e.digestStore.data.notes.map { it.text })
        assertEquals(LearnTuning.NOTES_DUE_COUNT, e.journal.data!!.entries.count { it.noted })
        // 换一个进程:句子在,这一批原文不再排队
        val again = restarted(e)
        again.open()
        assertEquals(1, again.digestNotes.value.size)
    }

    // 归纳那份有没存上的删除时,不发分段归纳的请求(结果留不住);存得上了,先把那次删除落盘,再发
    @Test fun noNotesRequestWhileADeleteIsStillUnsaved() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { oneNote }
        val at = e.now()
        e.digestStore.data = DigestData(notes = listOf(DigestNote("old", "com.gone", 20_000L, at, at, 1, text = "要清掉的 App 的一句", madeAtMs = at)))
        e.runner.open()
        e.digestStore.failSave = true
        e.runner.clearApp("com.gone")
        assertEquals(setOf("com.gone"), e.purges.digestPurges)
        e.post(count = LearnTuning.NOTES_DUE_COUNT)
        assertEquals(0, e.model.kinds.count { it == "notes" })
        assertEquals(LearnStatus.StoreUnavailable, e.runner.state.value.status)
        assertEquals(setOf("com.gone"), e.purges.digestPurges)
        e.digestStore.failSave = false
        e.runner.poke()
        runCurrent()
        assertEquals(1, e.model.kinds.count { it == "notes" })
        assertEquals(emptySet<String>(), e.purges.digestPurges)
        assertEquals(listOf("com.shop"), e.digestStore.data.notes.map { it.packageName })
    }

    // 当日归纳的结果没存上:不重发请求,下一次有事时先补存
    @Test fun aDayDigestThatCouldNotBeSavedIsWrittenLaterWithoutAskingAgain() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { oneNote }
        e.model.dayReply = { """{"lines":[{"ids":["s1"],"text":"今天的一句"}]}""" }
        e.post(count = LearnTuning.NOTES_DUE_COUNT)
        assertEquals(1, e.digestStore.data.notes.size)
        e.digestStore.failSave = true
        e.runner.digestToday()
        runCurrent()
        assertEquals(1, e.model.kinds.count { it == "day" })
        assertTrue(e.digestStore.data.days.isEmpty())
        assertEquals(1, e.runner.digestDays.value.size)
        // 存得上了:下一次有事(哪怕没有归纳要做)就补存上,不再问模型
        e.digestStore.failSave = false
        e.runner.poke()
        runCurrent()
        assertEquals(listOf("今天的一句"), e.digestStore.data.days.single().lines.map { it.text })
        assertEquals(1, e.model.kinds.count { it == "day" })
    }

    // 分段归纳的请求在途时,用户清掉了另一个 App 的、偏偏没存上;随后句子也没存上要退回去:
    // 退回去的是「清掉之后」的样子,那次清除照样记着、照样作数
    @Test fun backingOutNotesKeepsADeleteMadeWhileTheRequestWasInFlight() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { oneNote }
        val at = e.now()
        e.digestStore.data = DigestData(notes = listOf(DigestNote("old", "com.gone", 20_000L, at, at, 1, text = "要清掉的 App 的一句", madeAtMs = at)))
        e.runner.open()
        e.model.gate = CompletableDeferred()
        e.post(count = LearnTuning.NOTES_DUE_COUNT)
        // 归纳话题的那一批先发;放它过去,让分段归纳的请求停在途中
        while (e.model.kinds.lastOrNull() != "notes") {
            e.model.gate!!.complete(Unit)
            e.model.gate = CompletableDeferred()
            runCurrent()
        }
        assertTrue(e.runner.requestActive)
        e.digestStore.failSave = true
        e.runner.clearApp("com.gone")
        assertTrue(e.runner.requestActive)
        assertEquals(setOf("com.gone"), e.purges.digestPurges)
        e.model.gate!!.complete(Unit)
        e.model.gate = null
        runCurrent()
        // 句子没留下,清掉的也没回来,那次清除还记着
        assertTrue(e.runner.digestNotes.value.isEmpty())
        assertEquals(setOf("com.gone"), e.purges.digestPurges)
        assertEquals(0, e.journal.data!!.entries.count { it.noted })
        assertEquals(listOf("old"), e.digestStore.data.notes.map { it.id })
        // 存得上了:清除落盘,再把那一批归纳一次
        e.digestStore.failSave = false
        wait(LearnTuning.FAILURE_HOLD_MS.last() + 1)
        e.runner.poke()
        runCurrent()
        assertEquals(emptySet<String>(), e.purges.digestPurges)
        assertEquals(listOf("com.shop"), e.digestStore.data.notes.map { it.packageName })
    }

    // ---- 留存期:到期的按期从文件里去掉,读的人也拿不到 ----

    // 通知原文:读进来时就有过期的,内存里当场去掉了——文件里也得去掉(等别的改动顺带存的话,可能一直等不到)
    @Test fun expiredRawTextIsRemovedFromTheFileAsSoonAsItIsLoaded() = runTest {
        val e = Env(this)
        e.digest = true
        val old = e.now() - NoticeJournal.KEEP_MS - 1
        e.journal.data = NoticeJournalData(sinceMs = old, entries = listOf(
            NoticeEntry("com.shop", old, "Title", text = "过期的原文"),
            NoticeEntry("com.shop", e.now() - 1_000, "Title", text = "还新的原文"),
        ))
        val saves = e.journal.saves
        e.runner.poke()
        runCurrent()
        assertEquals(listOf("还新的原文"), e.journal.texts)
        assertEquals(saves + 1, e.journal.saves)
        // 没有过期的就不为这个多存一次(换一个进程,让它把原文读进来)
        val again = restarted(e)
        val view = again.notices(e.now() - LearnTuning.DAY_MS, e.now() + 1, LearnLevel.Aggressive) { SourceTier.High }!!
        assertEquals(listOf("还新的原文"), view.entries.map { it.text })
        assertEquals(saves + 1, e.journal.saves)
    }

    // 各级归纳:任务来读的时候,过了留存期的不给,文件里也去掉——不用等用户打开页面
    @Test fun expiredDigestsAreNeitherGivenToTasksNorKept() = runTest {
        val today = 20_000L
        fun line(text: String) = listOf(DigestLine(text, apps = listOf("com.shop")))
        val at = start
        val data = DigestData(
            notes = listOf(
                DigestNote("gone", "com.shop", today - LearnTuning.NOTES_KEEP_DAYS - 1, at - 9 * LearnTuning.DAY_MS, at - 9 * LearnTuning.DAY_MS, 1, text = "过期的一句", madeAtMs = at),
                DigestNote("kept", "com.shop", today, at, at, 1, text = "今天的一句", madeAtMs = at),
            ),
            days = listOf(DayDigest(today - LearnTuning.DAYS_KEEP - 1, 1, line("过期的一天")), DayDigest(today - 1, 1, line("昨天"))),
            weeks = listOf(
                SpanDigest(today - LearnTuning.WEEKS_KEEP_DAYS - 7, 1, line("过期的一周")),
                SpanDigest(DigestPool.weekOf(today) - 7, 1, line("上周")),
            ),
            months = listOf(
                SpanDigest(today - LearnTuning.MONTHS_KEEP_DAYS - 31, 1, line("过期的一个月")),
                SpanDigest(DigestPool.monthOf(DigestPool.monthOf(today) - 1), 1, line("上个月")),
            ),
        )
        val tier: (String) -> SourceTier = { SourceTier.High }
        // 三条读的路,各自从一份没清过的文件开始
        run {
            val e = Env(this); e.digest = true; e.digestStore.data = data
            val days = e.runner.digests(DigestLevel.Day, LearnLevel.Balanced, tier)!!
            assertEquals(listOf("昨天"), days.digests.flatMap { d -> d.lines.map { it.text } })
            assertEquals(listOf("上周"), e.runner.digests(DigestLevel.Week, LearnLevel.Balanced, tier)!!.digests.flatMap { d -> d.lines.map { it.text } })
            assertEquals(listOf("上个月"), e.runner.digests(DigestLevel.Month, LearnLevel.Balanced, tier)!!.digests.flatMap { d -> d.lines.map { it.text } })
            // 文件里也去掉了
            assertEquals(listOf("kept"), e.digestStore.data.notes.map { it.id })
            assertEquals(listOf(today - 1), e.digestStore.data.days.map { it.day })
            assertEquals(1, e.digestStore.data.weeks.size)
            assertEquals(1, e.digestStore.data.months.size)
        }
        run {
            val e = Env(this); e.digest = true; e.digestStore.data = data
            e.journal.data = NoticeJournalData(sinceMs = start, entries = emptyList())
            val view = e.runner.notices(start - 30 * LearnTuning.DAY_MS, start + LearnTuning.DAY_MS, LearnLevel.Balanced, tier)!!
            assertEquals(listOf("今天的一句"), view.notes.map { it.text })
            assertEquals(listOf("kept"), e.digestStore.data.notes.map { it.id })
        }
        run {
            val e = Env(this); e.digest = true; e.digestStore.data = data
            val carried = e.runner.observed(LearnLevel.Balanced, tier)!!
            assertTrue("昨天" in carried)
            for (no in listOf("过期的一天", "过期的一周", "过期的一个月")) assertFalse(no, no in carried)
            assertEquals(listOf(today - 1), e.digestStore.data.days.map { it.day })
        }
        // 没人读也一样:平常有事看一遍时就清
        run {
            val e = Env(this); e.digest = true; e.digestStore.data = data
            e.post()
            assertEquals(listOf("kept"), e.digestStore.data.notes.map { it.id })
            assertEquals(listOf(today - 1), e.digestStore.data.days.map { it.day })
            assertEquals(1, e.digestStore.data.weeks.size)
            assertEquals(1, e.digestStore.data.months.size)
        }
        // 「当日消息归纳」关着(东西还留着,期限照算):已经读进来的那份照样清
        run {
            val e = Env(this); e.digest = false
            e.digestStore.data = DigestData(days = listOf(DayDigest(today - LearnTuning.DAYS_KEEP + 1, 1, line("快到期的一天")), DayDigest(today - 1, 1, line("昨天"))))
            e.runner.open()
            assertEquals(2, e.runner.digestDays.value.size)
            wait(3 * LearnTuning.DAY_MS)
            e.post()
            assertEquals(listOf(today - 1), e.digestStore.data.days.map { it.day })
        }
    }

    // 会话样式的通知:正文是空的,话在消息列表里——归纳的请求里要有这回才见到的那几条
    @Test fun newMessagesReachTheLearningRequest() = runTest {
        val e = Env(this)
        repeat(LearnTuning.DUE_COUNT) { i ->
            val said = NoticeMessage("Alice", "第 ${i + 1} 句:明天三点开会", e.now())
            e.runner.capture(NoticeRecord("chat$i", "com.chat", e.now(), "Alice", "", messages = listOf(said), conversation = true), listOf(said))
        }
        runCurrent()
        assertEquals(1, e.model.calls.size)
        val input = e.model.input(0)
        assertTrue("Alice: 第 1 句:明天三点开会" in input)
        assertTrue("第 ${LearnTuning.DUE_COUNT} 句" in input)
    }

    // ---- 在发的请求不该被无关的事取消 ----

    // 上一次看守时在发的是某个 App 的分段归纳;看守停了又开,这一次在发的是别的 App 的归纳话题。
    // 这时前一个 App 不再归纳了:不能凭上一次留下的「在发的是它的」把现在这个请求取消掉
    @Test fun aRequestLeftFromAnEarlierWatchDoesNotCancelALaterOne() = runTest {
        val model = Fake().also { it.gate = CompletableDeferred() }
        val busy = MutableStateFlow(false)
        val journal = MemJournal().also {
            it.data = NoticeJournalData(sinceMs = start, entries = (1..LearnTuning.NOTES_DUE_COUNT).map { i -> NoticeEntry("com.old.notes", start + i, "t", text = "text $i") })
        }
        var oldAppEligible = true
        val locked = MutableStateFlow(false)
        val runner = LearnRunner(
            TriageHooks(target = { target }, model = { TargetedModel(target, model) }, tokensToday = { 0 }, callsToday = { 0 },
                usageVersion = { MutableStateFlow(0L) }, busy = busy),
            on = { true }, consent = { TriageConsent.of(target) }, budgetLimit = { ProactiveBudget.DEFAULT_DAILY_LIMIT },
            language = { "English" }, eligible = { it != "com.old.notes" || oldAppEligible }, sensitive = { false },
            store = MemStore(), digest = { true }, journalStore = journal, digestStore = MemDigest(),
            clock = { start + testScheduler.currentTime }, zone = { ZoneOffset.UTC },
        )
        val first = backgroundScope.launch { runner.watch(locked) { locked.value } }
        runCurrent()
        assertEquals(listOf("notes"), model.kinds)
        first.cancel()
        runCurrent()
        // 有任务在跑时重新开始看守,攒够一批;任务跑完,发的是归纳话题的请求
        busy.value = true
        backgroundScope.launch { runner.watch(locked) { locked.value } }
        runCurrent()
        repeat(LearnTuning.DUE_COUNT) { i -> runner.capture(NoticeRecord("k$i", "com.shop", start + testScheduler.currentTime, "title $i", "text $i")) }
        runCurrent()
        busy.value = false
        runCurrent()
        assertEquals(listOf("notes", "learn"), model.kinds)
        assertTrue(runner.requestActive)
        val cancelled = model.cancelled
        oldAppEligible = false
        runner.poke()
        runCurrent()
        assertTrue(runner.requestActive)
        assertEquals(cancelled, model.cancelled)
        assertEquals(listOf("notes", "learn"), model.kinds)
    }

    // 看一遍时读配置出了一次错:在发的请求不动,只是这一回不发新的
    @Test fun aFailedConfigurationReadLeavesTheRequestInFlightAlone() = runTest {
        val e = Env(this)
        e.model.gate = CompletableDeferred()
        e.post(count = LearnTuning.DUE_COUNT)
        assertTrue(e.runner.requestActive)
        val real = e.effective
        e.targetFails = true
        e.runner.poke()
        runCurrent()
        assertTrue(e.runner.requestActive)
        assertEquals(0, e.model.cancelled)
        assertEquals(LearnStatus.Sending, e.runner.state.value.status)
        e.targetFails = false
        e.effective = real
        e.model.reply = { newTopic("n1") }
        e.model.gate!!.complete(Unit)
        e.model.gate = null
        runCurrent()
        assertEquals(1, e.model.calls.size)
        assertEquals(listOf("Hiking"), e.store.data.topics.map { it.label })
    }

    // 锁屏状态读不出来的时候不能留着请求:锁屏不发是硬规矩,不知道锁没锁就当锁了。这一批留着
    @Test fun aFailedLockReadStopsTheRequest() = runTest {
        val e = Env(this)
        e.model.gate = CompletableDeferred()
        e.post(count = LearnTuning.DUE_COUNT)
        assertTrue(e.runner.requestActive)
        e.lockReadFails = true
        e.runner.poke()
        runCurrent()
        assertFalse(e.runner.requestActive)
        assertEquals(1, e.model.cancelled)
        assertEquals(LearnTuning.DUE_COUNT, e.runner.state.value.pending)
    }

    // 删掉今天的当日归纳:页面要说得出「删掉了,不会再自动做」(不是「还没有,过完这一天会自动归纳」);
    // 用户又点了「归纳到现在」做回来之后,就不再算删掉的
    @Test fun aDeletedDayIsReportedAsDeleted() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { oneNote }
        e.model.dayReply = { """{"lines":[{"ids":["s1"],"text":"今天的一句"}]}""" }
        e.post(count = LearnTuning.NOTES_DUE_COUNT)
        e.runner.digestToday()
        runCurrent()
        assertEquals(listOf(20_000L), e.runner.digestDays.value.map { it.day })
        assertTrue(e.runner.digestRemovedDays.value.isEmpty())
        e.runner.deleteDay(20_000)
        assertTrue(e.runner.digestDays.value.isEmpty())
        assertEquals(setOf(20_000L), e.runner.digestRemovedDays.value)
        // 换一个进程也说得出来
        val again = restarted(e)
        again.open()
        assertEquals(setOf(20_000L), again.digestRemovedDays.value)
        e.runner.digestToday()
        runCurrent()
        assertEquals(listOf(20_000L), e.runner.digestDays.value.map { it.day })
        assertTrue(e.runner.digestRemovedDays.value.isEmpty())
    }
}
