package com.wangzan101.mika.media

import android.content.Context
import android.content.ContextWrapper
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.VoiceSettings
import com.wangzan101.mika.voice.model.FakeVoiceProviderAccountSource
import com.wangzan101.mika.voice.model.MemoryVoiceCatalogBackend
import com.wangzan101.mika.voice.model.TtsBinding
import com.wangzan101.mika.voice.model.TtsProtocol
import com.wangzan101.mika.voice.model.TtsSelectionController
import com.wangzan101.mika.voice.model.VoiceAccount
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCatalogStore
import com.wangzan101.mika.voice.model.VoiceModel
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceService
import com.wangzan101.mika.voice.model.voiceTestEnvironment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudUnifiedTest {
    private val context = ContextWrapper(null)

    /** [ContextWrapper.applicationContext] delegates to a null base in JVM tests; override it so the system-TTS adapter can build. */
    private class FakeAppContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private fun controller(): com.wangzan101.mika.voice.model.TtsSelectionController {
        val provider = Provider("p1", "Provider", ProviderConfig.Kind.OpenAiCompat, "https://provider.example/v1")
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.upsertService(VoiceService("provider:p1", "Provider", VoiceAccount.ExistingProvider("p1")))
        catalog.upsertModel(VoiceModel("provider:p1", "voice-1", "Voice", tts = TtsBinding(TtsProtocol.OpenAiSpeech)))
        val environment = voiceTestEnvironment(catalog, FakeVoiceProviderAccountSource(provider))
        environment.tts.select(VoiceModelRef("provider:p1", "voice-1", VoiceCapability.TTS))
        return environment.tts
    }

    @Test fun `migrated read-aloud resolves through the catalog and keeps voice speed and streaming`() = runTest {
        val tts = controller()
        val settings = VoiceSettings(MemoryPrefs())
        settings.setVoice("alloy")
        settings.setSpeed(1.25f)
        settings.setStreaming(true)

        val requests = mutableListOf<TtsRequest>()
        var streaming = false
        readAloudMigrated("hello", settings, context, tts, play = { _, reqs, s ->
            requests += reqs
            streaming = s
        })

        assertEquals(listOf(TtsRequest("hello", "alloy", 1.25f, "wav")), requests)
        assertTrue(streaming)
    }

    @Test fun `voice speed and streaming preferences survive selection changes`() {
        val settings = VoiceSettings(MemoryPrefs())
        settings.setVoice("alloy")
        settings.setSpeed(1.25f)
        settings.setStreaming(true)
        val tts = controller()

        // Changing the catalog selection must not touch usage preferences.
        tts.select(null)

        assertEquals("alloy", settings.state.value.voice)
        assertEquals(1.25f, settings.state.value.speed)
        assertTrue(settings.state.value.streaming)
    }

    @Test fun `disabled or unselected migrated voice skips silently`() = runTest {
        val settings = VoiceSettings(MemoryPrefs())
        val tts = controller()

        settings.setEnabled(false)
        readAloudMigrated("hello", settings, context, tts, play = { _, _, _ -> error("must not play") })

        settings.setEnabled(true)
        tts.select(null)
        readAloudMigrated("hello", settings, context, tts, play = { _, _, _ -> error("must not play") })
    }

    @Test fun `built-in system TTS reaches read-aloud without any provider`() = runTest {
        val provider = Provider("p1", "Provider", ProviderConfig.Kind.OpenAiCompat, "https://provider.example/v1")
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        val environment = voiceTestEnvironment(catalog, FakeVoiceProviderAccountSource(provider))
        environment.tts.select(TtsSelectionController.SYSTEM_TTS_REF)
        val settings = VoiceSettings(MemoryPrefs())

        val requests = mutableListOf<TtsRequest>()
        var modelName: String? = null
        readAloudMigrated("hello", settings, FakeAppContext(), environment.tts, play = { model, reqs, _ ->
            modelName = model.name
            requests += reqs
        })

        assertEquals("system-tts", modelName)
        assertEquals(listOf(TtsRequest("hello", null, 1f, "wav")), requests)
    }
}
