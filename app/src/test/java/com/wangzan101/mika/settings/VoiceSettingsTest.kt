package com.wangzan101.mika.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun existingVoiceIsMarkedOnlyOnceAndKeepsItsSettings() {
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { error("No credentials in this fixture") })
        val p = store.connect("Speech", ProviderConfig.Kind.OpenAiCompat, "https://tts.test/v1", null, emptyList())
        val prefs = MemoryPrefs()
        val choice = VoiceChoice(p.id, "private-tts", TtsProtocol.OpenAiSpeech, "kaguya", speed = 1.25f)
        val settings = VoiceSettings(prefs)
        settings.setChoice(choice)

        settings.reconcileTtsChoice(store)
        assertEquals(setOf("private-tts"), store.provider(p.id)?.ttsModels)
        assertEquals(choice, settings.state.value.choice)

        store.setTtsModel(p.id, "private-tts", false)
        val restarted = VoiceSettings(prefs)
        restarted.reconcileTtsChoice(store)
        assertTrue(store.provider(p.id)!!.ttsModels.isEmpty())
        assertNull(restarted.state.value.choice)
        assertNull(VoiceSettings(prefs).state.value.choice)
    }

    @Test fun blankLegacyModelIsNotMarkedAndTheChoiceIsCleared() {
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { error("No credentials in this fixture") })
        val p = store.connect("Speech", ProviderConfig.Kind.OpenAiCompat, "https://tts.test/v1", null, emptyList())
        val prefs = MemoryPrefs()
        val settings = VoiceSettings(prefs)
        settings.setChoice(VoiceChoice(p.id, " ", TtsProtocol.OpenAiSpeech, "kaguya"))

        settings.reconcileTtsChoice(store)
        assertTrue(store.provider(p.id)!!.ttsModels.isEmpty())
        assertTrue(store.provider(p.id)!!.typedModels.isEmpty())
        assertNull(settings.state.value.choice)
        assertTrue(prefs.getBoolean("voice_tts_role_migrated", false))
    }

    @Test fun importingVoiceKeepsAnExistingMainModelWithATtsLikeName() {
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { error("No credentials in this fixture") })
        val p = store.connect("Relay", ProviderConfig.Kind.OpenAiCompat, "https://relay.test/v1", null, emptyList())
        val model = "xxx-tts-proxy"
        assertTrue(store.use(com.wangzan101.mika.core.model.ModelRole.Main, p.id, model, ModelOptions.baseOf(p)))
        val settings = VoiceSettings(MemoryPrefs())
        val original = VoiceChoice(p.id, model, TtsProtocol.OpenAiSpeech, "alloy")
        settings.setChoice(original)
        settings.reconcileTtsChoice(store)
        assertEquals(original, settings.state.value.choice)
        assertTrue(model in store.provider(p.id)!!.ttsModels)
        assertEquals(model, store.configFor(com.wangzan101.mika.core.model.ModelRole.Main)?.model)
    }

    @Test fun settingsPageAndChatShareLiveConfiguration() {
        val prefs = MemoryPrefs()
        val context = object : ContextWrapper(null), VoiceSettings.Owner {
            override val voiceSettings = VoiceSettings(prefs)
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        }
        val chat = VoiceSettings.get(context)
        val settings = VoiceSettings.get(context)
        val choice = VoiceChoice("p", "tts", TtsProtocol.OpenAiSpeech, "alloy", 1.2f)
        settings.setChoice(choice)
        settings.setAutoRead(true)
        assertSame(settings, chat)
        assertEquals(choice, chat.state.value.choice)
        assertTrue(chat.state.value.autoRead)
        settings.setEnabled(false)
        assertFalse(chat.state.value.enabled)
        assertSame(chat, VoiceSettings.get(context))
    }

    @Test fun freshDefaultsAreReadableButDoNotAutoSpeak() {
        val state = VoiceSettings(MemoryPrefs()).state.value
        assertTrue(state.enabled)
        assertNull(state.choice)
        assertFalse(state.autoRead)
        assertFalse(state.speakTool)
        assertEquals(VoiceSettingsState.DEFAULT_MAX_CHARS, state.maxCharsPerSpeak)
    }

    @Test fun choiceAndFlagsSurviveARestart() {
        val prefs = MemoryPrefs()
        val settings = VoiceSettings(prefs)
        val choice = VoiceChoice("p1", "gpt-4o-mini-tts", TtsProtocol.OpenAiSpeech, "alloy", 1.2f, streaming = true)
        settings.setChoice(choice)
        settings.setAutoRead(true)
        settings.setSpeakTool(true)
        settings.setMaxChars(300)
        settings.setEnabled(false)

        val restarted = VoiceSettings(prefs).state.value
        assertEquals(choice, restarted.choice)
        assertTrue(restarted.autoRead)
        assertTrue(restarted.speakTool)
        assertEquals(300, restarted.maxCharsPerSpeak)
        assertFalse(restarted.enabled)
    }

    @Test fun badJsonFallsBackToDefaults() {
        val prefs = MemoryPrefs()
        prefs.edit().putString("voice_settings", "not json").commit()
        val state = VoiceSettings(prefs).state.value
        assertNull(state.choice)
        assertFalse(state.autoRead)
        assertEquals(VoiceSettingsState.DEFAULT_MAX_CHARS, state.maxCharsPerSpeak)
    }

    @Test fun existingChoiceDefaultsToFullAudio() {
        val prefs = MemoryPrefs()
        prefs.edit().putString(
            "voice_settings",
            """{"providerId":"p","model":"tts","protocol":"openai-speech","voice":"alloy"}""",
        ).commit()
        assertFalse(VoiceSettings(prefs).state.value.choice!!.streaming)
    }

    @Test fun incompleteChoiceIsDropped() {
        val prefs = MemoryPrefs()
        prefs.edit().putString("voice_settings", """{"providerId":"p","model":"m","protocol":"unknown"}""").commit()
        assertNull(VoiceSettings(prefs).state.value.choice)
    }

    @Test fun speedFromUiAndLegacyPreferencesStaysInRange() {
        val prefs = MemoryPrefs()
        val settings = VoiceSettings(prefs)
        val choice = VoiceChoice("provider", "tts", TtsProtocol.OpenAiSpeech, "alloy")
        for ((input, expected) in listOf(0f to .25f, 100f to 4f, Float.NaN to 1f)) {
            settings.setChoice(choice.copy(speed = input))
            assertEquals(expected, settings.state.value.choice?.speed)
            assertEquals(expected, VoiceSettings(prefs).state.value.choice?.speed)
        }
        prefs.edit().putString("voice_settings",
            """{"providerId":"provider","model":"tts","protocol":"openai-speech","speed":100}""").commit()
        assertEquals(4f, VoiceSettings(prefs).state.value.choice?.speed)
    }

    @Test fun maxCharsAndSpeedAreClamped() {
        val prefs = MemoryPrefs()
        val settings = VoiceSettings(prefs)
        settings.setMaxChars(0)
        assertEquals(1, settings.state.value.maxCharsPerSpeak)
        settings.setMaxChars(999_999)
        assertEquals(VoiceSettingsState.MAX_CHARS_LIMIT, settings.state.value.maxCharsPerSpeak)
        settings.setChoice(VoiceChoice("p", "m", TtsProtocol.GeminiTts, "Kore", 9f))
        assertEquals(4.0f, settings.state.value.choice!!.speed)
    }

    @Test fun capabilitiesFollowProtocol() {
        val settings = VoiceSettings(MemoryPrefs())
        assertNull(settings.capabilities())
        settings.setChoice(VoiceChoice("p", "m", TtsProtocol.OpenAiSpeech, "alloy"))
        assertTrue("mp3" in settings.capabilities()!!.formats)
        settings.setChoice(VoiceChoice("p", "m", TtsProtocol.GeminiTts, "Kore"))
        assertEquals(setOf("wav"), settings.capabilities()!!.formats)
    }
}
