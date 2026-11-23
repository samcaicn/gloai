package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.mcp.FakeRemoteTasks
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunRecordExportTest {
    private val key = "sk-" + "a1B2c3D4e5F6g7H8i9J0k1L2"
    private val runId = "20261102-033700-abc123"
    private val conversation = "mcp-11111111-1111-1111-1111-111111111111"

    @Test fun redactsKeysAndSecretFieldsWithoutScreenshotBodies() {
        val payload = RunRecordExport.of(record(ended = false))
        val text = payload.json.toString() + payload.jsonl
        assertFalse(text, text.contains(key))
        assertFalse(text, text.contains("hunter2"))
        assertFalse(text, text.contains("screens/"))
        assertEquals(false, payload.json["includesScreenshots"]!!.jsonPrimitive.boolean)
        assertEquals("1", payload.json["exportFormat"]!!.jsonPrimitive.content)
        assertEquals("t1", payload.json["task_id"]!!.jsonPrimitive.content)
        assertEquals(conversation, payload.json["conversation_id"]!!.jsonPrimitive.content)
        assertEquals(runId, payload.json["run_id"]!!.jsonPrimitive.content)
        assertEquals("打开设置 ***", payload.json["run"]!!.jsonObject["goal"]!!.jsonPrimitive.content)
        val events = payload.json["events"]!!.jsonArray
        assertEquals("media", events.last().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("1", events.last().jsonObject["mediaId"]!!.jsonPrimitive.content)
        val result = events[1].jsonObject["resultJson"]!!.jsonPrimitive.content
        assertTrue(result, result.contains("\"password\":\"***\""))
        val lines = payload.jsonl.trimEnd().lines()
        assertEquals(1 + events.size, lines.size)
        assertFalse(Json.parseToJsonElement(lines[0]).jsonObject.containsKey("events"))
        assertEquals("task_started", Json.parseToJsonElement(lines[1]).jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun malformedAndNestedJsonTextAreRedactedWithoutDroppingStructure() {
        val nested = """[{"password":"hunter2"},{"deep":{"token":"$key"}}]"""
        val malformed = """{"password":"hunter2","unterminated":"""
        val value = buildJsonObject {
            put("argsJson", nested)
            put("resultJson", malformed)
            putJsonArray("ordinary") { add(buildJsonObject { put("note", key) }) }
        }
        val text = RunRecordExport.redact(value).toString()
        assertFalse(text, text.contains("hunter2"))
        assertFalse(text, text.contains(key))
        assertTrue(text.contains("\\\"deep\\\""))
    }

    @Test fun inProgressExportIsPartialAndAFinishedRunIsNot() {
        assertEquals(true, RunRecordExport.of(record(ended = false)).json["partial"]!!.jsonPrimitive.boolean)
        val finished = RunRecordExport.of(record(ended = true))
        assertEquals(false, finished.json["partial"]!!.jsonPrimitive.boolean)
        // 概要还没刷新,但事件里已经收尾:不算进行中。
        val stale = record(ended = false).let { it.copy(events = it.events + RunEvent.TaskEnded(30, "failed", "stopped", "environment")) }
        assertEquals(false, RunRecordExport.of(stale).json["partial"]!!.jsonPrimitive.boolean)
    }

    @Test fun taskWithNoRunYetIsPartialAndKeepsTheMapping() {
        val payload = RunRecordExport.of(RunExportRecord("t1", conversation, null, null, emptyList()))
        assertEquals(true, payload.json["partial"]!!.jsonPrimitive.boolean)
        assertTrue(payload.json["run_id"] is JsonNull)
        assertTrue(payload.json["run"] is JsonNull)
        assertEquals(0, payload.json["events"]!!.jsonArray.size)
        assertEquals("t1", payload.json["task_id"]!!.jsonPrimitive.content)
    }

    @Test fun unknownIdAndMissingSourceAreClearErrors() {
        val source = object : RunExportSource {
            override fun load(id: String) = RunExportLookup.Missing
        }
        val missing = runCatching { RemoteTaskService(FakeRemoteTasks(), records = source).export("missing") }.exceptionOrNull() as RemoteTaskError
        assertEquals(RemoteTaskError.Code.NOT_FOUND, missing.code)
        assertEquals("No remote task or run record with id missing", missing.message)
        val gone = object : RunExportSource {
            override fun load(id: String) = RunExportLookup.Gone("t1", runId)
        }
        val dropped = runCatching { RemoteTaskService(FakeRemoteTasks(), records = gone).export("t1") }.exceptionOrNull() as RemoteTaskError
        assertTrue(dropped.message.orEmpty(), dropped.message!!.contains(runId) && dropped.message!!.contains("t1"))
        val unavailable = runCatching { RemoteTaskService(FakeRemoteTasks()).export("t1") }.exceptionOrNull() as RemoteTaskError
        assertEquals(RemoteTaskError.Code.UNAVAILABLE, unavailable.code)
    }

    private fun record(ended: Boolean): RunExportRecord {
        val summary = RunSummary(
            id = runId, goal = "打开设置 $key", startedAtMs = 10,
            endedAtMs = if (ended) 30 else null, outcome = if (ended) "succeeded" else null, origin = "mcp",
        )
        val events = listOf(
            RunEvent.TaskStarted(10, "打开设置 $key", "main", "mcp"),
            RunEvent.ToolResult(20, 1, "phone_type_text", """{"password":"hunter2","note":"$key"}""", null, 7),
            RunEvent.Media(21, 1, 1, "设置"),
        ) + if (ended) listOf(RunEvent.TaskEnded(30, "succeeded", "好了")) else emptyList()
        return RunExportRecord("t1", conversation, runId, summary, events)
    }
}
