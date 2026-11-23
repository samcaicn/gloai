package com.wangzan101.mika.voice.cascade

import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.frontdesk.DeskBrain
import com.wangzan101.mika.core.frontdesk.ReplyContext
import com.wangzan101.mika.voice.Pcm16
import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.voice.SpeechSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceReplyCancellationTest {
    private fun TestScope.session(brain: DeskBrain): CascadeVoiceSession {
        var utterance = 0
        return CascadeVoiceSession(
            brain = brain,
            listen = { feed ->
                feed.start()
                val text = "turn${++utterance}"
                object : SpeechSession {
                    val out = Channel<SpeechEvent>(Channel.UNLIMITED)
                    override val events = out.receiveAsFlow()
                    override val live = false
                    override fun release() {
                        feed.stop()
                        out.trySend(SpeechEvent.Final(text))
                        out.close()
                    }
                    override fun cancel() { feed.cancel(); out.close() }
                }
            },
            speak = { CascadeVoiceSession.PcmAudioOut(ByteArray(0), 24_000) },
            silenceMs = 500,
            bargeIn = true,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
        )
    }

    private fun CascadeVoiceSession.talk() {
        repeat(20) { sendPcm16(Pcm16.toBytes(ShortArray(320) { 16_000 })) }
        repeat(30) { sendPcm16(Pcm16.toBytes(ShortArray(320))) }
    }

    private class Brain(val cleanup: CompletableDeferred<Unit>) : DeskBrain {
        val started = mutableListOf<String>()
        val remembered = mutableListOf<String>()
        override suspend fun reply(userText: String, ctx: ReplyContext) {
            started += userText
            if (userText == "turn1") {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.await() } }
            }
        }
        override suspend fun lateResult(name: String, result: JsonObject, ctx: ReplyContext) {}
        override suspend fun notice(text: String, ctx: ReplyContext) {}
        override fun remember(assistantText: String) { remembered += assistantText }
        override fun seed(prior: List<Turn>) {}
    }

    @Test fun newReplyWaitsForInterruptedHistoryCleanup() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val brain = Brain(cleanup)
        val session = session(brain)
        try {
            session.talk()
            runCurrent()
            session.talk()
            runCurrent()
            assertEquals(listOf("turn1"), brain.started)
            cleanup.complete(Unit)
            runCurrent()
            assertEquals(listOf("turn1", "turn2"), brain.started)
        } finally {
            cleanup.complete(Unit)
            session.close()
            advanceUntilIdle()
        }
    }

    @Test fun cancellingAWaitingReplyKeepsItsPredecessorInTheBarrier() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val brain = Brain(cleanup)
        val session = session(brain)
        try {
            session.talk()
            runCurrent()
            session.talk()
            runCurrent()
            session.talk()
            runCurrent()
            assertEquals(listOf("turn1"), brain.started)
            cleanup.complete(Unit)
            runCurrent()
            assertEquals(listOf("turn1", "turn3"), brain.started)
        } finally {
            cleanup.complete(Unit)
            session.close()
            advanceUntilIdle()
        }
    }

    @Test fun cancellingBeforeReplacementStartsKeepsTheOriginalBarrier() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val brain = Brain(cleanup)
        val session = session(brain)
        try {
            session.talk()
            runCurrent()
            // Queue a replacement, then interrupt it before its coroutine can start.
            session.sendGreeting("Hello")
            session.talk()
            runCurrent()
            assertEquals(listOf("turn1"), brain.started)
            assertEquals(emptyList<String>(), brain.remembered)
            cleanup.complete(Unit)
            runCurrent()
            assertEquals(listOf("turn1", "turn2"), brain.started)
            assertEquals(emptyList<String>(), brain.remembered)
        } finally {
            cleanup.complete(Unit)
            session.close()
            advanceUntilIdle()
        }
    }

    @Test fun greetingWaitsForInterruptedHistoryCleanupBeforeRemembering() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val brain = Brain(cleanup)
        val session = session(brain)
        try {
            session.talk()
            runCurrent()
            session.sendGreeting("Goodbye")
            runCurrent()
            assertEquals(emptyList<String>(), brain.remembered)
            cleanup.complete(Unit)
            runCurrent()
            assertEquals(listOf("Goodbye"), brain.remembered)
        } finally {
            cleanup.complete(Unit)
            session.close()
            advanceUntilIdle()
        }
    }
}
