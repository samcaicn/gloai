package com.jev.probe.jev

import android.util.Log
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.ChatContext
import com.jev.probe.core.license.PackLedger
import com.jev.probe.core.scriptpack.PackFewShot
import com.jev.probe.core.scriptpack.PackPrompts
import com.jev.probe.core.scriptpack.ScriptPack
import com.jev.probe.core.scriptpack.ScriptPackStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * The generative route: any OpenAI-compatible `/chat/completions` endpoint.
 * Drafts the 3 candidate replies, and (D stage) summarizes text. Reads
 * replyBaseUrl / replyKey / replyModel from [Prefs].
 */
class ReplyClient(private val prefs: Prefs) {

    /**
     * Exactly 3 varied candidate replies in Chinese.
     *
     * @param ctx D-stage knowledge context. When present its background and
     *        history are prepended to the prompt with an instruction to stay
     *        consistent with them and invent nothing beyond them.
     */
    fun draft(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): List<String> {
        val convo = snapshot.messages.takeLast(10).joinToString("\n") {
            val who = when {
                it.side == "me" -> "我"
                !it.sender.isNullOrBlank() -> it.sender!!   // 群聊：用真实发送人，别笼统写"对方"
                else -> "对方"
            }
            who + "：" + it.text
        }
        // 话术包（加密/防伪造/有期限，见 docs/scriptpack-spec.md）：启用且未过期时
        // 覆盖系统提示词、温度与 few-shot 示例；任何异常都回退内置默认话术。
        val pack = effectivePack()
        // 只记 pack_id（话术内容绝不进日志），实机排障时用它确认是否真的生效
        Log.i(TAG, "reply persona: ${pack?.header?.packId ?: "default(built-in)"} " +
            "temp=${pack?.let { PackPrompts.temperatureOf(it) } ?: 0.8}")
        val sys = pack?.let { PackPrompts.buildSystemPrompt(it) } ?: PackPrompts.DEFAULT_DRAFT_SYS
        val temperature = pack?.let { PackPrompts.temperatureOf(it) } ?: 0.8
        val fewShot: List<PackFewShot> = pack?.fewShot ?: emptyList()
        val want = pack?.params?.candidates?.coerceIn(1, 5) ?: 3
        val user = knowledgeBlock(relationship, ctx) +
            "关系：$relationship\n\n最近对话：\n$convo\n\n请给出 $want 条候选回复。"
        // cf worker ai 优先回复：主路打 Worker（Cloudflare Workers AI），失败再兜底 one-api 网关。
        val cloudMode = prefs.isWorkerMode || prefs.judgeProvider == Prefs.PROVIDER_CLOUD
        // 自定义大模型现在也经云端 /ai/custom 转发（SSE 直通），每日 100 条有云端硬墙，
        // 本地账本同样先自查，避免把已超限的流量打上去。
        val useLedger = cloudMode || prefs.isTextCustom()
        if (useLedger) {
            val q = PackLedger.checkQuota(prefs.appContext)
            if (!q.allowed) {
                Log.w(TAG, "reply quota exhausted: licensed=${q.licensed} " +
                    "remain=${q.remaining} tampered=${q.tampered}")
                return emptyList()
            }
        }
        return try {
            val out = parseThree(chat(sys, user, temperature = temperature, fewShot = fewShot))
            if (useLedger) PackLedger.recordUsage(prefs.appContext)
            out
        } catch (e: Exception) {
            if (!useLedger) return emptyList()
            Log.w(TAG, "worker AI reply failed -> cloud relay fallback: ${e.message}")
            try {
                val out = parseThree(chatFallback(sys, user, temperature = temperature, fewShot = fewShot))
                PackLedger.recordUsage(prefs.appContext)
                out
            } catch (e2: Exception) {
                Log.w(TAG, "cloud relay fallback also failed: ${e2.message}")
                emptyList()
            }
        }
    }

