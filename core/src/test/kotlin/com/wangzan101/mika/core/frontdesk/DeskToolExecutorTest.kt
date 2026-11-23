package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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

        override fun state(taskId: String): StateFlow<TaskState>? = states[taskId]
        override fun queuePosition(taskId: String): Int? = positions[taskId]
        override fun steer(taskId: String, words: String): Boolean = accept.also { steered += taskId to words }
        override fun answer(taskId: String, words: String): Boolean = accept.also { answered += taskId to words }
        override fun cancel(taskId: String): Boolean = accept.also { cancelled += taskId }
    }

    private class FakeLookup(val search: String? = null, val memory: String? = null) : DeskLookup {
        val queries = mutableListOf<String>()
        override suspend fun webSearch(query: String): String? = search.also { queries += query }
        override suspend fun recallMemory(query: String): String? = memory.also { queries += query }
    }

    private var now = 1_000_000L
    private val port = FakePort()
    private val ledger = DeskLedger()
    private val notified = mutableListOf<DeskTaskEntry>()
    private var verified: (String) -> Boolean = { true }

    private fun executor(lookup: DeskLookup = FakeLookup()) = DeskToolExecutor(
        tasks = port, lookup = lookup, ledger = ledger, verifyWords = { verified(it) }, clock = { now },
        conversationId = "c1", onDispatched = { notified += it },
    )

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    private fun run(name: String, vararg pairs: Pair<String, String>, lookup: DeskLookup = FakeLookup()) =
        runBlocking { executor(lookup).execute(name, args(*pairs)) }

    private fun JsonObject.str(key: String) = get(key)?.jsonPrimitive?.content

    private fun dispatch(words: String = "帮我订明天的会议室") =
        run(DeskTools.DISPATCH, "words" to words, "note" to "上午十点左右", "title" to "订会议室")

    @Test
    fun dispatchRejectsUnverifiedWords() {
        verified = { false }
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
            DeskToolExecutor(failing, FakeLookup(), ledger, { true }, { now }, "c1", { notified += it })
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
        verified = { false }
        val rejected = buildJsonObject { put("status", "rejected"); put("reason", "words_not_verified") }
        assertEquals(rejected, run(DeskTools.ANSWER, "task_id" to "t1", "words" to "选第二个"))
        assertEquals(rejected, run(DeskTools.ADD, "task_id" to "t1", "words" to "顺便订午饭"))
        assertTrue(port.answered.isEmpty())
        assertTrue(port.steered.isEmpty())

        verified = { true }
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
        assertEquals(refused, run(DeskTools.CANCEL, "task_id" to "t1"))
    }

    @Test
    fun cancelGoesToPort() {
        dispatch()
        assertEquals(buildJsonObject { put("status", "ok") }, run(DeskTools.CANCEL, "task_id" to "t1"))
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
    fun checkTasksListsLedgerWithStates() {
        val states = listOf(
            TaskState.Running(step = 2, note = "tap", narration = "打开「日历」 [10,20] 找日程"),
            TaskState.Held(GateReason.queued("前面还有 1 件")),
            TaskState.WaitingInput("要订哪一间?", listOf("A", "B")),
            TaskState.Held(GateReason("screen_locked", needsYou = true)),
            TaskState.Succeeded("订好了 A 间"),
            TaskState.Failed("会议室都满了", FailKind.GaveUp),
            TaskState.Cancelled,
            TaskState.Running(step = 1, note = "tap"),
        )
        states.forEachIndexed { i, state ->
            dispatch("任务 $i")
            port.states.getValue("t${i + 1}").value = state
        }
        dispatch("排在后面的")
        port.positions["t9"] = 1
        ledger.add(DeskTaskEntry("gone", "没了的", "没了的", now))

        val tasks = run(DeskTools.CHECK).also { assertEquals("ok", it.str("status")) }
            .getValue("tasks").jsonArray.map { it.jsonObject }
        assertEquals(10, tasks.size)
        assertEquals("t1", tasks[0].str("task_id"))
        assertEquals("订会议室", tasks[0].str("title"))
        assertEquals(
            listOf("running", "queued", "needs_you", "needs_you", "done", "failed", "cancelled", "running", "queued", "unknown"),
            tasks.map { it.str("state") },
        )
        assertEquals("打开 找日程", tasks[0].str("line"))
        assertNull(tasks[1].str("line"))
        assertEquals("要订哪一间?", tasks[2].str("line"))
        assertEquals("订好了 A 间", tasks[4].str("line"))
        assertEquals("会议室都满了", tasks[5].str("line"))
        assertNull(tasks[6].str("line"))
        assertNull(tasks[7].str("line"))
    }

    @Test
    fun checkTasksCapsTerminalLine() {
        dispatch()
        port.states.getValue("t1").value = TaskState.Succeeded("字".repeat(DESK_RESULT_MAX_CHARS + 5))
        val line = run(DeskTools.CHECK).getValue("tasks").jsonArray[0].jsonObject.str("line")!!
        assertEquals(DESK_RESULT_MAX_CHARS, line.length)
    }

    @Test
    fun lookupReturnsText() {
        val lookup = FakeLookup(search = "明天晴", memory = "喜欢靠窗")
        assertEquals(buildJsonObject { put("status", "ok"); put("text", "明天晴") }, run(DeskTools.SEARCH, "query" to "明天天气", lookup = lookup))
        assertEquals(buildJsonObject { put("status", "ok"); put("text", "喜欢靠窗") }, run(DeskTools.RECALL, "query" to "座位", lookup = lookup))
        assertEquals(listOf("明天天气", "座位"), lookup.queries)
    }

    @Test
    fun lookupUnavailableSaysSo() {
        val unavailable = buildJsonObject { put("status", "unavailable") }
        assertEquals(unavailable, run(DeskTools.SEARCH, "query" to "明天天气"))
        assertEquals(unavailable, run(DeskTools.RECALL, "query" to "座位"))
    }

    @Test
    fun askOwnerIsNotHandledHere() {
        assertEquals(buildJsonObject { put("status", "unsupported") }, run(DeskTools.ASK_OWNER, "question" to "可以吗"))
        assertEquals(buildJsonObject { put("status", "unsupported") }, run("tap"))
    }

    @Test
    fun toolSetsCarryTheRightTools() {
        fun names(set: DeskToolSet) = DeskTools.specs(set).map { LlmBrain.toolSpec(it).name }.toSet()
        assertEquals(
            setOf(DeskTools.DISPATCH, DeskTools.CHECK, DeskTools.ADD, DeskTools.ANSWER, DeskTools.CANCEL, DeskTools.SEARCH, DeskTools.RECALL),
            names(DeskToolSet.User),
        )
        assertEquals(setOf(DeskTools.CHECK, DeskTools.SEARCH, DeskTools.RECALL, DeskTools.ASK_OWNER), names(DeskToolSet.Phone))
        val dispatchSpec = LlmBrain.toolSpec(DeskTools.specs(DeskToolSet.User).first { LlmBrain.toolSpec(it).name == DeskTools.DISPATCH })
        assertTrue(dispatchSpec.parametersJsonSchema.contains("verbatim"))
        assertFalse(dispatchSpec.description.startsWith("\""))
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
}
