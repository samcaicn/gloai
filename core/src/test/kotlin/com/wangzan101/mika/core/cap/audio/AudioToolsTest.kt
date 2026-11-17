package com.wangzan101.mika.core.cap.audio

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.SavedAudio
import com.wangzan101.mika.core.model.StoredAudio
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.vision.LocScreen
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioToolsTest {
    private class FakePort : AudioPort {
        override val modelName = "test-voice"
        override val capabilities = TtsCapabilities(voices = null, maxChars = 100, formats = setOf("wav", "mp3"))
        var requests = mutableListOf<TtsRequest>()
        var savedPath: String? = null
        var savedName: String? = null
        var spoken: String? = null

        override suspend fun synthesize(request: TtsRequest): TtsResponse {
            requests += request
            return TtsResponse(byteArrayOf(1, 2, 3, 4), "audio/wav", 24_000)
        }

        override suspend fun store(response: TtsResponse, nameHint: String?, text: String) =
            StoredAudio("/workspace/out/audio/line.wav", response.bytes.size, response.mime, response.durationMs)

        override suspend fun speak(request: TtsRequest) {
            spoken = request.text
        }

        override suspend fun saveToMusic(path: String, name: String?): SavedAudio {
            savedPath = path
            savedName = name
            return SavedAudio("Music/Mika/line.wav", "content://mika/audio", 4)
        }
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    private fun dispatcher(port: AudioPort? = FakePort(), save: Boolean = false, ask: Boolean = true, speak: Boolean = false) =
        toolDispatcher(LocScreen(), audio = port, audioSaveEnabled = save, audioAskBeforeSave = ask, audioSpeakTool = speak)

    private fun call(name: String, args: String) = Part.ToolCall("c-$name", name, args)

    @Test fun generatesAndStoresAFile() = runTest {
        val port = FakePort()
        val d = dispatcher(port)
        val r = d.execute(call("audio_generate", """{"text":"hello","voice":"Kore","speed":1.1,"format":"wav"}"""), ActionPass()).content
        assertEquals("true", r.s("ok"))
        assertEquals("/workspace/out/audio/line.wav", r.s("path"))
        assertEquals("audio/wav", r.s("mime"))
        assertEquals("test-voice", r.s("model"))
        assertEquals("hello", port.requests.single().text)
        assertEquals("Kore", port.requests.single().voice)
        assertEquals(1.1f, port.requests.single().speed)
    }

    @Test fun notOfferedWithoutVoiceModel() = runTest {
        val d = dispatcher(port = null)
        assertFalse("audio_generate" in d.definitions().map { it.name })
        assertEquals("unknown_tool", d.execute(call("audio_generate", """{"text":"hello"}"""), ActionPass()).content.s("error"))
    }

    @Test fun rejectsUnsupportedFormatBeforeTheModel() = runTest {
        val d = dispatcher(FakePort())
        val r = d.execute(call("audio_generate", """{"text":"hello","format":"flac"}"""), ActionPass()).content
        assertEquals("invalid_arguments", r.s("error"))
    }

    @Test fun saveIsOnlyOfferedWhenAllowedAndAsksByDefault() = runTest {
        assertFalse("audio_save" in dispatcher(save = false).definitions().map { it.name })
        val on = dispatcher(save = true, ask = true)
        assertTrue("audio_save" in on.definitions().map { it.name })
        val risk = on.approvalRisk("audio_save", JsonObject(mapOf("path" to JsonPrimitive("/workspace/out/audio/line.wav"))))
        assertTrue(risk?.ask != null)
        val quiet = dispatcher(save = true, ask = false)
        assertNull(quiet.approvalRisk("audio_save", JsonObject(mapOf("path" to JsonPrimitive("/workspace/out/audio/line.wav"))))?.ask)
    }

    @Test fun speakIsOnlyOfferedWhenAllowedAndPlaysThroughThePort() = runTest {
        assertFalse("speak" in dispatcher(speak = false).definitions().map { it.name })
        val port = FakePort()
        val d = dispatcher(port, speak = true)
        assertTrue("speak" in d.definitions().map { it.name })
        val r = d.execute(call("speak", """{"text":"hello"}"""), ActionPass()).content
        assertEquals("true", r.s("ok"))
        assertEquals("hello", port.spoken)
    }

    @Test fun saveWritesThroughThePort() = runTest {
        val port = FakePort()
        val d = dispatcher(port, save = true, ask = false)
        val r = d.execute(call("audio_save", """{"path":"/workspace/out/audio/line.wav","name":"line.wav"}"""), ActionPass()).content
        assertEquals("true", r.s("ok"))
        assertEquals("Music/Mika/line.wav", r.s("savedAs"))
        assertEquals("/workspace/out/audio/line.wav", port.savedPath)
        assertEquals("line.wav", port.savedName)
    }
}
