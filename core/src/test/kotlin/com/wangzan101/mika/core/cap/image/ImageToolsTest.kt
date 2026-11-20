package com.wangzan101.mika.core.cap.image

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.GeneratedImage
import com.wangzan101.mika.core.model.ImageAspect
import com.wangzan101.mika.core.model.ImageCapabilities
import com.wangzan101.mika.core.model.ImageReference
import com.wangzan101.mika.core.model.ImageRequest
import com.wangzan101.mika.core.model.ImageResponse
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.vision.LocScreen
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ImageToolsTest {
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
    )

    private class FakePort : ImagePort {
        override val modelName = "test-image"
        override val capabilities = ImageCapabilities(
            supportsEdit = true, maxReferences = 2, maxCount = 2,
            aspects = setOf(ImageAspect.Square, ImageAspect.Portrait),
        )
        var requests = mutableListOf<ImageRequest>()
        var savedPath: String? = null
        var savedName: String? = null

        override suspend fun generate(request: ImageRequest): ImageResponse {
            requests += request
            return ImageResponse(List(request.count) { GeneratedImage(ByteArray(4), "image/png", 1, 1, "revised") })
        }

        override suspend fun readReference(path: String): ImageReference = ImageReference(ByteArray(4), "image/png")

        override suspend fun store(image: GeneratedImage, nameHint: String?, prompt: String): StoredImage =
            StoredImage("/workspace/out/images/test.png", image.bytes.size, image.width, image.height)

        override suspend fun saveToGallery(path: String, name: String?): SavedImage {
            savedPath = path
            savedName = name
            return SavedImage("Pictures/Mika/test.png", "content://mika/test", 4)
        }
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    private fun dispatcher(port: ImagePort? = FakePort(), save: Boolean = false, ask: Boolean = true, limit: Int = 8) =
        toolDispatcher(LocScreen(), image = port, imageSaveEnabled = save, imageAskBeforeSave = ask, imageMaxPerTask = limit)

    private fun call(name: String, args: String) = Part.ToolCall("c-${name.hashCode()}", name, args)

    @Test fun generateStoresAndReturnsPaths() = runTest {
        val port = FakePort()
        val d = dispatcher(port)
        val r = d.execute(call("image_generate", """{"prompt":"a cat","aspect":"1:1","count":2}"""), ActionPass()).content
        assertEquals("true", r.s("ok"))
        assertEquals("test-image", r.s("model"))
        assertEquals(2, r["images"]!!.jsonArray.size)
        assertEquals("/workspace/out/images/test.png", r["images"]!!.jsonArray[0].jsonObject.s("path"))
        assertEquals("2", r.s("taskUsed"))
        assertEquals(2, port.requests.single().count)
    }

    @Test fun generateIsNotOfferedWithoutModel() = runTest {
        val d = dispatcher(port = null)
        assertFalse("image_generate" in d.definitions().map { it.name })
        assertEquals("unknown_tool", d.execute(call("image_generate", """{"prompt":"a cat"}"""), ActionPass()).content.s("error"))
    }

    @Test fun editPassesReferencesAndNeedsThePath() = runTest {
        val port = FakePort()
        val d = dispatcher(port)
        val r = d.execute(call("image_edit", """{"paths":["/workspace/inbox/a.png"],"prompt":"make it blue"}"""), ActionPass()).content
        assertEquals("true", r.s("ok"))
        assertEquals(1, port.requests.single().references.size)
        val bad = d.execute(call("image_edit", """{"paths":["a.png","b.png","c.png"],"prompt":"x"}"""), ActionPass()).content
        assertEquals("invalid_arguments", bad.s("error"))
    }

    @Test fun saveIsOnlyOfferedWhenAllowedAndAsksByDefault() = runTest {
        val off = dispatcher(save = false)
        assertFalse("image_save" in off.definitions().map { it.name })

        val on = dispatcher(FakePort(), save = true, ask = true)
        assertTrue("image_save" in on.definitions().map { it.name })
        val risk = on.approvalRisk("image_save", JsonObject(mapOf("path" to kotlinx.serialization.json.JsonPrimitive("/workspace/out/images/a.png"))))
        assertTrue(risk?.ask != null)

        val noAsk = dispatcher(FakePort(), save = true, ask = false)
        val quiet = noAsk.approvalRisk("image_save", JsonObject(mapOf("path" to kotlinx.serialization.json.JsonPrimitive("/workspace/out/images/a.png"))))
        assertNull(quiet?.ask)
    }

    @Test fun saveWritesThroughThePort() = runTest {
        val port = FakePort()
        val d = dispatcher(port, save = true, ask = false)
        val r = d.execute(call("image_save", """{"path":"/workspace/out/images/a.png","name":"cat.png"}"""), ActionPass()).content
        assertEquals("true", r.s("ok"))
        assertEquals("Pictures/Mika/test.png", r.s("savedAs"))
        assertEquals("/workspace/out/images/a.png", port.savedPath)
        assertEquals("cat.png", port.savedName)
    }

    @Test fun taskLimitStopsBeforeTheModelCall() = runTest {
        val port = FakePort()
        val d = dispatcher(port, limit = 2)
        val ok = d.execute(call("image_generate", """{"prompt":"a","count":2}"""), ActionPass()).content
        assertEquals("true", ok.s("ok"))
        val stopped = d.execute(call("image_generate", """{"prompt":"b"}"""), ActionPass()).content
        assertEquals("task_image_limit_reached", stopped.s("error"))
        assertEquals(1, port.requests.size)
    }

    @Test fun unsupportedAspectIsRejectedByTheModelCapabilities() = runTest {
        val d = dispatcher(FakePort())
        val r = d.execute(call("image_generate", """{"prompt":"a","aspect":"16:9"}"""), ActionPass()).content
        assertEquals("invalid_arguments", r.s("error"))
    }
}
