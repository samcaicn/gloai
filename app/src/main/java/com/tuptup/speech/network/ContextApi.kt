package com.tuptup.speech.network

import com.tuptup.speech.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class ContextInfo(val time: String, val weather: String, val hotspots: String) {
    fun asText() = "时间：$time\n天气：$weather\n热点：$hotspots"
}

/** 实时上下文：时间(本机) + 天气(Open-Meteo 免Key) + 热点(多源尽力) */
class ContextApi(private val settings: Settings) {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    suspend fun build(): ContextInfo = withContext(Dispatchers.IO) {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date())
        val w = fetchWeather()
        val h = fetchHotspots()
        ContextInfo(now, w, h)
    }

    private fun get(url: String): String? = try {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        client.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (_: Exception) { null }

    private fun fetchWeather(): String {
        val city = settings.city
        if (city.isBlank()) return "未设置城市"
        // 地理编码
        val geoJson = get("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(city, "UTF-8")}&count=1&language=zh&format=json")
            ?: return "天气暂不可用"
        val results = JSONObject(geoJson).optJSONArray("results")
        if (results == null || results.length() == 0) return "未找到城市：$city"
        val loc = results.getJSONObject(0)
        val lat = loc.getDouble("latitude"); val lon = loc.getDouble("longitude")
        val wJson = get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min" +
                "&timezone=auto&forecast_days=1") ?: return "天气暂不可用"
        val cur = JSONObject(wJson).getJSONObject("current")
        val temp = cur.optDouble("temperature_2m", Double.NaN)
        return if (temp.isNaN()) "天气暂不可用" else "当前${temp.toInt()}度，${weatherCode(cur.optInt("weather_code"))}"
    }

    private fun weatherCode(c: Int) = when (c) {
        0 -> "晴"; 1 -> "大部晴朗"; 2 -> "多云"; 3 -> "阴"
        45, 48 -> "雾"; in 51..55 -> "毛毛雨"; in 61..65 -> "雨"
        in 71..77 -> "雪"; in 80..82 -> "阵雨"; in 95..99 -> "雷阵雨"
        else -> "天气多变"
    }

    private fun fetchHotspots(): String {
        val parts = mutableListOf<String>()
        // 抖音官方接口
        get("https://www.iesdouyin.com/web/api/v2/hotsearch/billboard/word/")?.let { t ->
            try {
                val arr = JSONObject(t).getJSONArray("word_list")
                if (arr.length() > 0) parts.add("抖音热搜：${arr.getJSONObject(0).getString("word")}")
            } catch (_: Exception) {}
        }
        // 百度热搜（HTML 内嵌 JSON）
        get("https://top.baidu.com/board?tab=realtime")?.let { html ->
            Regex("\"word\":\"(.*?)\"").find(html)?.let { parts.add("百度热搜：${it.groupValues[1]}") }
        }
        return if (parts.isEmpty()) "热点暂不可用" else parts.joinToString("；")
    }
}
