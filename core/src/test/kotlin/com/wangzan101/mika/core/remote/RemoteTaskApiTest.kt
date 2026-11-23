package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.mcp.FakeRemoteTasks
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSummary
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class RemoteTaskApiTest {
    private val tasks = FakeRemoteTasks()
    private val records = FakeExports()
    private val host = RemoteHost(tasks, { TOKEN }, 0, false, "test", records = records)
    private val client = OkHttpClient()
    @Before fun start() = host.start()
    @After fun stop() { host.stop(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }

    private fun request(path: String, method: String = "GET", body: String? = null, token: String? = TOKEN,
        headers: Map<String, String> = emptyMap()): Pair<Int, JsonObject> {
        val builder = Request.Builder().url("http://127.0.0.1:${host.boundPort}$path")
            .method(method, body?.toRequestBody((headers["Content-Type"] ?: "application/json").toMediaType()))
        token?.let { builder.header("Authorization", "Bearer $it") }
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.newCall(builder.build()).execute().use { r -> r.code to Json.parseToJsonElement(r.body!!.string()).jsonObject }
    }

    private fun mcp(name: String, args: JsonObject): JsonObject = request("/mcp", "POST", buildJsonObject {
        put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
        putJsonObject("params") { put("name", name); put("arguments", args) }
    }.toString()).second["result"]!!.jsonObject

    @Test fun bothAdaptersUseTheSameTasksAndConversationNamespace() {
        assertTrue(request("/api/v1").second["capabilities"]!!.jsonArray.any { it.jsonPrimitive.content == "task_recovery" })
        assertEquals(400, request("/api/v1/tasks", "POST", """{"goal":"invalid","request_id":null}""").first)
        val (code, first) = request("/api/v1/tasks", "POST", """{"goal":"from CLI"}""")
        assertEquals(201, code)
        val id = first["task_id"]!!.jsonPrimitive.content
        val viaMcp = mcp("mika_task_status", buildJsonObject { put("task_id", id) })["structuredContent"]!!.jsonObject
        assertEquals(first, viaMcp)
        val second = mcp("mika_run_task", buildJsonObject {
            put("goal", "from MCP"); put("conversation", first["conversation"]!!)
        })["structuredContent"]!!.jsonObject
        assertEquals(first["conversation"], second["conversation"])
        val list = request("/api/v1/tasks?limit=1").second["tasks"]!!.jsonArray
        assertEquals(1, list.size)
        assertEquals(second["task_id"], list[0].jsonObject["task_id"])
        assertEquals(2, tasks.tasks.size)
    }

    @Test fun answerAndCancellationGoThroughTheSameRules() {
        request("/api/v1/tasks", "POST", """{"goal":"a task"}""")
        tasks.set("t1", TaskState.WaitingTakeover("payment", "Use the phone"))
        assertEquals(409, request("/api/v1/tasks/t1/answer", "POST", """{"text":"yes"}""").first)
        assertTrue(mcp("mika_answer", buildJsonObject { put("task_id", "t1"); put("text", "yes") })["isError"]!!.jsonPrimitive.boolean)
        assertTrue(tasks.answers.isEmpty())
        tasks.set("t1", TaskState.WaitingInput("Which account?"))
        assertEquals(200, request("/api/v1/tasks/t1/answer", "POST", """{"text":"personal"}""").first)
        assertEquals(listOf("t1" to "personal"), tasks.answers)
        val (status, cancelled) = request("/api/v1/tasks/t1/cancel", "POST", "{}")
        assertEquals(202, status)
        assertTrue(cancelled["accepted"]!!.jsonPrimitive.boolean)
        assertNull(cancelled["status"])
        assertEquals("waiting_for_answer", request("/api/v1/tasks/t1").second["status"]!!.jsonPrimitive.content)
        tasks.set("t1", TaskState.Cancelled)
        assertEquals("cancelled", mcp("mika_task_status", buildJsonObject { put("task_id", "t1") })["structuredContent"]!!.jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test fun resultsPreserveVerificationUnknownFalseAndTrue() {
        request("/api/v1/tasks", "POST", """{"goal":"a task"}""")
        tasks.set("t1", TaskState.Succeeded("answer", reviewed = false))
        assertNull(request("/api/v1/tasks/t1").second["verified"])
        for (verified in listOf(false, true)) {
            tasks.set("t1", TaskState.Succeeded("answer", verified = verified, reviewed = true))
            val viaApi = request("/api/v1/tasks/t1").second
            val viaMcp = mcp("mika_task_status", buildJsonObject { put("task_id", "t1") })["structuredContent"]!!.jsonObject
            assertEquals(viaMcp, viaApi)
            assertEquals(verified, viaApi["verified"]!!.jsonPrimitive.boolean)
        }
    }

    @Test fun httpAndMcpSteerTheSameTaskAndReportReceiptSeparatelyFromConsumption() {
        val info = request("/api/v1").second
        assertTrue(info["capabilities"]!!.jsonArray.any { it.jsonPrimitive.content == "task_steer" })
        assertEquals(RemoteTaskService.MAX_STEER, info["limits"]!!.jsonObject["max_steer"]!!.jsonPrimitive.int)
        request("/api/v1/tasks", "POST", """{"goal":"read"}""")
        tasks.set("t1", TaskState.Running(1, "reading"))
        val (code, ack) = request("/api/v1/tasks/t1/steer", "POST", """{"text":"only today"}""")
        assertEquals(202, code)
        assertTrue(ack["accepted"]!!.jsonPrimitive.boolean)
        assertEquals("s1", ack["message_id"]!!.jsonPrimitive.content)
        assertNull(ack["status"])
        val viaMcp = mcp("mika_steer_task", buildJsonObject { put("task_id", "t1"); put("text", "only read") })["structuredContent"]!!.jsonObject
        assertEquals("s2", viaMcp["message_id"]!!.jsonPrimitive.content)
        val task = tasks.get("t1")!!
        assertEquals(listOf("t1" to "only today", "t1" to "only read"), tasks.steered)
        tasks.tasks["t1"] = task.copy(steers = listOf(RemoteSteer("s1", "joined"), RemoteSteer("s2", "received")))
        val status = request("/api/v1/tasks/t1").second
        assertEquals(status, mcp("mika_task_status", buildJsonObject { put("task_id", "t1") })["structuredContent"]!!.jsonObject)
        assertEquals(listOf("joined", "received"), status["steers"]!!.jsonArray.map { it.jsonObject["state"]!!.jsonPrimitive.content })
        assertEquals(1, tasks.tasks.size)
    }

    @Test fun rejectedSteersNeverDeliverOrStartNewTasks() {
        request("/api/v1/tasks", "POST", """{"goal":"read"}""")
        tasks.set("t1", TaskState.Running(1, "reading"))
        for (body in listOf("{}", """{"text":7}""", """{"text":" "}""", """{"text":"more","approve":true}""")) {
            assertEquals(400, request("/api/v1/tasks/t1/steer", "POST", body).first)
        }
        assertEquals(404, request("/api/v1/tasks/local-task/steer", "POST", """{"text":"more"}""").first)
        assertEquals(405, request("/api/v1/tasks/t1/steer").first)
        tasks.set("t1", TaskState.WaitingTakeover("payment", "Use phone"))
        assertEquals(409, request("/api/v1/tasks/t1/steer", "POST", """{"text":"approve"}""").first)
        tasks.set("t1", TaskState.Cancelled)
        assertEquals(409, request("/api/v1/tasks/t1/steer", "POST", """{"text":"continue"}""").first)
        assertTrue(tasks.steered.isEmpty())
        assertEquals(1, tasks.tasks.size)
    }

    @Test fun malformedInputNeverStartsATaskAndMissingTasksAre404() {
        for (body in listOf("[]", "bad", "{}", """{"goal":7}""", """{"goal":"x","read_only":true}""",
            """{"goal":"x","conversation":"local-id"}""")) {
            assertEquals(body, 400, request("/api/v1/tasks", "POST", body).first)
        }
        assertTrue(tasks.tasks.isEmpty())
        assertEquals(404, request("/api/v1/tasks/missing").first)
        assertEquals(400, request("/api/v1/tasks?limit=1&limit=2").first)
        assertEquals(400, request("/api/v1/tasks?limit=0").first)
        assertEquals(400, request("/api/v1/tasks?unknown=1").first)
        assertEquals(405, request("/api/v1/tasks/t1", "POST", "{}").first)
        assertEquals(415, request("/api/v1/tasks", "POST", "{}", headers = mapOf("Content-Type" to "text/plain")).first)
        assertEquals(404, request("/api/v1/other").first)
    }

    @Test fun authenticationAndRateLimitsAreSharedAcrossBothRoutes() {
        assertEquals(401, request("/api/v1", token = null).first)
        assertEquals(403, request("/api/v1", headers = mapOf("Origin" to "https://example.test")).first)
        // The same caller's failures on either route count against one policy instance.
        repeat(4) { assertEquals(401, request(if (it % 2 == 0) "/mcp" else "/api/v1", token = "wrong").first) }
        assertEquals(429, request("/api/v1").first)
        assertEquals(429, request("/mcp", "POST", "{}").first)
    }

    @Test fun unknownRoutesAreAuthenticatedAndLoopbackCredentialsStayLocal() {
        assertEquals(401, request("/other", token = null).first)
        assertEquals(404, request("/other").first)
        val localOnly = RemoteHost(tasks, { null }, 0, false, "test", loopbackToken = { TOKEN })
        fun req(address: String) = HttpRequest("GET", "/api/v1", mapOf("authorization" to "Bearer $TOKEN"), byteArrayOf(), InetAddress.getByName(address))
        assertEquals(200, localOnly.handle(req("127.0.0.1")).status)
        assertEquals(401, localOnly.handle(req("192.168.1.20")).status)
    }

    @Test fun tokenRotationIsCheckedAgainOnAReusedConnection() {
        var current = "old-token"
        val rotating = RemoteHost(FakeRemoteTasks(), { current }, 0, false, "test")
        val pooled = OkHttpClient()
        rotating.start()
        fun get(token: String): Int {
            val call = Request.Builder().url("http://127.0.0.1:${rotating.boundPort}/api/v1")
                .header("Authorization", "Bearer $token").build()
            return pooled.newCall(call).execute().use { it.code }
        }
        try {
            assertEquals(200, get("old-token"))
            assertEquals(1, pooled.connectionPool.idleConnectionCount())
            current = "new-token"
            assertEquals(401, get("old-token"))
            assertEquals(200, get("new-token"))
        } finally {
            rotating.stop()
            pooled.connectionPool.evictAll()
            pooled.dispatcher.executorService.shutdown()
        }
    }

    @Test fun exportsByTaskAndRunIdAreBoundToTheSameComputerToken() {
        var token = "one"
        val runId = "20261102-033700-abc123"
        val record = RunExportRecord("private", "mcp-chat", null, null, emptyList())
        val owner = RemoteOwnership.of("one")
        val all = mapOf("$owner:t1" to record, "$owner:$runId" to record)
        val source = object : RunExportSource {
            override fun scope(owner: String) = object : RunExportSource {
                override fun load(id: String): RunExportLookup = all["$owner:$id"]?.let { RunExportLookup.Found(it) }
                    ?: RunExportLookup.Missing
            }
            override fun load(id: String) = RunExportLookup.Missing
        }
        val scoped = RemoteHost(FakeRemoteTasks(), { token }, 0, false, "test", records = source)
        fun export(id: String) = scoped.handle(HttpRequest("GET", "/api/v1/tasks/$id/export",
            mapOf("authorization" to "Bearer $token"), byteArrayOf(), InetAddress.getLoopbackAddress()))
        try {
            assertEquals(200, export("t1").status)
            assertEquals(200, export(runId).status)
            token = "two"
            assertEquals(404, export("t1").status)
            assertEquals(404, export(runId).status)
        } finally { scoped.stop() }
    }

    @Test fun exportIsAuthenticatedRedactedAndPartialUntilTheRunEnds() {
        val info = request("/api/v1").second
        assertTrue(info["capabilities"]!!.jsonArray.any { it.jsonPrimitive.content == "run_export" })
        assertEquals(401, request("/api/v1/tasks/t1/export", token = null).first)
        val (missingCode, missing) = request("/api/v1/tasks/missing/export")
        assertEquals(404, missingCode)
        assertEquals("not_found", missing["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals("No remote task or run record with id missing", missing["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        assertEquals(400, request("/api/v1/tasks/t1/export?include=screenshots").first)
        assertEquals(400, request("/api/v1/tasks/t1/export?events=zip").first)
        assertEquals(405, request("/api/v1/tasks/t1/export", "POST", "{}").first)
        val key = "sk-" + "a1B2c3D4e5F6g7H8i9J0k1L2"
        val runId = "20261102-033700-abc123"
        val record = RunExportRecord(
            "t1", "mcp-11111111-1111-1111-1111-111111111111", runId,
            RunSummary(runId, "goal $key", startedAtMs = 10, origin = "mcp"),
            listOf(RunEvent.Observed(11, 1, "screen $key"), RunEvent.Media(12, 4, 1, "设置")),
        )
        records.items["t1"] = RunExportLookup.Found(record)
        records.items[runId] = RunExportLookup.Found(record)
        val (code, body) = request("/api/v1/tasks/t1/export")
        assertEquals(200, code)
        assertEquals(true, body["partial"]!!.jsonPrimitive.boolean)
        assertEquals(false, body["includesScreenshots"]!!.jsonPrimitive.boolean)
        assertEquals("t1", body["task_id"]!!.jsonPrimitive.content)
        assertEquals(runId, body["run_id"]!!.jsonPrimitive.content)
        assertFalse(body.toString(), body.toString().contains(key))
        assertEquals("media", body["events"]!!.jsonArray.last().jsonObject["type"]!!.jsonPrimitive.content)
        val ndjson = raw("/api/v1/tasks/$runId/export?events=jsonl", accept = "application/x-ndjson")
        assertEquals(200, ndjson.first)
        assertTrue(ndjson.third, ndjson.third.startsWith("application/x-ndjson"))
        assertTrue(ndjson.second.lineSequence().first().contains("\"partial\":true"))
        assertFalse(ndjson.second.contains(key))
        records.items["t1"] = RunExportLookup.Gone("t1", runId)
        val gone = request("/api/v1/tasks/t1/export").second["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        assertTrue(gone, gone.contains("no longer available") && gone.contains(runId))
    }

    private fun raw(path: String, token: String? = TOKEN, accept: String = "application/json"): Triple<Int, String, String> {
        val builder = Request.Builder().url("http://127.0.0.1:${host.boundPort}$path").header("Accept", accept)
        token?.let { builder.header("Authorization", "Bearer $it") }
        return client.newCall(builder.build()).execute().use { response ->
            Triple(response.code, response.body!!.string(), response.header("Content-Type").orEmpty())
        }
    }

    private class FakeExports : RunExportSource {
        val items = HashMap<String, RunExportLookup>()
        override fun load(id: String) = items[id] ?: RunExportLookup.Missing
    }

    private companion object { const val TOKEN = "test-api-token" }
}
