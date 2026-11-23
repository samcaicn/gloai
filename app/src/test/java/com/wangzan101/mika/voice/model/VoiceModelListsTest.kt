package com.wangzan101.mika.voice.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModelListsTest {
    private val s = "openai"
    private val sttBinding = SttBinding(SttProtocol.AudioTranscriptions, endpoint = "https://x/v1")
    private val ttsBinding = TtsBinding(TtsProtocol.OpenAiSpeech, "https://x/v1", voice = "alloy", voices = listOf("alloy", "echo"))
    private val builtin = listOf(
        VoiceModel(s, "whisper-1", "whisper-1", stt = sttBinding),
        VoiceModel(s, "tts-1", "tts-1", tts = ttsBinding),
    )

    @Test fun `listed models copy the vendor binding for what the list says they do`() {
        val merged = VoiceModelLists.merge(builtin, s, VoiceListing(listOf(
            ListedModel("gpt-4o-transcribe", caps = setOf(VoiceCapability.STT)),
            ListedModel("gpt-4o-mini-tts", caps = setOf(VoiceCapability.TTS)),
        )))
        val listen = merged.single { it.id == "gpt-4o-transcribe" }
        assertEquals(sttBinding, listen.stt)
        assertNull(listen.tts)
        assertEquals(VoiceModelOrigin.Listed, listen.origin)
        assertFalse(listen.guessed)
        assertEquals(ttsBinding, merged.single { it.id == "gpt-4o-mini-tts" }.tts)
    }

    @Test fun `a model the list says nothing about is kept for listening and speaking both`() {
        val merged = VoiceModelLists.merge(builtin, s, VoiceListing(listOf(ListedModel("gpt-5"))))
        val other = merged.single { it.id == "gpt-5" }
        assertTrue(other.guessed)
        assertEquals(setOf(VoiceCapability.STT, VoiceCapability.TTS), other.capabilities)
    }

    @Test fun `a capability with no binding to copy is not invented`() {
        val merged = VoiceModelLists.merge(builtin, s, VoiceListing(listOf(ListedModel("gpt-realtime", caps = setOf(VoiceCapability.REALTIME)))))
        // 没有实时模板时,照样留下来,当作「其他模型」给听写/朗读
        val model = merged.single { it.id == "gpt-realtime" }
        assertTrue(model.guessed)
        assertNull(model.realtime)
    }

    @Test fun `an account set up with fewer models borrows the vendor's built-in binding`() {
        val sttOnly = builtin.filter { it.stt != null }
        val merged = VoiceModelLists.merge(sttOnly, s, VoiceListing(listOf(ListedModel("gpt-4o-mini-tts", caps = setOf(VoiceCapability.TTS)))),
            fallback = builtin)
        val speak = merged.single { it.id == "gpt-4o-mini-tts" }
        assertFalse(speak.guessed)
        assertEquals(ttsBinding.protocol, speak.tts?.protocol)
        assertEquals(VoiceCapability.TTS, VoiceModelLists.manual(sttOnly, s, "custom-tts", VoiceCapability.TTS, fallback = builtin)
            ?.capabilities?.single())
    }

    @Test fun `built-in and typed-in models survive a refresh, listed ones are replaced`() {
        val manual = VoiceModel(s, "my-asr", "my-asr", stt = sttBinding, origin = VoiceModelOrigin.Manual)
        val oldListed = VoiceModel(s, "old", "old", stt = sttBinding, origin = VoiceModelOrigin.Listed)
        val chosen = VoiceModel(s, "chosen", "chosen", stt = sttBinding, origin = VoiceModelOrigin.Listed)
        val merged = VoiceModelLists.merge(builtin + manual + oldListed + chosen, s,
            VoiceListing(listOf(ListedModel("whisper-1", caps = setOf(VoiceCapability.STT)), ListedModel("new"))), keep = setOf("chosen"))
        assertEquals(listOf("whisper-1", "tts-1", "my-asr", "new", "chosen"), merged.map { it.id })
        assertEquals(VoiceModelOrigin.Builtin, merged.first().origin)
    }

    @Test fun `fetched voices replace the list and keep a default that is in it`() {
        val merged = VoiceModelLists.merge(builtin, s, VoiceListing(voices = listOf("v1", "v2"), voiceNames = mapOf("v1" to "One")))
        val tts = merged.single { it.id == "tts-1" }.tts!!
        assertEquals(listOf("v1", "v2"), tts.voices)
        assertEquals("v1", tts.voice)
        assertEquals(mapOf("v1" to "One"), tts.voiceNames)
        assertNull(merged.single { it.id == "whisper-1" }.tts)
    }

    @Test fun `per-model voices win over the account list`() {
        val merged = VoiceModelLists.merge(builtin, s, VoiceListing(
            listOf(ListedModel("tts-1", caps = setOf(VoiceCapability.TTS), voices = listOf("echo", "own"))), voices = listOf("shared"),
        ))
        val tts = merged.single { it.id == "tts-1" }.tts!!
        assertEquals(listOf("echo", "own"), tts.voices)
        assertEquals("echo", tts.voice)
    }

    @Test fun `retarget fixes a derived binding`() {
        val merged = VoiceModelLists.merge(builtin, s, VoiceListing(listOf(ListedModel("x", caps = setOf(VoiceCapability.TTS))))) { m ->
            m.copy(tts = m.tts?.copy(region = m.id))
        }
        assertEquals("x", merged.single { it.id == "x" }.tts?.region)
        assertEquals("", merged.single { it.id == "tts-1" }.tts?.region)
    }

    @Test fun `a typed-in id becomes a manual model or adds the capability to an existing one`() {
        val fresh = VoiceModelLists.manual(builtin, s, " my-tts ", VoiceCapability.TTS)
        assertNotNull(fresh)
        assertEquals("my-tts", fresh!!.id)
        assertEquals(VoiceModelOrigin.Manual, fresh.origin)
        assertEquals(ttsBinding, fresh.tts)
        val both = VoiceModelLists.manual(builtin, s, "whisper-1", VoiceCapability.TTS)!!
        assertEquals(setOf(VoiceCapability.STT, VoiceCapability.TTS), both.capabilities)
        assertEquals(VoiceModelOrigin.Builtin, both.origin)
        assertNull(VoiceModelLists.manual(builtin, s, "  ", VoiceCapability.STT))
        assertNull(VoiceModelLists.manual(builtin, s, "rt", VoiceCapability.REALTIME))
    }

    @Test fun `refresh writes the merge into the catalog and keeps selections`() {
        val catalog = VoiceCatalogStore(InMemoryBackend())
        catalog.upsertService(VoiceService(s, "OpenAI", VoiceAccount.None))
        builtin.forEach(catalog::upsertModel)
        catalog.replaceModels(s, VoiceModelLists.merge(builtin, s, VoiceListing(listOf(ListedModel("new", caps = setOf(VoiceCapability.STT))))))
        catalog.setTtsSelection(VoiceModelRef(s, "tts-1", VoiceCapability.TTS))
        catalog.replaceModels(s, VoiceModelLists.merge(catalog.state.value.models, s, VoiceListing()))
        assertEquals(listOf("whisper-1", "tts-1"), catalog.state.value.models.filter { it.serviceId == s }.map { it.id })
        assertEquals(VoiceModelRef(s, "tts-1", VoiceCapability.TTS), catalog.state.value.selections.tts)
    }

    private class InMemoryBackend : VoiceCatalogBackend {
        private var value: String? = null
        override fun read(): String? = value
        override fun write(value: String): Boolean { this.value = value; return true }
    }
}
