package com.wangzan101.mika.voice.cascade

import com.wangzan101.mika.calls.VolcRealtimeEvent
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.frontdesk.DeskBrain
import com.wangzan101.mika.core.frontdesk.ReplyContext
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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

    private class ScriptBrain(val onReply: suspend (String, ReplyContext) -> Unit) : DeskBrain {
        val late = mutableListOf<JsonObject>()
        val remembered = mutableListOf<String>()
        val notices = mutableListOf<String>()
        override suspend fun reply(userText: String, ctx: ReplyContext) = onReply(userText, ctx)
        override suspend fun lateResult(name: String, result: JsonObject, ctx: ReplyContext) {
            late += result
            ctx.say("Late: done.")
        }
        override suspend fun notice(text: String, ctx: ReplyContext) {
            notices += text
            ctx.say("Update: $text")
        }
        override fun remember(assistantText: String) { remembered += assistantText }
        override fun seed(prior: List<Turn>) {}
    }

    private fun TestScope.session(
        brain: DeskBrain,
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

    @Test
    fun `noticeWaitsForCurrentReply`() = runTest {
        val midway = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val brain = ScriptBrain { _, ctx ->
            ctx.say("One. ")
            midway.complete(Unit)
            resume.await()
            ctx.say("Two.")
        }
        val (session, events) = session(brain, FakeListen("hello"))
        session.talk(400)
        advanceUntilIdle()
        assertTrue(midway.isCompleted)
        // 正在说的时候来了一条汇报:不打断这句,排在后面。
        assertTrue(session.sendNotice("The booking is done."))
        advanceUntilIdle()
        assertTrue(brain.notices.isEmpty())
        resume.complete(Unit)
        advanceUntilIdle()
        assertFalse(events.any { it is VolcRealtimeEvent.AssistantInterrupted })
        assertEquals(listOf("The booking is done."), brain.notices)
        assertEquals(
            listOf("One.", "Two.", "Update: The booking is done."),
            events.filterIsInstance<VolcRealtimeEvent.AssistantTranscript>().map { it.delta.trim() },
        )
        session.close()
    }

    @Test
    fun `a cue goes to the brain as a note and is answered in its own words`() = runTest {
        val brain = ScriptBrain { _, _ -> }
        val (session, events) = session(brain, FakeListen())
        assertTrue(session.sendCue("Owner replied seven."))
        advanceUntilIdle()
        assertEquals(listOf("Owner replied seven."), brain.notices)
        assertEquals("Update: Owner replied seven.", events.filterIsInstance<VolcRealtimeEvent.AssistantTranscript>().joinToString("") { it.delta }.trim())
        session.close()
    }

    @Test
    fun `a notice with nothing being said starts right away`() = runTest {
        val brain = ScriptBrain { _, _ -> }
        val (session, events) = session(brain, FakeListen())
        assertTrue(session.sendNotice("Started."))
        advanceUntilIdle()
        assertEquals("Update: Started.", events.filterIsInstance<VolcRealtimeEvent.AssistantTranscript>().single().delta)
        session.close()
    }

    @Test
    fun `a notice waits while the user is still speaking`() = runTest {
        val brain = ScriptBrain { text, ctx -> ctx.say("You said $text.") }
        val (session, events) = session(brain, FakeListen("hello"))
        // 用户开口了、还没说完:汇报不插进来
        session.talk(400, silenceMs = 0)
        advanceUntilIdle()
        assertTrue(session.sendNotice("The booking is done."))
        advanceUntilIdle()
        assertTrue(brain.notices.isEmpty())
        // 说完了:先回这句话,再说汇报
        session.talk(0, silenceMs = 700)
        advanceUntilIdle()
        assertEquals(
            listOf("You said hello.", "Update: The booking is done."),
            events.filterIsInstance<VolcRealtimeEvent.AssistantTranscript>().map { it.delta.trim() },
        )
        session.close()
    }

    @Test
    fun `a notice cut off by barge-in reports it was not spoken, queued ones wait`() = runTest {
        val brain = ScriptBrain { _, _ -> }
        val (session, _) = session(brain, FakeListen("wait"), secondsPerPiece = 4)
        val ends = mutableListOf<Pair<String, Boolean>>()
        assertTrue(session.sendNotice("First. Second. Third.") { ends += "first" to it })
        assertTrue(session.sendNotice("Later.") { ends += "later" to it })
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("First. Second. Third."), brain.notices)
        // 用户插话:正在说的这条算没说出去,排着的那条还排着
        session.talk(600)
        advanceUntilIdle()
        assertEquals(listOf("first" to false, "later" to true), ends)
        session.close()
    }

    @Test
    fun `a notice spoken to the end reports it was spoken`() = runTest {
        val (session, _) = session(ScriptBrain { _, _ -> }, FakeListen())
        val ends = mutableListOf<Boolean>()
        assertTrue(session.sendNotice("Done.") { ends += it })
        advanceUntilIdle()
        assertEquals(listOf(true), ends)
        session.close()
    }

    @Test
    fun `closing the call ends unspoken notices as not spoken`() = runTest {
        val midway = CompletableDeferred<Unit>()
        val brain = ScriptBrain { _, ctx -> midway.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        val (session, _) = session(brain, FakeListen("hello"))
        session.talk(400)
        advanceUntilIdle()
        val ends = mutableListOf<Boolean>()
        assertTrue(session.sendNotice("Queued.") { ends += it })
        session.close()
        advanceUntilIdle()
        assertEquals(listOf(false), ends)
    }

    @Test
    fun `typed text is answered like an utterance`() = runTest {
        val brain = ScriptBrain { text, ctx -> ctx.say("You typed $text.") }
        val (session, events) = session(brain, FakeListen())
        assertTrue(session.sendUserText("hello"))
        advanceUntilIdle()
        assertEquals("You typed hello.", events.filterIsInstance<VolcRealtimeEvent.AssistantTranscript>().single().delta)
        session.close()
    }
}
