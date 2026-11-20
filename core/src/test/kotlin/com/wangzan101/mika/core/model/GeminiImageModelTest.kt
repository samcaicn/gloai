package com.wangzan101.mika.core.model

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

class GeminiImageModelTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private val pngB64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    private val png = Base64.getDecoder().decode(pngB64)

    private fun model(caps: ImageCapabilities = caps()) = GeminiImageModel(
        server.url("/").toString().trimEnd('/'), "gk", "gemini-3-pro-image-preview", caps, OkHttpClient(),
    )

    private fun caps() = ImageCapabilities(
        supportsEdit = true, maxReferences = 2, maxCount = 2,
        aspects = ImageAspect.entries.toSet(),
    )

    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun imageResponse(data: String = pngB64, finish: String = "STOP", text: String? = "a cat") = buildString {
        append("""{"candidates":[{"content":{"role":"model","parts":[""")
        if (text != null) append("""{"text":"$text"}""")
        if (text != null) append(",")
        append("""{"inlineData":{"mimeType":"image/png","data":"$data"}}""")
        append("""]},"finishReason":"$finish"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":7}}""")
    }

    @Test fun parsesInlineImageAndSendsImageConfig() = runTest {
        server.enqueue(ok(imageResponse()))
        val response = model().generate(ImageRequest("a cat", ImageAspect.Landscape, 1))
        assertEquals(1, response.images.size)
        assertEquals("image/png", response.images[0].mime)
        assertEquals(1, response.images[0].width)
        assertEquals(1, response.images[0].height)
        assertTrue(response.images[0].bytes.contentEquals(png))
        assertEquals(5, response.usage?.inputTokens)
        assertEquals(7, response.usage?.outputTokens)

        val request = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3-pro-image-preview:generateContent", request.path)
        assertEquals("gk", request.getHeader("x-goog-api-key"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val config = body["generationConfig"]!!.jsonObject
        val modalities = config["responseModalities"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("TEXT", "IMAGE"), modalities)
        assertEquals("3:2", config["imageConfig"]!!.jsonObject["aspectRatio"]!!.jsonPrimitive.content)
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals("a cat", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun sendsReferenceImagesInline() = runTest {
        server.enqueue(ok(imageResponse()))
        val ref = ImageReference(png, "image/png")
        model().generate(ImageRequest("make it blue", ImageAspect.Square, 1, listOf(ref)))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        val inline = parts[0].jsonObject["inlineData"]!!.jsonObject
        assertEquals("image/png", inline["mimeType"]!!.jsonPrimitive.content)
        assertEquals(pngB64, inline["data"]!!.jsonPrimitive.content)
        assertEquals("make it blue", parts[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun refusesBlockedPrompt() = runTest {
        server.enqueue(ok("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        try {
            model().generate(ImageRequest("a cat"))
            fail("expected content refused")
        } catch (e: ModelError.ContentRefused) {
            Unit
        }
    }

    @Test fun textOnlyResponseIsBadResponse() = runTest {
        server.enqueue(ok("""{"candidates":[{"content":{"parts":[{"text":"I cannot draw that."}]},"finishReason":"STOP"}]}"""))
        try {
            model().generate(ImageRequest("a cat"))
            fail("expected bad response")
        } catch (e: ModelError.BadResponse) {
            Unit
        }
    }

    @Test fun mapsSafetyFinishWithoutParts() = runTest {
        server.enqueue(ok("""{"candidates":[{"finishReason":"SAFETY"}]}"""))
        try {
            model().generate(ImageRequest("a cat"))
            fail("expected content refused")
        } catch (e: ModelError.ContentRefused) {
            Unit
        }
    }
}
