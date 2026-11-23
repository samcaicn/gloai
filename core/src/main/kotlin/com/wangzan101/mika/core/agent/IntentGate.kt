package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.model.decision.DecisionModel
import com.wangzan101.mika.core.model.decision.DecisionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 只在准备调用本轮第一个工具时，独立判断用户是在讨论，还是要读取或操作。 */
internal class IntentGate(
    private val judge: ChatModel,
    private val fallback: ChatModel? = null,
    private val decision: DecisionModel? = null,
    private val onDecision: (String) -> Unit = {},
) {
    enum class Intent { Discuss, Read, Act }

    suspend fun assess(task: String, priorTurns: List<Turn>): Intent? {
        val prior = Conversation.context(priorTurns, maxTurns = 3, maxTokens = 800)
            ?.parts?.filterIsInstance<Part.Text>()?.joinToString("\n") { it.text }.orEmpty()
        if (decision != null) {
            val picked = try {
                withTimeoutOrNull(DECISION_TIMEOUT_MS) {
                    decision.choose(buildJsonObject {
                        put("currentMessage", task)
                        put("recentContext", prior)
                    }, Prompts.role("intent_decision"), OPTIONS, applicability = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val intent = picked?.takeIf { it.probability.isFinite() && it.probability in DecisionPolicy.MIN_PROBABILITY..1.0 }
                ?.choice?.takeIf { it in OPTIONS }?.let(::parse)
            onDecision(intent?.let { "selected:${it.name}" } ?: "fallback")
            if (intent != null) return intent
        }
        val input = buildString {
            if (prior.isNotEmpty()) appendLine(prior).appendLine()
            append("The user's current message:\n").append(task)
        }
        val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.User))
        for (candidate in listOfNotNull(judge, fallback)) {
            val result = try {
                val reply = candidate.complete(Prompts.role("intent_gate"), messages, emptyList())
                parse(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (result != null) return result
        }
        return null
    }

    companion object {
        const val DECISION_TIMEOUT_MS = 10_000L
        private val OPTIONS = linkedMapOf(
            "DISCUSS" to "Explain, assess, plan, or discuss. No current app or website state is needed.",
            "READ" to "Fetch current information from an app, website, file, or other live source. Navigation to read is allowed.",
            "ACT" to "Create, change, send, purchase, schedule, or otherwise perform an action now.",
        )
        fun parse(raw: String): Intent? = when (raw.trim().removePrefix("`").removeSuffix("`").trim().uppercase()) {
            "DISCUSS" -> Intent.Discuss
            "READ" -> Intent.Read
            "ACT" -> Intent.Act
            else -> null
        }
    }
}
