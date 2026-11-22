package com.wangzan101.mika.voice.vendors

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.settings.ProviderStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared plumbing for vendor speech clients: one HTTP client, status mapping, JSON walking and request signing. */
internal object VendorHttp {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val client: OkHttpClient by lazy {
        ProviderStore.modelHttp.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    }

    /** Runs [request] and returns the body bytes; non-2xx statuses become [ModelError]s the voice layer categorizes. */
    suspend fun bytes(request: Request, http: OkHttpClient = client): ByteArray = execute(http, request).use { response ->
        val body = response.body?.bytes() ?: ByteArray(0)
        if (!response.isSuccessful) throw statusError(response.code, String(body, Charsets.UTF_8))
        body
    }

    suspend fun text(request: Request, http: OkHttpClient = client): String = String(bytes(request, http), Charsets.UTF_8)

    suspend fun jsonObject(request: Request, http: OkHttpClient = client): JsonObject =
        runCatching { json.parseToJsonElement(text(request, http)).jsonObject }.getOrElse {
            if (it is ModelError) throw it
            throw ModelError.BadResponse("response is not JSON", it)
        }

    fun statusError(status: Int, body: String): ModelError = when (status) {
        401, 403 -> ModelError.Auth()
        429 -> ModelError.RateLimited(null)
        else -> ModelError.Server(status, body.take(300))
    }

    private suspend fun execute(http: OkHttpClient, request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(ModelError.Network(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    /** Walks `a.b.0.c` through objects and arrays. */
    fun JsonElement.path(vararg keys: Any): JsonElement? = keys.fold(this as JsonElement?) { node, key ->
        when (key) {
            is Int -> (node as? kotlinx.serialization.json.JsonArray)?.getOrNull(key)
            else -> (node as? JsonObject)?.get(key.toString())
        }
    }

    fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    /** Splits a stream of concatenated or newline-separated JSON objects. */
    fun jsonObjects(text: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        text.forEachIndexed { i, c ->
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                return@forEachIndexed
            }
            when (c) {
                '"' -> inString = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        runCatching { json.parseToJsonElement(text.substring(start, i + 1)).jsonObject }.getOrNull()?.let(out::add)
                        start = -1
                    }
                }
            }
        }
        return out
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun sha256Hex(text: String): String = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray(Charsets.UTF_8))

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        if (clean.length % 2 != 0 || clean.any { it !in "0123456789abcdefABCDEF" }) throw ModelError.BadResponse("audio is not hex")
        return ByteArray(clean.length / 2) { i -> clean.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    fun rfc1123(epochMs: Long): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("GMT") }.format(epochMs)

    /** A WebSocket as a channel of text frames; the channel closes on close/failure (failure carried as the cause). */
    class Socket(val ws: WebSocket, val frames: Channel<String>)

    suspend fun openSocket(url: String, http: OkHttpClient = client, timeoutMs: Long = 10_000): Socket {
        val frames = Channel<String>(Channel.UNLIMITED)
        val opened = kotlinx.coroutines.CompletableDeferred<WebSocket>()
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) { frames.trySend(text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); frames.close() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { frames.close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val error = response?.let { statusError(it.code, "") } ?: ModelError.Network(t)
                opened.completeExceptionally(error)
                frames.close(error)
            }
        })
        try {
            withTimeout(timeoutMs) { opened.await() }
        } catch (e: Throwable) {
            ws.cancel()
            throw e
        }
        return Socket(ws, frames)
    }
}

/** iFlytek WebSocket auth: HMAC-SHA256 over host/date/request-line, carried in the query string. */
internal object XfyunAuth {
    fun url(host: String, path: String, apiKey: String, apiSecret: String, epochMs: Long = System.currentTimeMillis()): String {
        val date = VendorHttp.rfc1123(epochMs)
        val origin = "host: $host\ndate: $date\nGET $path HTTP/1.1"
        val signature = Base64.getEncoder().encodeToString(VendorHttp.hmac(apiSecret.toByteArray(Charsets.UTF_8), origin))
        val authorization = Base64.getEncoder().encodeToString(
            "api_key=\"$apiKey\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"$signature\""
                .toByteArray(Charsets.UTF_8),
        )
        fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        return "wss://$host$path?authorization=${enc(authorization)}&date=${enc(date)}&host=${enc(host)}"
    }
}

/** Tencent Cloud API 3.0 TC3-HMAC-SHA256 signing for JSON POST requests. */
internal object TencentAuth {
    fun headers(
        service: String,
        action: String,
        version: String,
        payload: String,
        secretId: String,
        secretKey: String,
        region: String? = null,
        epochSeconds: Long = System.currentTimeMillis() / 1000,
    ): Map<String, String> {
        val host = "$service.tencentcloudapi.com"
        val contentType = "application/json; charset=utf-8"
        val canonical = "POST\n/\n\ncontent-type:$contentType\nhost:$host\n\ncontent-type;host\n${VendorHttp.sha256Hex(payload)}"
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(epochSeconds * 1000)
        val scope = "$date/$service/tc3_request"
        val toSign = "TC3-HMAC-SHA256\n$epochSeconds\n$scope\n${VendorHttp.sha256Hex(canonical)}"
        val kDate = VendorHttp.hmac("TC3$secretKey".toByteArray(Charsets.UTF_8), date)
        val kService = VendorHttp.hmac(kDate, service)
        val kSigning = VendorHttp.hmac(kService, "tc3_request")
        val signature = VendorHttp.hex(VendorHttp.hmac(kSigning, toSign))
        return buildMap {
            put("Authorization", "TC3-HMAC-SHA256 Credential=$secretId/$scope, SignedHeaders=content-type;host, Signature=$signature")
            put("Content-Type", contentType)
            put("Host", host)
            put("X-TC-Action", action)
            put("X-TC-Timestamp", epochSeconds.toString())
            put("X-TC-Version", version)
            if (region != null) put("X-TC-Region", region)
        }
    }
}
