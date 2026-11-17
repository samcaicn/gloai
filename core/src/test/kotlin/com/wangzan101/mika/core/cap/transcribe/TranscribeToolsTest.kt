package com.wangzan101.mika.core.cap.transcribe

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.vision.LocScreen
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscribeToolsTest {
    private class FakePort : TranscribePort {
        var path: String? = null
        var language: String? = null
        override suspend fun transcribe(path: String, language: String?): Transcript {
            this.path = path
            this.language = language
            return Transcript("你好，世界", language)
        }
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun transcribesAWorkspaceAudioFile() = runTest {
        val port = FakePort()
        val d = toolDispatcher(LocScreen(), transcribe = port)
        val r = d.execute(Part.ToolCall("c", "audio_transcribe", """{"path":"/workspace/inbox/voice.m4a","language":"zh"}"""), ActionPass())
        assertEquals("true", r.content.s("ok"))
        assertEquals("你好，世界", r.content.s("text"))
        assertEquals("/workspace/inbox/voice.m4a", port.path)
        assertEquals("zh", port.language)
        assertTrue(r.observations.any { it.text == "你好，世界" })
    }

    @Test fun notOfferedWithoutAPort() = runTest {
        val d = toolDispatcher(LocScreen())
        assertFalse("audio_transcribe" in d.definitions().map { it.name })
        assertEquals("unknown_tool", d.execute(Part.ToolCall("c", "audio_transcribe", """{"path":"/workspace/inbox/a.wav"}"""), ActionPass()).content.s("error"))
    }

    @Test fun rejectsPathsOutsideWorkspace() = runTest {
        val d = toolDispatcher(LocScreen(), transcribe = FakePort())
        val r = d.execute(Part.ToolCall("c", "audio_transcribe", """{"path":"/sdcard/a.wav"}"""), ActionPass()).content
        assertEquals("invalid_arguments", r.s("error"))
    }

    @Test fun emptyTranscriptIsStillOk() = runTest {
        val empty = object : TranscribePort {
            override suspend fun transcribe(path: String, language: String?) = Transcript("")
        }
        val d = toolDispatcher(LocScreen(), transcribe = empty)
        val r = d.execute(Part.ToolCall("c", "audio_transcribe", """{"path":"/workspace/inbox/silence.wav"}"""), ActionPass()).content
        assertEquals("true", r.s("ok"))
        assertEquals("", r.s("text"))
    }
}
