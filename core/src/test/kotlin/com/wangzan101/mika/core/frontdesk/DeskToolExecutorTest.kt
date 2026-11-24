package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskToolExecutorTest {
    /** 假的任务端口:记下收到的调用,派出去的任务按顺序编号 t1、t2…;[queueAt] 非空时派单结果是排队。 */
    private class FakePort : DeskTaskPort {
        data class Dispatch(val words: String, val note: String, val parent: String)

        val dispatched = mutableListOf<Dispatch>()
        val steered = mutableListOf<Pair<String, String>>()
        val answered = mutableListOf<Pair<String, String>>()
        val cancelled = mutableListOf<String>()
        val states = HashMap<String, MutableStateFlow<TaskState>>()
        val positions = HashMap<String, Int>()
        var queueAt: Int? = null
        var accept = true

        override fun dispatch(words: String, note: String, parentConversation: String): DispatchOutcome {
            dispatched += Dispatch(words, note, parentConversation)
            val id = "t${dispatched.size}"
            states[id] = MutableStateFlow(TaskState.Idle)
            return queueAt?.let { DispatchOutcome.Queued(id, it) } ?: DispatchOutcome.Started(id)
        }

        /** 带文件派的:子会话编号 f1、f2…,[prepare] 给出的原话记在 [withFiles]。 */
        val withFiles = mutableListOf<Pair<String, String>>()

        override suspend fun dispatchWithFiles(parentConversation: String, prepare: suspend (child: String) -> String?): DispatchOutcome {
            val id = "f${withFiles.size + 1}"
            val words = prepare(id) ?: return DispatchOutcome.Failed("files_not_ready")
            withFiles += id to words
            states[id] = MutableStateFlow(TaskState.Idle)
            return queueAt?.let { DispatchOutcome.Queued(id, it) } ?: DispatchOutcome.Started(id)
        }

        override fun state(taskId: String): StateFlow<TaskState>? = states[taskId]
        override fun queuePosition(taskId: String): Int? = positions[taskId]
        override fun steer(taskId: String, words: String): Boolean = accept.also { steered += taskId to words }
        override fun answer(taskId: String, words: String): Boolean = accept.also { answered += taskId to words }
        override fun cancel(taskId: String): Boolean = accept.also { cancelled += taskId }
    }

    private var now = 1_000_000L
    private val port = FakePort()
    private val ledger = DeskLedger()
    private val notified = mutableListOf<DeskTaskEntry>()
    /** 核对用户原话:[said] 给派单、补话、回答,[saidNow] 给取消;默认认模型给的那段本身。 */
    private var said: (String) -> String? = { it }
    private var saidNow: (String) -> String? = { it }
    private val words = object : DeskWords {
        override fun said(fragment: String) = this@DeskToolExecutorTest.said(fragment)
        override fun saidNow(fragment: String) = this@DeskToolExecutorTest.saidNow(fragment)
    }

    private fun executor(words: DeskWords = this.words) = DeskToolExecutor(
        tasks = port, ledger = ledger, words = words, clock = { now },
        conversationId = "c1", onDispatched = { notified += it },
    )

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    private fun run(name: String, vararg pairs: Pair<String, String>) =
        runBlocking { executor().execute(name, args(*pairs)) }

    private fun JsonObject.str(key: String) = get(key)?.jsonPrimitive?.content

    private fun dispatch(words: String = "帮我订明天的会议室") =
        run(DeskTools.DISPATCH, "words" to words, "note" to "上午十点左右", "title" to "订会议室")

    @Test
    fun dispatchRejectsUnverifiedWords() {
        said = { null }
        val result = dispatch()
        assertEquals(buildJsonObject { put("status", "rejected"); put("reason", "words_not_verified") }, result)
        assertTrue(port.dispatched.isEmpty())
        assertTrue(ledger.all().isEmpty())
    }

    @Test
    fun dispatchPassesWordsAndNoteSeparately() {
        val result = dispatch()
        assertEquals(buildJsonObject { put("status", "started"); put("task_id", "t1") }, result)
        assertEquals(listOf(FakePort.Dispatch("帮我订明天的会议室", "上午十点左右", "c1")), port.dispatched)
        val entry = DeskTaskEntry("t1", "订会议室", "帮我订明天的会议室", now)
        assertEquals(listOf(entry), ledger.all())
        assertEquals(listOf(entry), notified)
    }

    @Test
    fun dispatchQueuedReportsPosition() {
        port.queueAt = 2
        val result = dispatch()
        assertEquals(buildJsonObject { put("status", "queued"); put("task_id", "t1"); put("position", 2) }, result)
        assertEquals(1, ledger.all().size)
    }

    @Test
    fun dispatchFailureIsNotRecorded() {
        val failing = object : DeskTaskPort by port {
            override fun dispatch(words: String, note: String, parentConversation: String) = DispatchOutcome.Failed("no_model")
        }
        val result = runBlocking {
            DeskToolExecutor(failing, ledger, words, { now }, "c1", { notified += it })
                .execute(DeskTools.DISPATCH, args("words" to "查天气", "title" to "天气"))
        }
        assertEquals(buildJsonObject { put("status", "failed"); put("reason", "no_model") }, result)
        assertTrue(ledger.all().isEmpty())
        assertTrue(notified.isEmpty())
    }

    @Test
    fun dispatchSameWordsTwiceReturnsExistingTask() {
        dispatch()
        now += DISPATCH_DEDUPE_MS - 1
        val again = dispatch()
        assertEquals(buildJsonObject { put("status", "already_dispatched"); put("task_id", "t1") }, again)
        assertEquals(1, port.dispatched.size)

        now += 2
        val later = dispatch()
        assertEquals("started", later.str("status"))
        assertEquals("t2", later.str("task_id"))
        assertEquals(2, port.dispatched.size)
    }

    @Test
    fun answerAndAddRequireVerifiedWords() {
        dispatch()
        said = { null }
        val rejected = buildJsonObject { put("status", "rejected"); put("reason", "words_not_verified") }
        assertEquals(rejected, run(DeskTools.ANSWER, "task_id" to "t1", "words" to "选第二个"))
        assertEquals(rejected, run(DeskTools.ADD, "task_id" to "t1", "words" to "顺便订午饭"))
        assertTrue(port.answered.isEmpty())
        assertTrue(port.steered.isEmpty())

        said = { it }
        val ok = buildJsonObject { put("status", "ok") }
        assertEquals(ok, run(DeskTools.ANSWER, "task_id" to "t1", "words" to "选第二个"))
        assertEquals(ok, run(DeskTools.ADD, "task_id" to "t1", "words" to "顺便订午饭"))
        assertEquals(listOf("t1" to "选第二个"), port.answered)
        assertEquals(listOf("t1" to "顺便订午饭"), port.steered)
    }

    @Test
    fun portRefusalIsNotAccepted() {
        dispatch()
        port.accept = false
        val refused = buildJsonObject { put("status", "not_accepted") }
        assertEquals(refused, run(DeskTools.ANSWER, "task_id" to "t1", "words" to "好"))
        assertEquals(refused, run(DeskTools.CANCEL, "task_id" to "t1", "words" to "别订了"))
    }

    @Test
    fun cancelGoesToPort() {
        dispatch()
        assertEquals(buildJsonObject { put("status", "ok") }, run(DeskTools.CANCEL, "task_id" to "t1", "words" to "别订了"))
        assertEquals(listOf("t1"), port.cancelled)
    }

    @Test
    fun unknownTaskIdIsRejected() {
        dispatch()
        val unknown = buildJsonObject { put("status", "unknown_task") }
        assertEquals(unknown, run(DeskTools.ADD, "task_id" to "t9", "words" to "顺便订午饭"))
        assertEquals(unknown, run(DeskTools.ANSWER, "task_id" to "t9", "words" to "好"))
        assertEquals(unknown, run(DeskTools.CANCEL, "task_id" to "t9"))
        assertEquals(unknown, run(DeskTools.CANCEL))
        assertTrue(port.steered.isEmpty() && port.answered.isEmpty() && port.cancelled.isEmpty())
    }

    @Test
    fun askOwnerIsNotHandledHere() {
        assertEquals(buildJsonObject { put("status", "unsupported") }, run(DeskTools.ASK_OWNER, "question" to "可以吗"))
        assertEquals(buildJsonObject { put("status", "unsupported") }, run("tap"))
    }

    @Test
    fun toolSetsCarryOnlyHandoffTools() {
        fun names(set: DeskToolSet) = DeskTools.specs(set).map { LlmBrain.toolSpec(it).name }.toSet()
        assertEquals(setOf("dispatch_task", "add_to_task", "answer_task", "cancel_task"), names(DeskToolSet.User))
        assertEquals(setOf("ask_owner"), names(DeskToolSet.Phone))
        val dispatchSpec = LlmBrain.toolSpec(DeskTools.specs(DeskToolSet.User).first { LlmBrain.toolSpec(it).name == DeskTools.DISPATCH })
        assertFalse(dispatchSpec.description.startsWith("\""))
    }

    @Test
    fun wordsDescriptionIsVerbatimRule() {
        val withWords = DeskTools.specs(DeskToolSet.User).filter { it.getValue("parameters").jsonObject.getValue("properties").jsonObject.containsKey("words") }
        assertEquals(setOf(DeskTools.DISPATCH, DeskTools.ADD, DeskTools.ANSWER, DeskTools.CANCEL), withWords.map { it.str("name") }.toSet())
        withWords.forEach {
            val words = it.getValue("parameters").jsonObject.getValue("properties").jsonObject.getValue("words").jsonObject
            assertEquals("用户说过的原话，逐字照抄，不要改写", words.str("description"))
        }
    }

    @Test
    fun lookupNamesAreUnsupported() {
        val unsupported = buildJsonObject { put("status", "unsupported") }
        assertEquals(unsupported, run("check_tasks"))
        assertEquals(unsupported, run("web_search", "query" to "明天天气"))
        assertEquals(unsupported, run("recall_memory", "query" to "座位"))
        assertTrue(port.dispatched.isEmpty() && port.steered.isEmpty() && port.answered.isEmpty() && port.cancelled.isEmpty())
    }

    @Test
    fun dedupeIgnoresFinishedTask() {
        val finished = listOf(TaskState.Succeeded("订好了"), TaskState.Failed("满了", FailKind.GaveUp), TaskState.Cancelled)
        var latest = dispatch().str("task_id")!!
        finished.forEachIndexed { i, state ->
            // 上一件同话任务已经结束:30 秒内再说同一句,当新任务派。
            port.states.getValue(latest).value = state
            now += 1_000
            val again = dispatch()
            assertEquals("started", again.str("status"))
            assertEquals("t${i + 2}", again.str("task_id"))
            latest = again.str("task_id")!!
        }
        // 最后派出的那件还在跑:同一句话仍然认成重复。
        port.states.getValue("t4").value = TaskState.Running(step = 1, note = "tap")
        now += 1_000
        assertEquals(buildJsonObject { put("status", "already_dispatched"); put("task_id", "t4") }, dispatch())
    }

    @Test
    fun dispatchThrowingPortGivesFailed() {
        val throwing = object : DeskTaskPort by port {
            override fun dispatch(words: String, note: String, parentConversation: String): DispatchOutcome = error("service gone")
        }
        val result = runBlocking {
            DeskToolExecutor(throwing, ledger, words, { now }, "c1", { notified += it })
                .execute(DeskTools.DISPATCH, args("words" to "查天气", "title" to "天气"))
        }
        assertEquals(buildJsonObject { put("status", "failed"); put("reason", "service gone") }, result)
        assertTrue(ledger.all().isEmpty())
        assertTrue(notified.isEmpty())
    }

    @Test
    fun ledgerFindMatchesWordsWithinWindow() {
        ledger.add(DeskTaskEntry("a", "A", "查天气", 100))
        ledger.add(DeskTaskEntry("b", "B", "查天气", 200))
        assertEquals("b", ledger.find("查天气", since = 150)?.taskId)
        assertEquals("b", ledger.find(" 查天气 ", since = 0)?.taskId)
        assertNull(ledger.find("查天气", since = 201))
        assertNull(ledger.find("订机票", since = 0))
    }
    // 带文件的一句:用户打的话照样要核对;派成一件新任务,登记进账本、交给前台跟进,和 dispatch_task 同形
    @Test
    fun dispatchWithFilesChecksWordsAndRecordsTheTask() {
        val result = runBlocking { executor().dispatchWithFiles("看看这份合同", "看合同") { child -> "看看这份合同\n附件:$child/a.pdf" } }
        assertEquals(buildJsonObject { put("status", "started"); put("task_id", "f1") }, result)
        assertEquals(listOf("f1" to "看看这份合同\n附件:f1/a.pdf"), port.withFiles)
        val entry = DeskTaskEntry("f1", "看合同", "看看这份合同", now)
        assertEquals(listOf(entry), ledger.all())
        assertEquals(listOf(entry), notified)
        assertTrue(port.dispatched.isEmpty())

        said = { null }
        val rejected = runBlocking { executor().dispatchWithFiles("不是用户说的", "x") { "不是用户说的" } }
        assertEquals("rejected", rejected.str("status"))
        assertEquals(1, port.withFiles.size)
    }

    // 只发了文件、没写话:没有要核对的原话,照样派;文件没放进去时回 failed,不登记
    @Test
    fun dispatchWithFilesWithoutWordsAndFailedPrepare() {
        said = { null }
        val onlyFiles = runBlocking { executor().dispatchWithFiles("", "a.pdf") { "附件:a.pdf" } }
        assertEquals("started", onlyFiles.str("status"))
        assertEquals("a.pdf", ledger.all().single().title)
        val failed = runBlocking { executor().dispatchWithFiles("", "b.pdf") { null } }
        assertEquals(buildJsonObject { put("status", "failed"); put("reason", "files_not_ready") }, failed)
        assertEquals(1, ledger.all().size)
    }

    // 交给后台的是片段所在的整句:摘掉否定词改不了用户的意思
    @Test
    fun dispatchHandsOverTheWholeSentence() {
        val sentence = "不要删除那些照片，先帮我看看有多少张"
        said = { f -> sentence.takeIf { f in it } }
        assertEquals("started", dispatch("删除那些照片").str("status"))
        assertEquals(sentence, port.dispatched.single().words)
        assertEquals(sentence, ledger.all().single().words)
    }

    @Test
    fun addAndAnswerHandOverTheWholeSentence() {
        dispatch()
        val sentence = "不是这家，换成楼下那家"
        said = { f -> sentence.takeIf { f in it } }
        run(DeskTools.ADD, "task_id" to "t1", "words" to "换成楼下那家")
        run(DeskTools.ANSWER, "task_id" to "t1", "words" to "楼下那家")
        assertEquals(listOf("t1" to sentence), port.steered)
        assertEquals(listOf("t1" to sentence), port.answered)
    }

    // 一句话办两件事:两件都带整句,不因为原话相同被当成重复;同一件再派一次才算重复
    @Test
    fun oneSentenceCanStartTwoTasks() {
        val sentence = "帮我订日料,再查汇率"
        said = { f -> sentence.takeIf { f in it } }
        val first = run(DeskTools.DISPATCH, "words" to "帮我订日料", "title" to "订日料")
        val second = run(DeskTools.DISPATCH, "words" to "再查汇率", "title" to "查汇率")
        assertEquals(listOf("started", "started"), listOf(first.str("status"), second.str("status")))
        assertEquals(listOf(sentence, sentence), port.dispatched.map { it.words })
        val again = run(DeskTools.DISPATCH, "words" to "再查汇率", "title" to "查汇率")
        assertEquals(buildJsonObject { put("status", "already_dispatched"); put("task_id", "t2") }, again)
    }

    // 取消也要用户这一轮的原话:没带、或只是之前说过的,都不停
    @Test
    fun cancelNeedsTheCurrentTurnsWords() {
        dispatch()
        val rejected = buildJsonObject { put("status", "rejected"); put("reason", "words_not_verified") }
        assertEquals(rejected, run(DeskTools.CANCEL, "task_id" to "t1"))
        saidNow = { null }
        assertEquals(rejected, run(DeskTools.CANCEL, "task_id" to "t1", "words" to "别订了"))
        assertTrue(port.cancelled.isEmpty())
        saidNow = { it }
        assertEquals(buildJsonObject { put("status", "ok") }, run(DeskTools.CANCEL, "task_id" to "t1", "words" to "别订了"))
        assertEquals(listOf("t1"), port.cancelled)
    }

    // 不是用户开口的一轮(汇报):什么原话都不认,四个工具都不动后台
    @Test
    fun noWordsAreAcceptedOnAReportTurn() {
        dispatch()
        val none = executor(DeskWords.None)
        val rejected = buildJsonObject { put("status", "rejected"); put("reason", "words_not_verified") }
        runBlocking {
            assertEquals(rejected, none.execute(DeskTools.DISPATCH, args("words" to "帮我订明天的会议室", "title" to "x")))
            assertEquals(rejected, none.execute(DeskTools.ADD, args("task_id" to "t1", "words" to "顺便订午饭")))
            assertEquals(rejected, none.execute(DeskTools.ANSWER, args("task_id" to "t1", "words" to "计算器")))
            assertEquals(rejected, none.execute(DeskTools.CANCEL, args("task_id" to "t1", "words" to "别订了")))
            assertEquals(rejected, none.dispatchWithFiles("看看这份合同", "x") { "看看这份合同" })
        }
        assertEquals(1, port.dispatched.size)
        assertTrue(port.steered.isEmpty() && port.answered.isEmpty() && port.cancelled.isEmpty() && port.withFiles.isEmpty())
    }
}
