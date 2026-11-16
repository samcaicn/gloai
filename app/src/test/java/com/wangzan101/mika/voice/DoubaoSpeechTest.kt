package com.wangzan101.mika.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class DoubaoSpeechTest {
    private class Capture(private val starts: Boolean = true) : StreamingRecorder {
        override val level = MutableStateFlow(0f)
        override val chunks = Channel<ShortArray>(100)
        var cancelled = false
        override fun start() = starts
        override fun stop(): ShortArray { chunks.close(); return shortArrayOf() }
        override fun cancel() { cancelled = true; chunks.cancel() }
    }
    private class Socket : DoubaoSocket {
        override val incoming = Channel<ByteArray>(32)
        val sent = Channel<ByteArray>(32)
        var closed = false
        override fun send(packet: ByteArray) { check(sent.trySend(packet).isSuccess) }
        override fun close() { closed = true; incoming.cancel() }
    }
    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(5_000) { block() }
    }
    private fun CoroutineScope.collect(session: SpeechSession): Channel<SpeechEvent> = Channel<SpeechEvent>(32).also { output ->
        launch { session.events.filter { it !is SpeechEvent.Level }.collect { output.send(it) }; output.close() }
    }

    @Test fun uploadsWhileHeldReplacesPartialSnapshotsAndWaitsForServerFinal() = test {
        val capture = Capture(); val socket = Socket()
        val session = DoubaoSpeech(capture, { socket }, this)
        val events = collect(session)
        assertTrue(session.live)
        assertEquals(0x10.toByte(), socket.sent.receive()[1])
        // Audio captured while connecting is retained until the initial acknowledgement.
        repeat(4) { capture.chunks.send(ShortArray(800) { 42 }) }
        assertTrue(socket.sent.tryReceive().isFailure)
        socket.incoming.send(asrResponse())
        val audio = socket.sent.receive()
        assertEquals(0x20.toByte(), audio[1]) // Sent before release, not a recorded-file upload.
        assertEquals(6400, clientPayload(audio).size)
        socket.incoming.send(asrResponse("""{"result":{"text":"你"}}"""))
        assertEquals(SpeechEvent.Partial("", "你"), events.receive())
        socket.incoming.send(asrResponse("""{"result":{"text":"你好"}}"""))
        assertEquals(SpeechEvent.Partial("", "你好"), events.receive())
        session.release(); session.release()
        assertEquals(SpeechEvent.Converting, events.receive())
        assertEquals(0x22.toByte(), socket.sent.receive()[1])
        assertTrue(events.tryReceive().isFailure)
        socket.incoming.send(asrResponse("""{"result":{"text":"你好！"}}""", flags = 3, sequence = -4))
        assertEquals(SpeechEvent.Final("你好！"), events.receive())
        assertTrue(events.receiveCatching().isClosed)
        yield()
        assertTrue(socket.closed); assertTrue(capture.cancelled)
    }

    @Test fun releaseBeforeConnectionFlushesShortTailAndEmptyFinalRetainsLatestSnapshot() = test {
        val capture = Capture(); val socket = Socket(); val ready = CompletableDeferred<Unit>()
        val session = DoubaoSpeech(capture, { ready.await(); socket }, this)
        val events = collect(session)
        capture.chunks.send(shortArrayOf(0x1234, -2))
        session.release()
        assertEquals(SpeechEvent.Converting, events.receive())
        ready.complete(Unit)
        socket.sent.receive(); socket.incoming.send(asrResponse())
        val last = socket.sent.receive()
        assertEquals(0x22.toByte(), last[1])
        assertArrayEquals(byteArrayOf(0x34, 0x12, -2, -1), clientPayload(last))
        socket.incoming.send(asrResponse("""{"result":{"text":"修正"}}"""))
        assertEquals(SpeechEvent.Partial("", "修正"), events.receive())
        socket.incoming.send(asrResponse(flags = 2))
        assertEquals(SpeechEvent.Final("修正"), events.receive())
    }

    @Test fun finalWaitTimesOutAndCleansUp() = test {
        val capture = Capture(); val socket = Socket()
        val session = DoubaoSpeech(capture, { socket }, this, finalTimeoutMs = 25)
        val events = collect(session)
        socket.sent.receive(); socket.incoming.send(asrResponse())
        session.release(); socket.sent.receive()
        assertEquals(SpeechEvent.Converting, events.receive())
        assertEquals(SpeechEvent.Failed(SpeechFailure.Network), events.receive())
    }

    @Test fun closedConnectionWithoutTerminalPacketIsAFailure() = test {
        val capture = Capture(); val socket = Socket()
        val session = DoubaoSpeech(capture, { socket }, this)
        val events = collect(session)
        socket.sent.receive(); socket.incoming.send(asrResponse()); socket.incoming.close()
        assertEquals(SpeechEvent.Failed(SpeechFailure.Network), events.receive())
    }

    @Test fun cancelSuppressesBufferedAndLateEventsAndIsIdempotent() = test {
        val capture = Capture(); val socket = Socket()
        val session = DoubaoSpeech(capture, { socket }, this)
        socket.sent.receive(); socket.incoming.send(asrResponse())
        session.cancel(); session.cancel()
        assertTrue(session.events.toList().isEmpty())
        yield()
        assertTrue(capture.cancelled); assertTrue(socket.closed)
    }

    @Test fun microphoneFailureDoesNotConnect() = test {
        var connected = false
        val session = DoubaoSpeech(Capture(false), { connected = true; Socket() }, this)
        assertEquals(listOf(SpeechEvent.Failed(SpeechFailure.MicUnavailable)), session.events.toList())
        assertFalse(connected)
    }

    @Test fun noInitialAcknowledgementTimesOut() = test {
        val socket = Socket()
        val session = DoubaoSpeech(Capture(), { socket }, this, connectTimeoutMs = 25)
        val events = collect(session)
        socket.sent.receive()
        assertEquals(SpeechEvent.Failed(SpeechFailure.Network), events.receive())
    }

    @Test fun automaticCaptureEndSendsTerminalPacket() = test {
        val capture = Capture(); val socket = Socket()
        val session = DoubaoSpeech(capture, { socket }, this)
        val events = collect(session)
        socket.sent.receive(); socket.incoming.send(asrResponse())
        capture.chunks.send(ShortArray(800)); capture.chunks.close()
        assertEquals(0x22.toByte(), socket.sent.receive()[1])
        socket.incoming.send(asrResponse(flags = 2))
        assertEquals(SpeechEvent.Final(""), events.receive())
    }

    @Test fun automaticCaptureEndKeepsHeldCancelGestureUntilRelease() = test {
        val capture = Capture(); val socket = Socket()
        val session = DoubaoSpeech(capture, { socket }, this)
        var now = 0L
        val hold = HoldToTalk({ now })
        hold.press(session); now = 2_000; hold.armCancel(true)
        val collector = launch { session.events.collect { hold.onEvent(it) } }
        socket.sent.receive(); socket.incoming.send(asrResponse())
        capture.chunks.close()
        assertEquals(0x22.toByte(), socket.sent.receive()[1])
        socket.incoming.send(asrResponse("""{"result":{"text":"不要发送"}}""", flags = 2))
        collector.join()
        val listening = hold.state.value as HoldToTalk.State.Listening
        assertTrue(listening.ended); assertTrue(listening.cancelArmed)
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }
}
