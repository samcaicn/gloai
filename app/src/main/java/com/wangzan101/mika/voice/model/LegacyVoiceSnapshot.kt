package com.wangzan101.mika.voice.model

import com.wangzan101.mika.calls.CallVoiceProfile
import com.wangzan101.mika.settings.VoiceSettingsState
import com.wangzan101.mika.voice.BailianRegion
import com.wangzan101.mika.voice.SpeechChoice

/** Immutable, read-only views used during migration. Secret material remains outside catalog state. */
data class LegacyVoiceSnapshot(
    val speech: LegacySpeechSnapshot = LegacySpeechSnapshot(),
    val tts: VoiceSettingsState = VoiceSettingsState(),
    val realtime: LegacyRealtimeSnapshot = LegacyRealtimeSnapshot(),
)

data class LegacySpeechSnapshot(
    val selection: SpeechChoice = SpeechChoice.Auto,
    val savedChoices: List<SpeechChoice> = emptyList(),
    val doubaoCredentials: VoiceCredentials? = null,
    val bailianCredentials: Map<BailianRegion, VoiceCredentials> = emptyMap(),
)

data class LegacyRealtimeSnapshot(
    val profiles: List<LegacyRealtimeProfileSnapshot> = emptyList(),
    val activeProfileId: String? = null,
)

data class LegacyRealtimeProfileSnapshot(
    val profile: CallVoiceProfile,
    val credentials: VoiceCredentials? = null,
)

fun interface LegacyVoiceSnapshotSource {
    fun readSnapshot(): LegacyVoiceSnapshot
}
