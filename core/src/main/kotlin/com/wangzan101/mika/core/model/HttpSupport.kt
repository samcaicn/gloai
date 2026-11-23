package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.net.ProxyFailure
import com.wangzan101.mika.core.run.trace.TraceCall
import com.wangzan101.mika.core.model.usage.ModelHttpAttempts
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class RawResponse(val status: Int, val retryAfter: String?, val body: String, private val onPacket: ((String) -> Unit)? = null) {
    /** Some chat endpoints send a buffered SSE body even when streaming was not requested. */
    fun replay(events: List<String>, accept: (String) -> Unit) {
        for (event in events) {
            onPacket?.invoke(event)
            accept(event)
        }
    }
}

/** 各适配器共用的 HTTP 执行(enqueue + 协程取消)与 Retry-After 解析。 */
internal object HttpSupport {
    suspend fun execute(http: OkHttpClient, req: Request): RawResponse =
        observe(req) { attempt -> executeRaw(http, req, attempt) }.also(::requireProxyAccepted)

    /** HTTP 407 只会是途中的代理要账号密码(或拒了给的),不是服务商的回复。 */
    private fun requireProxyAccepted(raw: RawResponse) {
        if (raw.status == 407) throw ModelError.Proxy(ProxyFailure.AuthFailed)
    }

    /** 连接失败:走了代理且错在代理那一段的说成代理的问题,别的照旧算网络失败。 */
    fun networkError(http: OkHttpClient, req: Request, e: IOException): ModelError =
        NetProxy.failure(http, req.url, e)?.let { ModelError.Proxy(it, e) } ?: ModelError.Network(e)

    private suspend inline fun observe(req: Request, send: (TraceCall.Attempt?) -> RawResponse): RawResponse {
        val wire = req.tag(InferenceRequest::class.java) ?: return send(null)
        val call = currentCoroutineContext()[TraceCall] ?: return send(null)
        val attempt = call.sending(wire) ?: return send(null)
        val response = send(attempt)
        if (response.status !in 200..299) {
            call.finish("error", null)
        } else if (response.body.isNotEmpty()) {
            attempt.packet(response.body)
            return response.copy(onPacket = attempt::packet)
        }
        return response
    }

    private suspend fun countAttempt(req: Request) {
        if (req.tag(InferenceRequest::class.java) != null) {
            currentCoroutineContext()[ModelHttpAttempts]?.start()
        }
    }

    private suspend fun executeRaw(http: OkHttpClient, req: Request, attempt: TraceCall.Attempt?): RawResponse {
        countAttempt(req)
        return suspendCancellableCoroutine { cont ->
            val call = http.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(networkError(http, req, e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        attempt?.httpStatus(it.code)
                        try {
                            val raw = RawResponse(it.code, it.header("retry-after"), it.body?.string().orEmpty())
                            if (cont.isActive) cont.resume(raw)
                        } catch (e: IOException) {
                            if (cont.isActive) cont.resumeWithException(ModelError.Network(e))
                        }
                    }
                }
            })
        }
    }

    /**
     * SSE 按事件交付；取消协程会同时取消 HTTP 请求。[onEvent] 返回 true 表示协议层已到
     * 终态(如 OpenAI 的 [DONE]、Anthropic 的 message_stop),读循环就此收尾并关闭连接——
     * 有的服务器发完终态后并不主动关流(经隧道时生成完却静默挂到读超时),
     * 此时不能干等 EOF。调用者仍按各自协议判断有没有正常收尾,没收尾算 [ModelError.StreamCut]。
     * 一个事件都没有、又不是事件流格式的成功响应(比如地址指到了网页)算 [ModelError.BadResponse]。
     */
    suspend fun stream(http: OkHttpClient, req: Request, onEvent: (String) -> Boolean): RawResponse =
        observe(req) { attempt -> streamRaw(http, req, attempt, onEvent) }.also(::requireProxyAccepted)

    private suspend fun streamRaw(http: OkHttpClient, req: Request, attempt: TraceCall.Attempt?, onEvent: (String) -> Boolean): RawResponse {
        countAttempt(req)
        return suspendCancellableCoroutine { cont ->
            val call = http.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(networkError(http, req, e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        attempt?.httpStatus(it.code)
                        try {
                            if (it.code !in 200..299) {
                                val error = RawResponse(it.code, it.header("retry-after"), it.body?.string().orEmpty())
                                if (cont.isActive) cont.resume(error)
                                return
                            }
                            if (it.header("Content-Type")?.contains("application/json", ignoreCase = true) == true) {
                                if (cont.isActive) cont.resume(RawResponse(it.code, it.header("retry-after"), it.body?.string().orEmpty()))
                                return
                            }
                            val source = it.body?.source() ?: throw ModelError.BadResponse("流式响应没有内容")
                            val data = mutableListOf<String>()
                            var events = 0
                            /** 派发一个事件;返回 true 表示协议层已到终态(如 [DONE]),读循环就此收尾,不再等连接关闭。 */
                            fun dispatch(): Boolean {
                                if (data.isEmpty()) return false
                                events++
                                val event = data.joinToString("\n")
                                attempt?.packet(event)
                                val terminal = onEvent(event)
                                data.clear()
                                return terminal
                            }
                            while (cont.isActive) {
                                val line = source.readUtf8Line() ?: break
                                when {
                                    line.isEmpty() -> if (dispatch()) break
                                    line.startsWith("data:") -> data += line.removePrefix("data:").removePrefix(" ")
                                }
                            }
                            if (cont.isActive) {
                                dispatch()
                                val eventStream = it.header("Content-Type")?.contains("event-stream", ignoreCase = true) == true
                                if (events == 0 && !eventStream) throw ModelError.BadResponse("返回的不是流式内容(Content-Type: ${it.header("Content-Type").orEmpty()})")
                                cont.resume(RawResponse(it.code, it.header("retry-after"), ""))
                            }
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resumeWithException(if (e is IOException) ModelError.Network(e) else e)
                        }
                    }
                }
            })
        }
    }

    private val RETRY_AFTER_BODY = Regex("""(?:try again in|retry after|wait|retry in)\s+(\d+(?:\.\d+)?)\s*(?:s|sec|second)""", RegexOption.IGNORE_CASE)

    fun retryAfterMs(header: String?, body: String? = null): Long? {
        header?.trim()?.toDoubleOrNull()?.let { return (it * 1000).toLong() }
        if (body != null) {
            RETRY_AFTER_BODY.find(body)?.groupValues?.get(1)?.toDoubleOrNull()?.let { return (it * 1000).toLong() }
        }
        return null
    }
}
