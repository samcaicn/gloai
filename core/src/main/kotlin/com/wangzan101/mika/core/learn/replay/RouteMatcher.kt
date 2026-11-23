package com.wangzan101.mika.core.learn.replay

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.learn.knowledge.KnowledgeStore
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.model.decision.DecisionModel
import com.wangzan101.mika.core.model.decision.DecisionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 选中的路线和本次原话里的槽值(按 `{1}`、`{2}` 的顺序)。 */
data class RouteChoice(val route: Route, val slots: List<String>)

/**
 * 任务开始时找一条以往成功的路线:先按 App 与字面相似度取候选,再用一次模型调用(没有候选就不调)选定并抽出槽值。
 * 代码再核一遍:槽值个数要对,且每个都是本次原话里逐字出现的一段。[store] 也供循环更新路线计数。
 */
class RouteMatcher(
    private val model: ChatModel,
    val store: RouteStore,
    private val knowledge: KnowledgeStore?,
    private val decision: DecisionModel? = null,
    private val onDecision: (String) -> Unit = {},
) {

    fun candidates(goal: String, limit: Int = 5): List<Route> {
        val apps = knowledge?.matchTask(goal, Int.MAX_VALUE)?.flatMap { it.packages }?.toSet().orEmpty()
        val g = bigrams(goal)
        return store.all().filter { !it.disabled }
            .map { it to jaccard(g, bigrams(SLOT.replace(it.template, "\u0000"))) }
            .filter { (r, sim) -> sim >= MIN_SIMILARITY || r.packages.any(apps::contains) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    suspend fun choose(goal: String): RouteChoice? {
        val list = candidates(goal)
        if (list.isEmpty()) return null
        if (decision != null) {
            // 只把参数可由原话唯一确定的路线交给决策模型,不用它生成文字。
            // 歧义模板、无匹配、服务失败或不确定均保留原模型抽取和原来的严格校验。
            val choices = list.mapNotNull { route -> RouteSlots.extract(route, goal)?.let { validated(route, it, goal) } }
            if (choices.isNotEmpty()) {
                val routesById = choices.mapIndexed { i, c -> "route_$i" to c }.toMap()
                val options = routesById.mapValues { it.value.route.template } +
                    ("skip" to "Do not reuse a route; let the main model plan this request.")
                val picked = try {
                    withTimeoutOrNull(10_000L) {
                        decision.choose(buildJsonObject {
                            put("currentRequest", goal)
                            put("routes", buildJsonObject {
                                routesById.forEach { (id, c) -> put(id, buildJsonObject {
                                    put("template", c.route.template)
                                    put("boundParameters", JsonArray(c.slots.map(::JsonPrimitive)))
                                    put("tools", JsonArray(c.route.steps.map { JsonPrimitive(it.tool) }))
                                }) }
                            })
                        }, Prompts.role("route_decision"), options,
                            "Does at least one supplied route fulfill the entire currentRequest without adding an action, dropping a constraint, or changing its meaning?")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val confident = picked != null && picked.probability.isFinite() &&
                    picked.probability in DecisionPolicy.MIN_PROBABILITY..1.0
                if (confident && picked?.choice == "skip") {
                    onDecision("skipped")
                    return null
                }
                if (confident && picked != null && picked.choice in routesById &&
                    picked.applicable?.let { it.isFinite() && it in DecisionPolicy.MIN_APPLICABLE..1.0 } == true) {
                    onDecision("selected")
                    return routesById.getValue(picked.choice)
                }
                onDecision("fallback")
            }
        }
        val text = list.withIndex().joinToString("\n", "用户原话:$goal\n\n做法模板:\n") { (i, r) -> "${i + 1}. ${r.template}" }
        val reply = try {
            withTimeoutOrNull(TIMEOUT_MS) { model.complete(PROMPT, listOf(Message(Role.User, listOf(Part.Text(text)), Author.Mika)), emptyList()) } ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return parse(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }, list, goal)
    }

    companion object {
        const val MIN_SIMILARITY = 0.3
        const val TIMEOUT_MS = 20_000L
        private val SLOT = Regex("\\{\\d+\\}")
        private val SLOT_N = Regex("\\{(\\d+)\\}")

        private fun squash(s: String) = s.filterNot { it.isWhitespace() }

        private fun occurrences(s: String, v: String): Int {
            var n = 0; var i = s.indexOf(v)
            while (i >= 0) { n++; i = s.indexOf(v, i + 1) }
            return n
        }

        val PROMPT: String by lazy { Prompts.role("route_match") }

        internal fun parse(text: String, list: List<Route>, goal: String): RouteChoice? {
            val start = text.indexOf('{'); val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val o = try { Json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject } catch (e: Exception) { null } ?: return null
            val pick = o["route"]
            if (pick == null || pick is JsonNull) return null
            val idx = (pick as? JsonPrimitive)?.intOrNull ?: return null
            val route = list.getOrNull(idx - 1) ?: return null
            val slots = (o["slots"] as? JsonArray ?: JsonArray(emptyList())).map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull ?: return null }
            return validated(route, slots, goal)
        }

        internal fun validated(route: Route, slots: List<String>, goal: String): RouteChoice? {
            if (slots.size != route.slots) return null
            // 每个槽值在原话里恰好出现一次,且把槽值填回模板要正好得到原话:槽的边界和模板的固定文字对齐
            // (「张三」不能从「张三丰」里抠出来)
            if (slots.any { it.isBlank() || occurrences(goal, it) != 1 }) return null
            var unknown = false
            val filled = SLOT_N.replace(route.template) { m -> slots.getOrNull(m.groupValues[1].toInt() - 1) ?: run { unknown = true; "" } }
            if (unknown || squash(filled) != squash(goal)) return null
            return RouteChoice(route, slots)
        }

        /** 字符二元组;占位符(\u0000)切断前后。 */
        internal fun bigrams(s: String): Set<String> =
            s.lowercase().filterNot { it.isWhitespace() }.windowed(2).filter { '\u0000' !in it }.toSet()

        internal fun jaccard(a: Set<String>, b: Set<String>): Double =
            if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size
    }
}
