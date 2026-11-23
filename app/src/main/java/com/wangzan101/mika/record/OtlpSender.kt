package com.wangzan101.mika.record

import com.wangzan101.mika.core.run.trace.TraceArchive
import com.wangzan101.mika.core.net.LocalHosts
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal sealed interface OtlpReceipt {
    data object Accepted : OtlpReceipt
    data object AcceptedWithWarning : OtlpReceipt
    data class Rejected(val spans: Long) : OtlpReceipt
    data class HttpError(val code: Int) : OtlpReceipt
}

internal object OtlpSender {
    private val protobuf = "application/x-protobuf".toMediaType()
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(15, TimeUnit.SECONDS).build()
    private val localClient = client.newBuilder().proxy(Proxy.NO_PROXY).dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            Dns.SYSTEM.lookup(hostname).filter { it.isLoopbackAddress || LocalHosts.isLocal(it.hostAddress.orEmpty().substringBefore('%')) }
                .ifEmpty { throw UnknownHostException("Local address required") }
    }).build()
    private val headerName = Regex("[!#$%&'*+.^_`|~0-9a-zA-Z-]+")
    private val reserved = setOf("host", "content-length", "content-type", "content-encoding", "transfer-encoding",
        "connection", "proxy-connection", "proxy-authorization", "accept", "accept-encoding", "te", "trailer", "upgrade", "expect", "cookie")

    fun endpoint(text: String): HttpUrl? = text.trim().toHttpUrlOrNull()?.takeIf {
        it.username.isEmpty() && it.password.isEmpty() && it.fragment == null && (it.isHttps || LocalHosts.isLocal(it.host))
    }

    fun validHeader(name: String, value: String): Boolean = value.isEmpty() ||
        (headerName.matches(name) && name.lowercase() !in reserved && value.isNotBlank() && value.all { it.code in 32..126 || it == '\t' })

    suspend fun send(archive: TraceArchive, address: String, name: String, value: String): OtlpReceipt = withContext(Dispatchers.IO) {
        val url = requireNotNull(endpoint(address)) { "Invalid traces endpoint" }
        require(validHeader(name, value)) { "Invalid authentication header" }
        // Release the archive's reader claim before waiting for the receiver.
        val bytes = ByteArrayOutputStream().also { archive.save(it, includeBody = false) }.toByteArray()
        val body = object : RequestBody() {
            override fun contentType() = protobuf
            override fun contentLength() = bytes.size.toLong()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
        }
        val request = Request.Builder().url(url).header("Accept", protobuf.toString())
            .apply { if (value.isNotEmpty()) header(name, value) }
            .post(body).build()
        val call = (if (url.isHttps) client else localClient).newCall(request)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val result = receipt(it)
                            if (continuation.isActive) continuation.resume(result)
                        } catch (e: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                }
            })
        }
    }

    private fun receipt(response: Response): OtlpReceipt {
        if (response.code != 200) return OtlpReceipt.HttpError(response.code)
        val body = response.body ?: throw IOException("Missing acknowledgement")
        val type = body.contentType()
        if (type?.type != protobuf.type || type.subtype != protobuf.subtype)
            throw IOException("Invalid acknowledgement type")
        val source = body.source()
        if (source.request(65_537)) throw IOException("Oversized acknowledgement")
        val ack = ExportTraceServiceResponse.parseFrom(source.readByteArray())
        val rejected = ack.partialSuccess.rejectedSpans
        if (rejected < 0) throw IOException("Invalid acknowledgement")
        return when {
            rejected > 0 -> OtlpReceipt.Rejected(rejected)
            ack.partialSuccess.errorMessage.isNotEmpty() -> OtlpReceipt.AcceptedWithWarning
            else -> OtlpReceipt.Accepted
        }
    }
}
