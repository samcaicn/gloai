package com.jev.probe.jev

import com.jev.probe.App
import com.jev.probe.core.Integrity
import com.jev.probe.core.Prefs
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
 * `Authorization: Bearer` 与 `X-WeAuto-Instance`，并经 [onQuota] 回调把 `X-Quota-*`
 * 响应头交给上层（PackLedger 校准本地账本）。
 */
object HttpJson {

    private const val MAX_ATTEMPTS = 3

    /**
     * Cloudflare 回源失败的统一提示。**这跟 429 限流是两回事**，必须说清楚：
     * 429 是"排队等一下就好"，52x 是"服务端自己出问题了，等多久都不行，
     * 得有人去修源站"。混为一谈会让用户反复重试白等，也掩盖了真正的故障。
     */
    fun cfOriginHint(code: Int): String =
        "服务端回源失败（HTTP $code）：源站证书或 443 端口有问题，" +
            "这不是你的配置错误，重试没用，需要站方修复"

    fun post(
        url: String,
        key: String,
        body: JSONObject,
        route: String,
        extraHeaders: Map<String, String> = emptyMap(),
        authToken: String = "",
        instanceId: String = "",
        onQuota: ((Int?, Int?) -> Unit)? = null
    ): JSONObject {
        var attempt = 0
        var last: ApiException? = null
        val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)
        while (attempt < MAX_ATTEMPTS) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Request cancelled")
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 20000
                    doOutput = true
                    // worker 模式用账户令牌；legacy 模式用 provider key。两者都是 Bearer。
                    if (authToken.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer $authToken")
                    } else if (key.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer $key")
                    }
                    if (instanceId.isNotBlank()) setRequestProperty("X-WeAuto-Instance", instanceId)
                    // 云端免费墙随导入话术包条数动态调整：把当前生效上限报给云端
                    // （X-WeAuto-Pack-Quota；无包/自定义大模型时云端按各自基线处理）
                    if (url.contains(Prefs.WORKER_HOST, ignoreCase = true)) {
                        runCatching {
                            com.jev.probe.core.license.PackLedger.dailyFreeLimit(com.jev.probe.App.APP_CONTEXT)
                        }.getOrNull()?.let {
                            setRequestProperty("X-WeAuto-Pack-Quota", it.toString())
                        }
                    }
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    // L3：对 WeAuto 云端请求自动附加官方签名指纹 + HMAC（服务端按官方指纹放行）。
                    workerIntegrityHeaders(url, "POST")
                        .forEach { (k, v) -> setRequestProperty(k, v) }
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                val bytes = bodyBytes
                conn.outputStream.use { os: OutputStream -> os.write(bytes) }
                val code = conn.responseCode
                // 429/529 是网关级限流/过载，立即重试无意义，直接失败让上层快速报错，
                // 避免此前"点了卡死"（最坏 3 次退避 + 40s 读超时把 UI 冻住）的问题。
                // 但**必须把响应体带出来**：网关会写明真实原因，例 one-hub 的
                // {"code":"insufficient_user_quota","message":"上游负载已饱和"}。
                // 丢掉它就只剩一句"服务繁忙"，用户既不知道该等还是该换，也没法反馈。
                if (code == 429 || code == 529) {
                    val detail = readBody(conn.errorStream).extractGatewayReason()
                    throw ApiException(route, code, detail.ifBlank { "服务繁忙，请稍后重试" })
                }
                // Cloudflare 525/521/522/523/524 = CF 到了源站但 TLS 握手/回源失败
                // （证书过期、443 没开、源站挂了）。这是**源站配置问题**，重试 100 次
                // 也是同样结果，必须像 429 一样立即失败——否则纯浪费 3 次往返，
                // 更糟的是把"服务器挂了"拖成"网络慢/在排队"的错误观感。
                if (code == 521 || code == 522 || code == 523 || code == 524 || code == 525) {
                    throw ApiException(route, code, cfOriginHint(code))
                }
                if (code !in 200..299) {
                    // 流只能读一次：先取原文，再从同一份原文里挖原因。
                    val rawErr = readBody(conn.errorStream)
                    val errText = rawErr.extractGatewayReason().ifBlank { rawErr }
                    throw ApiException(route, code, errText.ifBlank { "（响应体为空）" })
                }
                val text = readBody(conn.inputStream)
                if (text.isBlank()) throw ApiException(route, code, "响应体为空")
                // 成功：抓取计费头 + 配额头
                captureQuota(conn, onQuota)
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

