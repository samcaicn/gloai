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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskBrainTest {
    /** 假的流式模型：记下每次收到的 messages，回一句固定的话。 */
    private class FakeModel(val answer: String = "好的") : StreamingChatModel {
        override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 8000, reasoning = false)
        val seen = mutableListOf<List<Message>>()
        val systems = mutableListOf<String>()
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) = error("unused")
        override suspend fun completeStreaming(
            system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
        ): ModelReply {
            seen += messages
            systems += system
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

    @Test
    fun backgroundGoesToSystemNotHistory() = runBlocking {
        val model = FakeModel()
        val asked = mutableListOf<String>()
        var version = 0
        val background = DeskBackground { last -> asked += last; "BOARD v${++version}" }
        val brain = LlmBrain(model, "rules", emptyList(), background = background)
        brain.reply("订日料", Recorder())
        brain.notice("Task A started", Recorder())
        brain.reply("几点？", Recorder())

        assertEquals(listOf("rules\n\nBOARD v1", "rules\n\nBOARD v2", "rules\n\nBOARD v3"), model.systems)
        assertEquals(listOf("订日料", "订日料", "几点？"), asked)
        model.seen.flatten().forEach { assertFalse(it.text().contains("BOARD")) }
    }

    @Test
    fun noBackgroundKeepsSystemAsIs() = runBlocking {
        val model = FakeModel()
        LlmBrain(model, "rules", emptyList(), background = DeskBackground { null }).reply("在吗", Recorder())
        LlmBrain(model, "rules", emptyList()).reply("在吗", Recorder())
        assertEquals(listOf("rules", "rules"), model.systems)
    }

    @Test
    fun backgroundWithoutUserTurnGetsEmptyText() = runBlocking {
        val model = FakeModel()
        val asked = mutableListOf<String>()
        LlmBrain(model, "rules", emptyList(), background = DeskBackground { asked += it; null }).notice("Task A started", Recorder())
        assertEquals(listOf(""), asked)
    }
}
