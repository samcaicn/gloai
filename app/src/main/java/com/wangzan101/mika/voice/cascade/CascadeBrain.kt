package com.wangzan101.mika.voice.cascade

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** What a brain may do during one reply: speak, and call a tool whose result comes back through the session. */
interface ReplyContext {
    /** Streams reply text; it is split, synthesized and played in order. */
    suspend fun say(delta: String)

    /** Emits a tool call to the session's owner and waits for its result; null when it never arrives. */
    suspend fun callTool(name: String, arguments: JsonObject): JsonObject?
}

/** The "think" step of a standard call. */
interface CascadeBrain {
    /** Answers [userText]; cancelled when the user starts talking again. */
    suspend fun reply(userText: String, ctx: ReplyContext)

    /** A tool result that arrived after its reply was cancelled; answer it as a new turn. */
    suspend fun lateResult(name: String, result: JsonObject, ctx: ReplyContext)

    /** Something the assistant said verbatim (greeting, closing line). */
    fun remember(assistantText: String)
}

/**
 * A text model with a rolling history and optional tools. Streaming models speak while they write; others speak
 * when the reply is complete. Tool calls loop back into the model a bounded number of times.
 */
class LlmBrain(
    private val model: ChatModel,
    private val system: String,
    private val tools: List<ToolSpec>,
    private val maxMessages: Int = 40,
    private val maxToolRounds: Int = 4,
) : CascadeBrain {
    private val history = mutableListOf<Message>()
    private val opening = StringBuilder()
    private val lock = Any()

    override suspend fun reply(userText: String, ctx: ReplyContext) {
        add(Message(Role.User, listOf(Part.Text(userText)), Author.User))
        run(ctx)
    }

    override suspend fun lateResult(name: String, result: JsonObject, ctx: ReplyContext) {
        add(Message(Role.User, listOf(Part.Text("[$name result arrived] $result")), Author.Mika))
        run(ctx)
    }

    override fun remember(assistantText: String) {
        synchronized(lock) {
            if (history.isEmpty()) opening.append(assistantText).append('\n')
            else history += Message(Role.Assistant, listOf(Part.Text(assistantText)), Author.Mika)
        }
    }

    private suspend fun run(ctx: ReplyContext) {
        repeat(maxToolRounds) {
            val reply = complete(ctx)
            val calls = reply.filterIsInstance<Part.ToolCall>()
            add(Message(Role.Assistant, reply.filterNot { it is Part.Thinking }.ifEmpty { listOf(Part.Text("")) }, Author.Mika))
            if (calls.isEmpty()) return
            val results = mutableListOf<Part>()
            try {
                for (call in calls) {
                    val args = runCatching { Json.parseToJsonElement(call.argsJson).jsonObject }.getOrElse { JsonObject(emptyMap()) }
                    val result = ctx.callTool(call.name, args) ?: buildJsonObject { put("status", "no_result") }
                    results += Part.ToolResult(call.id, result.toString(), isError = false)
                }
            } finally {
                // A cancelled reply must still leave every tool call answered, or the next request is invalid.
                withContext(NonCancellable) {
                    val answered = results.map { (it as Part.ToolResult).callId }.toSet()
                    calls.filter { it.id !in answered }.forEach {
                        results += Part.ToolResult(it.id, """{"status":"interrupted"}""", isError = true)
                    }
                    add(Message(Role.Tool, results, Author.Mika))
                }
            }
        }
    }

    private suspend fun complete(ctx: ReplyContext): List<Part> {
        val (prompt, messages) = synchronized(lock) {
            val prompt = if (opening.isEmpty()) system else "$system\n\nYou opened the conversation by saying:\n$opening"
            prompt to history.toList()
        }
        if (model !is StreamingChatModel) {
            val reply = model.complete(prompt, messages, tools)
            reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf { it.isNotBlank() }?.let { ctx.say(it) }
            return reply.parts
        }
        return coroutineScope {
            val texts = Channel<String>(Channel.CONFLATED)
            var spoken = 0
            val speaker = launch {
                for (full in texts) {
                    // A failover restarts the stream from empty; what was already said stays said.
                    if (full.length < spoken) spoken = 0
                    if (full.length > spoken) {
                        val delta = full.substring(spoken)
                        spoken = full.length
                        ctx.say(delta)
                    }
                }
            }
            val reply = try {
                model.completeStreaming(prompt, messages, tools) { full -> texts.trySend(full) }
            } finally {
                texts.close()
            }
            speaker.join()
            // Text the stream never surfaced (a provider that only returns it at the end) is spoken now.
            val text = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
            if (text.length > spoken && text.isNotBlank()) ctx.say(text.substring(spoken))
            reply.parts
        }
    }

    private fun add(message: Message) {
        synchronized(lock) {
            history += message
            // Keep the tail, starting at a user turn so tool calls never lose their request.
            while (history.size > maxMessages) {
                history.removeAt(0)
                while (history.isNotEmpty() && !(history.first().role == Role.User && history.first().author == Author.User)) {
                    history.removeAt(0)
                }
            }
        }
    }

    companion object {
        /** How a spoken reply should read, prepended to every call's own instructions. */
        const val SPOKEN_STYLE = "You are talking by voice: every word you write is read aloud by speech synthesis. " +
            "Reply in short, natural spoken sentences in the language the other person speaks. " +
            "No markdown, lists, emoji, links or code."

        fun toolSpec(tool: JsonObject): ToolSpec = ToolSpec(
            name = tool["name"].toString().trim('"'),
            description = tool["description"].toString().trim('"'),
            parametersJsonSchema = tool["parameters"].toString(),
        )
    }
}