    /**
     * POST 并返回**原始响应文本**（不解析 JSON）。用于自定义大模型云端 SSE 转发
     * （/ai/custom）：响应可能是 text/event-stream（流式增量）或普通 JSON，
     * 由调用方自行解析。配额头回调语义与 [post] 相同。
     * 流式响应无法重放，失败不重试，直接抛 [ApiException]。
     */
    fun postRaw(
        url: String,
        body: JSONObject,
        route: String,
        authToken: String = "",
        instanceId: String = "",
        onQuota: ((Int?, Int?) -> Unit)? = null
    ): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                // SSE 转发可能等上游慢网关出首包 + 长流输出，读超时放宽到 60s
                readTimeout = 60000
                doOutput = true
                if (authToken.isNotBlank()) setRequestProperty("Authorization", "Bearer $authToken")
                if (instanceId.isNotBlank()) setRequestProperty("X-WeAuto-Instance", instanceId)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "text/event-stream, application/json")
                workerIntegrityHeaders(url, "POST")
                    .forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val rawErr = readBody(conn.errorStream)
                throw ApiException(
                    route, code,
                    rawErr.extractGatewayReason().ifBlank { rawErr.ifBlank { "（响应体为空）" } }
                )
            }
            captureQuota(conn, onQuota)
            return readBody(conn.inputStream)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(route, null, describe(e))
        } finally {
            conn?.disconnect()
        }
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
                // L3：WeAuto 云端 GET 同样附加完整性头（/token/status 等）。
                workerIntegrityHeaders(url, "GET")
                    .forEach { (k, v) -> setRequestProperty(k, v) }
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

    /**
     * 云端口径配额头（v1.1 授权账本用）：Worker 在响应头回带剩余次数/上限，
     * 调用方据此校准本地账本（PackLedger.syncFromHeaders）。非 Worker 请求
     * 没有这两个头，回调收 (null, null)，调用方自行忽略。
     */
    private fun captureQuota(conn: HttpURLConnection, onQuota: ((Int?, Int?) -> Unit)?) {
        if (onQuota == null) return
        val remain = conn.getHeaderField("X-Quota-Remaining")?.toIntOrNull()
        val limit = conn.getHeaderField("X-Quota-Limit")?.toIntOrNull()
        if (remain != null || limit != null) onQuota(remain, limit)
    }

    private fun String.toIntOrNull(): Int? = try { this.trim().toInt() } catch (_: Exception) { null }

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

    /**
     * L3：仅对 WeAuto 云端（pages.dev）请求附加完整性头。其它 host（OpenRouter / 自建）
     * 不挂，避免把签名指纹外泄给第三方。
     * 头内容由 [Integrity.integrityHeaders] 生成（官方证书指纹 + HMAC）。
     */
    private fun workerIntegrityHeaders(url: String, method: String): Map<String, String> {
        if (!url.contains(Prefs.WORKER_HOST, ignoreCase = true)) return emptyMap()
        val path = try { URL(url).path.ifBlank { "/" } } catch (_: Exception) { "/" }
        return try {
            Integrity.integrityHeaders(App.APP_CONTEXT, method, path)
        } catch (_: Exception) {
            emptyMap()
        }
    }

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

    /**
     * 从 429/5xx 的错误体里挖出**网关自己说的**原因。
     *
     * 网关（one-hub / new-api 这类）都会在 body 里给出可执行信息，例：
     * `{"error":{"code":"insufficient_user_quota","message":"上游负载已饱和，请稍后再试"}}`
     * 或 `{"error":{"message":"当前分组 default 下对于模型 X 无可用渠道"}}`（503）。
     * 这些比"服务繁忙"有用得多——前者说明等一会就好、后者说明换模型才行。
     * 解析不出来就返回空串，调用方回退到通用文案。
     */
    private fun String.extractGatewayReason(): String {
        val raw = trim()
        if (raw.isEmpty() || !raw.startsWith("{")) return ""
        return try {
            val root = JSONObject(raw)
            // 兼容三种形状：{error:{message}} / {message} / {error:"文本"}
            val err = root.opt("error")
            val message = when {
                err is JSONObject -> err.optString("message")
                err is String -> err
                else -> root.optString("message")
            }
            val code = when {
                err is JSONObject -> err.optString("code")
                else -> root.optString("code")
            }
            when {
                message.isNotBlank() && code.isNotBlank() -> "$message（$code）"
                message.isNotBlank() -> message
                code.isNotBlank() -> code
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }
}
