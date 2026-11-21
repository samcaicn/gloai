package com.wangzan101.mika.calls

import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolcRealtimeClientTest {
    @Test fun fullDuplexWaitsForSessionAckAndSuppressesInterruptedPcm() = runBlocking {
        withServer { server, http ->
            val messages = Channel<JsonObject>(16)
            val serverSocket = CompletableDeferred<WebSocket>()
            val protocolClosed = CompletableDeferred<Unit>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { serverSocket.complete(webSocket) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = Json.parseToJsonElement(text).jsonObject
                    check(messages.trySend(frame).isSuccess)
                    if (frame.type == "session.close") webSocket.send("""{"type":"session.closed"}""")
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { protocolClosed.complete(Unit) }
            }))
            val transport = transport(server, http)
            val connect = async { transport.connect(VolcRealtimeCredentials(apiKey = "test-key"), VolcRealtimeConfig("Mika speaks plainly.")) }
            val socket = withTimeout(3_000) { serverSocket.await() }
            val create = withTimeout(3_000) { messages.receive() }
            assertEquals("session.create", create.type)
            assertFalse(connect.isCompleted)
            assertTrue(messages.tryReceive().isFailure)
            val request = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("/api/v3/duplex/realtime/dialogue", request.path)
            assertEquals("test-key", request.getHeader("X-Api-Key"))
            assertNull(request.getHeader("X-Api-Resource-Id"))
            assertNull(request.getHeader("X-Api-App-Id"))
            assertNull(request.getHeader("Authorization"))
            val sessionConfig = create.getValue("session").jsonObject
            assertEquals("1.2.6.1", sessionConfig.getValue("model").jsonPrimitive.content)
            assertEquals("Mika speaks plainly.", sessionConfig.getValue("instructions").jsonPrimitive.content)
            val audioConfig = sessionConfig.getValue("audio").jsonObject
            val input = audioConfig.getValue("input").jsonObject.getValue("format").jsonObject
            val output = audioConfig.getValue("output").jsonObject
            assertEquals("pcm", input.getValue("type").jsonPrimitive.content)
            assertEquals(16_000, input.getValue("rate").jsonPrimitive.int)
            assertEquals("pcm_s16le", output.getValue("format").jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(24_000, output.getValue("format").jsonObject.getValue("rate").jsonPrimitive.int)
            assertEquals("zh_female_vv_jupiter_bigtts", output.getValue("voice").jsonPrimitive.content)

            socket.send("""{"type":"session.created","session":{"id":"mock-dialog"}}""")
            val session = withTimeout(3_000) { connect.await() }
            val observed = Channel<VolcRealtimeEvent>(32)
            val collect = async { session.events.collect { observed.send(it) } }

            val config = CallConfig(greeting = "你好")
            assertTrue(session.sendGreeting(CallPolicy.spokenGreeting(config.greeting, hasTask = false)))
            val greet = withTimeout(3_000) { messages.receive() }
            assertEquals("speech_text_buffer.commit", greet.type)
            assertEquals("你好", greet.getValue("text").jsonPrimitive.content)
            val pcm = ByteArray(VolcRealtimeClient.INPUT_CHUNK_BYTES + 4) { it.toByte() }
            assertFalse(session.sendPcm16(pcm, 3))
            assertTrue(session.sendPcm16(pcm, VolcRealtimeClient.INPUT_CHUNK_BYTES))
            val mic = withTimeout(3_000) { messages.receive() }
            assertEquals("input_audio_buffer.append", mic.type)
            assertArrayEquals(pcm.copyOf(640), Base64.getDecoder().decode(mic.getValue("audio").jsonPrimitive.content))
            assertTrue(session.setMuted(true))
            assertFalse(session.sendPcm16(pcm))
            assertEquals("input_audio_mute.commit", withTimeout(3_000) { messages.receive() }.type)
            assertTrue(session.setMuted(false))
            assertEquals("input_audio_unmute.commit", withTimeout(3_000) { messages.receive() }.type)

            listOf(
                """{"type":"response.output_audio.started","response_id":"old"}""",
                """{"type":"response.output_audio.delta","response_id":"old","delta":"AQI="}""",
                """{"type":"conversation.item.input_audio_transcription.started","item_id":"user-1"}""",
                """{"type":"response.output_audio.delta","response_id":"old","delta":"AgI="}""",
                """{"type":"conversation.item.input_audio_transcription.delta","item_id":"user-1","delta":"你好"}""",
                """{"type":"conversation.item.input_audio_transcription.delta","item_id":"user-1","delta":"你好呀"}""",
                """{"type":"conversation.item.input_audio_transcription.completed","item_id":"user-1","transcript":"你好呀。"}""",
                """{"type":"response.output_audio.started","response_id":"new"}""",
                // The previous response's cancel acknowledgment may follow the replacement's start.
                """{"type":"response.canceled","response_id":"old"}""",
                """{"type":"response.output_audio.delta","response_id":"old","delta":"AgI="}""",
                """{"type":"response.output_audio.delta","response_id":"new","delta":"AwQ="}""",
                """{"type":"response.output_text.delta","delta":"您好"}""",
                """{"type":"response.output_text.done","text":"您好"}""",
                """{"type":"response.output_audio.done","response_id":"new"}""",
                """{"type":"response.canceled"}""",
            ).forEach { assertTrue(socket.send(it)) }
            withTimeout(3_000) {
                assertEquals(VolcRealtimeEvent.AssistantAudioStarted, observed.receive())
                assertArrayEquals(byteArrayOf(1, 2), (observed.receive() as VolcRealtimeEvent.Audio).pcm)
                assertEquals(VolcRealtimeEvent.UserSpeaking, observed.receive())
                assertEquals(VolcRealtimeEvent.UserTranscript("你好", false), observed.receive())
                assertEquals(VolcRealtimeEvent.UserTranscript("你好呀", false), observed.receive())
                assertEquals(VolcRealtimeEvent.UserTranscript("你好呀。", true), observed.receive())
                assertEquals(VolcRealtimeEvent.UserSpeechEnded, observed.receive())
                assertEquals(VolcRealtimeEvent.AssistantAudioStarted, observed.receive())
                assertArrayEquals(byteArrayOf(3, 4), (observed.receive() as VolcRealtimeEvent.Audio).pcm)
                assertEquals(VolcRealtimeEvent.AssistantTranscript("您好"), observed.receive())
                assertEquals(VolcRealtimeEvent.AssistantTextEnded, observed.receive())
                assertEquals(VolcRealtimeEvent.AssistantAudioEnded, observed.receive())
                assertEquals(VolcRealtimeEvent.AssistantInterrupted, observed.receive())
            }

            session.close()
            session.close()
            assertFalse(session.sendPcm16(pcm))
            assertFalse(session.sendGreeting("又来了"))
            withTimeout(3_000) {
                assertEquals("session.close", messages.receive().type)
                protocolClosed.await()
                session.awaitClosed()
                assertEquals(VolcRealtimeEvent.Closed, observed.receive())
                collect.await()
            }
            assertTrue(messages.tryReceive().isFailure)
        }
    }

    @Test fun userTurnObservationsDistinguishCommitEmptyAndFailureWithoutSendingCommands() = runBlocking {
        withServer { server, http ->
            val prefix = "conversation.item.input_audio_transcription"
            val limit = 16_384
            val longText = "再见" + "啊".repeat(limit)
            val sent = Channel<String>(8)
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val type = Json.parseToJsonElement(text).jsonObject.type
                    check(sent.trySend(type).isSuccess)
                    when (type) {
                        "session.create" -> listOf(
                            """{"type":"session.created"}""",
                            """{"type":"$prefix.delta","delta":"再见"}""",
                            """{"type":"input_audio_buffer.committed"}""",
                            """{"type":"$prefix.completed"}""",
                            """{"type":"$prefix.completed","transcript":"$longText"}""",
                            """{"type":"$prefix.completed","transcript":"   "}""",
                            """{"type":"$prefix.delta","delta":"再见"}""",
                            """{"type":"$prefix.failed"}""",
                            """{"type":"$prefix.completed","text":"再见"}""",
                        ).forEach { webSocket.send(it) }
                        "session.close" -> webSocket.send("""{"type":"session.closed"}""")
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                }
            }))
            val expected = listOf(
                VolcRealtimeEvent.UserTranscript("再见", isFinal = false),
                VolcRealtimeEvent.InputAudioCommitted,
                VolcRealtimeEvent.UserTranscript("再见", isFinal = true),
                VolcRealtimeEvent.UserSpeechEnded,
                VolcRealtimeEvent.UserTranscript(longText.take(limit), isFinal = true, isCompleteFinal = false),
                VolcRealtimeEvent.UserSpeechEnded,
                VolcRealtimeEvent.UserSpeechEnded,
                VolcRealtimeEvent.UserTranscriptionEmpty,
                VolcRealtimeEvent.UserTranscript("再见", isFinal = false),
                VolcRealtimeEvent.UserSpeechEnded,
                VolcRealtimeEvent.UserTranscriptionFailed,
                VolcRealtimeEvent.UserTranscript("再见", isFinal = true),
                VolcRealtimeEvent.UserSpeechEnded,
            )
            val session = transport(server, http).connect(VolcRealtimeCredentials(apiKey = "test-key"))
            try {
                assertEquals(expected, withTimeout(3_000) { session.events.take(expected.size).toList() })
            } finally {
                session.close()
                withTimeout(3_000) { session.awaitClosed() }
            }
            assertEquals("session.create", sent.tryReceive().getOrThrow())
            assertEquals("session.close", sent.tryReceive().getOrThrow())
            assertTrue(sent.tryReceive().isFailure)
        }
    }

    @Test fun legacyAuthenticationAndMissingCloseAckAreBounded() = runBlocking {
        withServer { server, http ->
            val closeSent = CompletableDeferred<Unit>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (Json.parseToJsonElement(text).jsonObject.type) {
                        "session.create" -> webSocket.send("""{"type":"session.created"}""")
                        "session.close" -> closeSent.complete(Unit)
                    }
                }
            }))
            val session = transport(server, http, closeTimeoutMs = 250).connect(
                VolcRealtimeCredentials(appId = "test-app", accessKey = "test-token"),
            )
            val request = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("test-app", request.getHeader("X-Api-App-Id"))
            assertEquals("test-token", request.getHeader("X-Api-Access-Key"))
            assertEquals("volc.speech.dialog", request.getHeader("X-Api-Resource-Id"))
            assertNotNull(request.getHeader("X-Api-App-Key"))
            UUID.fromString(request.getHeader("X-Api-Request-Id"))
            assertNull(request.getHeader("X-Api-Key"))
            session.close()
            withTimeout(2_000) {
                closeSent.await()
                session.awaitClosed()
                assertEquals(listOf(VolcRealtimeEvent.Closed), session.events.toList())
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun providerErrorsAreRedactedAndHandshakeTimeoutNeverRetries() = runBlocking {
        withServer { server, http ->
            val credentials = VolcRealtimeCredentials(apiKey = "test-private-key", appId = "test-private-app", accessKey = "test-private-token")
            assertFalse(credentials.toString().contains("test-private"))
            server.enqueue(MockResponse().setResponseCode(403).setBody("test-private-upstream"))
            val rejected = runCatching { transport(server, http).connect(credentials) }.exceptionOrNull() as VolcRealtimeException
            assertEquals(VolcRealtimeException.Kind.AUTH, rejected.kind)
            assertEquals(403L, rejected.code)
            assertFalse(rejected.toString().contains("test-private"))
            val rejectedRequest = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("test-private-key", rejectedRequest.getHeader("X-Api-Key"))
            assertNull(rejectedRequest.getHeader("X-Api-Access-Key"))

            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            val timedOut = runCatching {
                transport(server, http, connectTimeoutMs = 250).connect(credentials)
            }.exceptionOrNull() as VolcRealtimeException
            assertEquals(VolcRealtimeException.Kind.TIMEOUT, timedOut.kind)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun redirectNeverForwardsApiKey() = runBlocking {
        withServer { server, http ->
            MockWebServer().use { other ->
                server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/other")))
                val failure = runCatching {
                    transport(server, http).connect(VolcRealtimeCredentials(apiKey = "test-key"))
                }.exceptionOrNull() as VolcRealtimeException
                assertEquals(307L, failure.code)
                assertEquals(0, other.requestCount)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun aStalledEventConsumerEndsTheSessionWithoutReplayingBufferedAudio() = runBlocking {
        withServer { server, http ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (Json.parseToJsonElement(text).jsonObject.type != "session.create") return
                    webSocket.send("""{"type":"session.created"}""")
                    webSocket.send("""{"type":"response.output_audio.started","response_id":"resp"}""")
                    repeat(40) {
                        webSocket.send("""{"type":"response.output_audio.delta","response_id":"resp","delta":"AQI="}""")
                    }
                }
            }))
            val session = transport(server, http).connect(VolcRealtimeCredentials(apiKey = "test-key"))
            withTimeout(3_000) { session.awaitClosed() }
            val terminal = withTimeout(3_000) { session.events.toList().single() } as VolcRealtimeEvent.Failed
            assertEquals(VolcRealtimeException.Kind.BACKPRESSURE, terminal.error.kind)
            assertFalse(session.sendPcm16(ByteArray(640)))
            assertEquals(1, server.requestCount)
        }
    }

    private val JsonObject.type get() = getValue("type").jsonPrimitive.content

    @Test fun asrSnapshotsReplaceProgressivePrefixesAndPreserveLegitimateRepeats() = runBlocking {
        // The protocol calls setQuestion for ASR delta, but
        // appendAnswer for assistant delta. These are revisable full snapshots.
        val observed = observeFrames(listOf(
            """{"type":"conversation.item.input_audio_transcription.started","item_id":"u1"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"u1","delta":"问"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"u1","delta":"问明天"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"u1","delta":"问明天几点开门"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"u1","delta":"问后天几点开门，不要不要预约"}""",
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"u1"}""",
            """{"type":"response.output_text.delta","delta":"好"}""",
            """{"type":"response.output_text.delta","delta":"好"}""",
        ))
        val transcripts = observed.filterIsInstance<VolcRealtimeEvent.UserTranscript>()
        assertEquals(listOf("问", "问明天", "问明天几点开门", "问后天几点开门，不要不要预约"),
            transcripts.filterNot { it.isFinal }.map { it.text })
        assertEquals(VolcRealtimeEvent.UserTranscript("问后天几点开门，不要不要预约", true), transcripts.last())
        assertEquals(listOf("好", "好"), observed.filterIsInstance<VolcRealtimeEvent.AssistantTranscript>().map { it.delta })
    }

    @Test fun asrUsesExpectedTextFieldPriorityAndCompletedPayload() = runBlocking {
        val observed = observeFrames(listOf(
            """{"type":"conversation.item.input_audio_transcription.started"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","text":"完整快照","delta":"不能追加"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","text":"","transcript":"修改后的快照"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","content":"最终快照"}""",
            """{"type":"conversation.item.input_audio_transcription.completed","delta":"最终完整定稿"}""",
        ))
        assertEquals(listOf("完整快照", "修改后的快照", "最终快照", "最终完整定稿"),
            observed.filterIsInstance<VolcRealtimeEvent.UserTranscript>().map { it.text })
        assertTrue(observed.filterIsInstance<VolcRealtimeEvent.UserTranscript>().last().isCompleteFinal)
    }

    @Test fun completedEventCannotAuthorizeTruncatedSnapshotOrExplicitlyBlankFinal() = runBlocking {
        val tooLong = "长".repeat(16_385)
        val observed = observeFrames(listOf(
            """{"type":"conversation.item.input_audio_transcription.started","item_id":"long"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"long","delta":"$tooLong"}""",
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"long"}""",
            """{"type":"conversation.item.input_audio_transcription.started","item_id":"blank"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"blank","delta":"我说完了，你去打"}""",
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"blank","text":" "}""",
        ))
        val finals = observed.filterIsInstance<VolcRealtimeEvent.UserTranscript>().filter { it.isFinal }
        assertEquals(2, finals.size)
        assertEquals(16_384, finals.first().text.length)
        assertTrue(finals.none { it.isCompleteFinal })
    }

    @Test fun snapshotAuthorizationNeedsCurrentCompletedEventAndNotJustPartialText() = runBlocking {
        val observed = observeFrames(listOf(
            """{"type":"conversation.item.input_audio_transcription.started","item_id":"old"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"old","delta":"我说完了，你去打"}""",
            """{"type":"conversation.item.input_audio_transcription.started","item_id":"current"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"current","delta":"先别打，还有一个限制"}""",
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"old"}""",
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"current"}""",
        ))
        val transcripts = observed.filterIsInstance<VolcRealtimeEvent.UserTranscript>()
        assertFalse(transcripts.first().isFinal)
        val finals = transcripts.filter { it.isFinal }
        assertTrue(finals.all { it.isCompleteFinal })
        assertEquals(listOf(false, true), finals.map { it.isCurrentTurn })
        assertEquals(listOf("我说完了，你去打", "先别打，还有一个限制"), finals.map { it.text })
    }

    @Test fun lateUserFinalIsArchivedOnceWithoutConsumingTheCurrentTurn() = runBlocking {
        val observed = observeFrames(listOf(
            """{"type":"conversation.item.input_audio_transcription.started","item_id":"u1"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"u1","delta":"第一句"}""",
            """{"type":"conversation.item.input_audio_transcription.started","item_id":"u2"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"u2","delta":"第二句"}""",
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"u1","transcript":"第一句完成"}""",
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"u1","transcript":"第一句完成"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"u1","delta":"旧的增量"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"u2","delta":"第二句继续"}""",
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"u2"}""",
        ))
        assertEquals(listOf("第一句完成", "第二句继续"), observed.filterIsInstance<VolcRealtimeEvent.UserTranscript>().filter { it.isFinal }.map { it.text })
        assertEquals(1, observed.count { it == VolcRealtimeEvent.UserSpeechEnded })
        assertEquals(listOf(false, true), observed.filterIsInstance<VolcRealtimeEvent.UserTranscript>().filter { it.isFinal }.map { it.isCurrentTurn })
    }

    @Test fun lateUserFailureCannotClearTheCurrentItemsPartialText() = runBlocking {
        val observed = observeFrames(listOf(
            """{"type":"conversation.item.input_audio_transcription.started","item_id":"u1"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"u1","delta":"第一句"}""",
            """{"type":"conversation.item.input_audio_transcription.started","item_id":"u2"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"u2","delta":"第二句"}""",
            """{"type":"conversation.item.input_audio_transcription.failed","item_id":"u1"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"u2","delta":"第二句继续"}""",
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"u2"}""",
        ))
        assertEquals(listOf("第二句继续"), observed.filterIsInstance<VolcRealtimeEvent.UserTranscript>().filter { it.isFinal }.map { it.text })
        assertEquals(1, observed.count { it == VolcRealtimeEvent.UserSpeechEnded })
        assertEquals(1, observed.count { it == VolcRealtimeEvent.UserTranscriptionFailed })
    }

    @Test fun audioFromTwoInterruptionsAgoCannotReplaceTheThirdResponse() = runBlocking {
        val observed = observeFrames(listOf(
            """{"type":"response.output_audio.started","response_id":"r1"}""",
            """{"type":"response.output_audio.delta","response_id":"r1","delta":"AQI="}""",
            """{"type":"conversation.item.input_audio_transcription.started","item_id":"u1"}""",
            """{"type":"response.output_audio.started","response_id":"r2"}""",
            """{"type":"response.output_audio.delta","response_id":"r2","delta":"AwQ="}""",
            """{"type":"conversation.item.input_audio_transcription.started","item_id":"u2"}""",
            """{"type":"response.output_audio.started","response_id":"r3"}""",
            """{"type":"response.output_audio.delta","response_id":"r3","delta":"BQY="}""",
            """{"type":"response.output_audio.started","response_id":"r1"}""",
            """{"type":"response.output_audio.delta","response_id":"r1","delta":"Bwg="}""",
            """{"type":"response.canceled","response_id":"r1"}""",
            """{"type":"response.canceled","response_id":"r2"}""",
            """{"type":"response.output_audio.delta","response_id":"r3","delta":"CQo="}""",
            """{"type":"response.output_audio.done","response_id":"r3"}""",
        ))
        assertEquals(listOf(listOf<Byte>(1, 2), listOf<Byte>(3, 4), listOf<Byte>(5, 6), listOf<Byte>(9, 10)), observed.filterIsInstance<VolcRealtimeEvent.Audio>().map { it.pcm.toList() })
        assertEquals(3, observed.count { it == VolcRealtimeEvent.AssistantAudioStarted })
        assertFalse(observed.contains(VolcRealtimeEvent.AssistantInterrupted))
    }

    @Test fun retiredTextBeforeAudioStartsCannotFinishTheReplacementReply() = runBlocking {
        val observed = observeFrames(listOf(
            """{"type":"response.output_text.delta","response_id":"r1","delta":"旧开头"}""",
            """{"type":"conversation.item.input_audio_transcription.started","item_id":"u1"}""",
            """{"type":"response.output_text.delta","response_id":"r2","delta":"新答"}""",
            """{"type":"response.output_text.delta","response_id":"r1","delta":"再见"}""",
            """{"type":"response.output_text.done","response_id":"r1"}""",
            """{"type":"response.output_text.done","response_id":"r2"}""",
        ))
        assertEquals(listOf("旧开头", "新答"), observed.filterIsInstance<VolcRealtimeEvent.AssistantTranscript>().map { it.delta })
        assertEquals(1, observed.count { it == VolcRealtimeEvent.AssistantTextEnded })
    }

    @Test fun duplicateStartedFromAnOlderItemCannotReopenItOverCurrentSpeech() = runBlocking {
        val observed = observeFrames(listOf(
            """{"type":"conversation.item.input_audio_transcription.started","item_id":"u1"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"u1","delta":"第一句"}""",
            """{"type":"conversation.item.input_audio_transcription.started","item_id":"u2"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"u2","delta":"第二句"}""",
            """{"type":"conversation.item.input_audio_transcription.started","item_id":"u1"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"u2","delta":"第二句继续"}""",
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"u2"}""",
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"u1","transcript":"第一句完成"}""",
        ))
        assertEquals(2, observed.count { it == VolcRealtimeEvent.UserSpeaking })
        assertEquals(listOf(true, false), observed.filterIsInstance<VolcRealtimeEvent.UserTranscript>().filter { it.isFinal }.map { it.isCurrentTurn })
    }

    @Test fun anonymousRepeatedTurnsAndEmptyRecognitionRemainCompatible() = runBlocking {
        val observed = observeFrames(listOf(
            """{"type":"conversation.item.input_audio_transcription.started"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","delta":"你好"}""",
            """{"type":"conversation.item.input_audio_transcription.completed","text":"你好"}""",
            """{"type":"conversation.item.input_audio_transcription.started"}""",
            """{"type":"conversation.item.input_audio_transcription.failed"}""",
            """{"type":"conversation.item.input_audio_transcription.started"}""",
            """{"type":"conversation.item.input_audio_transcription.completed","text":" "}""",
            """{"type":"conversation.item.input_audio_transcription.started"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","delta":"你好"}""",
            """{"type":"conversation.item.input_audio_transcription.completed","text":"你好"}""",
            """{"type":"response.output_audio.started"}""",
            """{"type":"response.output_audio.delta","delta":"AQI="}""",
        ))
        val finals = observed.filterIsInstance<VolcRealtimeEvent.UserTranscript>().filter { it.isFinal }
        assertEquals(listOf("你好", "你好"), finals.map { it.text })
        assertTrue(finals.all { it.isCurrentTurn })
        assertEquals(1, observed.count { it == VolcRealtimeEvent.UserTranscriptionFailed })
        assertEquals(1, observed.count { it == VolcRealtimeEvent.UserTranscriptionEmpty })
        assertArrayEquals(byteArrayOf(1, 2), observed.filterIsInstance<VolcRealtimeEvent.Audio>().single().pcm)
    }

    @Test fun firstNewIdentifiedDeltaAdoptsAnonymousSpeechButParkedItemsCannot() = runBlocking {
        val observed = observeFrames(listOf(
            """{"type":"conversation.item.input_audio_transcription.started","item_id":"old"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"old","delta":"旧问题"}""",
            """{"type":"conversation.item.input_audio_transcription.started"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"old","delta":"旧后缀"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","delta":"当前前缀"}""",
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"new","delta":"当前前缀当前后缀"}""",
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"old","text":"旧问题完成"}""",
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"new"}""",
        ))
        val finals = observed.filterIsInstance<VolcRealtimeEvent.UserTranscript>().filter { it.isFinal }
        assertEquals(listOf("旧问题完成", "当前前缀当前后缀"), finals.map { it.text })
        assertEquals(listOf(false, true), finals.map { it.isCurrentTurn })
        assertFalse(observed.filterIsInstance<VolcRealtimeEvent.UserTranscript>().any { !it.isFinal && it.text.contains("旧后缀") })
        assertEquals(1, observed.count { it == VolcRealtimeEvent.UserSpeechEnded })
    }

    @Test fun authorizationCannotSkipAnOlderUnfinishedRestriction() = runBlocking {
        for (identified in listOf(true, false)) for (hasPartial in listOf(true, false)) {
            val id = if (identified) ",\"item_id\":\"restriction\"" else ""
            val frames = mutableListOf("""{"type":"conversation.item.input_audio_transcription.started"$id}""")
            if (hasPartial) frames += """{"type":"conversation.item.input_audio_transcription.delta"$id,"delta":"不要同意任何付费项目"}"""
            frames += """{"type":"conversation.item.input_audio_transcription.started","item_id":"authorization"}"""
            frames += """{"type":"conversation.item.input_audio_transcription.completed","item_id":"authorization","text":"我说完了，你去打"}"""
            val observed = observeFrames(frames)
            val authorization = observed.filterIsInstance<VolcRealtimeEvent.UserTranscript>().single { it.isFinal }
            val conversation = com.wangzan101.mika.calls.briefing.VoiceBriefingConversation(
                CallTaskBrief(goal = "询问服务价格"),
            )
            assertTrue("Unfinished restriction must prevent dialing; identified=$identified partial=$hasPartial",
                runCatching { conversation.accept(authorization) }.isFailure)
        }
    }

    @Test fun finalizedOrEmptyEarlierTurnDoesNotBlockLaterAuthorization() = runBlocking {
        for (text in listOf("不要同意任何付费项目", "")) {
            val observed = observeFrames(listOf(
                """{"type":"conversation.item.input_audio_transcription.started","item_id":"restriction"}""",
                """{"type":"conversation.item.input_audio_transcription.completed","item_id":"restriction","text":"$text"}""",
                """{"type":"conversation.item.input_audio_transcription.started","item_id":"authorization"}""",
                """{"type":"conversation.item.input_audio_transcription.completed","item_id":"authorization","text":"我说完了，你去打"}""",
            ))
            val authorization = observed.filterIsInstance<VolcRealtimeEvent.UserTranscript>().last()
            assertFalse(authorization.hasUnresolvedPriorTurn)
            val conversation = com.wangzan101.mika.calls.briefing.VoiceBriefingConversation(CallTaskBrief(goal = "询问服务价格"))
            assertNotNull(conversation.accept(authorization))
        }
    }

    private suspend fun observeFrames(frames: List<String>): List<VolcRealtimeEvent> {
        var result = emptyList<VolcRealtimeEvent>()
        withServer { server, http ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (Json.parseToJsonElement(text).jsonObject.type) {
                        "session.create" -> {
                            webSocket.send("""{"type":"session.created"}""")
                            frames.forEach { webSocket.send(it) }
                            webSocket.send("""{"type":"input_audio_buffer.committed"}""")
                        }
                        "session.close" -> webSocket.send("""{"type":"session.closed"}""")
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            }))
            val session = transport(server, http).connect(VolcRealtimeCredentials(apiKey = "test-key"))
            try {
                result = withTimeout(3_000) { session.events.takeWhile { it != VolcRealtimeEvent.InputAudioCommitted }.toList() }
            } finally {
                session.close()
                withTimeout(3_000) { session.awaitClosed() }
            }
        }
        return result
    }

    private fun transport(
        server: MockWebServer,
        http: OkHttpClient,
        connectTimeoutMs: Long = 3_000,
        closeTimeoutMs: Long = 3_000,
    ) = VolcRealtimeClient(http, server.url("/api/v3/duplex/realtime/dialogue").toString(), connectTimeoutMs, closeTimeoutMs)

    private suspend fun withServer(block: suspend (MockWebServer, OkHttpClient) -> Unit) {
        val server = MockWebServer()
        val http = OkHttpClient()
        try {
            block(server, http)
        } finally {
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
