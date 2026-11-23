package com.wangzan101.mika.core.cap.wechat

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class WechatCdnMedia(
    @SerialName("encrypt_query_param") val encryptQueryParam: String? = null,
    @SerialName("aes_key") val aesKey: String? = null,
    @SerialName("encrypt_type") val encryptType: Int? = null,
    @SerialName("full_url") val fullUrl: String? = null,
)

@Serializable
data class WechatImage(
    val media: WechatCdnMedia? = null,
    @SerialName("aeskey") val aesKey: String? = null,
    @SerialName("mid_size") val midSize: Long? = null,
)

data class WechatDownloadedImage(val bytes: ByteArray, val mimeType: String, val extension: String)

/** Contains the CDN reference only, never the original image bytes. */
data class WechatUploadedImage(val image: WechatImage)

/** Client resource limits, not claims about the WeChat server's maximum sizes. */
object WechatImages {
    const val MAX_BYTES = 10 * 1024 * 1024
    const val MAX_IMAGES = 8
    const val CDN_BASE_URL = "https://novac2c.cdn.weixin.qq.com/c2c"
    private val random = SecureRandom()

    /** Inspect file signatures rather than trusting a URL, filename or response Content-Type. */
    fun validate(bytes: ByteArray): WechatDownloadedImage {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw IOException("WeChat image exceeds the 10 MiB limit or is empty")
        val format = when {
            bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png" to "png"
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg" to "jpg"
            bytes.startsWith("GIF87a".toByteArray()) || bytes.startsWith("GIF89a".toByteArray()) -> "image/gif" to "gif"
            bytes.size >= 12 && bytes.startsWith("RIFF".toByteArray()) &&
                bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "image/webp" to "webp"
            else -> throw IOException("Unsupported WeChat image format (use PNG, JPEG, GIF or WebP)")
        }
        return WechatDownloadedImage(bytes, format.first, format.second)
    }

    internal suspend fun prepare(bytes: ByteArray): PreparedWechatImage {
        currentCoroutineContext().ensureActive()
        val plain = validate(bytes).bytes
        val key = ByteArray(16).also(random::nextBytes)
        val encrypted = crypt(Cipher.ENCRYPT_MODE, key, plain)
        currentCoroutineContext().ensureActive()
        return PreparedWechatImage(ByteArray(16).also(random::nextBytes).hex(), key.hex(),
            plain.size, MessageDigest.getInstance("MD5").digest(plain).hex(), encrypted)
    }

    internal fun key(image: WechatImage): ByteArray? {
        image.aesKey?.takeIf(String::isNotBlank)?.let { return decodeHexKey(it) }
        val encoded = image.media?.aesKey?.takeIf(String::isNotBlank) ?: return null
        val decoded = try { Base64.getDecoder().decode(encoded) }
            catch (_: IllegalArgumentException) { throw IOException("Invalid WeChat image key") }
        return when (decoded.size) {
            16 -> decoded
            32 -> decodeHexKey(decoded.toString(Charsets.US_ASCII))
            else -> throw IOException("Invalid WeChat image key")
        }
    }

    private fun decodeHexKey(value: String): ByteArray {
        if (!value.matches(Regex("[0-9a-fA-F]{32}"))) throw IOException("Invalid WeChat image key")
        return ByteArray(16) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    internal fun crypt(mode: Int, key: ByteArray, bytes: ByteArray): ByteArray = try {
        // Java's PKCS5Padding uses PKCS#7 padding for AES's 16-byte block size.
        Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(mode, SecretKeySpec(key, "AES")) }.doFinal(bytes)
    } catch (_: GeneralSecurityException) { throw IOException("Cannot process encrypted WeChat image") }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}

@Serializable
internal data class WechatUploadRequest(
    @SerialName("filekey") val fileKey: String,
    @SerialName("media_type") val mediaType: Int,
    @SerialName("to_user_id") val toUserId: String,
    @SerialName("rawsize") val rawSize: Int,
    @SerialName("rawfilemd5") val rawFileMd5: String,
    @SerialName("filesize") val fileSize: Int,
    @SerialName("no_need_thumb") val noNeedThumb: Boolean,
    @SerialName("aeskey") val aesKey: String,
    @SerialName("base_info") val baseInfo: WechatBaseInfo,
)

@Serializable
internal data class WechatUploadResponse(
    val ret: Int = 0,
    val errmsg: String? = null,
    @SerialName("upload_param") val uploadParam: String? = null,
    @SerialName("upload_full_url") val uploadFullUrl: String? = null,
)

