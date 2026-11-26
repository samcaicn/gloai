package com.tuptup.speech.network

import com.tuptup.speech.data.Settings
import com.tuptup.speech.inference.Strategy
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

/** OpenAI 兼容 chat/completions —— 支持任意自建/第三方端点 */
class LlmApi(private val settings: Settings) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun generate(
        ctx: String,
        products: String,
        strategy: Strategy,
        scene: String,
        count: Int
    ): ScriptResult = withContext(Dispatchers.IO) {
        if (!settings.apiConfigured) {
            return@withContext ScriptResult(template(ctx, products, strategy, scene), "template", "未配置 API，使用本地模板话术")
        }
        try {
            val system = "你是门店主播，口语化介绍，每句不超过45字，遵守选定策略，自然融入时间天气热点，" +
                "不编造价格功效，不用绝对化用语。只输出JSON：{\"lines\":[\"...\"]}"
            val user = """
                【选定策略】${strategy.name}：${strategy.desc}
                $ctx
                产品清单：
                $products
                目标场景：$scene
                请生成 $count 句口播话术，输出 JSON {"lines":[...]}
            """.trimIndent()

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
            ScriptResult(template(ctx, products, strategy, scene), "template", "API 失败，降级模板：${e.message?.take(80)}")
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

    private fun template(ctx: String, products: String, strategy: Strategy, scene: String): List<String> {
        val out = mutableListOf<String>()
        when (strategy.id) {
            "weather" -> out.add("今天天气多变，来店里给您备好了应季好货，进来看看")
            "hotspot" -> out.add("最近网上有不少新鲜事，咱们店今天也有新到的好东西")
            "promo" -> out.add("今天店里有特别优惠，进来看看准没错")
            "weekend" -> out.add("周末好呀，来店里逛逛，轻松挑点喜欢的")
            else -> out.add("您好，欢迎光临，进来看看有什么喜欢的")
        }
        products.lines().filter { it.isNotBlank() }.take(2).forEach { out.add(it) }
        out.add("喜欢哪样跟我说一声，我帮您介绍")
        return out
    }
}
