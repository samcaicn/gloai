package com.wangzan101.mika.calls

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Local real WebSocket tests for mika_request registration; no credentials, provider calls or carrier calls. */
class VolcRealtimeAgentToolsTest {
    @Test fun toolDefinitionRequiresTheCompleteUtterance() {
        val tool = VolcRealtimeProtocol.mikaRequestTool()
        assertEquals("function", tool.getValue("type").jsonPrimitive.content)
        assertEquals("mika_request", tool.getValue("name").jsonPrimitive.content)
        assertTrue(tool.getValue("description").jsonPrimitive.content.isNotBlank())
        val schema = tool.getValue("parameters").jsonObject
        assertEquals("object", schema.getValue("type").jsonPrimitive.content)
        assertEquals(listOf(JsonPrimitive("utterance")), schema.getValue("required").jsonArray)
        val utterance = schema.getValue("properties").jsonObject.getValue("utterance").jsonObject
        assertEquals("string", utterance.getValue("type").jsonPrimitive.content)
        assertEquals("false", schema.getValue("additionalProperties").jsonPrimitive.content)
    }

    @Test fun disabledAgentToolsSendNoUpdateAndKeepInstructionsUntouched() = runBlocking {
        fixture {
            val session = client.connect(credentials, VolcRealtimeConfig(systemPrompt = "照着说。"))
            val create = nextMessage()
            assertEquals("session.create", create.type)
            val created = create.getValue("session").jsonObject
            assertEquals("照着说。", created.getValue("instructions").jsonPrimitive.content)
            assertFalse(created.containsKey("tools"))
            assertTrue(messages.tryReceive().isFailure)
            finish(session)
        }
    }

    @Test fun enabledAgentToolsRegisterMikaRequestAndWaitForTheAck() = runBlocking {
        fixture(autoAck = false) {
            val connecting = async {
                client.connect(credentials, VolcRealtimeConfig(systemPrompt = "照着说。", agentTools = true))
            }
            val create = nextMessage()
            assertEquals("session.create", create.type)
            val created = create.getValue("session").jsonObject
            val instructions = created.getValue("instructions").jsonPrimitive.content
            assertTrue(instructions.startsWith("照着说。"))
            // The constraint section: one mika_request per complete utterance, otherwise silent,
            // and the tool result is repeated exactly once.
            assertTrue(instructions.contains("mika_request"))
            assertTrue(instructions.contains("沉默"))
            assertTrue(instructions.contains("复述"))
            assertFalse(created.containsKey("tools"))
            peer().send("""{"type":"session.created","session":{"id":"local"}}""")
            val update = nextMessage()
            assertEquals("session.update", update.type)
            val tools = update.getValue("session").jsonObject.getValue("tools").jsonArray
            assertEquals(1, tools.size)
            val tool = tools.single().jsonObject
            assertEquals("function", tool.getValue("type").jsonPrimitive.content)
            assertEquals("mika_request", tool.getValue("name").jsonPrimitive.content)
            assertEquals(
                listOf(JsonPrimitive("utterance")),
                tool.getValue("parameters").jsonObject.getValue("required").jsonArray,
            )
            assertFalse(connecting.isCompleted)
            // event_id identifies this server event; it need not echo the request ID.
            peer().send("""{"type":"session.updated","event_id":"server-generated-update-event"}""")
            val session = withTimeout(3_000) { connecting.await() }
            assertTrue(session.sendGreeting("你好"))
            assertEquals("speech_text_buffer.commit", nextMessage().type)
            finish(session)
        }
    }

    @Test fun blankPromptStillCarriesTheConstraintSection() = runBlocking {
        fixture {
            val session = client.connect(credentials, VolcRealtimeConfig(agentTools = true))
            val create = nextMessage()
            assertEquals("session.create", create.type)
            val instructions = create.getValue("session").jsonObject.getValue("instructions").jsonPrimitive.content
            assertTrue(instructions.contains("mika_request"))
            assertTrue(instructions.contains("沉默"))
            assertEquals("session.update", nextMessage().type)
            finish(session)
        }
    }

    @Test fun phoneAndAgentToolsShareOneCompleteReplacementUpdate() = runBlocking {
        fixture {
            val session = client.connect(
                credentials,
                VolcRealtimeConfig(enablePhoneDtmf = true, agentTools = true),
            )
            assertEquals("session.create", nextMessage().type)
            val update = nextMessage()
            assertEquals("session.update", update.type)
            // tools is a complete replacement on the wire, so one update must carry the whole set.
            val names = update.getValue("session").jsonObject.getValue("tools").jsonArray
                .map { it.jsonObject.getValue("name").jsonPrimitive.content }
            assertEquals(listOf("send_dtmf", "mika_request"), names)
            assertTrue(messages.tryReceive().isFailure)
            finish(session)
        }
    }

