package com.wangzan101.mika.calls

import com.wangzan101.mika.core.frontdesk.DeskPrompt
import com.wangzan101.mika.core.frontdesk.DeskToolSet
import com.wangzan101.mika.core.frontdesk.DeskTools
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

/** Local real WebSocket tests for front desk tool registration; no credentials, provider calls or carrier calls. */
class VolcRealtimeAgentToolsTest {
    @Test fun deskToolsAreTheUserSetAndWordsGetAFullUtterance() {
        assertEquals(DeskTools.specs(DeskToolSet.User).map { it.getValue("name").jsonPrimitive.content }.toSet(), VolcRealtimeProtocol.deskToolNames)
        for (name in listOf(DeskTools.DISPATCH, DeskTools.ADD, DeskTools.ANSWER)) {
            assertEquals(VolcRealtimeProtocol.MAX_UTTERANCE_CHARS, VolcRealtimeProtocol.maxArgumentCharsFor(name))
        }
        assertEquals(VolcRealtimeProtocol.MAX_FUNCTION_ARGUMENT_CHARS, VolcRealtimeProtocol.maxArgumentCharsFor(DeskTools.CANCEL))
        assertEquals(VolcRealtimeProtocol.MAX_FUNCTION_ARGUMENT_CHARS, VolcRealtimeProtocol.maxArgumentCharsFor("send_dtmf"))
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

    @Test fun enabledAgentToolsRegisterDeskToolsAndWaitForTheAck() = runBlocking {
        fixture(autoAck = false) {
            val connecting = async {
                client.connect(credentials, VolcRealtimeConfig(systemPrompt = "照着说。", agentTools = true))
            }
            val create = nextMessage()
            assertEquals("session.create", create.type)
            val created = create.getValue("session").jsonObject
            val instructions = created.getValue("instructions").jsonPrimitive.content
            // 前台说明在前,开场的任务板接在后面。
            assertTrue(instructions.startsWith(DeskPrompt.user(spoken = true)))
            assertTrue(instructions.endsWith("照着说。"))
            assertFalse(created.containsKey("tools"))
            peer().send("""{"type":"session.created","session":{"id":"local"}}""")
            val update = nextMessage()
            assertEquals("session.update", update.type)
            val tools = update.getValue("session").jsonObject.getValue("tools").jsonArray
            assertEquals(JsonArray(DeskTools.specs(DeskToolSet.User)), tools)
            assertFalse(connecting.isCompleted)
            // event_id identifies this server event; it need not echo the request ID.
            peer().send("""{"type":"session.updated","event_id":"server-generated-update-event"}""")
            val session = withTimeout(3_000) { connecting.await() }
            assertTrue(session.sendGreeting("你好"))
            assertEquals("speech_text_buffer.commit", nextMessage().type)
            // 前台汇报走同一条路,原样念出代码拼好的一句话。
            assertTrue(session.sendNotice("订日料那件，办好了"))
            val notice = nextMessage()
            assertEquals("speech_text_buffer.commit", notice.type)
            assertEquals("订日料那件，办好了", notice.getValue("text").jsonPrimitive.content)
            assertFalse(session.sendNotice(""))
            finish(session)
        }
    }

    @Test fun blankPromptStillCarriesTheDeskPrompt() = runBlocking {
        fixture {
            val session = client.connect(credentials, VolcRealtimeConfig(agentTools = true))
            val create = nextMessage()
            assertEquals("session.create", create.type)
            val instructions = create.getValue("session").jsonObject.getValue("instructions").jsonPrimitive.content
            assertEquals(DeskPrompt.user(spoken = true), instructions)
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
            assertEquals(listOf("send_dtmf") + DeskTools.specs(DeskToolSet.User).map { it.getValue("name").jsonPrimitive.content }, names)
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

    @Test fun dispatchCallsArriveWithFullLengthWordsAndGetResults() = runBlocking {
        fixture {
            val session = connectAgent()
            val events = Channel<VolcRealtimeEvent>(32)
            val collector = async { session.events.collect { events.send(it) } }
            // Longer than the 256-char DTMF argument cap, well within the 4096-char utterance cap.
            val utterance = "把这段话完整交给任务系统执行" + "，一字不落".repeat(500)
            peer().send(functionFrame(item("call-1", DeskTools.DISPATCH, """{"words":"$utterance","title":"转交"}""")))
            val call = nextEvent(events) as VolcRealtimeEvent.FunctionCall
            assertEquals("call-1", call.callId)
            assertEquals(DeskTools.DISPATCH, call.name)
            assertEquals(utterance, call.arguments.getValue("words").jsonPrimitive.content)
            assertTrue(session.sendToolResult("call-1", buildJsonObject { put("ok", true); put("text", "已转交") }))
            val returned = nextMessage()
            assertEquals("conversation.item.create", returned.type)
            val item = returned.getValue("items").jsonArray.single().jsonObject
            assertEquals("call-1", item.getValue("call_id").jsonPrimitive.content)
            finish(session)
            withTimeout(3_000) { collector.await() }
        }
    }

    @Test fun oversizedOrMalformedDeskArgumentsAreAnsweredWithErrors() = runBlocking {
        fixture {
            val session = connectAgent()
            val events = Channel<VolcRealtimeEvent>(32)
            val collector = async { session.events.collect { events.send(it) } }
            val cases = listOf(
                item("huge", DeskTools.DISPATCH, """{"words":"${"字".repeat(4_200)}","title":"x"}"""),
                item("empty", DeskTools.DISPATCH, """{"words":"","title":"x"}"""),
                item("missing", DeskTools.ANSWER, """{"words":"七点"}"""),
                item("extra", DeskTools.DISPATCH, """{"words":"帮我订个闹钟","title":"x","foo":"x"}"""),
                item("number", DeskTools.CANCEL, """{"task_id":1}"""),
                item("old", "mika_request", """{"utterance":"帮我订个闹钟"}"""),
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
