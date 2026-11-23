package com.wangzan101.mika.record

import com.wangzan101.mika.core.run.record.RunEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RunStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_760_000_000_000L
    private fun store(keep: Int = 30, external: File? = null, encode: (ByteArray) -> ByteArray? = { png -> png + byteArrayOf(9) }) =
        RunStore(tmp.root.resolve("files"), { external }, keep, { clock }, encode)

    @Test fun exportsUsageAndCompactionFromExistingRunInRecordedOrder() {
        val id = "20250102-030405-abc123"
        val dir = tmp.root.resolve("files/runs/$id").also { it.mkdirs() }
        val events = dir.resolve("events.jsonl")
        val log = buildString {
            appendLine("""{"type":"task_started","atMs":1,"goal":"private goal","model":"main"}""")
            for (step in 1..78) {
                if (step == 42) {
                    appendLine("""{"type":"compressed","atMs":420,"foldedSteps":16}""")
                    appendLine("""{"type":"model_reply","atMs":421,"step":42,"role":"light_rejected","text":"private reply","inputTokens":null,"cachedTokens":null}""")
                }
                appendLine("""{"type":"model_reply","atMs":${step * 10 + 2},"step":$step,"inputTokens":200,"cachedTokens":${if (step == 1) 0 else 128},"outputTokens":4,"argsJson":"private arguments"}""")
            }
            appendLine("""{"type":"tool_result","atMs":783,"step":78,"resultJson":"private content","screenshotId":1}""")
            appendLine("""{"type":"compressed","atMs":784,"foldedSteps":20}""")
            appendLine("""{"type":"task_ended","atMs":785,"outcome":"cancelled","answerOrReason":"private answer"}""")
        }
        events.writeText(log)

        val exported = store().usageLog(id)
        val rows = exported.lineSequence().filter { it.isNotEmpty() }.map { Json.parseToJsonElement(it).jsonObject }.toList()
        val replies = rows.filter { it["type"]?.jsonPrimitive?.content == "model_reply" }
        assertEquals((1..42).toList() + (42..78).toList(), replies.map { it.getValue("step").jsonPrimitive.content.toInt() })
        assertEquals("0", replies.first().getValue("cachedTokens").jsonPrimitive.content)
        assertEquals(JsonNull, replies[41]["inputTokens"])
        assertEquals(JsonNull, replies[41]["cachedTokens"])
        assertEquals("light_rejected", replies[41].getValue("role").jsonPrimitive.content)
        assertEquals("128", replies.last().getValue("cachedTokens").jsonPrimitive.content)
        assertEquals("200", replies.last().getValue("inputTokens").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""{"type":"compressed","atMs":420,"foldedSteps":16}"""), rows[42])
        assertEquals(Json.parseToJsonElement("""{"type":"compressed","atMs":784,"foldedSteps":20}"""), rows[rows.lastIndex - 1])
        assertFalse(exported.contains("private"))
        assertFalse(exported.contains("screenshotId"))
        assertEquals(log, events.readText())
    }

    @Test fun usageLogMarksADamagedLineAndPreservesMissingCounters() {
        val s = store()
        val id = s.open("g").also { it.close() }.runId
        tmp.root.resolve("files/runs/$id/events.jsonl").writeText("""
            {"type":"model_reply","atMs":1,"step":1,"inputTokens":50}
            {"type":"tool_result","private":
            {"type":"compressed","atMs":3,"foldedSteps":1}
        """.trimIndent())
        val rows = s.usageLog(id).trimEnd().lines().map { Json.parseToJsonElement(it).jsonObject }
        assertFalse(rows[0].containsKey("cachedTokens"))
        assertEquals(Json.parseToJsonElement("""{"type":"unreadable_event","line":2}"""), rows[1])
        assertEquals("compressed", rows[2].getValue("type").jsonPrimitive.content)
    }

    @Test fun writesRunJsonEventsAndScreens() {
        val s = store()
        val h = s.open("查天气")
        h.event(RunEvent.TaskStarted(clock, "查天气", "m1"))
        h.event(RunEvent.Criteria(clock, listOf("看到今天的气温"), emptyList()))
        h.event(RunEvent.ModelReply(clock, 1, "看一下", "phone_observe", "{}", 100, 10, 50))
        h.event(RunEvent.ToolResult(clock, 1, "phone_observe", "{\"ok\":true}", null, 1))
        h.media(1, byteArrayOf(1, 2))
        h.event(RunEvent.Media(clock, 1, 1, "com.x"))
        h.event(RunEvent.ModelReply(clock, 2, "", "finish", "{}", 200, 20, null))
        h.event(RunEvent.Verifier(clock, "pass", "{}"))
        clock += 5_000
        h.event(RunEvent.TaskEnded(clock, "succeeded", "25 度"))
        h.close()

        val dir = tmp.root.resolve("files/runs/${h.runId}")
        assertTrue(h.runId, Regex("""\d{8}-\d{6}-[a-z0-9]{6}""").matches(h.runId))
        assertTrue(dir.resolve("run.json").isFile)
        assertEquals(8, dir.resolve("events.jsonl").readLines().size)
        assertArrayEquals(byteArrayOf(1, 2, 9), dir.resolve("screens/0001.jpg").readBytes())
        assertEquals(dir.resolve("screens/0001.jpg"), s.screen(h.runId, 1))
        assertNull(s.screen(h.runId, 2))

        val sum = s.list().single()
        assertEquals(h.runId, sum.id)
        assertEquals("查天气", sum.goal)
        assertEquals("m1", sum.model)
        assertEquals("succeeded", sum.outcome)
        assertEquals("25 度", sum.answerOrReason)
        assertEquals(listOf("看到今天的气温"), sum.criteria)
        assertEquals("pass", sum.verdict)
        assertEquals(2, sum.steps)
        assertEquals(300, sum.inputTokens); assertEquals(30, sum.outputTokens); assertEquals(50, sum.cachedTokens)
        assertEquals(sum.startedAtMs + 5_000, sum.endedAtMs)
    }

    // 跑起来过的运行关上时记一份小结;交出回答后判的结局补进去;记录清掉了小结还在
    @Test fun closingWritesMetricsThatOutliveTheRecord() {
        val s = store(keep = 1)
        val h = s.open("查天气")
        h.event(RunEvent.TaskStarted(clock, "查天气", "m1"))
        h.event(RunEvent.ModelReply(clock + 2_000, 1, "", null, null, 100, 10, null, 1_500, repliedAtMs = clock + 2_000))
        h.event(RunEvent.TaskEnded(clock + 3_000, "succeeded", "晴"))
        h.close()
        val m = s.metrics.all().single()
        assertEquals(h.runId, m.runId)
        assertEquals(3_000L, m.totalMs)
        assertEquals(2_000L, m.firstFeedbackMs)
        s.append(h.runId, RunEvent.OutcomeJudged(clock + 4_000, "partial"))
        assertEquals("partial", s.metrics.all().single().judged)

        clock += 1_000
        s.open("别的").close()
        assertTrue(s.list().none { it.id == h.runId })
        assertEquals(listOf(h.runId), s.metrics.all().map { it.runId })
    }

    @Test fun runThatNeverStartedWritesNoMetrics() {
        val s = store()
        s.open("没跑起来").close()
        assertTrue(s.metrics.all().isEmpty())
    }

    @Test fun keepsOnlyLatest30() {
        val s = store()
        val ids = (1..35).map {
            clock += 1_000
            s.open("任务 $it").also { h -> h.close() }.runId
        }
        val left = s.list().map { it.id }
        assertEquals(30, left.size)
        assertEquals(ids.drop(5).reversed(), left) // 新的在前
        ids.take(5).forEach { assertFalse(tmp.root.resolve("files/runs/$it").exists()) }
    }

    @Test fun eventsRoundTrip() {
        val s = store()
        val h = s.open("g")
        val written = listOf(
            RunEvent.TaskStarted(1, "g", "m"),
            RunEvent.ToolResult(2, 1, "phone_tap", "{\"ok\":true,\"note\":\"换行\\n也行\"}", true, null),
            RunEvent.Takeover(3, "login", "handed_back", 1200, 4),
            RunEvent.TaskEnded(4, "failed", "没办成"),
        )
        written.forEach(h::event)
        h.close()
        assertEquals(written, s.events(h.runId))
        assertEquals(emptyList<RunEvent>(), s.events("no-such-run"))
    }

    @Test fun mirrorCopiesRunToExternalDir() {
        val ext = tmp.newFolder("ext")
        val s = store(external = ext)
        val h = s.open("g")
        h.event(RunEvent.TaskStarted(1, "g", "m"))
        h.media(1, byteArrayOf(5))
        h.close()
        s.mirrorToExternal(h.runId)
        assertTrue(ext.resolve("runs/${h.runId}/run.json").isFile)
        assertTrue(ext.resolve("runs/${h.runId}/events.jsonl").isFile)
        assertTrue(ext.resolve("runs/${h.runId}/screens/0001.jpg").isFile)
    }

    @Test fun goalInRunJsonIsRedacted() {
        val key = "sk-" + "abcdefghijklmnopqrstuvwx"
        val s = store()
        s.open("用 $key 调接口").close()
        val goal = s.list().single().goal
        assertFalse(goal.contains(key))
        assertTrue(goal.contains("***"))
    }

    @Test fun slowEncoderNeverBlocksCallerAndDropsOldestFrames() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val s = store(encode = { png -> entered.countDown(); release.await(5, TimeUnit.SECONDS); png })
        val h = s.open("g")
        val t0 = System.nanoTime()
        h.media(1, byteArrayOf(1))
        assertTrue(entered.await(5, TimeUnit.SECONDS)) // 写线程卡在第 1 张上
        for (id in 2..20) h.media(id, byteArrayOf(id.toByte()))
        repeat(50) { h.event(RunEvent.State(clock, "x", "$it")) }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        assertTrue("caller blocked for $elapsedMs ms", elapsedMs < 1_000)
        release.countDown()
        h.close()
        val saved = (1..20).filter { s.screen(h.runId, it) != null }
        // 卡住的那张 + 积压里最新的几张
        assertEquals(listOf(1) + (21 - RunHandle.MAX_PENDING_FRAMES..20).toList(), saved)
        assertEquals(50, s.events(h.runId).size)
    }

    @Test fun runJsonRefreshedBeforeClose() {
        val s = store()
        val h = s.open("g")
        for (step in 1..RunHandle.SUMMARY_EVERY) h.event(RunEvent.ModelReply(clock, step, "", "phone_observe", "{}", 10, 1, null))
        val deadline = System.currentTimeMillis() + 5_000
        while (s.list().single().steps < RunHandle.SUMMARY_EVERY && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(RunHandle.SUMMARY_EVERY, s.list().single().steps)
        h.close()
    }

    @Test fun externalMirrorKeepsOnlyLatest() {
        val ext = tmp.newFolder("ext")
        val s = store(keep = 2, external = ext)
        val ids = (1..3).map {
            clock += 1_000
            s.open("g$it").also { h -> h.close(); s.mirrorToExternal(h.runId) }.runId
        }
        assertEquals(ids.drop(1), ext.resolve("runs").list()!!.sorted())
    }
}