    @Test fun agentToolsFailFastOnTheOpenAiProtocol() {
        val error = runCatching {
            VolcRealtimeConfig(agentTools = true, protocol = CallVoiceProtocol.OPENAI_REALTIME)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test fun mikaRequestCallsArriveWithFullLengthUtterancesAndGetResults() = runBlocking {
        fixture {
            val session = connectAgent()
            val events = Channel<VolcRealtimeEvent>(32)
            val collector = async { session.events.collect { events.send(it) } }
            // Longer than the 256-char DTMF argument cap, well within the 4096-char utterance cap.
            val utterance = "把这段话完整交给任务系统执行" + "，一字不落".repeat(500)
            peer().send(functionFrame(item("call-1", "mika_request", """{"utterance":"$utterance"}""")))
            val call = nextEvent(events) as VolcRealtimeEvent.FunctionCall
            assertEquals("call-1", call.callId)
            assertEquals("mika_request", call.name)
            assertEquals(utterance, call.arguments.getValue("utterance").jsonPrimitive.content)
            assertTrue(session.sendToolResult("call-1", buildJsonObject { put("ok", true); put("text", "已转交") }))
            val returned = nextMessage()
            assertEquals("conversation.item.create", returned.type)
            val item = returned.getValue("items").jsonArray.single().jsonObject
            assertEquals("call-1", item.getValue("call_id").jsonPrimitive.content)
            finish(session)
            withTimeout(3_000) { collector.await() }
        }
    }

    @Test fun oversizedOrMalformedMikaRequestArgumentsAreAnsweredWithErrors() = runBlocking {
        fixture {
            val session = connectAgent()
            val events = Channel<VolcRealtimeEvent>(32)
            val collector = async { session.events.collect { events.send(it) } }
            val cases = listOf(
                item("huge", "mika_request", """{"utterance":"${"字".repeat(4_200)}"}"""),
                item("empty", "mika_request", """{"utterance":""}"""),
                item("extra", "mika_request", """{"utterance":"帮我订个闹钟","note":"x"}"""),
                item("dtmf", "send_dtmf", """{"digits":"1"}"""),
                item("other", "dial_number", """{"number":"123"}"""),
            )
            for (case in cases) peer().send(functionFrame(case))
            val errors = (1..cases.size).map { nextMessage() }
            val contents = errors.flatMap { frame ->
                frame.getValue("items").jsonArray.map {
                    it.jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
                }
            }
            assertTrue(contents.all { it.contains("invalid_arguments") || it.contains("unsupported_tool") })
            assertTrue(contents.any { it.contains("unsupported_tool") })
            assertFalse(events.tryReceive().isSuccess)
            finish(session)
            withTimeout(3_000) { collector.await() }
        }
    }

    @Test fun toolSetupTimeoutIsBoundedAndNeverRetries() = runBlocking {
        fixture(autoAck = true, ackTools = false, timeoutMs = 250) {
            val failure = runCatching { client.connect(credentials, VolcRealtimeConfig(agentTools = true)) }
                .exceptionOrNull()
            assertEquals(VolcRealtimeException.Kind.TIMEOUT, (failure as VolcRealtimeException).kind)
            assertEquals("session.create", nextMessage().type)
            assertEquals("session.update", nextMessage().type)
            assertEquals(1, server.requestCount)
        }
    }

    private val credentials = VolcRealtimeCredentials(apiKey = "local-test-key")
    private val JsonObject.type get() = getValue("type").jsonPrimitive.content

    private fun item(id: String, name: String, arguments: String) = buildJsonObject {
        put("call_id", id); put("name", name); put("arguments", arguments)
    }

    private fun functionFrame(vararg items: JsonObject) = buildJsonObject {
        put("type", VolcRealtimeProtocol.FUNCTION_CALL)
        put("items", JsonArray(items.toList()))
    }.toString()

    private suspend fun nextEvent(events: Channel<VolcRealtimeEvent>) = withTimeout(3_000) { events.receive() }

    private inner class Fixture(val server: MockWebServer, val client: VolcRealtimeClient, val scope: CoroutineScope) : CoroutineScope by scope {
        val messages = Channel<JsonObject>(32)
        val socket = CompletableDeferred<WebSocket>()
        suspend fun peer() = withTimeout(3_000) { socket.await() }
        suspend fun nextMessage() = withTimeout(3_000) { messages.receive() }
        suspend fun connectAgent(): VolcRealtimeSession {
            val session = client.connect(credentials, VolcRealtimeConfig(agentTools = true))
            assertEquals("session.create", nextMessage().type)
            assertEquals("session.update", nextMessage().type)
            return session
        }
        suspend fun finish(session: VolcRealtimeSession) {
            session.close()
            assertEquals("session.close", nextMessage().type)
            withTimeout(3_000) { session.awaitClosed() }
        }
    }

    private suspend fun fixture(
        autoAck: Boolean = true,
        ackTools: Boolean = true,
        timeoutMs: Long = 3_000,
        block: suspend Fixture.() -> Unit,
    ) = coroutineScope {
        val server = MockWebServer()
        val http = OkHttpClient()
        val fixture = Fixture(server, VolcRealtimeClient(http, server.url("/realtime").toString(), timeoutMs, 250), this)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { fixture.socket.complete(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = Json.parseToJsonElement(text).jsonObject
                check(fixture.messages.trySend(frame).isSuccess)
                when (frame.type) {
                    "session.create" -> if (autoAck) webSocket.send("""{"type":"session.created"}""")
                    "session.update" -> if (autoAck && ackTools) webSocket.send("""{"type":"session.updated"}""")
                    "session.close" -> webSocket.send("""{"type":"session.closed"}""")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        }))
        try { fixture.block() } finally {
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
