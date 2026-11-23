package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskBrainTest {
    /** 假的流式模型：记下每次收到的 messages，回一句固定的话。 */
    private class FakeModel(val answer: String = "好的") : StreamingChatModel {
        override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 8000, reasoning = false)
        val seen = mutableListOf<List<Message>>()
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) = error("unused")
        override suspend fun completeStreaming(
            system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
        ): ModelReply {
            seen += messages
            onText(answer)
            return ModelReply(listOf(Part.Text(answer)), StopReason.EndTurn, null)
        }
    }

    private class Recorder : ReplyContext {
        val said = StringBuilder()
        override suspend fun say(delta: String) { said.append(delta) }
        override suspend fun callTool(name: String, arguments: JsonObject): JsonObject? = null
    }

    private fun Message.text() = parts.filterIsInstance<Part.Text>().joinToString("") { it.text }

    @Test
    fun noticeIsAddedAsMikaNoteAndAnswered() = runBlocking {
        val model = FakeModel("订好了，七点。")
        val ctx = Recorder()
        LlmBrain(model, "rules", emptyList()).notice("Task A finished: booked", ctx)
        val last = model.seen.single().last()
        assertEquals(Role.User, last.role)
        assertEquals(Author.Mika, last.author)
        assertTrue(last.text().startsWith("[update] "))
        assertEquals("[update] Task A finished: booked", last.text())
        assertEquals("订好了，七点。", ctx.said.toString())
    }

    @Test
    fun seedPutsPriorTurnsBeforeFirstReply() = runBlocking {
        val model = FakeModel()
        val brain = LlmBrain(model, "rules", emptyList())
        brain.seed(listOf(Turn("订日料", "好，我让后面去订", "desk")))
        brain.reply("几点？", Recorder())
        val messages = model.seen.single()
        assertEquals(listOf(Role.User, Role.Assistant, Role.User), messages.map { it.role })
        assertEquals(listOf("订日料", "好，我让后面去订", "几点？"), messages.map { it.text() })
        assertEquals(Author.User, messages[0].author)
    }

    @Test
    fun seedAfterHistoryIsIgnored() = runBlocking {
        val model = FakeModel("你好")
        val brain = LlmBrain(model, "rules", emptyList())
        brain.reply("在吗", Recorder())
        brain.seed(listOf(Turn("订日料", "好，我让后面去订", "succeeded")))
        brain.reply("几点？", Recorder())
        assertEquals(listOf("在吗", "你好", "几点？"), model.seen.last().map { it.text() })
    }
}
