package com.wangzan101.mika.voice.cascade

import com.wangzan101.mika.core.frontdesk.LlmBrain
import com.wangzan101.mika.core.frontdesk.ReplyContext
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

internal fun tone(ms: Int, amplitude: Int = 8000, rate: Int = 16_000): ShortArray =
    ShortArray(rate * ms / 1000) { i -> (amplitude * sin(i * 2 * Math.PI * 220 / rate)).toInt().toShort() }

class UtteranceDetectorTest {
    private fun frames(samples: ShortArray) = samples.toList().chunked(320).map { it.toShortArray() }

    @Test
    fun `speech opens a turn and silence closes it`() {
        val detector = UtteranceDetector(silenceMs = 500)
        val signals = (frames(ShortArray(16_000 / 2)) + frames(tone(400)) + frames(ShortArray(16_000)))
            .map { detector.push(it) }
        assertEquals(1, signals.count { it == UtteranceDetector.Signal.Start })
        assertEquals(1, signals.count { it == UtteranceDetector.Signal.End })
        assertTrue(signals.indexOf(UtteranceDetector.Signal.Start) < signals.indexOf(UtteranceDetector.Signal.End))
    }

    @Test
    fun `a click shorter than the minimum is not a turn`() {
        val detector = UtteranceDetector(silenceMs = 500)
        val signals = (frames(tone(60)) + frames(ShortArray(16_000))).map { detector.push(it) }
        assertTrue(signals.all { it == UtteranceDetector.Signal.None })
    }

    @Test
    fun `strict mode needs longer speech to barge in`() {
        val normal = UtteranceDetector(silenceMs = 500)
        val strict = UtteranceDetector(silenceMs = 500)
        val speech = frames(tone(200))
        assertTrue(speech.map { normal.push(it) }.contains(UtteranceDetector.Signal.Start))
        assertTrue(speech.map { strict.push(it, strict = true) }.none { it == UtteranceDetector.Signal.Start })
    }

    @Test
    fun `preroll keeps the start of the utterance`() {
        val detector = UtteranceDetector(silenceMs = 500, prerollMs = 300)
        var preroll = ShortArray(0)
        for (frame in frames(tone(400))) {
            if (detector.push(frame) == UtteranceDetector.Signal.Start) { preroll = detector.takePreroll(); break }
        }
        assertTrue(preroll.size >= 16_000 * 120 / 1000)
    }
}

class SentenceSplitterTest {
    @Test
    fun `cuts at sentence marks while streaming`() {
        val splitter = SentenceSplitter()
        val out = mutableListOf<String>()
        listOf("你好，", "我是 Mika。今天", "想做什么？", "好的").forEach { out += splitter.push(it) }
        splitter.flush()?.let(out::add)
        assertEquals(listOf("你好，我是 Mika。", "今天想做什么？", "好的"), out)
    }

    @Test
    fun `long clauses cut at commas`() {
        val splitter = SentenceSplitter(minClause = 8)
        val out = splitter.push("First of all, thanks for calling, ")
        assertEquals(listOf("First of all,", "thanks for calling,"), out)
    }

    @Test
    fun `markdown and empty pieces are dropped`() {
        val splitter = SentenceSplitter()
        assertEquals(listOf("Bold text."), splitter.push("**Bold** text. ... "))
        assertNull(splitter.flush())
    }
}

class LlmBrainTest {
    private val caps = Capabilities(toolCalls = true, vision = false, contextTokens = 8000, reasoning = false)

    private class Recorder : ReplyContext {
        val said = StringBuilder()
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var result: suspend () -> JsonObject? = { JsonObject(mapOf("result" to JsonPrimitive("done"))) }
        override suspend fun say(delta: String) { said.append(delta) }
        override suspend fun callTool(name: String, arguments: JsonObject): JsonObject? {
            calls += name to arguments
            return result()
        }
    }

    @Test
    fun `tool call loops back to the model and the answer is spoken`() = runBlocking {
        val seen = mutableListOf<List<Message>>()
        val model = object : ChatModel {
            override val capabilities = caps
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                seen += messages
                return if (messages.last().role == Role.User) {
                    ModelReply(listOf(Part.ToolCall("t1", "send_dtmf", """{"digits":"1"}""")), StopReason.ToolUse, null)
                } else ModelReply(listOf(Part.Text("Pressed one.")), StopReason.EndTurn, null)
            }
        }
        val brain = LlmBrain(model, "rules", listOf(ToolSpec("send_dtmf", "keys", "{}")))
        val ctx = Recorder()
        brain.reply("press one", ctx)
        assertEquals("send_dtmf", ctx.calls.single().first)
        assertEquals("Pressed one.", ctx.said.toString())
        val tool = seen.last().last()
        assertEquals(Role.Tool, tool.role)
        assertTrue((tool.parts.single() as Part.ToolResult).content.contains("done"))
    }

    @Test
    fun `streaming text is spoken as it arrives, then the rest`() = runBlocking {
        val model = object : StreamingChatModel {
            override val capabilities = caps
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) = error("unused")
            override suspend fun completeStreaming(
                system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
            ): ModelReply {
                onText("Hello")
                yield()
                onText("Hello there")
                return ModelReply(listOf(Part.Text("Hello there, friend.")), StopReason.EndTurn, null)
            }
        }
        val ctx = Recorder()
        LlmBrain(model, "rules", emptyList()).reply("hi", ctx)
        assertEquals("Hello there, friend.", ctx.said.toString())
    }

    @Test
    fun `an interrupted tool call is closed before the next request`() = runBlocking {
        val seen = mutableListOf<List<Message>>()
        val model = object : ChatModel {
            override val capabilities = caps
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                seen += messages
                return if (seen.size == 1) ModelReply(listOf(Part.ToolCall("t1", "mika_request", "{}")), StopReason.ToolUse, null)
                else ModelReply(listOf(Part.Text("ok")), StopReason.EndTurn, null)
            }
        }
        val brain = LlmBrain(model, "rules", emptyList())
        val ctx = Recorder()
        val waiting = CompletableDeferred<Unit>()
        ctx.result = { waiting.complete(Unit); awaitCancellation() }
        val job = launch { brain.reply("open the app", ctx) }
        waiting.await()
        job.cancel()
        job.join()
        brain.reply("never mind", Recorder())
        val history = seen.last()
        val result = history.first { it.role == Role.Tool }.parts.single() as Part.ToolResult
        assertEquals("t1", result.callId)
        assertTrue(result.isError)
        assertEquals(Author.User, history.last().author)
    }
}
