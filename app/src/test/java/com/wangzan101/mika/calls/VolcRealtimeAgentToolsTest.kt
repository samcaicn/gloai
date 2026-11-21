package com.wangzan101.mika.calls

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    private inner class Fixture(val server: MockWebServer, val client: VolcRealtimeClient, val scope: CoroutineScope) : CoroutineScope by scope {
        val messages = Channel<JsonObject>(32)
        val socket = CompletableDeferred<WebSocket>()
        suspend fun peer() = withTimeout(3_000) { socket.await() }
        suspend fun nextMessage() = withTimeout(3_000) { messages.receive() }
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
