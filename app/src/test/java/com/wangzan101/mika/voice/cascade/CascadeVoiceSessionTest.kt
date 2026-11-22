package com.wangzan101.mika.voice.cascade

import com.wangzan101.mika.calls.VolcRealtimeEvent
import com.wangzan101.mika.voice.Pcm16
import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.voice.SpeechSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CascadeVoiceSessionTest {
    /** Recognizes every utterance as the next scripted text once released. */
    private class FakeListen(vararg texts: String) : (FeedRecorder) -> SpeechSession {
        private val queue = ArrayDeque(texts.toList())
        var started = 0
        override fun invoke(feed: FeedRecorder): SpeechSession {
            started++
            feed.start()
            val text = queue.removeFirstOrNull() ?: ""
            return object : SpeechSession {
                val out = Channel<SpeechEvent>(Channel.UNLIMITED)
                override val events: Flow<SpeechEvent> = out.receiveAsFlow()
                override val live = false
                override fun release() {
                    feed.stop()
                    out.trySend(SpeechEvent.Final(text))
                    out.close()
                }
                override fun cancel() { feed.cancel(); out.close() }
            }
        }
    }

    private class ScriptBrain(val onReply: suspend (String, ReplyContext) -> Unit) : CascadeBrain {
        val late = mutableListOf<JsonObject>()
        val remembered = mutableListOf<String>()
        override suspend fun reply(userText: String, ctx: ReplyContext) = onReply(userText, ctx)
        override suspend fun lateResult(name: String, result: JsonObject, ctx: ReplyContext) {
            late += result
            ctx.say("Late: done.")
        }
        override fun remember(assistantText: String) { remembered += assistantText }
    }

    private fun TestScope.session(
        brain: CascadeBrain,
        listen: (FeedRecorder) -> SpeechSession,
        bargeIn: Boolean = true,
        secondsPerPiece: Int = 1,
    ): Pair<CascadeVoiceSession, MutableList<VolcRealtimeEvent>> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = CascadeVoiceSession(
            brain = brain,
            listen = listen,
            speak = { CascadeVoiceSession.PcmAudioOut(ByteArray(16_000 * 2 * secondsPerPiece), 16_000) },
            silenceMs = 500,
            bargeIn = bargeIn,
            dispatcher = dispatcher,
            clock = { testScheduler.currentTime },
        )
        val events = mutableListOf<VolcRealtimeEvent>()
        // Collected in the test's own scope so the test clock runs it; close() ends the flow.
        launch(dispatcher) { session.events.collect { events += it } }
        return session to events
    }

    private fun CascadeVoiceSession.talk(ms: Int, silenceMs: Int = 700) {
        val pcm = Pcm16.toBytes(tone(ms)) + ByteArray(16 * 2 * silenceMs)
        pcm.toList().chunked(640).forEach { sendPcm16(it.toByteArray()) }
    }

    private fun List<VolcRealtimeEvent>.kinds() = filterNot { it is VolcRealtimeEvent.Audio }.map { it::class.simpleName }

    @Test
    fun `an utterance becomes a transcript, a reply and paced audio`() = runTest {
        val (session, events) = session(ScriptBrain { text, ctx -> ctx.say("You said $text. Anything else?") }, FakeListen("hello"))
        session.talk(400)
        advanceUntilIdle()
        assertEquals(
            listOf(
                "UserSpeaking", "UserSpeechEnded", "UserTranscript", "AssistantAudioStarted",
                "AssistantTranscript", "AssistantTranscript", "AssistantTextEnded", "AssistantAudioEnded",
            ),
            events.kinds(),
        )
        val transcript = events.filterIsInstance<VolcRealtimeEvent.UserTranscript>().single()
        assertEquals("hello", transcript.text)
        assertTrue(transcript.isCompleteFinal && transcript.isCurrentTurn)
        assertEquals(
            listOf("You said hello.", "Anything else?"),
            events.filterIsInstance<VolcRealtimeEvent.AssistantTranscript>().map { it.delta },
        )
        // Two one-second pieces at 24 kHz, 16-bit.
        assertEquals(2 * 24_000 * 2, events.filterIsInstance<VolcRealtimeEvent.Audio>().sumOf { it.pcm.size })
        session.close()
    }

    @Test
    fun `talking over the reply interrupts it`() = runTest {
        val (session, events) = session(
            ScriptBrain { _, ctx -> ctx.say("One. Two. Three. Four.") }, FakeListen("first", "second"), secondsPerPiece = 4,
        )
        session.talk(400)
        advanceUntilIdle()
        assertTrue(events.any { it is VolcRealtimeEvent.AssistantAudioStarted })
        events.clear()
        session.talk(600)
        advanceUntilIdle()
        val kinds = events.kinds()
        assertEquals("AssistantInterrupted", kinds.first())
        assertTrue(kinds.indexOf("UserSpeaking") > 0)
        assertEquals("second", events.filterIsInstance<VolcRealtimeEvent.UserTranscript>().single().text)
        session.close()
    }

    @Test
    fun `without barge-in speech during the reply is ignored`() = runTest {
        val listen = FakeListen("first", "second")
        val (session, events) = session(
            ScriptBrain { _, ctx -> ctx.say("One. Two. Three. Four.") }, listen, bargeIn = false, secondsPerPiece = 4,
        )
        session.talk(400)
        advanceUntilIdle()
        events.clear()
        session.talk(600)
        advanceUntilIdle()
        assertFalse(events.any { it is VolcRealtimeEvent.UserSpeaking || it is VolcRealtimeEvent.AssistantInterrupted })
        assertEquals(1, listen.started)
        session.close()
    }

    @Test
    fun `tool calls surface as function calls and their results are spoken`() = runTest {
        val brain = ScriptBrain { text, ctx ->
            val result = ctx.callTool("mika_request", JsonObject(mapOf("utterance" to JsonPrimitive(text))))
            ctx.say("Result: ${result?.get("result")?.jsonPrimitive?.content}.")
        }
        val (session, events) = session(brain, FakeListen("open the camera"))
        session.talk(400)
        advanceUntilIdle()
        val call = events.filterIsInstance<VolcRealtimeEvent.FunctionCall>().single()
        assertEquals("mika_request", call.name)
        assertEquals("open the camera", call.arguments["utterance"]?.jsonPrimitive?.content)
        assertTrue(session.sendToolResult(call.callId, JsonObject(mapOf("result" to JsonPrimitive("opened")))))
        advanceUntilIdle()
        assertEquals("Result: opened.", events.filterIsInstance<VolcRealtimeEvent.AssistantTranscript>().single().delta)
        session.close()
    }

    @Test
    fun `a result that arrives after the user moved on is answered as its own turn`() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val brain = ScriptBrain { text, ctx ->
            if (text == "start a task") {
                waiting.complete(Unit)
                ctx.callTool("mika_request", JsonObject(emptyMap()))
            }
        }
        val (session, events) = session(brain, FakeListen("start a task", "thanks"))
        session.talk(400)
        advanceUntilIdle()
        val call = events.filterIsInstance<VolcRealtimeEvent.FunctionCall>().single()
        session.talk(400)
        advanceUntilIdle()
        assertTrue(session.sendToolResult(call.callId, JsonObject(mapOf("result" to JsonPrimitive("done")))))
        advanceUntilIdle()
        assertEquals(1, brain.late.size)
        assertEquals("Late: done.", events.filterIsInstance<VolcRealtimeEvent.AssistantTranscript>().last().delta)
        session.close()
    }

    @Test
    fun `greeting is spoken verbatim and remembered`() = runTest {
        val brain = ScriptBrain { _, _ -> }
        val (session, events) = session(brain, FakeListen())
        assertTrue(session.sendGreeting("Hello, this is Mika."))
        advanceUntilIdle()
        assertEquals(listOf("Hello, this is Mika."), brain.remembered)
        assertEquals("Hello, this is Mika.", events.filterIsInstance<VolcRealtimeEvent.AssistantTranscript>().single().delta)
        session.close()
    }

    @Test
    fun `muted input never opens a turn`() = runTest {
        val listen = FakeListen("x")
        val (session, events) = session(ScriptBrain { _, _ -> }, listen)
        session.setMuted(true)
        session.talk(400)
        advanceUntilIdle()
        assertEquals(0, listen.started)
        assertTrue(events.isEmpty())
        session.close()
    }
}
