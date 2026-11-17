package com.wangzan101.mika.settings

import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSettingsTest {
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
        val choice = VoiceChoice("p1", "gpt-4o-mini-tts", TtsProtocol.OpenAiSpeech, "alloy", 1.2f)
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

    @Test fun incompleteChoiceIsDropped() {
        val prefs = MemoryPrefs()
        prefs.edit().putString("voice_settings", """{"providerId":"p","model":"m","protocol":"unknown"}""").commit()
        assertNull(VoiceSettings(prefs).state.value.choice)
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