    /**
     * 启用中且未到期的话术包。任何失败（未导入 / 已到期 / 验签失败 / 设备不匹配）
     * 都返回 null 回退默认话术——话术包不允许把回复链路搞挂。
     */
    private fun effectivePack(): ScriptPack? = try {
        ScriptPackStore.effectivePack(prefs.appContext)
    } catch (e: Exception) {
        Log.w(TAG, "scriptpack effective failed: ${e.javaClass.simpleName}")
        null
    }

    /** 兜底：经 Worker 的 aiapi 转发（Worker 注入 AIAPI_KEY 并强制 auto 模型），仅当 Worker 自带 AI 不可用时使用。 */
    private fun chatFallback(
        system: String, user: String, temperature: Double,
        fewShot: List<PackFewShot> = emptyList()
    ): String {
        // 客户端不再直连网关（key 曾硬编码在源码）：改打 Worker /ai/relay/v1 转发。
        // 2026-10-15 实测 one-api default 组四模型全「无可用渠道」，仅 auto 通——
        // model 由 Worker 侧强制覆盖为 auto，这里传 RELAY_MODEL 只为语义清晰。
        val url = Prefs.WORKER_BASE.trimEnd('/') + "/ai/relay/v1/chat/completions"
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
        for (turn in fewShot) {
            messages.put(JSONObject().put("role", "user").put("content", turn.user))
            messages.put(JSONObject().put("role", "assistant").put("content", turn.assistant))
        }
        messages.put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", Prefs.RELAY_MODEL)
            .put("messages", messages)
            .put("temperature", temperature)
        val resp = HttpJson.post(
            url, "", body, Route.REPLY, HttpJson.headersFor(url),
            authToken = if (prefs.isWorkerMode) prefs.accountToken else Prefs.CLOUD_RELAY_KEY,
            instanceId = prefs.instanceId
        )
        return resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""
    }

