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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Local real WebSocket protocol tests; no credentials, provider calls or carrier calls. */
class VolcRealtimeDtmfTest {
    @Test fun toolsAreOptInAndConnectWaitsForTheirOwnAck() = runBlocking {
        fixture(autoAck = false) {
            val connecting = async { client.connect(credentials, VolcRealtimeConfig(enablePhoneDtmf = true)) }
            val create = nextMessage()
            assertEquals("session.create", create.type)
            assertFalse(create["session"]!!.jsonObject.containsKey("tools"))
            peer().send("""{"type":"session.created","session":{"id":"local"}}""")
            val update = nextMessage()
            assertEquals("session.update", update.type)
            val tools = update["session"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
            // 电话里只多一个请示主人,用户前台那套工具不在
            assertEquals(listOf("send_dtmf", "ask_owner"), tools.map { it["name"]!!.jsonPrimitive.content })
            val tool = tools.first()
            assertEquals("function", tool["type"]!!.jsonPrimitive.content)
            assertEquals("send_dtmf", tool["name"]!!.jsonPrimitive.content)
            val schema = tool["parameters"]!!.jsonObject
            assertEquals(listOf(JsonPrimitive("digits")), schema["required"]!!.jsonArray)
            assertEquals("^[0-9*#]{1,32}$", schema["properties"]!!.jsonObject["digits"]!!.jsonObject["pattern"]!!.jsonPrimitive.content)
            assertFalse(connecting.isCompleted)
            // event_id identifies this server event; it need not echo the request ID.
            peer().send("""{"type":"session.updated","event_id":"server-generated-update-event"}""")
            val session = withTimeout(3_000) { connecting.await() }
            assertTrue(session.sendGreeting("你好"))
            assertEquals("speech_text_buffer.commit", nextMessage().type)
            finish(session)
        }
    }

    @Test fun providerBatchExecutesOnceAndReturnsOnePairedToolResultBatch() = runBlocking {
        fixture {
            val session = connectTools()
            val events = Channel<VolcRealtimeEvent>(32)
            val collector = async { session.events.collect { events.send(it) } }
            val frame = functionFrame(call("one", "1"), call("two", "02*#"))
            peer().send(frame)
            val first = nextEvent(events) as VolcRealtimeEvent.FunctionCall
            val second = nextEvent(events) as VolcRealtimeEvent.FunctionCall
            assertEquals("one", first.callId)
            assertEquals("send_dtmf", first.name)
            assertEquals("02*#", second.arguments["digits"]!!.jsonPrimitive.content)
            assertFalse(session.sendToolResult("unknown", success))
            assertTrue(session.sendToolResult(second.callId, success))
            assertTrue(messages.tryReceive().isFailure) // First call is still in flight.
            assertTrue(session.sendToolResult(first.callId, success))
            val returned = nextMessage()
            assertEquals("conversation.item.create", returned.type)
            val items = returned["items"]!!.jsonArray
            assertEquals(listOf("one", "two"), items.map { it.jsonObject["call_id"]!!.jsonPrimitive.content })
            items.forEach {
                assertEquals("tool", it.jsonObject["role"]!!.jsonPrimitive.content)
                val content = it.jsonObject["content"]!!.jsonArray.single().jsonObject
                assertEquals("input_text", content["type"]!!.jsonPrimitive.content)
                assertEquals(success, Json.parseToJsonElement(content["text"]!!.jsonPrimitive.content))
            }
            assertFalse(session.sendToolResult(first.callId, success))
            peer().send(frame) // Completed replay and pending replay never press keys again.
            peer().send(functionFrame(call("three", "3")))
            val third = nextEvent(events) as VolcRealtimeEvent.FunctionCall
            peer().send(functionFrame(call("three", "3")))
            peer().send("""{"type":"input_audio_buffer.committed"}""")
            assertEquals(VolcRealtimeEvent.InputAudioCommitted, nextEvent(events))
            assertTrue(session.sendToolResult(third.callId, success))
            assertEquals("conversation.item.create", nextMessage().type)
            finish(session)
            withTimeout(3_000) { collector.await() }
            assertFalse(session.sendToolResult(third.callId, success))
        }
    }

    @Test fun malformedUnknownAndOversizedArgumentsReturnErrorsWithoutPhoneEvents() = runBlocking {
        fixture {
            val session = connectTools()
            val events = Channel<VolcRealtimeEvent>(32)
            val collector = async { session.events.collect { events.send(it) } }
            val cases = listOf(
                item("empty", "send_dtmf", """{"digits":""}"""),
                item("letters", "send_dtmf", """{"digits":"12a"}"""),
                item("longdigits", "send_dtmf", """{"digits":"${"1".repeat(33)}"}"""),
                item("extra", "send_dtmf", """{"digits":"1","number":"123"}"""),
                item("json", "send_dtmf", "{"),
                item("huge", "send_dtmf", "x".repeat(257)),
                item("other", "dial_number", """{"digits":"1"}"""),
                buildJsonObject { put("call_id", "notstring"); put("name", "send_dtmf"); put("arguments", buildJsonObject { put("digits", "1") }) },
            )
            peer().send(functionFrame(*cases.toTypedArray()))
            val results = nextMessage()["items"]!!.jsonArray
            assertEquals(cases.size, results.size)
            results.forEach { assertTrue(it.toString().contains("error")) }
            peer().send("""{"type":"input_audio_buffer.committed"}""")
            assertEquals(VolcRealtimeEvent.InputAudioCommitted, nextEvent(events))
            finish(session)
            withTimeout(3_000) { collector.await() }
        }
    }

    @Test fun explicitlyRetiredResponseCannotSendKeys() = runBlocking {
        fixture {
            val session = connectTools()
            val events = Channel<VolcRealtimeEvent>(32)
            val collector = async { session.events.collect { events.send(it) } }
            peer().send("""{"type":"response.output_text.delta","response_id":"old","delta":"等待菜单"}""")
            peer().send("""{"type":"conversation.item.input_audio_transcription.started","item_id":"menu"}""")
            peer().send(functionFrame(call("stale", "1"), responseId = "old"))
            assertTrue(nextMessage().toString().contains("stale_response"))
            assertTrue(nextEvent(events) is VolcRealtimeEvent.AssistantTranscript)
            assertEquals(VolcRealtimeEvent.UserSpeaking, nextEvent(events))
            peer().send(functionFrame(call("current", "2"), responseId = "new"))
            assertEquals("current", (nextEvent(events) as VolcRealtimeEvent.FunctionCall).callId)
            assertTrue(session.sendToolResult("current", success))
            nextMessage()
            finish(session)
            withTimeout(3_000) { collector.await() }
        }
    }

    @Test fun queuedIdentifiedCallRetiredBeforeCollectionCannotExecute() = runBlocking {
        fixture {
            val session = connectTools()
            peer().send(functionFrame(call("queued", "1"), responseId = "old"))
            peer().send("""{"type":"conversation.item.input_audio_transcription.started","item_id":"menu"}""")
            // This automatic rejection is a protocol barrier: earlier frames were processed,
            // while the valid call remains buffered with no consumer yet.
            peer().send(functionFrame(item("barrier", "unknown", "{}")))
            assertTrue(nextMessage().toString().contains("unsupported_tool"))
            val events = Channel<VolcRealtimeEvent>(32)
            val collector = async { session.events.collect { events.send(it) } }
            assertEquals(VolcRealtimeEvent.UserSpeaking, nextEvent(events))
            assertTrue(nextMessage().toString().contains("stale_response"))
            // Anonymous calls have no response identity from which to infer retirement.
            peer().send(functionFrame(call("anonymous", "2")))
            assertEquals("anonymous", (nextEvent(events) as VolcRealtimeEvent.FunctionCall).callId)
            assertTrue(session.sendToolResult("anonymous", success))
            nextMessage()
            finish(session)
            withTimeout(3_000) { collector.await() }
        }
    }

    @Test fun disabledToolsAndUnpairableOrOversizedBatchesFailClosed() = runBlocking {
        listOf(
            false to functionFrame(call("disabled", "1")),
            true to functionFrame(buildJsonObject { put("name", "send_dtmf"); put("arguments", """{"digits":"1"}""") }),
            true to functionFrame(*Array(9) { call("too-many-$it", "1") }),
            true to functionFrame(call("ambiguous", "1"), call("ambiguous", "2")),
        ).forEach { (enabled, frame) ->
            fixture {
                val session = if (enabled) connectTools() else {
                    val connected = client.connect(credentials)
                    assertFalse(nextMessage()["session"]!!.jsonObject.containsKey("tools"))
                    assertTrue(messages.tryReceive().isFailure)
                    connected
                }
                peer().send(frame)
                withTimeout(3_000) { session.awaitClosed() }
                val events = mutableListOf<VolcRealtimeEvent>()
                session.events.collect { events.add(it) }
                assertFalse(events.any { it is VolcRealtimeEvent.FunctionCall })
                assertEquals(VolcRealtimeException.Kind.PROTOCOL, (events.last() as VolcRealtimeEvent.Failed).error.kind)
            }
        }
    }

    @Test fun toolSetupTimeoutIsBoundedAndNeverRetries() = runBlocking {
        fixture(ackTools = false, timeoutMs = 250) {
            val failure = runCatching { client.connect(credentials, VolcRealtimeConfig(enablePhoneDtmf = true)) }.exceptionOrNull()
            assertEquals(VolcRealtimeException.Kind.TIMEOUT, (failure as VolcRealtimeException).kind)
            assertEquals("session.create", nextMessage().type)
            assertEquals("session.update", nextMessage().type)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun dedupeMemoryHasHardCapRatherThanEvictingExecutedIds() = runBlocking {
        fixture {
            val session = connectTools()
            // Unsupported tools still consume IDs; the bound covers all successful/rejected deliveries.
            repeat(16) { batch ->
                peer().send(functionFrame(*Array(8) { item("id-${batch * 8 + it}", "unknown", "{}") }))
                assertEquals(8, nextMessage()["items"]!!.jsonArray.size)
            }
            peer().send(functionFrame(call("id-0", "1")))
            peer().send(functionFrame(call("over-limit", "1")))
            withTimeout(3_000) { session.awaitClosed() }
            val events = mutableListOf<VolcRealtimeEvent>()
            session.events.collect { events.add(it) }
            assertFalse(events.any { it is VolcRealtimeEvent.FunctionCall })
            assertEquals(VolcRealtimeException.Kind.PROTOCOL, (events.last() as VolcRealtimeEvent.Failed).error.kind)
        }
    }

    private val success = buildJsonObject { put("ok", true); put("sent", 1) }
    private val credentials = VolcRealtimeCredentials(apiKey = "local-test-key")
    private val JsonObject.type get() = getValue("type").jsonPrimitive.content
    private fun call(id: String, digits: String) = item(id, "send_dtmf", buildJsonObject { put("digits", digits) }.toString())
    private fun item(id: String, name: String, arguments: String) = buildJsonObject {
        put("call_id", id); put("name", name); put("arguments", arguments)
    }
    private fun functionFrame(vararg items: JsonObject, responseId: String? = null) = buildJsonObject {
        put("type", "response.function_call_arguments.done")
        if (responseId != null) put("response_id", responseId)
        put("items", JsonArray(items.toList()))
    }.toString()
    private suspend fun nextEvent(events: Channel<VolcRealtimeEvent>) = withTimeout(3_000) { events.receive() }

    private inner class Fixture(val server: MockWebServer, val client: VolcRealtimeClient, val scope: CoroutineScope) : CoroutineScope by scope {
        val messages = Channel<JsonObject>(32)
        val socket = CompletableDeferred<WebSocket>()
        suspend fun peer() = withTimeout(3_000) { socket.await() }
        suspend fun nextMessage() = withTimeout(3_000) { messages.receive() }
        suspend fun connectTools(): VolcRealtimeSession {
            val session = client.connect(credentials, VolcRealtimeConfig(enablePhoneDtmf = true))
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
