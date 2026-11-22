package com.wangzan101.mika.voice.model

import com.wangzan101.mika.calls.CallAudioMode
import com.wangzan101.mika.calls.CallVoiceProfile
import com.wangzan101.mika.calls.CallVoiceProfileBackend
import com.wangzan101.mika.calls.CallVoiceProfileStore
import com.wangzan101.mika.calls.CallVoiceProtocol
import com.wangzan101.mika.calls.OpenAiCallProtocol
import com.wangzan101.mika.calls.VolcRealtimeCredentials
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.VoiceChoice
import com.wangzan101.mika.settings.VoiceSettings
import com.wangzan101.mika.settings.VoiceSettingsState
import com.wangzan101.mika.voice.AsrLanguage
import com.wangzan101.mika.voice.BailianProtocol
import com.wangzan101.mika.voice.BailianRegion
import com.wangzan101.mika.voice.DoubaoCredentials
import com.wangzan101.mika.voice.DoubaoProtocol
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SpeechSettings
import com.wangzan101.mika.voice.TranscribeMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyVoiceMigrationTest {
    @Test
    fun `every speech choice and transcribe method keeps its exact protocol and selection`() {
        val systemRef = VoiceModelRef("android-system", "system-stt", VoiceCapability.STT)
        val cases = listOf(
            SpeechCase(SpeechChoice.Auto, SttSelection.Auto),
            SpeechCase(SpeechChoice.System, SttSelection.Model(systemRef)),
            SpeechCase(SpeechChoice.Off, SttSelection.Off),
            SpeechCase(
                SpeechChoice.Doubao(DoubaoProtocol.CONCURRENT),
                SttSelection.Model(VoiceModelRef("stt-doubao", "doubao", VoiceCapability.STT)),
                SttBinding(SttProtocol.DoubaoStreaming, resourceId = DoubaoProtocol.CONCURRENT),
            ),
            SpeechCase(
                SpeechChoice.FunAsr("wss://asr.example/ws", AsrLanguage.Japanese),
                SttSelection.Model(VoiceModelRef("stt-funasr:$FUNASR_HASH", "funasr", VoiceCapability.STT)),
                SttBinding(SttProtocol.FunAsrStreaming, endpoint = "wss://asr.example/ws", language = "ja"),
            ),
            SpeechCase(
                SpeechChoice.Bailian("fun-asr-realtime", BailianRegion.Singapore, "llm-workspace", AsrLanguage.Cantonese),
                SttSelection.Model(VoiceModelRef("stt-bailian:singapore", "fun-asr-realtime", VoiceCapability.STT)),
                SttBinding(
                    SttProtocol.BailianStreaming,
                    endpoint = BailianRegion.Singapore.endpoint("llm-workspace"),
                    region = "Singapore",
                    businessSpace = "llm-workspace",
                    language = "yue",
                ),
            ),
            SpeechCase(
                SpeechChoice.Provider("p1", "whisper-1", TranscribeMethod.AudioTranscriptions),
                providerSelection("whisper-1"),
                SttBinding(SttProtocol.AudioTranscriptions),
            ),
            SpeechCase(
                SpeechChoice.Provider("p1", "chat-audio", TranscribeMethod.ChatInputAudio),
                providerSelection("chat-audio"),
                SttBinding(SttProtocol.ChatInputAudio),
            ),
            SpeechCase(
                SpeechChoice.Provider("p1", "gemini-audio", TranscribeMethod.GeminiInline),
                providerSelection("gemini-audio"),
                SttBinding(SttProtocol.GeminiInline),
            ),
            SpeechCase(
                SpeechChoice.Provider("p1", "uri-audio", TranscribeMethod.ChatInputAudioUri),
                providerSelection("uri-audio"),
                SttBinding(SttProtocol.ChatInputAudio, chatInputData = ChatInputAudioData.DataUri),
            ),
            SpeechCase(
                SpeechChoice.Provider("p1", "audio-only", TranscribeMethod.ChatInputAudioOnly),
                providerSelection("audio-only"),
                SttBinding(
                    SttProtocol.ChatInputAudio,
                    chatInputData = ChatInputAudioData.DataUri,
                    includeTranscriptionInstruction = false,
                ),
            ),
        )

        cases.forEach { case ->
            val fixture = Fixture(
                LegacyVoiceSnapshot(
                    speech = LegacySpeechSnapshot(
                        selection = case.choice,
                        doubaoCredentials = if (case.choice is SpeechChoice.Doubao) {
                            VoiceCredentials(apiKey = "test-doubao-credential")
                        } else null,
                        bailianCredentials = if (case.choice is SpeechChoice.Bailian) {
                            mapOf(case.choice.region to VoiceCredentials(apiKey = "test-bailian-credential"))
                        } else emptyMap(),
                    ),
                ),
            )

            assertEquals(case.choice.toString(), MigrationResult.Migrated, fixture.migration.migrateIfNeeded())
            val snapshot = fixture.catalog.state.value
            assertEquals(SttSelectionController.SYSTEM_STT_SERVICE, snapshot.services.single { it.id == "android-system" })
            assertEquals(SttSelectionController.SYSTEM_STT_MODEL, snapshot.models.single { it.id == "system-stt" })
            assertEquals(case.choice.toString(), case.selection, snapshot.selections.stt)
            assertEquals(case.choice.toString(), case.choice, snapshot.selections.stt.toLegacySelection(snapshot))
            val ref = (case.selection as? SttSelection.Model)?.ref
            if (ref != null && ref != systemRef) {
                val model = snapshot.models.single { it.serviceId == ref.serviceId && it.id == ref.modelId }
                assertEquals(case.choice.toString(), case.binding, model.stt)
            } else {
                assertNull(case.binding)
            }
            if (case.choice is SpeechChoice.FunAsr) {
                assertEquals(FUNASR_HASH, ref!!.serviceId.removePrefix("stt-funasr:"))
            }
        }
    }

    @Test
    fun `complete legacy stores preserve shared models options profiles and every audio key`() {
        val cipher = TestCipher
        val speechPrefs = MemoryPrefs()
        val speech = SpeechSettings(speechPrefs, cipher)
        speech.saveFunAsr("https://asr.example/ws/", AsrLanguage.Japanese)
        speech.saveBailian(
            "test-beijing-credential",
            SpeechChoice.Bailian("fun-asr-realtime", BailianRegion.Beijing, "llm-beijing", AsrLanguage.English),
        )
        speech.saveBailian(
            "test-singapore-credential",
            SpeechChoice.Bailian("qwen3-asr-flash-realtime", BailianRegion.Singapore, "", AsrLanguage.Cantonese),
        )
        val legacySelection = SpeechChoice.Provider("provider-1", "shared-model", TranscribeMethod.ChatInputAudioOnly)
        speech.set(legacySelection)

        val voicePrefs = MemoryPrefs()
        val voice = VoiceSettings(voicePrefs)
        voice.setChoice(
            VoiceChoice(
                providerId = "provider-1",
                model = "shared-model",
                protocol = com.wangzan101.mika.settings.TtsProtocol.OpenAiSpeech,
                voice = "alloy",
                speed = 1.25f,
                streaming = true,
            ),
        )
        voice.setEnabled(false)
        voice.setAutoRead(true)
        voice.setSpeakTool(true)
        voice.setSaveEnabled(true)
        voice.setAskBeforeSave(false)
        voice.setMaxChars(345)
        val legacyVoiceState = voice.state.value

        val realtimeBackend = ProfileBackend()
        val realtime = CallVoiceProfileStore(realtimeBackend, cipher, newId = { "unused" })
        realtime.save(
            CallVoiceProfile(
                id = "volc-profile",
                name = "Volc profile",
                protocol = CallVoiceProtocol.VOLC_DUPLEX,
                endpoint = "wss://volc.example/ws",
                model = "volc-model",
                speaker = "speaker_a",
            ),
            VolcRealtimeCredentials(appId = "test-app", accessKey = "test-access"),
        )
        realtime.save(
            CallVoiceProfile(
                id = "openai-profile",
                name = "OpenAI profile",
                protocol = CallVoiceProtocol.OPENAI_REALTIME,
                endpoint = OpenAiCallProtocol.ENDPOINT,
                model = "gpt-realtime-test",
                speaker = "alloy",
            ),
            VolcRealtimeCredentials(apiKey = "test-openai-credential"),
        )
        realtime.markConnectionTested("volc-profile", true)
        realtime.markConnectionTested("openai-profile", true)
        realtime.markAudioVerified("volc-profile", CallAudioMode.DIRECT, true, "device-one")
        realtime.markAudioVerified("volc-profile", CallAudioMode.REDIRECT, true, "device-two")
        realtime.markAudioVerified("openai-profile", CallAudioMode.DIRECT, true, "device-one")

        val speechBefore = speechPrefs.all
        val voiceBefore = voicePrefs.all
        val realtimeBefore = realtimeBackend.value
        val source = {
            LegacyVoiceSnapshot(
                speech = speech.legacySnapshot(),
                tts = voice.legacySnapshot(),
                realtime = realtime.legacySnapshot("openai-profile"),
            )
        }
        val fixture = Fixture(source = source)

        assertEquals(MigrationResult.Migrated, fixture.migration.migrateIfNeeded())
        val snapshot = fixture.catalog.state.value
        assertTrue(snapshot.legacyMigrationComplete)
        assertEquals(legacySelection, snapshot.selections.stt.toLegacySelection(snapshot))

        val sharedRef = VoiceModelRef("provider:provider-1", "shared-model", VoiceCapability.STT)
        val shared = snapshot.models.single { it.serviceId == sharedRef.serviceId && it.id == sharedRef.modelId }
        assertEquals(
            SttBinding(
                SttProtocol.ChatInputAudio,
                chatInputData = ChatInputAudioData.DataUri,
                includeTranscriptionInstruction = false,
            ),
            shared.stt,
        )
        assertEquals(TtsBinding(TtsProtocol.OpenAiSpeech), shared.tts)
        assertEquals(sharedRef, snapshot.selections.stt.let { (it as SttSelection.Model).ref })
        assertEquals(sharedRef.copy(capability = VoiceCapability.TTS), snapshot.selections.tts)
        assertEquals(VoiceAccount.ExistingProvider("provider-1"), snapshot.services.single { it.id == "provider:provider-1" }.account)

        val funAsr = snapshot.models.single { it.serviceId == "stt-funasr:$FUNASR_HASH" }
        assertEquals(
            SttBinding(SttProtocol.FunAsrStreaming, endpoint = "wss://asr.example/ws", language = "ja"),
            funAsr.stt,
        )
        val beijing = snapshot.models.single { it.serviceId == "stt-bailian:beijing" }
        assertEquals(
            SttBinding(
                SttProtocol.BailianStreaming,
                endpoint = BailianRegion.Beijing.endpoint("llm-beijing"),
                region = "Beijing",
                businessSpace = "llm-beijing",
                language = "en",
            ),
            beijing.stt,
        )
        val singapore = snapshot.models.single { it.serviceId == "stt-bailian:singapore" }
        assertEquals("qwen3-asr-flash-realtime", singapore.id)
        assertEquals("yue", singapore.stt?.language)
        assertEquals(legacyVoiceState, voice.state.value)
        assertEquals("alloy", voice.state.value.choice?.voice)
        assertEquals(1.25f, voice.state.value.choice?.speed)
        assertEquals(true, voice.state.value.choice?.streaming)

        val volcRef = VoiceModelRef("realtime:volc-profile", "volc-model", VoiceCapability.REALTIME)
        val openAiRef = VoiceModelRef("realtime:openai-profile", "gpt-realtime-test", VoiceCapability.REALTIME)
        assertEquals(openAiRef, snapshot.selections.realtime)
        assertEquals(
            RealtimeBinding(RealtimeProtocol.VolcDuplex, endpoint = "wss://volc.example/ws", speaker = "speaker_a"),
            fixture.catalog.requireModel(volcRef).realtime,
        )
        assertEquals(
            RealtimeBinding(RealtimeProtocol.OpenAiRealtime, endpoint = OpenAiCallProtocol.ENDPOINT, speaker = "alloy"),
            fixture.catalog.requireModel(openAiRef).realtime,
        )
        assertEquals(
            setOf("", "DIRECT:device-one", "REDIRECT:device-two"),
            snapshot.verifications.filter { it.ref == volcRef }.map { it.scope }.toSet(),
        )
        assertEquals(
            setOf("", "DIRECT:device-one"),
            snapshot.verifications.filter { it.ref == openAiRef }.map { it.scope }.toSet(),
        )
        assertTrue(snapshot.verifications.all { it.status == VoiceVerification.Verified })

        val restored = VoiceCatalogStore(fixture.backend)
        assertEquals(snapshot.verifications, restored.state.value.verifications)
        assertEquals(VoiceVerification.Verified, restored.verification(volcRef))
        assertEquals(VoiceVerification.Verified, restored.verification(volcRef, "REDIRECT:device-two"))
        assertEquals(VoiceVerification.Unverified, restored.verification(openAiRef, "REDIRECT:device-two"))
        listOf(
            "legacy-migration:stt-bailian:beijing",
            "legacy-migration:stt-bailian:singapore",
            "legacy-migration:realtime:volc-profile",
            "legacy-migration:realtime:openai-profile",
        ).forEach { secretId -> assertTrue(fixture.credentials.credentialsConfigured(secretId)) }

        assertEquals(speechBefore, speechPrefs.all)
        assertEquals(voiceBefore, voicePrefs.all)
        assertEquals(realtimeBefore, realtimeBackend.value)
        assertEquals(1, fixture.backend.writeAttempts)
    }

    @Test
    fun `both TTS protocols retain model bindings and usage options`() {
        val cases = listOf(
            com.wangzan101.mika.settings.TtsProtocol.OpenAiSpeech to TtsProtocol.OpenAiSpeech,
            com.wangzan101.mika.settings.TtsProtocol.GeminiTts to TtsProtocol.GeminiTts,
        )
        cases.forEach { (legacyProtocol, protocol) ->
            val legacyState = VoiceSettingsState(
                enabled = false,
                choice = VoiceChoice("p-tts", "voice-model", legacyProtocol, "Kore", 1.75f, streaming = false),
                autoRead = true,
                speakTool = true,
                saveEnabled = true,
                askBeforeSave = false,
                maxCharsPerSpeak = 456,
            )
            val fixture = Fixture(LegacyVoiceSnapshot(tts = legacyState))

            assertEquals(MigrationResult.Migrated, fixture.migration.migrateIfNeeded())
            val snapshot = fixture.catalog.state.value
            val ref = VoiceModelRef("provider:p-tts", "voice-model", VoiceCapability.TTS)
            assertEquals(ref, snapshot.selections.tts)
            assertEquals(TtsBinding(protocol), fixture.catalog.requireModel(ref).tts)
            assertEquals(legacyState, fixture.source.snapshot.tts)
            assertEquals("Kore", fixture.source.snapshot.tts.choice?.voice)
            assertEquals(1.75f, fixture.source.snapshot.tts.choice?.speed)
            assertEquals(false, fixture.source.snapshot.tts.choice?.streaming)
        }
    }

    @Test
    fun `a fresh install with no legacy TTS choice defaults to the built-in system engine`() {
        val fixture = Fixture(LegacyVoiceSnapshot())

        assertEquals(MigrationResult.Migrated, fixture.migration.migrateIfNeeded())
        val snapshot = fixture.catalog.state.value
        assertEquals(TtsSelectionController.SYSTEM_TTS_REF, snapshot.selections.tts)
        assertEquals(
            TtsSelectionController.SYSTEM_TTS_MODEL,
            snapshot.models.single { it.id == "system-tts" },
        )
        assertNull(fixture.source.snapshot.tts.choice)
    }

    @Test
    fun `an existing legacy cloud TTS choice is preserved through migration`() {
        val legacyState = VoiceSettingsState(
            choice = VoiceChoice(
                providerId = "p-tts",
                model = "voice-model",
                protocol = com.wangzan101.mika.settings.TtsProtocol.OpenAiSpeech,
                voice = "Kore",
                speed = 1.75f,
            ),
        )
        val fixture = Fixture(LegacyVoiceSnapshot(tts = legacyState))

        assertEquals(MigrationResult.Migrated, fixture.migration.migrateIfNeeded())
        val snapshot = fixture.catalog.state.value
        val ref = VoiceModelRef("provider:p-tts", "voice-model", VoiceCapability.TTS)
        assertEquals(ref, snapshot.selections.tts)
        assertEquals(TtsBinding(TtsProtocol.OpenAiSpeech), fixture.catalog.requireModel(ref).tts)
        assertEquals("Kore", fixture.source.snapshot.tts.choice?.voice)
        assertEquals(1.75f, fixture.source.snapshot.tts.choice?.speed)
    }

    @Test
    fun `failure after credential write keeps legacy active and retry is idempotent`() {
        val legacySelection = SpeechChoice.Doubao(DoubaoProtocol.CONCURRENT)
        val speechPrefs = MemoryPrefs()
        val speech = SpeechSettings(speechPrefs, TestCipher)
        speech.saveDoubao(DoubaoCredentials(apiKey = "test-doubao-credential"), legacySelection.resourceId)
        val legacyBefore = speechPrefs.all
        val source = {
            LegacyVoiceSnapshot(speech = speech.legacySnapshot())
        }
        val fixture = Fixture(source = source)
        fixture.backend.failNextCatalogWrite()

        assertTrue(fixture.migration.migrateIfNeeded() is MigrationResult.Failed)
        assertFalse(fixture.catalog.state.value.legacyMigrationComplete)
        assertEquals(legacySelection, speech.choice.value)
        assertEquals(legacyBefore, speechPrefs.all)
        assertEquals(1, fixture.credentialBackend.writeAttempts)
        assertEquals(1, fixture.backend.writeAttempts)

        assertEquals(MigrationResult.Migrated, fixture.migration.migrateIfNeeded())
        assertTrue(fixture.catalog.state.value.legacyMigrationComplete)
        assertEquals(1, fixture.catalog.state.value.services.count { it.id == "stt-doubao" })
        assertEquals(1, fixture.catalog.state.value.models.count { it.serviceId == "stt-doubao" })
        assertEquals(legacySelection, fixture.catalog.state.value.selections.stt.toLegacySelection(fixture.catalog.state.value))
        assertEquals(1, fixture.credentialBackend.values.size)
        assertEquals(2, fixture.credentialBackend.writeAttempts)
        assertEquals(2, fixture.backend.writeAttempts)
        assertEquals(legacyBefore, speechPrefs.all)

        assertEquals(MigrationResult.AlreadyMigrated, fixture.migration.migrateIfNeeded())
        assertEquals(2, fixture.source.reads)
        assertEquals(2, fixture.credentialBackend.writeAttempts)
        assertEquals(2, fixture.backend.writeAttempts)
    }

    @Test
    fun `inactive Doubao credentials are retained without inventing a resource selection`() {
        val speechPrefs = MemoryPrefs()
        val speech = SpeechSettings(speechPrefs, TestCipher)
        speech.saveDoubao(DoubaoCredentials(apiKey = "test-doubao-credential"), DoubaoProtocol.CONCURRENT)
        speech.set(SpeechChoice.Off)
        val legacyBefore = speechPrefs.all
        val fixture = Fixture(
            source = { LegacyVoiceSnapshot(speech = speech.legacySnapshot()) },
        )

        assertEquals(MigrationResult.Migrated, fixture.migration.migrateIfNeeded())
        val snapshot = fixture.catalog.state.value
        assertEquals(SttSelection.Off, snapshot.selections.stt)
        assertEquals(1, snapshot.services.count { it.id == "stt-doubao" })
        assertTrue(snapshot.models.none { it.serviceId == "stt-doubao" })
        assertTrue(fixture.credentials.credentialsConfigured("legacy-migration:stt-doubao"))
        assertEquals(SpeechChoice.Off, speech.choice.value)
        assertEquals(legacyBefore, speechPrefs.all)
    }

    @Test
    fun `credential failure happens before the only catalog commit`() {
        val fixture = Fixture(
            LegacyVoiceSnapshot(
                speech = LegacySpeechSnapshot(
                    selection = SpeechChoice.Doubao(),
                    doubaoCredentials = VoiceCredentials(apiKey = "test-doubao-credential"),
                ),
            ),
        )
        fixture.credentialBackend.failWrites = true

        assertTrue(fixture.migration.migrateIfNeeded() is MigrationResult.Failed)
        assertFalse(fixture.catalog.state.value.legacyMigrationComplete)
        assertEquals(SttSelection.Auto, fixture.catalog.state.value.selections.stt)
        assertEquals(0, fixture.backend.writeAttempts)
        assertEquals(SpeechChoice.Doubao(), fixture.source.snapshot.speech.selection)
    }

    private data class SpeechCase(
        val choice: SpeechChoice,
        val selection: SttSelection,
        val binding: SttBinding? = null,
    )

    private class Fixture(
        snapshot: LegacyVoiceSnapshot = LegacyVoiceSnapshot(),
        source: (() -> LegacyVoiceSnapshot)? = null,
    ) {
        val backend = CatalogBackend()
        val credentialBackend = CredentialBackend()
        val catalog = VoiceCatalogStore(backend)
        val credentials = VoiceCredentialStore(credentialBackend, TestCipher)
        val source = RecordingSource(source ?: { snapshot })
        val migration = LegacyVoiceMigration(this.source, catalog, credentials)
    }

    private class RecordingSource(
        private val block: () -> LegacyVoiceSnapshot,
    ) : LegacyVoiceSnapshotSource {
        var reads = 0
            private set
        lateinit var snapshot: LegacyVoiceSnapshot
            private set

        override fun readSnapshot(): LegacyVoiceSnapshot {
            reads++
            return block().also { snapshot = it }
        }
    }

    private class CatalogBackend : VoiceCatalogBackend {
        var value: String? = null
        var writeAttempts = 0
        private var failNext = false

        fun failNextCatalogWrite() {
            failNext = true
        }

        override fun read(): String? = value

        override fun write(value: String): Boolean {
            writeAttempts++
            if (failNext) {
                failNext = false
                return false
            }
            this.value = value
            return true
        }
    }

    private class CredentialBackend : VoiceCredentialBackend {
        val values = mutableMapOf<String, String>()
        var writeAttempts = 0
        var failWrites = false

        override fun read(secretId: String): String? = values[secretId]

        override fun write(secretId: String, value: String): Boolean {
            writeAttempts++
            if (failWrites) return false
            values[secretId] = value
            return true
        }

        override fun delete(secretId: String) {
            values.remove(secretId)
        }
    }

    private class ProfileBackend(var value: String? = null) : CallVoiceProfileBackend {
        override fun read(): String? = value
        override fun write(value: String): Boolean {
            this.value = value
            return true
        }
    }

    private object TestCipher : KeyCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain.reversedArray()
        override fun decrypt(data: ByteArray): ByteArray = data.reversedArray()
    }

    private companion object {
        const val FUNASR_HASH = "e47d5c6f705a6642a69767b4e8a2bf484172d6f1483318de434bb372036487a0"

        fun providerSelection(model: String) =
            SttSelection.Model(VoiceModelRef("provider:p1", model, VoiceCapability.STT))
    }
}
