package com.jev.probe.jev

import com.jev.probe.core.BillingState
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 路由标识：仅用于拼错误信息（"判断接口 HTTP 401" vs "回复接口"）。
 */
object Route {
    const val JUDGE = "判断接口"
    const val REPLY = "回复接口"
    const val VISION = "视觉接口"
    const val ACCOUNT = "账户接口"
}

/**
 * 携带路由、HTTP 状态码（null=传输失败）和响应体前 120 字，便于设置页展示真实原因。
 */
class ApiException(
    val route: String,
    val status: Int?,
    val snippet: String
) : RuntimeException(buildMessage(route, status, snippet)) {

    companion object {
        fun buildMessage(route: String, status: Int?, snippet: String): String =
            if (status != null) "$route HTTP $status：${snippet.take(120)}"
            else "$route 请求失败：${snippet.take(120)}"
    }
}

/**
 * 共享 POST-JSON 工具：UTF-8 body、429/529 指数退避、其它 4xx 不重试，
 * 所有失败归一化为 [ApiException]。token 与密钥均按调用传入、绝不记录内容。
 *
 * worker 模式下调用方传入 [authToken]（账户令牌）与 [instanceId]，本函数自动带上
 * `Authorization: Bearer` 与 `X-WeAuto-Instance`，并在成功后读取 `X-Billing-*` 响应头
 * 填入 [billing]，供上层刷新本地额度展示。
 */
object HttpJson {

    private const val MAX_ATTEMPTS = 3

    fun post(
        url: String,
        key: String,
        body: JSONObject,
        route: String,
        extraHeaders: Map<String, String> = emptyMap(),
        authToken: String = "",
        instanceId: String = "",
        billing: BillingState? = null
    ): JSONObject {
        var attempt = 0
        var last: ApiException? = null
        while (attempt < MAX_ATTEMPTS) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Request cancelled")
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 40000
                    doOutput = true
                    // worker 模式用账户令牌；legacy 模式用 provider key。两者都是 Bearer。
                    if (authToken.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer $authToken")
                    } else if (key.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer $key")
                    }
                    if (instanceId.isNotBlank()) setRequestProperty("X-WeAuto-Instance", instanceId)
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { os: OutputStream -> os.write(bytes) }
                val code = conn.responseCode
                if (code == 429 || code == 529) {
                    last = ApiException(route, code, "服务繁忙，已重试")
                    attempt++
                    if (attempt < MAX_ATTEMPTS) Thread.sleep(500L * (1L shl attempt))
                    continue
                }
                if (code !in 200..299) {
                    val errText = readBody(conn.errorStream)
                    throw ApiException(route, code, errText.ifBlank { "（响应体为空）" })
                }
                val text = readBody(conn.inputStream)
                if (text.isBlank()) throw ApiException(route, code, "响应体为空")
                // 成功：抓取计费头
                captureBilling(conn, billing)
                return JSONObject(text)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: ApiException) {
                if (e.status != null && e.status in 400..499) throw e
                last = e
                attempt++
                if (attempt < MAX_ATTEMPTS) Thread.sleep(500L * (1L shl attempt))
            } catch (e: Exception) {
                last = ApiException(route, null, describe(e))
                attempt++
                if (attempt < MAX_ATTEMPTS) Thread.sleep(500L * (1L shl attempt))
            } finally {
                conn?.disconnect()
            }
        }
        throw last ?: ApiException(route, null, "请求失败")
    }

    /** 简单 GET-JSON（用于 /token/status 等无 body 端点）。 */
    fun getJson(url: String, token: String = "", route: String = Route.ACCOUNT): JSONObject {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                // 部分网关对无 UA 请求 403（含自家域名）
                setRequestProperty("User-Agent", "jev-assistant-android")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = readBody(conn.errorStream)
                throw ApiException(route, code, err.ifBlank { "（空）" })
            }
            val text = readBody(conn.inputStream)
            if (text.isBlank()) throw ApiException(route, code, "响应体为空")
            return JSONObject(text)
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(route, null, describe(e))
        } finally {
            conn?.disconnect()
        }
    }

    /** 读取 `X-Billing-*` 响应头，写入 [billing]（云端计费权威，客户端只展示）。 */
    private fun captureBilling(conn: HttpURLConnection, billing: BillingState?) {
        if (billing == null) return
        conn.getHeaderField("X-Billing-Tier")?.let { if (it.isNotBlank()) billing.tier = it }
        conn.getHeaderField("X-Billing-Used")?.toLongOrNull()?.let { billing.used = it }
        conn.getHeaderField("X-Billing-Quota")?.toLongOrNull()?.let { billing.quota = it }
        conn.getHeaderField("X-Billing-Remain")?.toLongOrNull()?.let { billing.remain = it }
    }

    private fun String.toLongOrNull(): Long? = try { this.toLong() } catch (_: Exception) { null }

    private fun readBody(stream: java.io.InputStream?): String {
        stream ?: return ""
        return try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } catch (_: Exception) {
            ""
        }
    }

    /** OpenRouter 需要归因头；其它 host 礼貌地忽略未知头。 */
    fun headersFor(url: String): Map<String, String> =
        if (url.contains("openrouter.ai", ignoreCase = true))
            mapOf("HTTP-Referer" to "https://jev-assistant.local", "X-Title" to "Jev Assistant")
        else emptyMap()

    private fun describe(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("timed out") || m.contains("timeout", true) -> "网络超时，请检查连接"
            m.contains("Unable to resolve host") -> "域名解析失败，地址填错或无网络"
            m.contains("Failed to connect") || m.contains("ECONNREFUSED") -> "无法连接该地址"
            m.contains("CertPath") || m.contains("SSL") -> "HTTPS 证书校验失败"
            else -> m
        }
    }
}
