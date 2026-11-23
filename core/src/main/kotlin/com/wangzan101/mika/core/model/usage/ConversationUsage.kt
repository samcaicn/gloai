package com.wangzan101.mika.core.model.usage

import com.wangzan101.mika.core.agent.ContextBuilder
import com.wangzan101.mika.core.agent.ContextKind
import com.wangzan101.mika.core.agent.TokenEstimate
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import kotlinx.serialization.Serializable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Only counts survive the request. No messages, images, tool arguments or prompt text are retained. */
@Serializable
data class RequestComposition(
    val model: String, val step: Int, val window: Int,
    val instructions: Long, val dialogue: Long, val references: Long, val toolsAndMedia: Long, val runtime: Long,
) {
    val total get() = instructions + dialogue + references + toolsAndMedia + runtime

    companion object {
        fun estimate(model: String, step: Int, window: Int, prompt: String, tools: List<ToolSpec>, messages: List<Message>): RequestComposition {
            var instructions = TokenEstimate.of(prompt).toLong() + tools.sumOf {
                TokenEstimate.of(it.name + it.description + it.parametersJsonSchema).toLong()
            }
            var dialogue = 0L
            var references = 0L
            var toolsAndMedia = 0L
            var runtime = 0L
            for (message in messages) for (part in message.parts) {
                when (part) {
                    is Part.Image -> toolsAndMedia += TokenEstimate.image(part.width, part.height)
                    is Part.ToolResult -> toolsAndMedia += TokenEstimate.of(part.content)
                    is Part.ToolCall -> toolsAndMedia += TokenEstimate.of(part.name) + TokenEstimate.of(part.argsJson)
                    is Part.Thinking -> dialogue += TokenEstimate.of(part.text)
                    is Part.Text -> {
                        val size = TokenEstimate.of(part.text)
                        val kind = ContextBuilder.kindOf(part.text).takeIf {
                            (message.role == Role.User && message.author == Author.Mika) ||
                                (it == ContextKind.RuntimeState && part === message.parts.last() && message.parts.size > 1)
                        }
                        when (kind) {
                            ContextKind.Memory, ContextKind.AppKnowledge, ContextKind.SiteKnowledge, ContextKind.SkillsIndex, ContextKind.ScreenMap -> references += size
                            ContextKind.UserScreen -> toolsAndMedia += size
                            ContextKind.Notice, ContextKind.Channel, ContextKind.RuntimeState, ContextKind.Observed, ContextKind.DeskNote -> runtime += size
                            ContextKind.PriorTurns -> dialogue += size
                            null -> when (message.role) {
                                Role.System -> instructions += size
                                Role.Tool -> toolsAndMedia += size
                                else -> dialogue += size
                            }
                        }
                    }
                }
            }
            return RequestComposition(model, step, window, instructions, dialogue, references, toolsAndMedia, runtime)
        }
    }
}

@Serializable
data class ConversationUsage(
    val input: Long = 0, val output: Long = 0, val cached: Long = 0, val cacheReportedInput: Long = 0,
    val partial: Boolean = false, val latest: RequestComposition? = null,
) {
    val cacheRate: Double? get() = if (cacheReportedInput > 0) cached.toDouble() / cacheReportedInput else null

    fun add(usage: Usage?, attempts: Int): ConversationUsage {
        if (usage == null) return if (attempts > 0) copy(partial = true) else this
        return copy(input = input + usage.inputTokens, output = output + usage.outputTokens,
            cached = cached + if (usage.cacheReported) usage.cachedInputTokens else 0,
            cacheReportedInput = cacheReportedInput + if (usage.cacheReported) usage.inputTokens else 0,
            partial = partial || attempts > 1)
    }
}

/** Attached to work owned by a conversation, including auxiliary work that outlives a task's budget. */
class ConversationMeter(private val update: ((ConversationUsage) -> ConversationUsage) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ConversationMeter>

    fun record(usage: Usage?, attempts: Int) = update { it.add(usage, attempts) }
    fun request(composition: RequestComposition) = update { it.copy(latest = composition) }

    /** Set only at the primary agent's call site. Auxiliary calls still contribute tokens. */
    internal class MainRequest(val step: Int) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<MainRequest>
    }
}
