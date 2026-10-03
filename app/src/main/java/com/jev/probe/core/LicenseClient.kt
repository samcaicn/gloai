package com.jev.probe.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Creem 支付购买闭环的客户端半边（服务端是 weauto-license Worker，即 [Prefs.WORKER_BASE]，
 * 与桌面端 GLOAI EXE 同一套：normal(月)/premium(月)/lifetime(一次性) 三档）。
 *
 * 闭环：本机生成 [Prefs.licenseMid] → 打开 [buyUrl] 收银台（checkout metadata 带上 mid）
 * → 用户付款 → Creem webhook 在 Worker 侧写 lic:<mid>=卡密 → 本端 [poll] 每 3s 轮询
 * → 拿到卡密后由调用方写入 accountToken 并切 worker 模式，判断接口即恢复。
 *
 * 客户端绝不持有 Creem API key；所有请求只打自己的 Worker。
 */
object LicenseClient {

    /** 三档套餐，与桌面端/Worker creem_products 完全一致。 */
    val TIERS = listOf("normal", "premium", "lifetime")

    const val TIER_NORMAL = "normal"
    const val TIER_PREMIUM = "premium"
    const val TIER_LIFETIME = "lifetime"

    /** 收银台直达 URL（Worker /buy 动态创建 checkout 并 302 到 creem.io）。 */
    fun buyUrl(prefs: Prefs, tier: String): String =
        "${Prefs.WORKER_BASE}/buy?go=1&tier=${tier}&mid=${prefs.licenseMid}"

    /**
     * 轮询一次 /license?mid=。未付款返回 null（404 pending）；
     * 付款成功返回卡密（兼容 key/license/license_key/kami 字段名）。
     * 网络异常抛 [LicenseException]。
     */
    fun poll(prefs: Prefs): String? {
        val url = "${Prefs.WORKER_BASE}/license?mid=${prefs.licenseMid}"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 15000
                setRequestProperty("Accept", "application/json")
                // Cloudflare WAF 对无 UA 请求 403
                setRequestProperty("User-Agent", "jev-assistant-android")
            }
            val code = conn.responseCode
            if (code == 404) return null // pending：还没付款
            if (code !in 200..299) throw LicenseException("license HTTP $code")
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            if (body.isBlank()) return null
            val json = JSONObject(body)
            // 卡密字段名以服务端实现为准，逐个候选取第一个非空
            for (f in arrayOf("key", "license", "license_key", "kami", "token")) {
                val v = json.optString(f, "")
                if (v.isNotBlank()) return v
            }
            return null
        } catch (e: LicenseException) {
            throw e
        } catch (e: Exception) {
            throw LicenseException(e.message ?: "网络错误")
        } finally {
            conn?.disconnect()
        }
    }

    /** 激活：把卡密落盘为账户令牌并切到云端 worker 模式（判断接口随即恢复）。 */
    fun activate(prefs: Prefs, key: String, tier: String) {
        prefs.licenseKey = key
        prefs.accountToken = key
        prefs.licenseTier = tier
        prefs.judgeProvider = Prefs.PROVIDER_WORKER
    }
}

class LicenseException(msg: String) : Exception(msg)