    /** The background + history preamble; empty string when there is no context. */
    private fun knowledgeBlock(relationship: String, ctx: ChatContext?): String {
        ctx ?: return ""
        val background = ctx.background(relationship)
        val history = ctx.history
        if (background.isBlank() && history.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("以下是关于我和对方的背景与知识库，回复必须与之一致，")
            .append("可以直接引用其中事实，不要编造知识库里没有的事实。\n")
        if (background.isNotBlank()) sb.append(background).append('\n')
        if (history.isNotEmpty()) {
            sb.append("\n更早的聊天记录（越靠下越新）：\n")
            history.takeLast(prefs.contextHistoryCount.coerceIn(0, 100)).forEach {
                sb.append(if (it.side == "me") "我：" else "对方：").append(it.text).append('\n')
            }
        }
        sb.append('\n')
        return sb.toString()
    }

    /**
     * One plain chat round trip for the settings connectivity test. Deliberately
     * NOT [summarize]: the test should exercise the ordinary path, not whatever
     * the summary prompt happens to be.
     */
    fun ping(): String =
        chat("你是连通性测试助手，只按要求回答，不要解释。", "请只回复两个字：收到", temperature = 0.0).trim()

    /** Condense a block of text (used by the D-stage contact auto-summary). */
    fun summarize(text: String): String {
        if (text.isBlank()) return ""
        val sys = "你是中文摘要助手。把给到的聊天记录压缩成不超过 120 字的第三人称要点摘要，" +
            "只保留事实、偏好、承诺和待办，不要评论，不要编造。直接输出摘要正文。"
        return chat(sys, text, temperature = 0.2).trim()
    }

    /** One chat-completions round trip; returns the assistant message content. */
    private fun chat(
        system: String, user: String, temperature: Double,
        fewShot: List<PackFewShot> = emptyList()
    ): String {
        val custom = prefs.isTextCustom()
        // 自定义大模型：不再直连用户自配接口（大陆直连境外网关经常被墙/慢），
        // 改经云端 /ai/custom 转发——SSE 流式直通，每日 100 条云端硬墙。
        if (custom) return chatViaRelay(system, user, temperature, fewShot)
        val url = prefs.replyEndpoint()
        val auth = prefs.effectiveReplyKey()
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
        for (turn in fewShot) {
            messages.put(JSONObject().put("role", "user").put("content", turn.user))
            messages.put(JSONObject().put("role", "assistant").put("content", turn.assistant))
        }
        messages.put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", prefs.replyModel)
            .put("messages", messages)
            .put("temperature", temperature)
        val resp = HttpJson.post(
            url, "", body, Route.REPLY, HttpJson.headersFor(url),
            authToken = auth, instanceId = prefs.instanceId,
            // 云端口径校准：Worker 回带剩余次数/上限，覆盖本地账本计数
            onQuota = { r, l -> PackLedger.syncFromHeaders(prefs.appContext, r, l) }
        )
        return resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""
    }

    /**
     * 自定义大模型云端转发路：把用户自配的 {base, key, model} 与原始 payload
     * 装进信封发到 Worker /ai/custom，云端代请求上游并直通回传响应流。
     * 上游 stream:true 时回传 text/event-stream，按增量拼出正文；
     * 上游不支持流式时回传普通 JSON，两条路都能解析。
     */
    private fun chatViaRelay(
        system: String, user: String, temperature: Double,
        fewShot: List<PackFewShot> = emptyList()
    ): String {
        val url = Prefs.WORKER_BASE.trimEnd('/') + "/ai/custom"
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
        for (turn in fewShot) {
            messages.put(JSONObject().put("role", "user").put("content", turn.user))
            messages.put(JSONObject().put("role", "assistant").put("content", turn.assistant))
        }
        messages.put(JSONObject().put("role", "user").put("content", user))
        val payload = JSONObject()
            .put("model", prefs.textModel.ifBlank { prefs.replyModel })
            .put("messages", messages)
            .put("temperature", temperature)
            .put("stream", true)
        val envelope = JSONObject()
            .put("cfg", JSONObject()
                .put("base", prefs.textBaseUrl)
                .put("key", prefs.textKey)
                .put("model", prefs.textModel))
            .put("payload", payload)
        val raw = HttpJson.postRaw(
            url, envelope, Route.REPLY,
            authToken = prefs.accountToken, instanceId = prefs.instanceId,
            // 云端自定义路（100 条/天）回带配额头，同样校准本地账本
            onQuota = { r, l -> PackLedger.syncFromHeaders(prefs.appContext, r, l) }
        )
        return relayContent(raw)
    }

    /** 解析 /ai/custom 直通响应：text/event-stream 增量拼接，或普通 JSON 取正文。 */
    private fun relayContent(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("data:") || t.contains("\ndata:")) {
            val sb = StringBuilder()
            for (line in t.lineSequence()) {
                val s = line.trim()
                if (!s.startsWith("data:")) continue
                val data = s.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                runCatching {
                    val o = JSONObject(data)
                    val delta = o.optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("delta")?.optString("content")
                    if (!delta.isNullOrEmpty()) sb.append(delta)
                }
            }
            return sb.toString()
        }
        return runCatching {
            JSONObject(t).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content") ?: ""
        }.getOrDefault("")
    }

    companion object { private const val TAG = "JEVASSIST" }

    private fun parseThree(content: String): List<String> {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start >= 0 && end > start) {
            try {
                val arr = JSONArray(content.substring(start, end + 1))
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i).trim())
                if (out.size >= 3) return out.take(3)
                while (out.size < 3) out.add("（稍等，我看下）")
                return out
            } catch (_: Exception) { }
        }
        // Fallback: split lines.
        val lines = content.split("\n").map { it.trim().trimStart('-', '*', '1', '2', '3', '.', ' ', '"') }
            .filter { it.isNotBlank() }
        val out = lines.take(3).toMutableList()
        while (out.size < 3) out.add("（稍等，我看下）")
        return out
    }
}
