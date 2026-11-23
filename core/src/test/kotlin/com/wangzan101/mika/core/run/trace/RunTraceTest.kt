package com.wangzan101.mika.core.run.trace

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import io.opentelemetry.proto.trace.v1.Span
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class RunTraceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun realLoopPreservesWireRequestsAndRecordsRetriesToolsAndPortableUsage() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val model = ResponsesModel(server.url("/v1").toString(), BearerToken.fixed("dummy-auth-for-test"),
                "trace-test", Capabilities(true, false, 4096, false), OkHttpClient())
            val store = TraceStore(temp.newFolder())
            val goal = "列出应用 PRIVATE_GOAL"
            suspend fun replay(trace: RunTrace?): Pair<List<String>, List<String>> {
                fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)
                server.enqueue(json("""{"status":"completed","output":[{"type":"function_call","call_id":"call_apps","name":"phone_list_apps","arguments":"{}"}],"usage":{"input_tokens":3800,"output_tokens":4}}"""))
                server.enqueue(json("""{"error":{"param":"include","message":"unsupported"}}""").setResponseCode(400))
                server.enqueue(json("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"好了"}]}],"usage":{"input_tokens":1100,"input_tokens_details":{"cached_tokens":0},"output_tokens":2}}"""))
                val tools = mutableListOf<String>()
                val screen = object : ScreenPort by FakeScreen() {
                    override suspend fun listApps() = listOf("com.example" to ("very long application label ".repeat(5000)))
                }
                val monitor = object : RunSink {
                    override fun event(e: RunEvent) { if (e is RunEvent.ToolResult) tools += e.tool }
                }
                val end = AgentLoop(model, toolDispatcher(screen), "BASE", maxSteps = 2, sink = monitor, trace = trace,
                    clock = { 1_780_000_000_000L }).run(goal)
                assertTrue(end is TaskState.Succeeded)
                return (1..3).map { server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8() } to tools
            }
            val off = replay(null)
            val trace = store.start("test")!!
            val on = replay(trace)
            store.finish(trace)
            assertEquals(off, on)
            assertEquals(listOf("phone_list_apps"), on.second)
            val archive = store.archive("test")!!
            val rows = archive.rows()
            val attempts = rows.filter { it.attempt != null }.sortedWith(compareBy({ it.step }, { it.attempt }))
            assertEquals(listOf(1L, 2L, 2L), attempts.map { it.step })
            assertEquals(listOf("ok", "error", "ok"), attempts.map { it.status })
            assertEquals(listOf(null, null, 0L), attempts.map { it.cached })
            assertEquals(1100L, attempts.last().uncached)
            assertTrue(attempts[1].contextChange.orEmpty().contains("history_rewritten"))
            assertNull(attempts.last().contextChange)
            assertTrue(rows.any { it.name == "execute_tool phone_list_apps" })
            assertTrue(archive.bodyPage(attempts.first().id, limit = 65536)!!.text.contains("PRIVATE_GOAL"))
            assertTrue(archive.bodyPage(attempts.last().id, limit = 65536)!!.text.contains("call_apps"))
            assertFalse(archive.bodyPage(attempts.first().id, limit = 65536)!!.text.contains("dummy-auth-for-test"))
            assertEquals("removed include", attempts.last().difference)
            assertEquals("call_apps", rows.single { it.name == "execute_tool phone_list_apps" }.toolCallId)
            val binary = ByteArrayOutputStream().also { archive.save(it, false) }.toByteArray()
            val packet = ExportTraceServiceRequest.parseFrom(binary)
            val spans = packet.getResourceSpans(0).getScopeSpans(0).spansList
            assertEquals(5, spans.size)
            assertEquals(16, spans.first().traceId.size())
            assertTrue(spans.drop(1).all { it.traceId == spans.first().traceId && it.parentSpanId == spans.first().spanId })
            assertFalse(packet.toString().contains("PRIVATE_GOAL"))
            assertTrue(packet.toString().contains("gen_ai.usage.cache_read.input_tokens"))

            val zip = ByteArrayOutputStream().also { archive.save(it, true) }.toByteArray()
            ZipInputStream(zip.inputStream()).use { stream ->
                assertEquals("trace.pb", stream.nextEntry.name)
                assertArrayEquals(binary, stream.readBytes())
                assertTrue(stream.nextEntry.name.startsWith("requests/"))
                assertTrue(stream.readBytes().decodeToString().contains("PRIVATE_GOAL"))
            }
        }
    }

    @Test fun privatePayloadMasksSecretsAndOpaqueStateAndFingerprintsTheActualImage() {
        val img = Part.Image("aW1hZ2VkYXRh", width = 20, height = 30)
        val messages = listOf(Message(Role.User, listOf(img), Author.User))
        val body = Json.parseToJsonElement("""{"instructions":"go","input":[
            {"type":"reasoning","encrypted_content":"private_opaque"},
            {"type":"function_call","name":"phone_type_text","call_id":"c","arguments":"{\"text\":\"private_typed\"}"},
            {"type":"function_call_output","call_id":"c","output":"{\"password\":\"private_secret\",\"url\":\"https://bob:private_user@local/path?token=private_token\"}"},
            {"type":"message","content":[{"type":"input_image","image_url":"data:image/png;base64,aW1hZ2VkYXRh"}]}]}""").jsonObject
        val blocks = TracePayload.blocks(body, messages, { Verb.TypeText }, InferenceProtocol.Responses)
        val display = blocks.joinToString { it.text }
        for (secret in listOf("private_opaque","private_typed","private_secret","private_token","private_user","aW1hZ2VkYXRh"))
            assertFalse(secret, display.contains(secret))
        assertTrue(display.contains("sha256") && display.contains("\"width\":20") && display.contains("\"height\":30"))
    }

    @Test fun appendRewriteAndStopWhileARequestIsInFlight() {
        val store = TraceStore(temp.newFolder())
        val model = ResponsesModel("http://localhost", BearerToken.fixed(null), "model", Capabilities(true,false,1000,false), OkHttpClient())
        val trace = store.start("running")!!
        val completed = mutableListOf<String>()
        fun request(step: Int, input: String) = TraceCall(trace, step, model, { null }, null)
            .begin(Json.parseToJsonElement(input).jsonObject, emptyList())!!
        for ((n, json) in listOf(
            """{"input":["first"]}""", """{"input":["first","new"]}""", """{"input":["rewritten","new"]}"""
        ).withIndex()) {
            val span = request(n + 1, json)
            completed += span.id
            trace.end(span, "ok", 100, 20, 5)
        }
        val pending = request(4, """{"input":["rewritten","new"]}""")
        val files = store.archive("running")!!
        assertEquals(listOf("first", "append input[1]", "input[0]", "same"), files.rows().drop(1).map { it.difference })
        val body = generateSequence(0) { it + 1 }.map { files.bodyPage(completed[2], it * 8, 8)!! }
        val full = buildString { for (chunk in body) { append(chunk.text); if (!chunk.hasMore) break } }
        assertTrue(full.contains("rewritten") && full.contains("new"))
        trace.toolStarted(4, "phone_observe", "call_wait", System.currentTimeMillis())
        assertTrue("still writing while the run is live", store.recording("running"))
        store.stopAll()
        assertFalse("stopped runs are final", store.recording("running"))
        trace.end(pending, "ok", 300, 250, 4)
        assertEquals("partial", files.rows().first().status)
        assertEquals("partial", files.rows().last().status)
        assertEquals("call_wait", files.rows().last().toolCallId)
        assertNull(files.rows().single { it.id == pending.id }.cached)
    }

    @Test fun stopAndQuotaLeaveReadablePartialRunsAndLateCallbacksCannotResumeCapture() {
        val root = temp.newFolder()
        val store = TraceStore(root, byteLimit = 100)
        val trace = store.start("limited")!!
        val model = ResponsesModel("http://localhost", BearerToken.fixed(null), "model", Capabilities(true,false,1000,false), OkHttpClient())
        val call = TraceCall(trace, 1, model, { null }, null)
        val attempt = call.begin(buildJsonObject { put("instructions", "long".repeat(100)) }, emptyList())
        trace.end(attempt, "ok", 80, 20, 2)
        assertTrue(store.recording("limited"))
        store.finish(trace)
        assertFalse(store.recording("limited"))
        val limited = store.archive("limited")!!.rows()
        assertEquals("partial", limited.first().status)
        assertEquals("truncated", limited.last().status)
        assertTrue(store.archive("limited")!!.bodyPage(limited.last().id)!!.text.contains("instructions\n\n[truncated]"))
        val limitedSpans = store.archive("limited")!!.exported()
        assertEquals("run_body_limit", limitedSpans.last().cuts()["instructions"])
        val other = store.start("stopped")!!
        store.stopAll()
        assertNull(TraceCall(other, 1, model, { null }, null).begin(JsonObject(emptyMap()), emptyList()))
        assertEquals("partial", store.archive("stopped")!!.rows().single().status)
        assertEquals("recording_disabled", store.archive("stopped")!!.exported().single().attr("mika.trace.stop_reason").stringValue)
        assertNull(store.archive("../limited"))
        assertNull(store.archive("older-run"))
    }
    private fun TraceArchive.exported(): List<Span> {
        val bytes = ByteArrayOutputStream().also { save(it, false) }
        return ExportTraceServiceRequest.parseFrom(bytes.toByteArray()).getResourceSpans(0).getScopeSpans(0).spansList
    }

    private fun Span.attr(name: String) = attributesList.single { it.key == name }.value

    private fun Span.cuts() = eventsList.filter { it.name == "mika.trace.truncated" }.associate { event ->
        val attrs = event.attributesList.associate { it.key to it.value.stringValue }
        attrs.getValue("mika.block.path") to attrs.getValue("mika.truncation.reason")
    }

    @Test fun protobufLocatesEachRequestAndReplyCutAndCanReexportALegacyManifest() {
        val dir = temp.newFolder()
        val store = TraceStore(dir)
        val trace = store.start("case")!!
        val model = ResponsesModel("http://localhost", BearerToken.fixed(null), "m", Capabilities(true,false,1000,false), OkHttpClient())
        val longText = "private-body ".repeat(3_000)
        var deep: JsonElement = JsonPrimitive("leaf")
        repeat(40) { deep = JsonArray(listOf(deep)) }
        val body = buildJsonObject { put("input", buildJsonArray {
            add("short"); add(longText); add(deep)
        }) }
        val attempt = TraceCall(trace, 7, model, { null }, null).begin(body, emptyList())!!
        val response = ModelReply(listOf(Part.Text(longText)), StopReason.EndTurn, null)
        trace.end(attempt, "ok", 50, 0, 1) { TracePayload.reply(response) { null } }
        trace.event(RunEvent.TaskEnded(System.currentTimeMillis(), "succeeded", ""))
        store.finish(trace)
        val archive = store.archive("case")!!
        val (root, child) = archive.exported()
        assertEquals("partial", root.attr("mika.status").stringValue)
        assertEquals("succeeded", root.attr("mika.run.outcome").stringValue)
        assertEquals(setOf("block_size_limit", "nesting_limit"), root.attr("mika.trace.partial_reasons").arrayValue.valuesList.map { it.stringValue }.toSet())
        assertEquals(7L, child.attr("mika.step.index").intValue)
        assertEquals(1L, child.attr("mika.attempt").intValue)
        assertEquals(mapOf("input[1]" to "block_size_limit", "input[2]" to "nesting_limit", "response" to "block_size_limit"), child.cuts())
        assertFalse(child.toString().contains("private-body"))

        // Old archives have booleans in the manifest, a reserved reply suffix and no format attribute.
        fun strip(file: File, key: String) {
            val b = Span.parseFrom(file.readBytes()).toBuilder()
            b.removeAttributes(b.attributesList.indexOfFirst { it.key == key })
            file.writeBytes(b.build().toByteArray())
        }
        strip(File(dir, "case/root.pb"), "mika.trace.format_version")
        val id = attempt.id
        strip(File(dir, "case/spans/$id.pb"), "mika.trace.reply_truncation")
        val manifest = File(dir, "case/requests/$id.json")
        manifest.writeText(JsonArray(Json.parseToJsonElement(manifest.readText()).jsonArray.map {
            JsonObject(it.jsonObject - "reason")
        }).toString())
        val (_, legacy) = TraceStore(dir).archive("case")!!.exported()
        assertEquals(setOf("input[1]", "input[2]", "response"), legacy.cuts().keys)
        assertEquals(setOf("block_limit"), legacy.cuts().values.toSet())
    }

    @Test fun rotatesByFileBytesAcrossRestartAndProtectsAnActiveRun() {
        val dir = temp.newFolder()
        val store = TraceStore(dir)
        for (i in 1..15) {
            store.finish(store.start("run$i")!!)
            File(dir, "run$i").setLastModified(1_700_000_000_000L + i)
        }
        assertEquals(15, dir.listFiles()!!.size)
        val saved = temp.newFile()
        saved.outputStream().use { store.archive("run1")!!.save(it, false) }
        File(dir, "run1/blocks/old").writeBytes(ByteArray(8_192))
        val before = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val reopened = TraceStore(dir)
        reopened.setStorageLimit(before - 1)
        assertNull(reopened.archive("run1"))
        assertEquals(14, dir.listFiles()!!.size)
        assertTrue(saved.length() > 0)
        val live = reopened.start("live")!!
        reopened.setStorageLimit(1_100)
        assertNotNull(reopened.archive("live"))
        assertNull(reopened.start("extra"))
        reopened.finish(live)
        assertTrue(dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= 1_100)
    }

    @Test fun fullStoreSealsTheActiveRootWithTheExactStepAndBlock() {
        val dir = temp.newFolder()
        val store = TraceStore(dir, storageLimitBytes = 2_000)
        val trace = store.start("full")!!
        val model = ResponsesModel("http://localhost", BearerToken.fixed(null), "m", Capabilities(true,false,1000,false), OkHttpClient())
        val body = buildJsonObject { put("input", buildJsonArray { add("a".repeat(3_000)) }) }
        assertNull(TraceCall(trace, 4, model, { null }, null).begin(body, emptyList()))
        store.finish(trace)
        val root = store.archive("full")!!.exported().first()
        assertEquals(listOf("storage_limit"), root.attr("mika.trace.partial_reasons").arrayValue.valuesList.map { it.stringValue })
        assertEquals(4L, root.attr("mika.trace.stopped_step").intValue)
        assertEquals("input[0]", root.attr("mika.trace.stopped_block").stringValue)
        assertTrue(dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= 2_000)
    }

}
