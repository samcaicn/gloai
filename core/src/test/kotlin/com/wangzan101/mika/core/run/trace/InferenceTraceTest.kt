package com.wangzan101.mika.core.run.trace

import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.tool.Verb
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InferenceTraceTest {
    @get:Rule val temp = TemporaryFolder()
    private val caps = Capabilities(true, true, 100_000, false)
    private val http = OkHttpClient()
    private val history = listOf(
        Message(Role.User, listOf(Part.Text("PUBLIC_GOAL")), Author.User),
        Message(Role.Assistant, listOf(Part.ToolCall("call1", "phone_type_text", """{"text":"PRIVATE_TYPED"}""")), Author.Mika),
        Message(Role.Tool, listOf(Part.ToolResult("call1", """{"ok":true,"password":"PRIVATE_PASSWORD"}""", false),
            Part.Image("cHJpdmF0ZWltYWdl", width = 20, height = 30)), Author.Mika),
    )
    private fun response(text: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(text)

    @Test fun allProtocolsMeasureTheRealRequestAndKeepZeroDistinctFromUnreported() = runBlocking {
        MockWebServer().use { server ->
            val url = server.url("/").toString().trimEnd('/')
            val key = "PRIVATE_HEADER"
            val cases = listOf(
                OpenAiCompatModel(url,key,"chat",caps,http) to
                    """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":3}}""",
                ResponsesModel(url,BearerToken.fixed(key),"responses",caps,http) to
                    """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}],"usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":0},"output_tokens":3}}""",
                AnthropicModel(url,key,"anthropic",caps,http) to
                    """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":78,"cache_read_input_tokens":20,"cache_creation_input_tokens":2,"output_tokens":3}}""",
                GeminiModel(url,key,"gemini",caps,http) to
                    """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":3,"cachedContentTokenCount":0}}""",
                OpenAiCompatModel(url,key,"buffered-chat",caps,http) to
                    """data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}

data: {"choices":[],"us\u0061ge":{"prompt_tokens":100,"completion_tokens":3,"prompt_cache_hit_tokens":12}}

""",
            )
            val expectedCache = listOf(null, 0L, 20L, 0L, 12L)
            for ((i, pair) in cases.withIndex()) {
                val (model, json) = pair
                val runDir = temp.newFolder()
                val store = TraceStore(runDir)
                val trace = store.start("short")!!
                server.enqueue(response(json))
                model.complete("system", history, emptyList())
                val control = server.takeRequest().body.readByteString()
                server.enqueue(response(json))
                trace.modelCall(1, model, { Verb.TypeText }, null, history) { model.complete("system",history,emptyList()) }
                assertEquals(control, server.takeRequest().body.readByteString())
                trace.event(RunEvent.TaskEnded(0, "succeeded", "")); store.finish(trace)
                val archive = store.archive("short")!!
                val row = archive.rows().single { it.attempt != null }
                assertEquals(100L, row.input); assertEquals(expectedCache[i], row.cached); assertEquals("ok", row.status)
                val evidence = archive.bodyPage(row.id, 0, 65_536)!!.text
                assertTrue(evidence.contains("PUBLIC_GOAL")); assertTrue(evidence.contains("sha256"))
                for (privateValue in listOf(key, "PRIVATE_TYPED", "PRIVATE_PASSWORD", "cHJpdmF0ZWltYWdl"))
                    assertFalse(model.name + " " + privateValue, evidence.contains(privateValue))
            }
        }
    }

    @Test fun protocolSpecificPrivateFieldsNeverEnterBodyFiles() {
        val cases = mapOf(
            InferenceProtocol.Chat to """{"messages":[{"role":"assistant","reasoning_content":"PRIVATE_THOUGHT","tool_calls":[{"id":"c","type":"function","function":{"name":"cmd","arguments":"{}"}}]},{"role":"tool","tool_call_id":"c","content":[{"type":"text","text":"{\"output\":\"PRIVATE_RESULT\"}","cache_control":{"type":"ephemeral"}}]}]}""",
            InferenceProtocol.Anthropic to """{"messages":[{"content":[{"type":"thinking","thinking":"PRIVATE_THOUGHT","signature":"PRIVATE_SIG"},{"type":"tool_use","id":"c","name":"cmd","input":{}},{"type":"tool_result","tool_use_id":"c","content":"{\"output\":\"PRIVATE_RESULT\"}"}]}]}""",
            InferenceProtocol.Gemini to """{"contents":[{"parts":[{"thoughtSignature":"PRIVATE_SIG","functionCall":{"name":"cmd","args":{}}},{"functionResponse":{"name":"cmd","response":{"content":"{\"output\":\"PRIVATE_RESULT\"}"}}}]}]}""",
        )
        for ((protocol, json) in cases) {
            val display = TracePayload.blocks(Json.parseToJsonElement(json).jsonObject, emptyList(), { Verb.PhoneCommand }, protocol)
                .joinToString { it.text }
            assertFalse(protocol.name, "PRIVATE_" in display)
        }
    }

    @Test fun optionRetryAndBrokenEventStreamAreDistinctAttemptsAndAuthTrafficIsExcluded() = runBlocking {
        MockWebServer().use { server ->
            val url = server.url("/").toString()
            val model = OpenAiCompatModel(url,"key","chat",caps,http)
            val store = TraceStore(temp.newFolder()); val trace = store.start("two")!!
            server.enqueue(response("{}"))
            server.enqueue(MockResponse().setResponseCode(400).setBody("stream_options unsupported"))
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream")
                .setBody("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"prompt_tokens_details\":{\"cached_tokens\":0}}}\n\n"))
            try {
                trace.modelCall(7, model, { null }, null, history) {
                    ModelHttp.send(http, Request.Builder().url(server.url("/auth-refresh")).build())
                    model.completeStreaming("system", history, emptyList()) {}
                }
                fail("incomplete stream")
            } catch (_: ModelError.StreamCut) {}
            store.finish(trace)
            val rows = store.archive("two")!!.rows().drop(1)
            assertEquals(listOf(1L,2L), rows.map { it.attempt })
            assertEquals(listOf(7L,7L), rows.map { it.step }); assertEquals(listOf("error","error"), rows.map { it.status })
            assertEquals(listOf(null,0L), rows.map { it.cached })
            assertEquals(listOf(null,null), rows.map { it.output })
            assertEquals(3,server.requestCount)
        }
    }
}
