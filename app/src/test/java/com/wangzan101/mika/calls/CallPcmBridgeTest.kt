package com.wangzan101.mika.calls

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallPcmBridgeTest {
    @Test fun `idle speech and duplicate interruption do not flush or stop following spoken replies`() = runBlocking {
        val session = VolcRealtimeSession(VolcRealtimeConfig(), closeTimeoutMs = 100L)
        val socket = FakeSocket()
        val playback = Channel<ByteArray>(8)
        val capture = BlockingCapture()
        var flushes = 0
        val bridge = CallPcmBridge(capture, object : OutputStream() {
            override fun write(value: Int) = error("PCM should be written as a block")
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                check(playback.trySend(bytes.copyOfRange(offset, offset + count)).isSuccess)
            }
        }) { flushes++; true }
        session.listener.onOpen(socket, Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1)
            .code(101).message("Switching Protocols").build())
        fun receiveFrame(frame: String) = session.listener.onMessage(socket, frame)
        fun callerTurn(id: String) = receiveFrame("""{"type":"conversation.item.input_audio_transcription.started","item_id":"$id"}""")
        fun finishCallerTurn(id: String) = receiveFrame("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"$id","transcript":"还有一个问题"}""")
        fun reply(id: String, delta: String) {
            receiveFrame("""{"type":"response.output_audio.started","response_id":"$id"}""")
            receiveFrame("""{"type":"response.output_audio.delta","response_id":"$id","delta":"$delta"}""")
        }
        receiveFrame("""{"type":"session.created"}""")
        val running = launch(start = CoroutineStart.UNDISPATCHED) {
            bridge.run(session, "", counters = null, onTranscript = { _, _ -> })
        }
        try {
            withTimeout(4_000L) {
                val flushCounts = mutableListOf<Int>()
                callerTurn("turn1")
                finishCallerTurn("turn1")
                reply("reply1", "AQI=")
                assertArrayEquals(byteArrayOf(1, 2), playback.receive())
                flushCounts += flushes

                callerTurn("turn2")
                receiveFrame("""{"type":"response.canceled"}""")
                finishCallerTurn("turn2")
                reply("reply2", "AwQ=")
                assertArrayEquals(byteArrayOf(3, 4), playback.receive())
                flushCounts += flushes

                // A standalone cancellation while audio exists must still clear playback.
                receiveFrame("""{"type":"response.canceled"}""")
                callerTurn("turn3")
                finishCallerTurn("turn3")
                reply("reply3", "BQY=")
                assertArrayEquals(byteArrayOf(5, 6), playback.receive())
                flushCounts += flushes
                assertEquals("One paired flush per interruption with audio, none with an empty epoch", listOf(0, 2, 4), flushCounts)
            }
        } finally {
            running.cancelAndJoin()
            session.close()
            playback.close()
        }
    }

    @Test fun `OpenAI interruption truncates only the unheard part using playback head before flush`() = runBlocking {
        val session = VolcRealtimeSession(VolcRealtimeConfig(protocol = CallVoiceProtocol.OPENAI_REALTIME), 100)
        val truncated = CompletableDeferred<String>()
        val socket = FakeSocket { text ->
            if (text.contains("conversation.item.truncate")) truncated.complete(text)
        }
        session.listener.onOpen(socket, Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1)
            .code(101).message("Switching Protocols").build())
        fun receive(frame: String) = session.listener.onMessage(socket, frame)
        receive("""{"type":"session.created"}""")
        receive("""{"type":"session.updated"}""")
        var flushes = 0
        var headReads = 0
        val bridge = CallPcmBridge(BlockingCapture(), OutputStream.nullOutputStream()) { flushes++; true }
        val running = launch(start = CoroutineStart.UNDISPATCHED) {
            bridge.run(session, "", null, { _, _ -> }, playedOutputFrames = {
                headReads++
                assertEquals("sample before Android resets its playback head", 0, flushes)
                240L + 120L
            })
        }
        try {
            val audio = Base64.getEncoder().encodeToString(ByteArray(480)) // 240 frames = 10 ms
            for (item in listOf("heard", "interrupted")) {
                receive("""{"type":"response.output_audio.delta","response_id":"r","item_id":"$item","content_index":1,"delta":"$audio"}""")
                receive("""{"type":"response.output_audio.done","response_id":"r"}""")
            }
            // The server finished both items before the phone finished playing the second.
            receive("""{"type":"input_audio_buffer.speech_started","item_id":"caller"}""")
            val frame = Json.parseToJsonElement(withTimeout(4000) { truncated.await() }).jsonObject
            assertEquals("interrupted", frame["item_id"]!!.jsonPrimitive.content)
            assertEquals("1", frame["content_index"].toString())
            assertEquals("5", frame["audio_end_ms"].toString())
            assertEquals(1, headReads)
            assertEquals(2, flushes)
        } finally {
            running.cancelAndJoin()
            session.close()
        }
    }

    @Test fun `late history is archived but known unfinished input prevents premature hangup`() = runBlocking {
      for (knownPrior in listOf(false, true)) {
        val session = VolcRealtimeSession(VolcRealtimeConfig(), closeTimeoutMs = 100L)
        val socket = FakeSocket()
        val queryStarted = CompletableDeferred<Unit>()
        val queryAnswer = CompletableDeferred<Boolean>()
        val lateArchived = CompletableDeferred<Unit>()
        val history = mutableListOf<String>()
        val liveFinals = mutableListOf<String>()
        var committed = false
        val bridge = CallPcmBridge(BlockingCapture(), object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, count: Int) = Unit
        }) { true }
        session.listener.onOpen(socket, Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1)
            .code(101).message("Switching Protocols").build())
        fun receive(frame: String) = session.listener.onMessage(socket, frame)
        receive("""{"type":"session.created"}""")
        val running = launch(start = CoroutineStart.UNDISPATCHED) {
            bridge.run(session, "", counters = null,
                onTranscript = { role, text ->
                    if (role == "caller") history += text
                    if (text == "迟到的第一句") lateArchived.complete(Unit)
                },
                onFinalTranscript = { role, text -> if (role == "caller") liveFinals += text },
                awaitFarewellDrain = { queryStarted.complete(Unit); queryAnswer.await() },
                commitFarewell = { committed = true; true })
        }
        try {
            if (knownPrior) receive("""{"type":"conversation.item.input_audio_transcription.started","item_id":"u1"}""")
            receive("""{"type":"conversation.item.input_audio_transcription.started","item_id":"u2"}""")
            receive("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"u2","transcript":"谢谢，再见"}""")
            receive("""{"type":"response.output_audio.started","response_id":"reply2"}""")
            receive("""{"type":"response.output_audio.delta","response_id":"reply2","delta":"AQI="}""")
            receive("""{"type":"response.output_text.delta","response_id":"reply2","delta":"好的，再见"}""")
            receive("""{"type":"response.output_text.done","response_id":"reply2"}""")
            receive("""{"type":"response.output_audio.done","response_id":"reply2"}""")
            if (!knownPrior) withTimeout(3_000L) { queryStarted.await() }
            receive("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"u1","transcript":"迟到的第一句"}""")
            withTimeout(3_000L) { lateArchived.await() }
            kotlinx.coroutines.yield()
            if (knownPrior) {
                assertFalse("Do not hang up before earlier recognized speech is finalized", queryStarted.isCompleted)
                assertFalse(committed)
                assertTrue(running.isActive)
            } else {
                queryAnswer.complete(true)
                withTimeout(3_000L) { running.join() }
                assertTrue(committed)
            }
            assertEquals(listOf("谢谢，再见", "迟到的第一句"), history)
            assertEquals(history, liveFinals)
        } finally {
            running.cancelAndJoin()
            session.close()
        }
    }
      }

    @Test fun `caller resuming during the final playback query cannot commit a stale hangup`() = runTest {
        val playbackQuery = CompletableDeferred<Boolean>()
        var committed = false
        val waiter = launch {
            // A Binder operation can still return a positive result after speech cancels the waiter.
            if (withContext(NonCancellable) { playbackQuery.await() }) {
                commitDrainedFarewell { committed = true; true }
            }
        }
        runCurrent()
        waiter.cancel()
        playbackQuery.complete(true)
        waiter.join()
        assertFalse(committed)
    }

    @Test fun `closing a blocked capture cannot replace the bridge deadline with an IO fault`() = runTest {
        val capture = BlockingCapture()
        val timed = async {
            withTimeoutOrNull(1000L) {
                coroutineScope {
                    val worker = launch(Dispatchers.IO) { callPipeIo { capture.read() } }
                    try {
                        awaitCancellation()
                    } finally {
                        worker.cancel()
                        capture.close()
                    }
                }
            }
        }
        try {
            runCurrent()
            assertTrue("Capture was blocked before reaching the timeout", capture.entered.await(5, TimeUnit.SECONDS))
            advanceTimeBy(1000L)
            runCurrent()
            assertNull("The Telecom duration-limit branch relies on a null timeout result", timed.await())
            assertEquals("Close woke up the blocked pipe read", 0L, capture.closeFault.count)
        } finally {
            capture.close()
        }
    }

    @Test fun `real IO failure still reaches the active call runtime`() = runTest {
        val fault = IOException("pipe failed while active")
        val result = runCatching { callPipeIo { throw fault } }
        assertEquals(fault, result.exceptionOrNull())
    }

    @Test fun `provider burst queues by PCM duration rather than packet count and preserves order`() = runTest {
        val queue = CallPlaybackQueue()
        // A previous successful Direct call averaged 389 ms per provider audio event.
        val packets = List(20) { index -> ByteArray(18_672) { (index + it).toByte() } }
        packets.forEachIndexed { index, packet -> assertEquals((index + 1) * packet.size, queue.offer(packet)) }
        val expected = packets.fold(ByteArray(0)) { acc, packet -> acc + packet }
        val played = ByteArray(expected.size)
        val buffer = ByteArray(CallPlaybackQueue.WRITE_BYTES)
        var position = 0
        while (position < played.size) {
            val next = checkNotNull(queue.take(buffer))
            assertTrue(queue.isCurrent(next))
            assertTrue(next.byteCount <= CallPlaybackQueue.WRITE_BYTES)
            buffer.copyInto(played, position, 0, next.byteCount)
            position += next.byteCount
            queue.release(next)
        }
        assertArrayEquals(expected, played)
        queue.close()
    }

    @Test fun `healthy real time sink survives repeated bursts past thirty seconds`() = runTest {
        val queue = CallPlaybackQueue()
        val burstPacket = ByteArray(18_672) // 389 ms at the actual 24 kHz PCM16 format.
        val sink = ByteArray(CallPlaybackQueue.WRITE_BYTES)
        var written = 0L
        val writer = launch {
            while (true) {
                val block = queue.take(sink) ?: break
                delay((block.byteCount * 1_000L + 47_999L) / 48_000L)
                written += block.byteCount
                queue.release(block)
            }
        }
        try {
            repeat(12) {
                // Six deltas land together; each reply drains at media rate before the next burst.
                repeat(6) { assertTrue(queue.offer(burstPacket) != null) }
                advanceTimeBy(3_000L)
                runCurrent()
            }
            assertEquals(36_000L, currentTime)
            assertEquals(72L * burstPacket.size, written)
        } finally {
            queue.close()
            writer.join()
        }
    }

    @Test fun `blocked writer counts against cap and interruption clears only stale playback`() = runTest {
        val blockBytes = CallPlaybackQueue.WRITE_BYTES
        val queue = CallPlaybackQueue(blockBytes * 2)
        val sink = ByteArray(blockBytes)
        val stale = ByteArray(blockBytes * 2) { 12 }
        assertEquals(stale.size, queue.offer(stale))
        val blocked = checkNotNull(queue.take(sink)) // Deliberately do not release: output pipe is blocked.
        assertNull(queue.offer(ByteArray(2))) // In-flight bytes still count, so rejection is bounded.
        queue.invalidate()
        assertFalse(queue.isCurrent(blocked))
        val nextPcm = ByteArray(blockBytes) { 42 }
        assertEquals(nextPcm.size, queue.offer(nextPcm))
        queue.release(blocked)
        val next = checkNotNull(queue.take(sink))
        assertTrue(queue.isCurrent(next))
        assertArrayEquals(nextPcm, sink)
        queue.release(next)
        queue.close()
    }

    @Test fun `a silent opener does not mistake absent playback for a dead bridge`() {
        assertFalse(callAudioHasStarted("", downBytes = 0, upBytes = 0))
        assertTrue(callAudioHasStarted("", downBytes = 640, upBytes = 0))
        assertFalse(callAudioHasStarted("开场白", downBytes = 640, upBytes = 0))
        assertTrue(callAudioHasStarted("开场白", downBytes = 640, upBytes = 960))
    }

    private class BlockingCapture : InputStream() {
        val entered = CountDownLatch(1)
        val closeFault = CountDownLatch(1)
        private val closed = CountDownLatch(1)

        override fun read(): Int {
            entered.countDown()
            check(closed.await(5, TimeUnit.SECONDS)) { "Test did not close the blocked stream" }
            closeFault.countDown()
            throw IOException("pipe closed")
        }

        override fun close() { closed.countDown() }
    }

    private class FakeSocket(private val sent: (String) -> Unit = {}) : WebSocket {
        override fun request(): Request = Request.Builder().url("https://localhost/").build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean { sent(text); return true }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
    }
}