internal data class PreparedWechatImage(
    val fileKey: String, val keyHex: String, val plainSize: Int, val md5: String, val encrypted: ByteArray,
)

/** iLink media transfer. No Bot authorization is ever attached to a CDN request. */
internal class WechatImageTransfer(private val client: OkHttpClient, cdnBaseUrl: String) {
    private val base = cdnBaseUrl.trimEnd('/').toHttpUrl()

    suspend fun download(image: WechatImage): WechatDownloadedImage {
        currentCoroutineContext().ensureActive()
        val media = image.media ?: throw IOException("WeChat image has no media reference")
        if ((image.midSize ?: 0) > WechatImages.MAX_BYTES + 16) throw IOException("WeChat image exceeds the 10 MiB limit")
        val key = WechatImages.key(image)
        val url = media.fullUrl?.takeIf(String::isNotBlank)?.let(::trustedUrl) ?: run {
            val param = media.encryptQueryParam?.takeIf(String::isNotBlank)
                ?: throw IOException("WeChat image has no download reference")
            trustedUrl(base.newBuilder().addPathSegment("download").addQueryParameter("encrypted_query_param", param).build().toString())
        }
        val bytes = awaitWechatResponse(client, Request.Builder().url(url).get().build()) { response ->
            if (response.code != 200) throw WechatHttpException(response.code)
            val body = response.body ?: throw IOException("Empty WeChat image response")
            val limit = WechatImages.MAX_BYTES + if (key == null) 0 else 16
            if (body.contentLength() > limit) throw IOException("WeChat image exceeds the 10 MiB limit")
            body.byteStream().use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    total += read
                    if (total > limit) throw IOException("WeChat image exceeds the 10 MiB limit")
                    out.write(buf, 0, read)
                }
                out.toByteArray()
            }
        }
        currentCoroutineContext().ensureActive()
        return WechatImages.validate(if (key == null) bytes else WechatImages.crypt(Cipher.DECRYPT_MODE, key, bytes))
    }

    suspend fun upload(prepared: PreparedWechatImage, response: WechatUploadResponse): WechatUploadedImage {
        currentCoroutineContext().ensureActive()
        val url = response.uploadFullUrl?.takeIf(String::isNotBlank)?.let(::trustedUrl) ?: run {
            val param = response.uploadParam?.takeIf(String::isNotBlank)
                ?: throw IOException("WeChat did not provide an image upload address")
            trustedUrl(base.newBuilder().addPathSegment("upload").addQueryParameter("encrypted_query_param", param)
                .addQueryParameter("filekey", prepared.fileKey).build().toString())
        }
        val request = Request.Builder().url(url)
            .post(prepared.encrypted.toRequestBody("application/octet-stream".toMediaType())).build()
        val downloadParam = awaitWechatResponse(client, request) { uploaded ->
            if (uploaded.code != 200) throw WechatHttpException(uploaded.code)
            uploaded.header("x-encrypted-param")?.takeIf(String::isNotBlank)
                ?: throw IOException("WeChat image upload returned no download reference")
        }
        return WechatUploadedImage(WechatImage(media = WechatCdnMedia(encryptQueryParam = downloadParam,
            aesKey = Base64.getEncoder().encodeToString(prepared.keyHex.toByteArray(Charsets.US_ASCII)), encryptType = 1),
            midSize = prepared.encrypted.size.toLong()))
    }

    private fun trustedUrl(value: String): HttpUrl {
        val url = try { value.toHttpUrl() } catch (_: IllegalArgumentException) { throw IOException("Invalid WeChat CDN address") }
        val sameOrigin = url.scheme == base.scheme && url.host == base.host && url.port == base.port
        val official = url.isHttps && url.port == 443 &&
            (url.host == "cdn.weixin.qq.com" || url.host.endsWith(".cdn.weixin.qq.com"))
        val localTest = sameOrigin && base.scheme == "http" && base.host in setOf("127.0.0.1", "::1", "localhost")
        if ((!official && !(sameOrigin && url.isHttps) && !localTest) ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null)
            throw IOException("Untrusted WeChat CDN address")
        return url
    }
}

/** Cancel the HTTP call while connecting or reading its body, and close responses on every path. */
internal suspend fun <T> awaitWechatResponse(client: OkHttpClient, request: Request, decode: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    try {
                        val result = decode(it)
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }
