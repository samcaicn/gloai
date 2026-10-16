package com.jev.probe.jev

import android.util.Log
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Choice
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.Score
import com.jev.probe.core.kb.ChatContext
import com.jev.probe.core.license.PackLedger
import org.json.JSONObject

/**
 * The Jev judgment route only: the 7 judgment questions in one call, and the
 * ranking question over already-drafted candidates. Reads judgeProvider /
 * judgeBaseUrl / judgeKey / judgeModel from [Prefs]; nothing generative here.
 */
class JudgeClient(private val prefs: Prefs) {

    /**
     * The 7 judgment questions (fast, ~1s). Errors are returned, not thrown.
     *
     * @param ctx D-stage knowledge context; null or empty means the request body
     *        is byte-for-byte what v1.2 sent.
     */
    fun judge(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis {
        val start = System.currentTimeMillis()
        // jev 优先：worker 与 tuptup（免费）两种云端模式都先打云端 jev 决策服务
        // （CF Workers AI，免费且不饱和）。Worker 对无签名头的免费请求直接放行，
        // 只有带错签名的请求才 401，所以默认（tuptup）探针也能走通判断而不必撞
        // 饱和的 one-api 网关。失败（Worker 挂了 / 超时）再退回本地 LLM 版 jev 协议。
        return try {
            val answers = postDecisions(
                snapshot, relationship, ctx,
                JevQuestions.judge()
            )
            Analysis(
                trueIntent = parseChoice(answers.optJSONObject("true_intent")),
                dangerLevel = parseScore(answers.optJSONObject("danger_level")),
                sheNeeds = parseChoice(answers.optJSONObject("she_needs")),
                shouldReplyNow = answers.optJSONObject("should_reply_now")?.optDouble("noul"),
                bestAction = parseChoice(answers.optJSONObject("best_action")),
                tensionResolved = answers.optJSONObject("tension_resolved")?.optDouble("noul"),
                literalQuestion = answers.optJSONObject("literal_question")?.optDouble("noul"),
                rankedReplies = emptyList(),
                latencyMs = System.currentTimeMillis() - start
            )
        } catch (e: Exception) {
            Log.w(TAG, "jev judge failed -> llm fallback: ${e.message}")
            LlmJudge(prefs).judge(snapshot, relationship, ctx)
        }
    }

    /** Ask Jev which of the candidate replies is best. jev 优先，失败退回 LLM 兜底。 */
    fun rank(
        snapshot: ChatSnapshot,
        relationship: String,
        candidates: List<String>,
        ctx: ChatContext? = null
    ): List<RankedReply> {
        return try {
            val questions = JSONObject().put("best_reply",
                JevQuestions.rankQuestion(candidates).getJSONObject("best_reply"))
            val answers = postDecisions(snapshot, relationship, ctx, questions)
            parseRanked(answers.optJSONObject("best_reply"), candidates)
        } catch (e: Exception) {
            Log.w(TAG, "jev rank failed -> llm fallback: ${e.message}")
            LlmJudge(prefs).rank(snapshot, relationship, candidates, ctx)
        }
    }

    /**
     * POST one decisions request, with the knowledge fields when there are any.
     *
     * Defensive retry: whether the live `alpha/decisions` endpoint accepts the
     * new `background` / `history` state fields or rejects unknown ones with a
     * 4xx is not verified against production yet (see the A-stage report). If a
     * request carrying them comes back 4xx, it is sent again once without them,
     * so an unverified field can degrade the analysis but never break it.
     */
    private fun postDecisions(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext?,
        questions: JSONObject
    ): JSONObject {
        val background = ctx?.background(relationship) ?: ""
        val history = ctx?.history ?: emptyList()
        val enriched = background.isNotBlank() || history.isNotEmpty()
        return try {
            send(JevQuestions.buildState(snapshot, relationship, background, history), questions)
        } catch (e: ApiException) {
            if (enriched && e.status != null && e.status in 400..499) {
                Log.w(TAG, "judge HTTP ${e.status} with background/history; retrying plain")
                send(JevQuestions.buildState(snapshot, relationship), questions)
            } else throw e
        }
    }

    private fun send(state: JSONObject, questions: JSONObject): JSONObject {
        // jev 优先路径：无论 provider 是什么，决策协议都打 Jev 决策服务地址
        // （provider=tuptup 时即云端 worker；自建 provider 用各自配置的地址）。
        val url = prefs.jevDecisionsEndpoint()
        // v1.1 免费模式（对齐 PC 版 jevDecisions）：/ai/jev/decisions 无凭证放行，
        // 不再带账户令牌鉴权。
        val auth = if (prefs.isWorkerMode) "" else prefs.judgeKey
        val body = JSONObject()
            .put("model", prefs.judgeModel)
            .put("state", state)
            .put("questions", questions)
        val resp = HttpJson.post(
            url, "", body, Route.JUDGE, HttpJson.headersFor(url),
            authToken = auth, instanceId = prefs.instanceId,
            onQuota = if (prefs.isWorkerMode) { r, l ->
                PackLedger.syncFromHeaders(prefs.appContext, r, l)
            } else null
        )
        return resp.optJSONObject("answers") ?: JSONObject()
    }

    private fun parseChoice(o: JSONObject?): Choice? {
        o ?: return null
        val probs = HashMap<String, Double>()
        o.optJSONObject("probabilities")?.let { p ->
            p.keys().forEach { k -> probs[k] = p.optDouble(k) }
        }
        return Choice(o.optString("choice"), o.optDouble("confidence", 0.0), probs)
    }

    private fun parseScore(o: JSONObject?): Score? {
        o ?: return null
        val legend = o.optJSONObject("legend")
        val maxLevel = legend?.keys()?.asSequence()?.mapNotNull { it.toIntOrNull() }?.maxOrNull() ?: 9
        return Score(o.optDouble("score", 0.0), o.optDouble("confidence", 0.0), maxLevel)
    }

    private fun parseRanked(o: JSONObject?, candidates: List<String>): List<RankedReply> {
        val keys = listOf("reply_a", "reply_b", "reply_c")
        val probs = o?.optJSONObject("probabilities")
        val list = candidates.mapIndexed { i, text ->
            RankedReply(text, probs?.optDouble(keys.getOrElse(i) { "" }, 0.0) ?: 0.0)
        }
        return list.sortedByDescending { it.prob }
    }

    companion object { private const val TAG = "JEVASSIST" }
}
