package com.wangzan101.mika.media

import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import com.wangzan101.mika.settings.TtsProtocol
import com.wangzan101.mika.settings.VoiceChoice
import com.wangzan101.mika.settings.VoiceSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudTest {
    @Test fun disabledVoiceNeverResolvesAModelOrSynthesizes() = runTest {
        val prefs = MemoryPrefs()
        val settings = VoiceSettings(prefs)
        settings.setChoice(VoiceChoice("p", "m", TtsProtocol.OpenAiSpeech, "alloy"))
        settings.setAutoRead(true)
        settings.setEnabled(false)
        val restored = VoiceSettings(prefs)

        readAloud("answer", restored,
            model = { _, _ -> error("disabled voice must not resolve a provider") },
            play = { error("disabled voice must not play audio") },
        )
    }

    @Test fun enabledVoiceUsesSettingsAndDisablingBlocksTheNextCall() = runTest {
        val settings = VoiceSettings(MemoryPrefs())
        settings.setChoice(VoiceChoice("p", "m", TtsProtocol.OpenAiSpeech, "alloy", 1.2f))
        settings.setMaxChars(4)
        val requests = mutableListOf<TtsRequest>()
        var resolved = 0
        var played = 0
        val model = object : TtsModel {
            override val name = "test"
            override val capabilities = TtsCapabilities()
            override suspend fun synthesize(request: TtsRequest): TtsResponse {
                requests += request
                return TtsResponse(byteArrayOf(1, 2), "audio/wav")
            }
        }
        suspend fun speak() = readAloud("answer", settings,
            model = { _, _ -> resolved++; model },
            play = { played++ },
        )

        speak()
        settings.setEnabled(false)
        speak()

        assertEquals(1, resolved)
        assertEquals(1, played)
        assertEquals(listOf(TtsRequest("answ", "alloy", 1.2f, "wav")), requests)
    }
}
