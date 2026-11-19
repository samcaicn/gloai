package com.wangzan101.mika.core.wechat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class WechatImagesTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYPj/HwADAgH/5ncLrgAAAABJRU5ErkJggg==")
    // Fixed OpenSSL AES-128-ECB/PKCS#7 fixture for the valid PNG above and key 000102...0f.
    private val encrypted = Base64.getDecoder().decode("yLuipry+RbJL9obQb6z5cD5Y+GagqRQ2pbl6n8g3G9tgDgl1H3Uxuk7IIGOQwYNLxQWbYhWbpEMhp6gczIOpOF3+J7pqpuzhDh3HYAu7y/E=")
    private val key = ByteArray(16) { it.toByte() }
    private val keyHex = "000102030405060708090a0b0c0d0e0f"

    private fun server(block: suspend (MockWebServer, WechatApi) -> Unit) = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            block(server, WechatApi(OkHttpClient(), "0.1.38", cdnBaseUrl = server.url("/c2c").toString()))
        }
    }

    private fun account(server: MockWebServer) = WechatAccount("bot-secret", server.url("/").toString(), "bot", "owner")
    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun image(key: String? = null, fullUrl: String? = null, hex: String? = null) =
        WechatImage(WechatCdnMedia("download+/=&", key, fullUrl = fullUrl), aesKey = hex)

    private suspend fun expectIo(block: suspend () -> Unit): IOException {
        try { block() } catch (e: IOException) { return e }
        fail("Expected an IOException")
        error("unreachable")
    }

    @Test fun incomingContentPreservesTextImageOrderAndMalformedImages() {
        val message = Json.decodeFromString(WechatMessage.serializer(), """{
            "message_id":123,"message_type":1,"from_user_id":"owner","item_list":[
                {"type":1,"text_item":{"text":"first"}},
                {"type":2,"image_item":{"media":{"encrypt_query_param":"one"}}},
                {"type":1,"text_item":{"text":"second"}},
                {"type":2,"image_item":{"media":{"encrypt_query_param":"two"}}},
                {"type":2}, {"type":3}]
        }""")
        val content = message.userContent()!!
        assertEquals("first\nsecond", content.text)
        assertEquals(listOf("one", "two", null), content.images.map { it.media?.encryptQueryParam })
        assertEquals("first", message.userText())
        assertNull(message.copy(groupId = "group").userContent())
        assertNull(message.copy(messageType = 2).userContent())
        assertNull(message.copy(messageId = null).userContent())
        assertNull(message.copy(fromUserId = "").userContent())
        assertEquals("", message.copy(items = listOf(WechatItem(type = 2))).userContent()!!.text)
        assertNull(message.copy(items = listOf(WechatItem(type = 3))).userContent())
        assertEquals(9, message.copy(items = List(9) { WechatItem(type = 2) }).userContent()!!.images.size)
    }

    @Test fun quoteIsOnlyAnImageFallbackForTextMessages() {
        val quote = """{"type":1,"text_item":{"text":"see this"},"ref_msg":{"message_item":{"type":2}}}"""
        val direct = """{"type":2,"image_item":{"media":{"encrypt_query_param":"new"}}}"""
        fun content(items: String) = Json.decodeFromString(WechatMessage.serializer(), """{
            "message_id":1,"message_type":1,"from_user_id":"owner","item_list":[$items]
        }""").userContent()!!

        // An explicitly referenced image with missing media must use the normal failed-image path.
        assertEquals(2, content(quote).references.single().item?.type)
        assertEquals(listOf("new"), content("$quote,$direct").images.map { it.media?.encryptQueryParam })
        assertTrue(content("""$quote,{"type":4}""").references.isEmpty())
    }

    @Test fun downloadsBothBase64KeyEncodingsAndPrefersTopLevelHex() = server { server, api ->
        val keys = listOf(
            image(key = Base64.getEncoder().encodeToString(key)),
            image(key = Base64.getEncoder().encodeToString(keyHex.toByteArray())),
            image(key = "invalid fallback key", hex = keyHex),
        )
        for (item in keys) {
            server.enqueue(MockResponse().setBody(Buffer().write(encrypted)))
            val downloaded = api.downloadImage(item)
            assertArrayEquals(png, downloaded.bytes)
            assertEquals("image/png", downloaded.mimeType)
            assertEquals("png", downloaded.extension)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/c2c/download", request.requestUrl!!.encodedPath)
            assertEquals("download+/=&", request.requestUrl!!.queryParameter("encrypted_query_param"))
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("AuthorizationType"))
        }
    }

    @Test fun fullUrlWinsAndPlainImagesAreAcceptedBySignature() = server { server, api ->
        server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(Buffer().write(png)))
        val downloaded = api.downloadImage(image(fullUrl = server.url("/actual-image?reference=1").toString()))
        assertArrayEquals(png, downloaded.bytes)
        assertEquals("/actual-image?reference=1", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
    }

    @Test fun uploadEncryptsBytesAndImageSendCarriesReferenceAndContext() = server { server, api ->
        server.enqueue(response("""{"upload_param":"upload+/=&"}"""))
        server.enqueue(MockResponse().setHeader("x-encrypted-param", "download-reference"))
        server.enqueue(response("""{"ret":0}"""))
        val uploaded = api.uploadImage(account(server), "owner", png)
        assertEquals(2, server.requestCount) // Uploading alone does not send a chat message.
        api.sendImage(account(server), "owner", "image:run:1", uploaded, "conversation-context")

        val reservation = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/ilink/bot/getuploadurl", reservation.path)
        assertEquals("Bearer bot-secret", reservation.getHeader("Authorization"))
        val metadata = Json.parseToJsonElement(reservation.body.readUtf8()).jsonObject
        assertEquals("1", metadata.getValue("media_type").jsonPrimitive.content)
        assertEquals("owner", metadata.getValue("to_user_id").jsonPrimitive.content)
        assertEquals("70", metadata.getValue("rawsize").jsonPrimitive.content)
        assertEquals("80", metadata.getValue("filesize").jsonPrimitive.content)
        assertEquals("a9ec0b91a87a3635a4da2f273c70d4a7", metadata.getValue("rawfilemd5").jsonPrimitive.content)
        assertEquals("true", metadata.getValue("no_need_thumb").jsonPrimitive.content)
        val generatedHex = metadata.getValue("aeskey").jsonPrimitive.content
        val generatedKey = ByteArray(16) { generatedHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        val upload = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", upload.method)
        assertEquals("/c2c/upload", upload.requestUrl!!.encodedPath)
        assertEquals("upload+/=&", upload.requestUrl!!.queryParameter("encrypted_query_param"))
        assertEquals(metadata.getValue("filekey").jsonPrimitive.content, upload.requestUrl!!.queryParameter("filekey"))
        assertEquals("application/octet-stream", upload.getHeader("Content-Type"))
        assertNull(upload.getHeader("Authorization"))
        assertNull(upload.getHeader("X-WECHAT-UIN"))
        val decryptor = Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(generatedKey, "AES")) }
        assertArrayEquals(png, decryptor.doFinal(upload.body.readByteArray()))

        val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/ilink/bot/sendmessage", sent.path)
        val message = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject.getValue("msg").jsonObject
        assertEquals("image:run:1", message.getValue("client_id").jsonPrimitive.content)
        assertEquals("conversation-context", message.getValue("context_token").jsonPrimitive.content)
        assertEquals("owner", message.getValue("to_user_id").jsonPrimitive.content)
        val item = message.getValue("item_list").jsonArray.single().jsonObject
        assertEquals("2", item.getValue("type").jsonPrimitive.content)
        assertFalse(item.containsKey("text_item"))
        val attachment = item.getValue("image_item").jsonObject
        assertEquals("80", attachment.getValue("mid_size").jsonPrimitive.content)
        val media = attachment.getValue("media").jsonObject
        assertEquals("download-reference", media.getValue("encrypt_query_param").jsonPrimitive.content)
        assertEquals(generatedHex, String(Base64.getDecoder().decode(media.getValue("aes_key").jsonPrimitive.content)))
    }

    @Test fun uploadUsesTrustedFullUrlAndRequiresDownloadHeader() = server { server, api ->
        val url = server.url("/direct-upload").toString()
        server.enqueue(response("""{"upload_full_url":"$url","upload_param":"ignored"}"""))
        server.enqueue(MockResponse())
        assertEquals("WeChat image upload returned no download reference", expectIo { api.uploadImage(account(server), "owner", png) }.message)
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("/direct-upload", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
        assertEquals(2, server.requestCount)
    }

    @Test fun rejectsUntrustedUrlsBeforeAccessingThem() = server { server, api ->
        val urls = listOf("https://evil.example/image", "http://novac2c.cdn.weixin.qq.com/c2c/image",
            "http://127.0.0.1:9/image", "file:///tmp/image",
            server.url("/image").newBuilder().username("user").build().toString(), server.url("/image#fragment").toString())
        for (url in urls) expectIo { api.downloadImage(image(fullUrl = url)) }
        assertEquals(0, server.requestCount)
        server.enqueue(response("""{"upload_full_url":"https://evil.example/upload"}"""))
        expectIo { api.uploadImage(account(server), "owner", png) }
        assertEquals(1, server.requestCount)
    }

    @Test fun neverFollowsCdnOrAuthenticatedUploadRedirects() = server { server, api ->
        MockWebServer().use { destination ->
            destination.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/stolen")))
            assertEquals(302, (expectIo { api.downloadImage(image()) } as WechatHttpException).httpCode)
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", destination.url("/stolen")))
            assertEquals(307, (expectIo { api.uploadImage(account(server), "owner", png) } as WechatHttpException).httpCode)
            assertEquals(0, destination.requestCount)
        }
    }

    @Test fun enforcesDeclaredAndStreamingSizeLimits() = server { server, api ->
        expectIo { api.downloadImage(image().copy(midSize = WechatImages.MAX_BYTES.toLong() + 17)) }
        expectIo { api.uploadImage(account(server), "owner", ByteArray(WechatImages.MAX_BYTES + 1)) }
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setBody(Buffer().write(png)).setHeader("Content-Length", WechatImages.MAX_BYTES + 1))
        expectIo { api.downloadImage(image()) }
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(WechatImages.MAX_BYTES + 1)), 8192))
        expectIo { api.downloadImage(image()) }
    }

    @Test fun reportsInvalidMediaWithoutLeakingKeysOrReferences() = server { server, api ->
        val keyFailure = expectIo { api.downloadImage(image(key = "secret-invalid-key")) }
        assertFalse(keyFailure.message.orEmpty().contains("secret-invalid-key"))
        expectIo { api.downloadImage(WechatImage()) }
        expectIo { api.uploadImage(account(server), "owner", "not an image".toByteArray()) }
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setBody("not an image"))
        expectIo { api.downloadImage(image()) }
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3))))
        expectIo { api.downloadImage(image(hex = keyHex)) }
        server.enqueue(response("""{"ret":-1,"errmsg":"rejected"}"""))
        assertEquals(-1, (expectIo { api.uploadImage(account(server), "owner", png) } as WechatApiException).code)
    }

    @Test fun cancellationStopsDownloadWhileReadingBody() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val http = OkHttpClient()
            val api = WechatApi(http, "0.1.38", cdnBaseUrl = server.url("/c2c").toString())
            server.enqueue(MockResponse().setBody(Buffer().write(png)).throttleBody(1, 1, TimeUnit.SECONDS))
            val work = async(Dispatchers.Default) { api.downloadImage(image()) }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            withTimeout(2_000) {
                work.cancelAndJoin()
                while (http.dispatcher.runningCallsCount() != 0) delay(10)
            }
            assertTrue(work.isCancelled)
        }
    }

    @Test fun cancellationStopsUploadReservationWithoutSendingImage() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val http = OkHttpClient()
            val api = WechatApi(http, "0.1.38", cdnBaseUrl = server.url("/c2c").toString())
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val work = async(Dispatchers.Default) { api.uploadImage(account(server), "owner", png) }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            withTimeout(2_000) {
                work.cancelAndJoin()
                while (http.dispatcher.runningCallsCount() != 0) delay(10)
            }
            assertTrue(work.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cancellationStopsCdnUploadAfterReservation() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val http = OkHttpClient()
            val api = WechatApi(http, "0.1.38", cdnBaseUrl = server.url("/c2c").toString())
            server.enqueue(response("""{"upload_param":"reserved"}"""))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val work = async(Dispatchers.Default) { api.uploadImage(account(server), "owner", png) }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            withTimeout(2_000) {
                work.cancelAndJoin()
                while (http.dispatcher.runningCallsCount() != 0) delay(10)
            }
            assertTrue(work.isCancelled)
            assertEquals(2, server.requestCount)
        }
    }
}
