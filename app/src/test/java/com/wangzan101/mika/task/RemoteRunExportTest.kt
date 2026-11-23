package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSummary
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.remote.RunExportLookup
import com.wangzan101.mika.core.remote.RunRecordExport
import com.wangzan101.mika.record.RunStore
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RemoteRunExportTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = "sk-" + "a1B2c3D4e5F6g7H8i9J0k1L2"
    private val runId = "20261102-033700-abc123"
    private val conversation = "mcp-11111111-1111-1111-1111-111111111111"
    private val marker = "SCREENBYTESzzzzNOTJSON"

    private val index = object : RemoteRunIndex {
        val tasks = LinkedHashMap<String, RemoteTask>()
        val runs = LinkedHashMap<String, String>()
        override fun task(id: String) = tasks[id]
        override fun rememberedRunId(taskId: String) = runs[taskId]
        override fun taskForRun(runId: String) = runs.entries.firstOrNull { it.value == runId }?.key?.let { tasks[it] }
    }

    private val export by lazy {
        val files = tmp.root.resolve("files")
        RemoteRunExport(index) { RunStore(files, { null }, 30, { 1L }, { it }) }
    }

    @Test fun taskMapsToItsRunAndScreenshotBytesStayOnDisk() {
        writeRun(ended = false, origin = "mcp")
        File(tmp.root, "files/runs/$runId/screens").mkdirs()
        File(tmp.root, "files/runs/$runId/screens/0001.jpg").writeBytes(marker.toByteArray())
        val task = RemoteTask("t1", conversation, "打开设置", 10L, TaskState.Running(1, "working"))
        index.tasks["t1"] = task
        index.runs["t1"] = runId
        val byTask = found(export.load("t1"))
        val byRun = found(export.load(runId))
        assertEquals(byTask.runId, byRun.runId)
        val payload = RunRecordExport.of(byTask)
        val text = payload.json.toString()
        assertEquals(true, payload.json["partial"]!!.jsonPrimitive.boolean)
        assertEquals("t1", payload.json["task_id"]!!.jsonPrimitive.content)
        assertEquals(conversation, payload.json["conversation_id"]!!.jsonPrimitive.content)
        assertEquals(runId, payload.json["run_id"]!!.jsonPrimitive.content)
        assertEquals(false, payload.json["includesScreenshots"]!!.jsonPrimitive.boolean)
        assertFalse(text, text.contains(key))
        assertFalse(text, text.contains(marker))
        assertTrue(File(tmp.root, "files/runs/$runId/screens/0001.jpg").isFile)
    }

    @Test fun unknownPhoneLocalAndUnindexedRemoteRunsAreRejected() {
        assertTrue(export.load("missing") is RunExportLookup.Missing)
        writeRun(ended = true, origin = null, id = "20261102-033701-phone1")
        assertTrue(export.load("20261102-033701-phone1") is RunExportLookup.Missing)
        writeRun(ended = false, origin = "mcp", id = "20261102-033702-remote")
        assertTrue(export.load("20261102-033702-remote") is RunExportLookup.Missing)
    }

    @Test fun taskWithoutARunIsPartialAndAPrunedRunSaysItIsGone() {
        index.tasks["t1"] = RemoteTask("t1", conversation, "打开设置", 10L, null)
        val waiting = found(export.load("t1"))
        assertEquals(null, waiting.runId)
        assertEquals(true, RunRecordExport.of(waiting).json["partial"]!!.jsonPrimitive.boolean)
        index.runs["t1"] = runId
        val gone = export.load("t1")
        assertEquals(RunExportLookup.Gone("t1", runId), gone)
    }

    private fun found(lookup: RunExportLookup) = (lookup as RunExportLookup.Found).record

    private fun writeRun(ended: Boolean, origin: String?, id: String = runId) {
        val dir = tmp.root.resolve("files/runs/$id").also { it.mkdirs() }
        val summary = RunSummary(
            id = id, goal = "打开设置 $key", startedAtMs = 10,
            endedAtMs = if (ended) 30 else null, outcome = if (ended) "succeeded" else null, origin = origin,
        )
        dir.resolve(RunStore.RUN_JSON).writeText(RunEvent.json.encodeToString(RunSummary.serializer(), summary))
        val events = buildString {
            appendLine(RunEvent.encode(RunEvent.TaskStarted(10, "打开设置 $key", "main", origin)))
            appendLine(RunEvent.encode(RunEvent.Observed(11, 1, "screen $key")))
            if (ended) appendLine(RunEvent.encode(RunEvent.TaskEnded(30, "succeeded", "好了")))
        }
        dir.resolve(RunStore.EVENTS).writeText(events)
    }
}
