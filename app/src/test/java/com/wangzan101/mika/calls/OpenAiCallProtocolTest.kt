package com.wangzan101.mika.calls

import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class OpenAiCallProtocolTest {
    @Test fun streamingConversionIsIndependentOfChunkBoundaries() {
        val input = ByteArray(640) { (it * 17).toByte() }
        val whole = CallPcm16To24().convert(input, input.size)
        val stream = CallPcm16To24()
        val pieces = input.toList().chunked(10).flatMap {
            stream.convert(it.toByteArray(), it.size).toList()
        }
        assertEquals(960, whole.size)
        assertArrayEquals(whole, pieces.toByteArray())
        assertArrayEquals(byteArrayOf(10, 0, 30, 0, 50, 0),
            CallPcm16To24().convert(byteArrayOf(30, 0, 60, 0), 4))
    }

    @Test fun openAiStringErrorCodesUseTheExistingCredentialAndQuotaMessages() {
        fun kind(code: String) = OpenAiCallProtocol.errorKind(buildJsonObject { put("code", code) })
        assertEquals(VolcRealtimeException.Kind.AUTH, kind("invalid_api_key"))
        assertEquals(VolcRealtimeException.Kind.RATE_LIMITED, kind("insufficient_quota"))
        assertEquals(VolcRealtimeException.Kind.PROVIDER, kind("unknown"))
    }

    @Test fun phoneSessionUsesOpenAiHandshakeAudioTranscriptsAndTools() = runBlocking {
        val server = MockWebServer()
        val socket = CompletableDeferred<WebSocket>()
        val messages = Channel<JsonObject>(16)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket.complete(webSocket)
                webSocket.send("""{"type":"session.created"}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                messages.trySend(Json.parseToJsonElement(text).jsonObject)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
        }))
        server.start()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("api.openai.com", request.url.host)
            chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath + "?" + request.url.encodedQuery)).build())
        }.build()
        try {
            withTimeout(6000) {
                val connection = async(Dispatchers.IO) {
                    VolcRealtimeClient(http, OpenAiCallProtocol.ENDPOINT).connect(
                        VolcRealtimeCredentials(apiKey = "test-only"),
                        VolcRealtimeConfig("hello", "marin", "gpt-realtime", true, CallVoiceProtocol.OPENAI_REALTIME))
                }
                val peer = socket.await()
                val update = messages.receive()
                assertEquals("session.update", update["type"]!!.jsonPrimitive.content)
                val settings = update["session"]!!.jsonObject
                assertEquals("realtime", settings["type"]!!.jsonPrimitive.content)
                assertEquals("24000", settings["audio"]!!.jsonObject["input"]!!.jsonObject["format"]!!.jsonObject["rate"]!!.jsonPrimitive.content)
                assertNotNull(settings["tools"])
                assertFalse(connection.isCompleted)
                peer.send("""{"type":"session.updated"}""")
                val session = connection.await()
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("/v1/realtime?model=gpt-realtime", request.path)
                assertEquals("Bearer test-only", request.getHeader("Authorization"))
                assertNull(request.getHeader("X-Api-Key"))
                assertTrue(session.setMuted(true))
                assertFalse(session.sendPcm16(ByteArray(640)))
                assertTrue(session.setMuted(false))
                assertTrue(session.sendGreeting("hello"))
                assertEquals("response.create", messages.receive()["type"]!!.jsonPrimitive.content)
                assertTrue(session.sendPcm16(ByteArray(640)))
                assertEquals(960, Base64.getDecoder().decode(messages.receive()["audio"]!!.jsonPrimitive.content).size)
                peer.send("""{"type":"input_audio_buffer.speech_started","item_id":"u"}""")
                peer.send("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"u","transcript":"hello"}""")
                peer.send("""{"type":"response.output_audio_transcript.delta","response_id":"r","delta":"OK"}""")
                peer.send("""{"type":"response.output_audio.delta","response_id":"r","item_id":"answer","content_index":0,"delta":"YWI="}""")
                peer.send("""{"type":"response.done","response":{"id":"r","status":"completed","output":[{"type":"function_call","name":"send_dtmf","call_id":"c","arguments":"{\"digits\":\"1\"}"}]}}""")
                val observed = Channel<VolcRealtimeEvent>(16)
                val collector = launch { session.events.collect { observed.send(it) } }
                val events = List(7) { observed.receive() }
                assertEquals(VolcRealtimeEvent.UserSpeaking, events[0])
                assertEquals("hello", (events[1] as VolcRealtimeEvent.UserTranscript).text)
                assertEquals(VolcRealtimeEvent.AssistantTranscript("OK"), events[3])
                assertArrayEquals("ab".toByteArray(), (events[5] as VolcRealtimeEvent.Audio).pcm)
                assertEquals("c", (events[6] as VolcRealtimeEvent.FunctionCall).callId)
                assertTrue(session.sendToolResult("c", buildJsonObject { put("ok", true) }))
                assertEquals("function_call_output", messages.receive()["item"]!!.jsonObject["type"]!!.jsonPrimitive.content)
                assertEquals("response.create", messages.receive()["type"]!!.jsonPrimitive.content)
                // A result can finish after the caller has interrupted its originating turn.
                peer.send("""{"type":"response.done","response":{"id":"second","status":"completed","output":[{"type":"function_call","name":"send_dtmf","call_id":"c2","arguments":"{\"digits\":\"2\"}"}]}}""")
                assertEquals("c2", (observed.receive() as VolcRealtimeEvent.FunctionCall).callId)
                peer.send("""{"type":"input_audio_buffer.speech_started","item_id":"next-caller-turn"}""")
                assertEquals(VolcRealtimeEvent.UserSpeaking, observed.receive())
                assertTrue(session.sendToolResult("c2", buildJsonObject { put("ok", true) }))
                assertEquals("c2", messages.receive()["item"]!!.jsonObject["call_id"]!!.jsonPrimitive.content)
                assertTrue(session.sendPcm16(ByteArray(640)))
                assertEquals("input_audio_buffer.append", messages.receive()["type"]!!.jsonPrimitive.content)
                session.close()
                session.awaitClosed()
                collector.join()
            }
        } finally {
            server.shutdown()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
