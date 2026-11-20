package com.wangzan101.mika.core.model

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

class OpenAiImagesModelTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
    )

    private fun model(caps: ImageCapabilities = caps()) = OpenAiImagesModel(
        server.url("/").toString().trimEnd('/'), "sk", "gpt-image-1", caps, OkHttpClient(),
    )

    private fun caps() = ImageCapabilities(
        supportsEdit = true, maxReferences = 2, maxCount = 2,
        aspects = setOf(ImageAspect.Square, ImageAspect.Portrait, ImageAspect.Landscape),
    )

    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun parsesBase64AndSendsGenerationRequest() = runTest {
        server.enqueue(ok("""{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}","revised_prompt":"a cat"}],"usage":{"input_tokens":10,"output_tokens":20}}"""))
        val response = model().generate(ImageRequest("a cat", ImageAspect.Square, 1))
        assertEquals(1, response.images.size)
        assertEquals("image/png", response.images[0].mime)
        assertEquals(1, response.images[0].width)
        assertEquals(1, response.images[0].height)
        assertEquals("a cat", response.images[0].revisedPrompt)
        assertArrayEquals(png, response.images[0].bytes)
        assertEquals(10, response.usage?.inputTokens)
        assertEquals(20, response.usage?.outputTokens)

        val request = server.takeRequest()
        assertEquals("/images/generations", request.path)
        assertEquals("Bearer sk", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("gpt-image-1", body["model"]!!.jsonPrimitive.content)
        assertEquals("a cat", body["prompt"]!!.jsonPrimitive.content)
        assertEquals("1024x1024", body["size"]!!.jsonPrimitive.content)
        assertEquals("1", body["n"]!!.jsonPrimitive.content)
    }

    @Test fun downloadsReturnedUrl() = runTest {
        server.enqueue(ok("""{"data":[{"url":"${server.url("/generated.png")}"}]}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png)))
        val response = model().generate(ImageRequest("a cat"))
        assertEquals(1, response.images.size)
        assertEquals("image/png", response.images[0].mime)
        assertArrayEquals(png, response.images[0].bytes)
        assertEquals("/images/generations", server.takeRequest().path)
        assertEquals("/generated.png", server.takeRequest().path)
    }

    @Test fun editsSendMultipartWithReference() = runTest {
        server.enqueue(ok("""{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}"""))
        val ref = ImageReference(png, "image/png")
        model().generate(ImageRequest("make it blue", ImageAspect.Square, 1, listOf(ref)))
        val request = server.takeRequest()
        assertEquals("/images/edits", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"model\""))
        assertTrue(body.contains("gpt-image-1"))
        assertTrue(body.contains("name=\"image\""))
        assertTrue(body.contains("make it blue"))
    }

    @Test fun mapsAuthError() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        try {
            model().generate(ImageRequest("a cat"))
            fail("expected auth error")
        } catch (e: ModelError.Auth) {
            Unit
        }
    }

    @Test fun rejectsUnsupportedRequestBeforeSending() = runTest {
        try {
            model().generate(ImageRequest("a cat", ImageAspect.Tall, 1))
            fail("expected unsupported aspect")
        } catch (e: ImageError.Unsupported) {
            Unit
        }
        try {
            model().generate(ImageRequest("a cat", count = 3))
            fail("expected too many images")
        } catch (e: ImageError.Unsupported) {
            Unit
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun missingUsageIsNull() = runTest {
        server.enqueue(ok("""{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}"""))
        assertNull(model().generate(ImageRequest("a cat")).usage)
    }
}
