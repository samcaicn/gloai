package com.wangzan101.mika.task

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.DeskTurn
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ResponsesModel
import com.wangzan101.mika.cap.phone.a11y.LazyPhoneScreen
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.run.trace.TraceStore
import com.wangzan101.mika.record.RunStore
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhoneHistoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun runs() = RunStore(tmp.root, { null }, 30, { 1_780_000_000_000L }, { it })
    private fun conversations() = ConversationStore(tmp.root.resolve("conversations"))
    private fun traces() = TraceStore(tmp.root.resolve("traces"))
    private fun history() = PhoneHistory(conversations(), runs(), traces(), { true })

    @Test fun savedVerificationUsesTheRecordedVerdictIncludingAfterReopen() {
        val store = conversations()
        store.append("phone", Turn("old", "answer", "succeeded"))
        store.append("phone", Turn("checked", "answer", "succeeded", verified = true))
        store.append("phone", Turn("failed check", "answer", "unverified", verified = false))
        val turns = history().turns("phone", 0, 20)!!.getValue("turns").jsonArray
        assertEquals(listOf(null, true, false), turns.map { it.jsonObject["verified"]?.jsonPrimitive?.boolean })
    }

    @Test fun deskRepliesAndReportsReachTheRemoteHistoryAsDone() {
        // 前台回的话、前台的汇报都是办完了的回话;汇报那一轮没有用户的话;前台没回出来的一轮报没办成,原因是记下的 note
        val store = conversations()
        store.append("chat", Turn("帮我查下天气", "好,我让后台去查", DeskTurn.STATUS, runId = "desk:1"))
        store.append("chat", Turn("", "天气查好了", DeskTurn.REPORT_STATUS, runId = "desk:2"))
        store.append("chat", Turn("看看这个文件", "", DeskTurn.STATUS, note = "files_not_ready", runId = "desk:3"))
        val turns = history().turns("chat", 0, 20)!!.getValue("turns").jsonArray.map { it.jsonObject }
        assertEquals(listOf("done", "done", "failed"), turns.map { it.getValue("status").jsonPrimitive.content })
        assertEquals(listOf("帮我查下天气", "", "看看这个文件"), turns.map { it.getValue("goal").jsonPrimitive.content })
        assertEquals(listOf(null, null, "files_not_ready"), turns.map { it["reason"]?.jsonPrimitive?.content })
    }

    @Test fun queuedCancellationsKeepSeparateRemoteIdsWithoutRunRecords() {
        val store = conversations()
        for (id in listOf("one", "two")) store.append("chat", Turn("repeat", "cancelled", "cancelled", remoteTaskId = id))
        // The second task can still be present in the task center while its cancellation is published.
        val reader = PhoneHistory(conversations(), runs(), traces(), { true }) {
            listOf(RemoteTask("two", "chat", "repeat", 0, TaskState.Cancelled))
        }
        val turns = reader.turns("chat", 0, 20)!!.getValue("turns").jsonArray
        assertEquals(listOf("one", "two"), turns.map { it.jsonObject.getValue("source_task_id").jsonPrimitive.content })
        assertTrue(turns.none { "run_id" in it.jsonObject })
    }

    @Test fun archivedConversationsRemainInPagedHistoryAndCanBeRead() {
        val store = conversations()
        store.append("archived", Turn("old question", "old answer", "succeeded"))
        store.append("active", Turn("new question", "new answer", "succeeded"))
        assertTrue(tmp.root.resolve("conversations/archived.json").setLastModified(1_000L))
        assertTrue(tmp.root.resolve("conversations/active.json").setLastModified(2_000L))
        assertTrue(store.setArchived("archived", true, 3_000L))
        val reader = PhoneHistory(store, runs(), traces(), { true })
        val first = reader.conversations(0, 1)
        val active = first.getValue("conversations").jsonArray.single().jsonObject
        assertEquals("active", active.getValue("id").jsonPrimitive.content)
        assertEquals(1, first.getValue("next_offset").jsonPrimitive.int)
        assertFalse(active.getValue("archived").jsonPrimitive.boolean)
        val second = reader.conversations(1, 1)
        val archived = second.getValue("conversations").jsonArray.single().jsonObject
        assertEquals("archived", archived.getValue("id").jsonPrimitive.content)
        assertTrue(archived.getValue("archived").jsonPrimitive.boolean)
        assertEquals(JsonNull, second.getValue("next_offset"))
        assertEquals("old answer", reader.turns("archived", 0, 20)!!.getValue("turns").jsonArray.single().jsonObject.getValue("answer").jsonPrimitive.content)
        assertTrue(store.setArchived("archived", false))
        assertFalse(reader.conversations(1, 1).getValue("conversations").jsonArray.single().jsonObject.getValue("archived").jsonPrimitive.boolean)
    }

    @Test fun historyListReadsOnlyThePageAndReusesUnchangedConversationBodies() {
        val dir = tmp.newFolder("old-chats")
        val writer = ConversationStore(dir)
        repeat(65) { i ->
            writer.append("c$i", Turn("first $i", "answer", "succeeded", runId = "r$i"))
            assertTrue(dir.resolve("c$i.json").setLastModified(1_000L * (i + 1)))
        }
        val reads = mutableListOf<String>()
        val store = ConversationStore(dir) { file -> reads += file.nameWithoutExtension; file.readText() }
        val reader = PhoneHistory(store, runs(), traces(), { true })
        fun page(offset: Int) = reader.conversations(offset, 20).getValue("conversations").jsonArray
        assertEquals("c64", page(0).first().jsonObject.getValue("id").jsonPrimitive.content)
        assertTrue("cold page read $reads", reads.size <= 21 && reads.all { it.removePrefix("c").toInt() in 44..64 })
        reads.clear()
        assertEquals(20, page(0).size)
        assertTrue(reads.isEmpty())
        assertEquals("c44", page(20).first().jsonObject.getValue("id").jsonPrimitive.content)
        assertTrue("next page read $reads", reads.size <= 21 && reads.all { it.removePrefix("c").toInt() in 24..44 })
        store.updateTurn("c64", "r64") { it.copy(userText = "updated") }
        assertEquals("updated", page(0).first().jsonObject.getValue("title").jsonPrimitive.content)
        store.delete("c64")
        store.append("c64", Turn("new first message", "answer", "succeeded"))
        assertEquals("new first message", page(0).first().jsonObject.getValue("title").jsonPrimitive.content)
    }

    @Test fun recordedModelAttemptAndPagedBodyReachHistoryWithoutInventingUsage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Recorded answer"}]}],"usage":{"input_tokens":1000,"input_tokens_details":{"cached_tokens":0},"output_tokens":2}}"""))
            server.start()
            val model = ResponsesModel(server.url("/v1").toString(), BearerToken.fixed("private-test-header"),
                "test-model", Capabilities(true, false, 128000, false), OkHttpClient())
            val record = runs().open("Read the saved source")
            val store = traces()
            val trace = store.start(record.runId)!!
            try {
                val screen = LazyPhoneScreen(100, 100, { emptyList() }) { error("This read-only fixture must not open a phone app") }
                val result = AgentLoop(model, toolDispatcher(screen), "Base", maxSteps = 3,
                    reviewFinalAnswer = false, trace = trace, sink = record).run("Read the saved source")
                assertTrue(result is TaskState.Succeeded)
            } finally { store.finish(trace); record.close() }
            conversations().append("phone", Turn("Read the saved source", "Recorded answer", "unverified", runId = record.runId))
            val reader = PhoneHistory(conversations(), runs(), store, { true })
            val row = reader.run("phone", record.runId, 0, 20)!!.getValue("rows").jsonArray.first().jsonObject
            assertEquals(1000, row.getValue("input").jsonPrimitive.int)
            assertEquals(0, row.getValue("cached").jsonPrimitive.int)
            val span = row.getValue("id").jsonPrimitive.content
            val text = StringBuilder()
            var offset = 0
            do {
                val page = reader.body("phone", record.runId, span, offset, 3000)!!
                text.append(page.getValue("text").jsonPrimitive.content)
                offset = page.getValue("next_offset").jsonPrimitive.intOrNull ?: -1
            } while (offset >= 0)
            assertTrue(text.contains("Read the saved source"))
            assertTrue(text.contains("Recorded answer"))
            assertFalse(text.contains("private-test-header"))
        }
    }

    @Test fun nestedAndMalformedEventContentUsesExportRedaction() {
        val secret = "sk-a1B2c3D4e5F6g7H8i9J0k1L2"
        val run = runs().open("nested")
        run.event(RunEvent.TaskStarted(1, "nested", "main"))
        run.event(RunEvent.ToolResult(2, 1, "tool", """[{"password":"hunter2"},{"note":"$secret"}]""", null, 1))
        run.event(RunEvent.ToolResult(3, 2, "tool", """{"password":"hunter2","broken":""", null, 1))
        run.close()
        conversations().append("phone", Turn("nested", "done", "unverified", runId = run.runId))
        val events = history().run("phone", run.runId, 0, 20)!!.getValue("events").jsonArray.toString()
        assertFalse(events.contains("hunter2"))
        assertFalse(events.contains(secret))
        assertTrue(events.contains("password"))
    }

    @Test fun readsPhoneOnlyConversationsAfterReopeningWithOlderTurnsPaged() {
        val store = conversations()
        repeat(24) { store.append("phone-conversation", Turn("question $it", "answer $it", "unverified")) }
        store.setTitle("phone-conversation", "A phone conversation")
        val reader = history()
        assertEquals("phone-conversation", reader.conversations(0, 20).getValue("conversations").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        val recent = reader.turns("phone-conversation", 0, 20)!!
        assertEquals("question 4", recent.getValue("turns").jsonArray.first().jsonObject.getValue("goal").jsonPrimitive.content)
        val older = reader.turns("phone-conversation", 20, 20)!!
        assertEquals(4, older.getValue("turns").jsonArray.size)
        assertEquals(JsonNull, older["next_offset"])
        val first = older.getValue("turns").jsonArray.first().jsonObject
        assertTrue(first.getValue("read_only").jsonPrimitive.boolean)
        assertNull(first["verified"])
        assertNull(reader.turns("missing", 0, 20))
    }

    @Test fun runMustBelongToTheRequestedConversationAndSurvivesRemoteIndexLoss() {
        val run = runs().open("a task")
        run.event(RunEvent.TaskStarted(1, "a task", "model"))
        run.event(RunEvent.ModelReply(2, 1, "done", null, null, 100, 2, null))
        run.close()
        conversations().append("phone", Turn("a task", "done", "unverified", runId = run.runId))
        conversations().append("other", Turn("different", "done", "unverified"))
        val reader = history()
        val data = reader.run("phone", run.runId, 0, 1)!!
        assertTrue(data.getValue("record_available").jsonPrimitive.boolean)
        assertFalse(data.getValue("trace_available").jsonPrimitive.boolean)
        assertEquals(1, data.getValue("next_offset").jsonPrimitive.int)
        assertEquals("task_started", data.getValue("events").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
        assertNull(reader.run("other", run.runId, 0, 20))
        assertNull(reader.body("other", run.runId, "0123456789abcdef", 0, 3000))
        conversations().delete("phone")
        assertNull(reader.run("phone", run.runId, 0, 20))
    }

    @Test fun livePhoneRunIsVisibleBeforeItsFirstTurnIsSavedAndTraceFinishes() {
        val traceStore = traces()
        val trace = traceStore.start("live-run")!!
        val task = RemoteTask("live", "phone", "first turn", 0, TaskState.Running(1, "reading"), runId = "live-run")
        val reader = PhoneHistory(conversations(), runs(), traceStore, { true }, { listOf(task) })
        assertEquals(1, reader.conversations(0, 20).getValue("conversations").jsonArray.size)
        val turns = reader.turns("phone", 0, 20)!!.getValue("turns").jsonArray
        assertEquals("live-run", turns.single().jsonObject.getValue("run_id").jsonPrimitive.content)
        assertTrue(reader.run("phone", "live-run", 0, 20)!!.getValue("recording").jsonPrimitive.boolean)
        traceStore.finish(trace)
        assertFalse(reader.run("phone", "live-run", 0, 20)!!.getValue("recording").jsonPrimitive.boolean)
        conversations().append("phone", Turn("first turn", "done", "unverified", runId = "live-run"))
        assertEquals(1, reader.turns("phone", 0, 20)!!.getValue("turns").jsonArray.size)
    }

    @Test fun missingArchivesAndLongTextAreReportedExplicitly() {
        conversations().append("phone", Turn("long".repeat(3000), "done", "unverified", runId = "old-run"))
        val reader = history()
        val turn = reader.turns("phone", 0, 20)!!.getValue("turns").jsonArray.single().jsonObject
        assertTrue(turn.getValue("text_truncated").jsonPrimitive.boolean)
        assertEquals(8000, turn.getValue("goal").jsonPrimitive.content.length)
        val record = reader.run("phone", "old-run", 0, 20)!!
        assertFalse(record.getValue("record_available").jsonPrimitive.boolean)
        assertFalse(record.getValue("trace_available").jsonPrimitive.boolean)
    }

    // 通知提醒那一轮列的是别的 App 的通知:经电脑端读历史时不给出这些字
    @Test fun noticeTurnTextIsNotExported() {
        val store = conversations()
        store.append("talk", Turn("", "1. 微信 张三:明天来吗", "succeeded", runId = "notice:n1"))
        store.append("talk", Turn("帮我回个好的", "已回复", "succeeded"))
        val turns = history().turns("talk", 0, 20)!!.getValue("turns").jsonArray.map { it.jsonObject }
        assertEquals(listOf("", "已回复"), turns.map { it.getValue("answer").jsonPrimitive.content })
        assertEquals(listOf("", "帮我回个好的"), turns.map { it.getValue("goal").jsonPrimitive.content })
        assertFalse(turns.toString().contains("张三"))
    }
}
