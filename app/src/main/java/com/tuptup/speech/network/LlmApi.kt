package com.tuptup.speech.network

import com.tuptup.speech.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ScriptResult(val lines: List<String>, val mode: String, val note: String = "")

/**
 * OpenAI 兼容 chat/completions —— 话术的一切（策略/风格/语气/场景）都由 AI 判断。
 * 用户只需提供工作信息与产品信息；时间/天气/热点由 App 自动补充注入。
 */
class LlmApi(private val settings: Settings) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * @param workInfo 用户设置的基本工作信息（店铺名称 + 工作内容）
     * @param products 产品清单（名称 + 介绍要点）
     * @param extra    实时补充信息（时间/天气/热点）；可能为空
     */
    suspend fun generate(workInfo: String, products: String, extra: String): ScriptResult =
        withContext(Dispatchers.IO) {
            if (!settings.apiConfigured) {
                return@withContext ScriptResult(template(products), "template", "未配置 AI 接口，使用本地模板话术")
            }
            try {
                val system = "你是门店直播主播。根据工作信息、产品和实时信息，自行判断合适的策略、风格与语气，" +
                    "生成口语化、可直接口播的话术。每句不超过45字，自然融入有效信息，" +
                    "不编造价格功效，不用绝对化用语。只输出 JSON：{\"lines\":[\"...\"]}"
                val user = buildString {
                    append("【工作信息】").append(workInfo.ifBlank { "（未填写，围绕直播讲解）" }).append('\n')
                    append("产品清单：\n").append(products.ifBlank { "（未添加产品，围绕工作信息介绍）" }).append('\n')
                    if (extra.isNotBlank()) append("实时补充信息：\n").append(extra).append('\n')
                    append("请生成 5 句直播话术，输出 JSON {\"lines\":[...]}")
                }

                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
                val body = JSONObject()
                    .put("model", settings.apiModel)
                    .put("messages", messages)
                    .put("temperature", 0.8)
                    .put("stream", false)
                    .toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val req = Request.Builder()
                    .url("${settings.apiBase}/chat/completions")
                    .header("Authorization", "Bearer ${settings.apiKey}")
                    .post(body).build()
                val resp = client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
                    r.body?.string() ?: ""
                }
                val content = JSONObject(resp)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                val lines = extract(content)
                if (lines.isEmpty()) throw Exception("未返回可解析话术")
                ScriptResult(lines, "api")
            } catch (e: Exception) {
                ScriptResult(template(products), "template", "AI 调用失败，使用本地模板：${e.message?.take(80)}")
            }
        }

    /** 针对一条观众评论，让 AI 自行生成回复话术 */
    suspend fun reply(workInfo: String, products: String, comment: String): ScriptResult =
        withContext(Dispatchers.IO) {
            if (!settings.apiConfigured) {
                return@withContext ScriptResult(listOf(ackFallback(comment)), "template", "未配置 AI 接口，使用简短回复")
            }
            try {
                val system = "你是门店直播主播。针对观众评论，自行判断语气和内容，生成一句得体、口语化、可直接发出的回复。" +
                    "不超过40字，不编造价格功效，不用绝对化用语。只输出 JSON：{\"lines\":[\"...\"]}"
                val user = buildString {
                    append("【工作信息】").append(workInfo.ifBlank { "直播间主播" }).append('\n')
                    append("产品：").append(products.replace("\n", "，").ifBlank { "（未添加）" }).append('\n')
                    append("观众评论：").append(comment).append('\n')
                    append("请生成一句回复，输出 JSON {\"lines\":[...]}")
                }
                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
                val body = JSONObject()
                    .put("model", settings.apiModel)
                    .put("messages", messages)
                    .put("temperature", 0.8)
                    .put("stream", false)
                    .toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder()
                    .url("${settings.apiBase}/chat/completions")
                    .header("Authorization", "Bearer ${settings.apiKey}")
                    .post(body).build()
                val resp = client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
                    r.body?.string() ?: ""
                }
                val content = JSONObject(resp)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                val lines = extract(content)
                if (lines.isEmpty()) throw Exception("未返回可解析回复")
                ScriptResult(listOf(lines.first()), "api")
            } catch (e: Exception) {
                ScriptResult(listOf(ackFallback(comment)), "template", "AI 回复失败：${e.message?.take(60)}")
            }
        }

    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        if (!settings.apiConfigured) return@withContext "请先配置 base_url / key / model"
        try {
            val body = JSONObject()
                .put("model", settings.apiModel)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "回复OK")))
                .put("max_tokens", 5).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url("${settings.apiBase}/chat/completions")
                .header("Authorization", "Bearer ${settings.apiKey}").post(body).build()
            client.newCall(req).execute().use { if (it.isSuccessful) "连接成功" else "HTTP ${it.code}" }
        } catch (e: Exception) { "失败：${e.message?.take(100)}" }
    }

    private fun extract(text: String): List<String> {
        var t = text.trim()
        Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```").find(t)?.let { t = it.groupValues[1] }
        try {
            val arr = JSONObject(t).optJSONArray("lines") ?: return emptyList()
            return (0 until arr.length()).map { arr.getString(it).trim() }.filter { it.isNotEmpty() }.take(6)
        } catch (_: Exception) {}
        return t.lines().map { it.replace(Regex("^\\s*[\\d.、\\-*]+\\s*"), "").trim() }
            .filter { it.length > 3 }.take(6).map { it.take(60) }
    }

    private fun template(products: String): List<String> {
        val out = mutableListOf("欢迎来到直播间，喜欢的朋友点个关注")
        products.lines().map { it.trim() }.filter { it.isNotBlank() }.take(4).forEach { out.add(it) }
        out.add("想了解哪一款，评论区告诉我")
        return out
    }

    private fun ackFallback(comment: String): String = "收到你的留言啦，想了解哪一款我给你介绍"
}
