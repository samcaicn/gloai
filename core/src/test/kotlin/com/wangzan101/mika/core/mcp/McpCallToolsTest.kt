package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.calls.PhoneCallPort
import com.wangzan101.mika.core.calls.PhoneCallTask
import com.wangzan101.mika.core.remote.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class McpCallToolsTest {
    private var writes = 0
    private var requested: Pair<String, String?>? = null
    private var requestedTask: PhoneCallTask? = null
    private val port = object : PhoneCallPort {
        override suspend fun state() = buildJsonObject { put("phase", "ringing"); put("call_id", "tracked-call") }
        override suspend fun dial(number: String, simId: String?, task: PhoneCallTask?, ownerConversationId: String?): JsonObject {
            writes++; requested = number to simId
            requestedTask = task
            return buildJsonObject { put("ok", true); put("phase", "dialing") }
        }
        override suspend fun answer(callId: String) = dial("answer", callId)
        override suspend fun hangup(callId: String) = dial("hangup", callId)
        override suspend fun takeover() = dial("takeover", null)
    }
    @Test fun callsStayBehindDeviceAccessAndExistingAuthentication() {
        val endpoints = RemoteEndpoints(FakeRemoteTasks(), "test", calls = port)
        val host = RemoteHost(FakeRemoteTasks(), { "test-token" }, 0, false, "test", endpoints = endpoints)
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"mika_call_dial","arguments":{"number":"13800000000"}}}"""
        fun req(token: String?, origin: String? = null, payload: String = body) = HttpRequest("POST", "/mcp", buildMap {
            put("content-type", "application/json")
            token?.let { put("authorization", "Bearer $it") }
            origin?.let { put("origin", it) }
        }, payload.toByteArray(), InetAddress.getLoopbackAddress())
        assertEquals(401, host.handle(req(null)).status)
        assertEquals(401, host.handle(req("wrong")).status)
        assertEquals(403, host.handle(req("test-token", "https://example.com")).status)
        val denied = endpoints.handle(req(null), false).body.toString(Charsets.UTF_8)
        assertTrue(denied.contains("Unknown tool"))
        assertEquals(0, writes)
        assertEquals(200, host.handle(req("test-token")).status)
        assertEquals(1, writes)
        assertEquals("13800000000" to null, requested)
        assertNull(requestedTask)

        val withTask = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"mika_call_dial","arguments":{"number":"13800000000","sim_id":"sim-2","task":{"goal":"确认今晚几点关门","questions":["今天几点关门？","关门前多久停止入场？"],"context":"今天是假日","constraints":"不要预约，也不要留号码"}}}}"""
        assertEquals(401, host.handle(req(null, payload = withTask)).status)
        assertTrue(endpoints.handle(req(null, payload = withTask), false).body.toString(Charsets.UTF_8).contains("Unknown tool"))
        assertEquals(1, writes)
        assertEquals(200, host.handle(req("test-token", payload = withTask)).status)
        assertEquals(2, writes)
        assertEquals("13800000000" to "sim-2", requested)
        assertEquals(PhoneCallTask("确认今晚几点关门", listOf("今天几点关门？", "关门前多久停止入场？"), "今天是假日", "不要预约，也不要留号码"), requestedTask)
    }
    @Test fun credentialsAndBadArgumentTypesNeverReachPhone() = runTest {
        val calls = McpCallTools(port)
        for (arguments in listOf(
            buildJsonObject { put("number", 13800000000L) },
            buildJsonObject { put("number", "13800000000"); put("sim_id", false) },
            buildJsonObject { put("number", "13800000000"); put("api_key", "secret") },
            buildJsonObject { put("number", "13800000000"); put("task", JsonNull) },
            buildJsonObject { put("number", "13800000000"); putJsonObject("task") { put("goal", "订位"); put("api_key", "secret") } },
            buildJsonObject { put("number", "13800000000"); putJsonObject("task") { put("goal", "查营业时间"); put("questions", true) } },
            buildJsonObject { put("number", "13800000000"); putJsonObject("task") { put("goal", "查营业时间"); put("context", "字".repeat(1001)) } },
        )) {
            try { calls.call(McpCallTools.DIAL, arguments); fail("accepted invalid args") }
            catch (_: McpTaskTools.ToolError) { }
        }
        assertEquals(0, writes)
        val result = calls.call(McpCallTools.DIAL, buildJsonObject { put("number", "13800000000"); put("sim_id", "sim-2") })
        assertEquals("13800000000" to "sim-2", requested)
        assertNull(requestedTask)
        assertEquals(JsonPrimitive("dialing"), result["phase"])
        assertFalse(calls.tools.any { it.name.contains("credential") || it.name.contains("enable") })
    }

    @Test fun dialPublishesOptionalTaskWithClosedSchema() {
        val schema = McpCallTools(port).tools.single { it.name == McpCallTools.DIAL }.schema
        assertEquals(listOf(JsonPrimitive("number")), schema.getValue("required").jsonArray)
        val task = schema.getValue("properties").jsonObject.getValue("task").jsonObject
        assertEquals(JsonPrimitive(false), task["additionalProperties"])
        assertEquals(listOf(JsonPrimitive("goal")), task.getValue("required").jsonArray)
        val properties = task.getValue("properties").jsonObject
        assertEquals(setOf("goal", "questions", "context", "constraints"), properties.keys)
        assertEquals(JsonPrimitive(8), properties.getValue("questions").jsonObject["maxItems"])
    }

    @Test fun answerAndHangupRequireAndForwardTheExactCallId() = runTest {
        val calls = McpCallTools(port)
        val id = calls.call(McpCallTools.STATE, buildJsonObject {}).getValue("call_id").jsonPrimitive.content
        for (tool in listOf(McpCallTools.ANSWER, McpCallTools.HANGUP)) {
            val schema = calls.tools.single { it.name == tool }.schema
            assertEquals(listOf(JsonPrimitive("call_id")), schema.getValue("required").jsonArray)
            val before = writes
            for (arguments in listOf(
                buildJsonObject {},
                buildJsonObject { put("call_id", 123) },
                buildJsonObject { putJsonObject("call_id") { put("id", id) } },
                buildJsonObject { put("call_id", " ") },
            )) {
                try { calls.call(tool, arguments); fail("accepted invalid call_id") }
                catch (_: McpTaskTools.ToolError) { }
            }
            assertEquals(before, writes)
            calls.call(tool, buildJsonObject { put("call_id", id) })
            assertEquals(before + 1, writes)
            assertEquals(tool.substringAfterLast('_') to id, requested)
        }
    }
}
