package com.jev.probe.jev

import android.util.Log
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Choice
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.Score
import com.jev.probe.core.kb.ChatContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 兼容网关版的「Jev 判断引擎」：用一次 /chat/completions 复现原 Jev 的 7 题决策
 * 协议（真实意图 / 危险等级 / 对方要什么 / 该不该回 / 最佳动作 / 张力是否消解 / 是否字面），
 * 并把返回的结构化 JSON 解析回与原 [Analysis] 完全一致的字段，悬浮窗与排序逻辑无需改动。
 *
 * 用途：
 *  - provider=tuptup 时作为判断主路径（tuptup.top 是 OpenAI 兼容网关，跑不了 Jev 自定义协议）；
 *  - 其它 provider（worker / 自建 Jev）判断失败时，作为兜底退回这里，符合「优先 jev，不行再 llm」。
 *
 * 密钥与模型来自 [Prefs.TUPTUP_KEY] / [Prefs.TUPTUP_MODEL]（用户自备网关，硬编码默认值）。
 */
class LlmJudge(private val prefs: Prefs) {

    /**
     * 7 题决策。出错时返回带 [Analysis.error] 的空结果（调用方据此走兜底或展示）。
     */
    fun judge(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis {
        val start = System.currentTimeMillis()
        return try {
            val content = chat(buildJudgePrompt(snapshot, relationship, ctx), temperature = 0.2)
            parseJudge(content)
        } catch (e: Exception) {
            Log.w(TAG, "llmJudge failed: ${e.message}")
            Analysis(
                null, null, null, null, null, null, null, emptyList(),
                System.currentTimeMillis() - start, error = e.message ?: "LLM 判断接口请求失败"
            )
        }
    }

    /** 对 3 条候选回复打分排序（OpenAI 兼容版 best_reply 题）。 */
    fun rank(
        snapshot: ChatSnapshot,
        relationship: String,
        candidates: List<String>,
        ctx: ChatContext? = null
    ): List<RankedReply> {
        return try {
            val content = chat(buildRankPrompt(snapshot, relationship, candidates, ctx), temperature = 0.2)
            parseRank(content, candidates)
        } catch (e: Exception) {
            Log.w(TAG, "llmRank failed: ${e.message}")
            candidates.map { RankedReply(it, 0.0) }
        }
    }

    // ----------------------------------------------------------- 请求

    private fun chat(userPrompt: String, temperature: Double): String {
        val url = Prefs.TUPTUP_BASE.trimEnd('/') + "/chat/completions"
        val sys = "你是 Jev 对话判断引擎。只依据给定对话与背景给出结构化判断，绝不编造事实。" +
            "必须只输出一个 JSON 对象，不要任何解释、不要 markdown 代码块。"
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", sys))
            .put(JSONObject().put("role", "user").put("content", userPrompt))
        val body = JSONObject()
            .put("model", Prefs.TUPTUP_MODEL)
            .put("messages", messages)
            .put("temperature", temperature)
        val resp = HttpJson.post(
            url, "", body, Route.JUDGE, HttpJson.headersFor(url),
            authToken = Prefs.TUPTUP_KEY, instanceId = prefs.instanceId
        )
        return resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""
    }

    // ----------------------------------------------------------- 提示词

    private fun buildJudgePrompt(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext?): String {
        val sb = StringBuilder()
        sb.append("关系：").append(relationship.ifBlank { Prefs.DEFAULT_REL }).append('\n')
        sb.append('\n').append("最近对话（最多 10 条，越靠下越新）：\n")
        snapshot.messages.takeLast(10).forEach {
            sb.append(if (it.side == "me") "我：" else "对方：").append(it.text).append('\n')
        }
        ctx?.let { c ->
            val bg = c.background(relationship)
            if (bg.isNotBlank()) sb.append("\n背景与知识库（视作给定上下文，不是跑题）：\n").append(bg).append('\n')
            if (c.history.isNotEmpty()) {
                sb.append("\n更早的聊天记录（越靠下越新）：\n")
                c.history.takeLast(prefs.contextHistoryCount.coerceIn(0, 100)).forEach {
                    sb.append(if (it.side == "me") "我：" else "对方：").append(it.text).append('\n')
                }
            }
        }
        sb.append('\n').append(JUDGE_SCHEMA_PROMPT)
        return sb.toString()
    }

    private fun buildRankPrompt(
        snapshot: ChatSnapshot,
        relationship: String,
        candidates: List<String>,
        ctx: ChatContext?
    ): String {
        val sb = StringBuilder()
        sb.append("关系：").append(relationship.ifBlank { Prefs.DEFAULT_REL }).append('\n')
        sb.append('\n').append("最近对话：\n")
        snapshot.messages.takeLast(10).forEach {
            sb.append(if (it.side == "me") "我：" else "对方：").append(it.text).append('\n')
        }
        sb.append('\n').append("以下是 3 条候选回复：\n")
        val keys = listOf("reply_a", "reply_b", "reply_c")
        candidates.take(3).forEachIndexed { i, t -> sb.append(keys[i]).append("：").append(t).append('\n') }
        sb.append('\n').append(RANK_SCHEMA_PROMPT)
        return sb.toString()
    }

    // ----------------------------------------------------------- 解析

    private fun extractJson(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { JSONObject(text.substring(start, end + 1)) } catch (_: Exception) { null }
    }

    private fun parseJudge(content: String): Analysis {
        val o = extractJson(content) ?: throw IllegalStateException("模型未返回可解析的 JSON")
        val maxLevel = 9
        return Analysis(
            trueIntent = parseChoice(o.optJSONObject("true_intent"), TRUE_INTENT_OPTS),
            dangerLevel = parseScore(o.optJSONObject("danger_level"), maxLevel),
            sheNeeds = parseChoice(o.optJSONObject("she_needs"), SHE_NEEDS_OPTS),
            shouldReplyNow = numOrNull(o, "should_reply_now"),
            bestAction = parseChoice(o.optJSONObject("best_action"), BEST_ACTION_OPTS),
            tensionResolved = numOrNull(o, "tension_resolved"),
            literalQuestion = numOrNull(o, "literal_question"),
            rankedReplies = emptyList(),
            latencyMs = 0L
        )
    }

    private fun parseRank(content: String, candidates: List<String>): List<RankedReply> {
        val o = extractJson(content) ?: return candidates.map { RankedReply(it, 0.0) }
        val keys = listOf("reply_a", "reply_b", "reply_c")
        val list = candidates.take(3).mapIndexed { i, text ->
            RankedReply(text, numOrNull(o, keys[i]) ?: 0.0)
        }
        return list.sortedByDescending { it.prob }
    }

    private fun parseChoice(o: JSONObject?, opts: List<String>): Choice? {
        o ?: return null
        val raw = o.optString("choice", "").trim()
        val choice = if (raw in opts) raw else raw
        val conf = o.optDouble("confidence", 0.5).coerceIn(0.0, 1.0)
        return Choice(choice, conf, emptyMap<String, Double>())
    }

    private fun parseScore(o: JSONObject?, maxLevel: Int): Score? {
        o ?: return null
        val score = o.optDouble("score", 0.0).coerceIn(0.0, maxLevel.toDouble())
        val conf = o.optDouble("confidence", 0.5).coerceIn(0.0, 1.0)
        return Score(score, conf, maxLevel)
    }

    /** 容忍布尔（true→1.0 / false→0.0）与数字，做 0–1 裁剪。 */
    private fun numOrNull(o: JSONObject, key: String): Double? {
        if (!o.has(key)) return null
        when {
            o.opt(key) is Boolean -> return if (o.optBoolean(key)) 1.0 else 0.0
            o.isNull(key) -> return null
            else -> {
                val d = o.optDouble(key, Double.NaN)
                if (d.isNaN()) return null
                return d.coerceIn(0.0, 1.0)
            }
        }
    }

    companion object {
        private const val TAG = "JEVASSIST"

        private val TRUE_INTENT_OPTS = listOf(
            "confirm_you_care", "vent_anger", "request_action",
            "seek_explanation", "casual_chat", "close_topic"
        )
        private val SHE_NEEDS_OPTS = listOf(
            "apology", "action", "explanation", "care", "nothing"
        )
        private val BEST_ACTION_OPTS = listOf(
            "check_history", "apologize", "give_commitment", "explain",
            "acknowledge", "say_less", "make_plan"
        )

        private val JUDGE_SCHEMA_PROMPT = """
请只输出一个 JSON 对象，字段严格如下（不要多余字段）：
{
  "true_intent": {"choice": "confirm_you_care|vent_anger|request_action|seek_explanation|casual_chat|close_topic", "confidence": 0.0-1.0},
  "danger_level": {"score": 1-9 的整数, "confidence": 0.0-1.0},
  "she_needs": {"choice": "apology|action|explanation|care|nothing", "confidence": 0.0-1.0},
  "should_reply_now": 0.0-1.0 的数字,
  "best_action": {"choice": "check_history|apologize|give_commitment|explain|acknowledge|say_less|make_plan", "confidence": 0.0-1.0},
  "tension_resolved": 0.0-1.0 的数字,
  "literal_question": 0.0-1.0 的数字
}
判断口径（沿用 Jev 校准口径）：
- true_intent：对方最新一句的真实意图。若对方在测试你是否记得/还在乎，选 confirm_you_care；已平和收尾选 close_topic（分手/拉黑/别理我属于 vent_anger，不是 close_topic）。
- danger_level：当前对话离吵架/伤关系的接近度，1 最轻 9 最重（已生效的最后通牒仍按高位计）。
- she_needs：对方此刻需要你给什么。若对方已真正接受（谢了/收到了/没事了）选 nothing；讽刺式「习惯了」算 care 不是 nothing。
- should_reply_now：下一句是否该含实质内容（认具体错/给具体时间或承诺/解释你知道的事实），仅看本片段里是否已有该实质，与时机无关。
- best_action：下一步动作类型（check_history=先查记录再表态；apologize=先真诚道歉；give_commitment=给承诺/期限；explain=解释；acknowledge=表示听到了、在乎；say_less=少说或不说；make_plan=约具体安排）。
- tension_resolved：人际张力是否已消解（从未有张力或对方已接受/降温/开玩笑/说没事=true）。
- literal_question：对方最新一句是否纯字面、无潜台词（true 接近 1）。
confidence 是你对该判断的把握度。""".trimIndent()

        private val RANK_SCHEMA_PROMPT = """
请只输出一个 JSON 对象，对三条候选各给 0.0-1.0 的合适度分数：
{"reply_a": 0.0-1.0, "reply_b": 0.0-1.0, "reply_c": 0.0-1.0}
优先匹配 best_action 类型；贬低、过度承诺、跑题的候选给低分；事实未确认时优先会去查证而不是假装记得或泛泛道歉的那条。"""
    }
}
