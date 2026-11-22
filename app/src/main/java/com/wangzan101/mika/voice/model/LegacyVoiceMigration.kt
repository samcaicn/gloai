package com.wangzan101.mika.voice.model

import com.wangzan101.mika.calls.CallVoiceProtocol
import com.wangzan101.mika.settings.VoiceChoice
import com.wangzan101.mika.voice.AsrLanguage
import com.wangzan101.mika.voice.BailianProtocol
import com.wangzan101.mika.voice.BailianRegion
import com.wangzan101.mika.voice.FunAsrProtocol
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.TranscribeMethod
import java.security.MessageDigest

sealed interface MigrationResult {
    data object AlreadyMigrated : MigrationResult
    data object Migrated : MigrationResult
    data class Failed(val error: VoiceModelError) : MigrationResult
}

/** Builds the complete new catalog first, writes owned secrets, then atomically activates it. */
class LegacyVoiceMigration(
    private val source: LegacyVoiceSnapshotSource,
    private val catalog: VoiceCatalogStore,
    private val credentials: VoiceCredentialStore,
) {
    @Synchronized
    fun migrateIfNeeded(): MigrationResult {
        if (catalog.state.value.legacyMigrationComplete) return MigrationResult.AlreadyMigrated
        return try {
            val legacy = source.readSnapshot()
            val builder = CatalogBuilder(legacy)
            val snapshot = builder.build()
            writeOwnedCredentials(legacy)
            catalog.replaceAfterMigration(snapshot)
            MigrationResult.Migrated
        } catch (error: Exception) {
            MigrationResult.Failed(VoiceModelError.from(error))
        }
    }

    private fun writeOwnedCredentials(legacy: LegacyVoiceSnapshot) {
        legacy.speech.doubaoCredentials?.let {
            credentials.save(migrationSecretId(DOUBAO_SERVICE), it)
        }
        legacy.speech.bailianCredentials.forEach { (region, credentialsToWrite) ->
            credentials.save(migrationSecretId(bailianServiceId(region)), credentialsToWrite)
        }
        legacy.realtime.profiles.forEach { snapshot ->
            snapshot.credentials?.let {
                credentials.save(migrationSecretId(realtimeServiceId(snapshot.profile.id)), it)
            }
        }
    }

    private class CatalogBuilder(legacy: LegacyVoiceSnapshot) {
        private val legacySpeech = legacy.speech
        private val legacyTts = legacy.tts
        private val legacyRealtime = legacy.realtime
        private val services = LinkedHashMap<String, VoiceService>()
        private val models = LinkedHashMap<ModelKey, VoiceModel>()
        private val verifications = mutableListOf<VoiceVerificationRecord>()

        fun build(): VoiceCatalogSnapshot {
            addService(SttSelectionController.SYSTEM_STT_SERVICE)
            addModel(SttSelectionController.SYSTEM_STT_MODEL)
            addModel(TtsSelectionController.SYSTEM_TTS_MODEL)
            addChoice(legacySpeech.selection)
            legacySpeech.savedChoices.forEach(::addChoice)
            if (legacySpeech.doubaoCredentials != null) addService(doubaoService())
            addTts(legacyTts.choice)
            legacyRealtime.profiles.forEach(::addRealtime)
            return VoiceCatalogSnapshot(
                services = services.values.toList(),
                models = models.values.toList(),
                selections = VoiceSelections(
                    stt = legacySpeech.selection.toSttSelection(),
                    // 全新安装(没有旧朗读选择)默认落在内置系统 TTS;已有的云端选择原样保留。
                    tts = legacyTts.choice?.ref() ?: TtsSelectionController.SYSTEM_TTS_REF,
                    realtime = legacyRealtime.profiles.firstOrNull {
                        it.profile.id == legacyRealtime.activeProfileId
                    }?.let { realtimeRef(it.profile) },
                ),
                verifications = verifications,
                legacyMigrationComplete = true,
            )
        }

        private fun addChoice(choice: SpeechChoice) {
            when (choice) {
                SpeechChoice.Auto, SpeechChoice.System, SpeechChoice.Off -> Unit
                is SpeechChoice.Provider -> {
                    addService(providerService(choice.providerId))
                    addModel(
                        VoiceModel(
                            serviceId = providerServiceId(choice.providerId),
                            id = choice.model,
                            displayName = choice.model,
                            stt = choice.method.binding(),
                        ),
                    )
                }
                is SpeechChoice.Doubao -> {
                    addService(doubaoService())
                    addModel(
                        VoiceModel(
                            serviceId = DOUBAO_SERVICE,
                            id = DOUBAO_MODEL,
                            displayName = "Doubao Streaming",
                            stt = SttBinding(SttProtocol.DoubaoStreaming, resourceId = choice.resourceId),
                        ),
                    )
                }
                is SpeechChoice.FunAsr -> {
                    val endpoint = FunAsrProtocol.normalize(choice.url)
                        ?: throw VoiceModelError.IncompleteConfig("Legacy Fun-ASR endpoint is invalid")
                    val serviceId = funAsrServiceId(endpoint)
                    addService(VoiceService(serviceId, "Fun-ASR", VoiceAccount.None))
                    addModel(
                        VoiceModel(
                            serviceId = serviceId,
                            id = FUNASR_MODEL,
                            displayName = "Fun-ASR Streaming",
                            stt = SttBinding(
                                SttProtocol.FunAsrStreaming,
                                endpoint = endpoint,
                                language = choice.language.code,
                            ),
                        ),
                    )
                }
                is SpeechChoice.Bailian -> {
                    val workspace = BailianProtocol.workspace(choice.workspace)
                        ?: throw VoiceModelError.IncompleteConfig("Legacy Bailian business space is invalid")
                    val serviceId = bailianServiceId(choice.region)
                    addService(
                        VoiceService(
                            id = serviceId,
                            name = "Bailian ${choice.region.name}",
                            account = VoiceAccount.VoiceOwned(migrationSecretId(serviceId)),
                        ),
                    )
                    addModel(
                        VoiceModel(
                            serviceId = serviceId,
                            id = choice.model,
                            displayName = choice.model,
                            stt = SttBinding(
                                SttProtocol.BailianStreaming,
                                endpoint = choice.region.endpoint(workspace),
                                region = choice.region.name,
                                businessSpace = workspace,
                                language = choice.language.code,
                            ),
                        ),
                    )
                }
            }
        }

        private fun addTts(choice: VoiceChoice?) {
            if (choice == null) return
            addService(providerService(choice.providerId))
            addModel(
                VoiceModel(
                    serviceId = providerServiceId(choice.providerId),
                    id = choice.model,
                    displayName = choice.model,
                    tts = TtsBinding(
                        when (choice.protocol) {
                            com.wangzan101.mika.settings.TtsProtocol.OpenAiSpeech -> TtsProtocol.OpenAiSpeech
                            com.wangzan101.mika.settings.TtsProtocol.GeminiTts -> TtsProtocol.GeminiTts
                            com.wangzan101.mika.settings.TtsProtocol.AndroidSystem ->
                                throw VoiceModelError.IncompleteConfig("Legacy TTS choice cannot be the built-in system engine")
                        },
                    ),
                ),
            )
        }

        private fun addRealtime(snapshot: LegacyRealtimeProfileSnapshot) {
            val profile = snapshot.profile
            val serviceId = realtimeServiceId(profile.id)
            addService(
                VoiceService(
                    id = serviceId,
                    name = profile.name,
                    account = VoiceAccount.VoiceOwned(migrationSecretId(serviceId)),
                ),
            )
            addModel(
                VoiceModel(
                    serviceId = serviceId,
                    id = profile.model,
                    displayName = profile.name,
                    realtime = RealtimeBinding(
                        protocol = when (profile.protocol) {
                            CallVoiceProtocol.VOLC_DUPLEX -> RealtimeProtocol.VolcDuplex
                            CallVoiceProtocol.OPENAI_REALTIME -> RealtimeProtocol.OpenAiRealtime
                        },
                        endpoint = profile.endpoint,
                        speaker = profile.speaker,
                    ),
                ),
            )
            val ref = realtimeRef(profile)
            if (profile.connectionTested) {
                verifications += VoiceVerificationRecord(ref, VoiceVerification.Verified)
            }
            profile.audioVerifications.sorted().forEach { key ->
                verifications += VoiceVerificationRecord(ref, VoiceVerification.Verified, key)
            }
        }

        private fun addService(service: VoiceService) {
            val existing = services[service.id]
            if (existing == null) {
                services[service.id] = service
            } else if (existing.account != service.account) {
                throw VoiceModelError.IncompleteConfig("Conflicting legacy voice accounts")
            }
        }

        private fun addModel(model: VoiceModel) {
            val key = ModelKey(model.serviceId, model.id)
            val existing = models[key]
            if (existing == null) {
                models[key] = model
                return
            }
            if (existing.stt != null && model.stt != null && existing.stt != model.stt ||
                existing.tts != null && model.tts != null && existing.tts != model.tts ||
                existing.realtime != null && model.realtime != null && existing.realtime != model.realtime
            ) {
                throw VoiceModelError.IncompleteConfig("Conflicting legacy voice model bindings")
            }
            models[key] = existing.copy(
                stt = existing.stt ?: model.stt,
                tts = existing.tts ?: model.tts,
                realtime = existing.realtime ?: model.realtime,
            )
        }
    }

    private data class ModelKey(val serviceId: String, val modelId: String)

    companion object {
        private const val DOUBAO_SERVICE = "stt-doubao"
        private const val DOUBAO_MODEL = "doubao"
        private const val FUNASR_MODEL = "funasr"
    }
}

/** Uses the persisted binding, never a model-name guess, to verify the migrated STT selection. */
fun SttSelection.toLegacySelection(snapshot: VoiceCatalogSnapshot): SpeechChoice = when (this) {
    SttSelection.Auto -> SpeechChoice.Auto
    SttSelection.Off -> SpeechChoice.Off
    is SttSelection.Model -> {
        if (ref == VoiceModelRef("android-system", "system-stt", VoiceCapability.STT)) {
            SpeechChoice.System
        } else {
            val model = snapshot.models.firstOrNull { it.serviceId == ref.serviceId && it.id == ref.modelId }
                ?: throw VoiceModelError.IncompleteConfig("Migrated STT selection is missing its model")
            val binding = model.stt
                ?: throw VoiceModelError.IncompleteConfig("Migrated STT selection is missing its binding")
            when (binding.protocol) {
                SttProtocol.AndroidSystem -> SpeechChoice.System
                SttProtocol.AudioTranscriptions,
                SttProtocol.ChatInputAudio,
                SttProtocol.GeminiInline,
                -> SpeechChoice.Provider(
                    providerId = snapshot.providerId(model.serviceId),
                    model = model.id,
                    method = binding.toTranscribeMethod(),
                )
                SttProtocol.DoubaoStreaming -> SpeechChoice.Doubao(binding.resourceId)
                SttProtocol.FunAsrStreaming -> SpeechChoice.FunAsr(
                    url = binding.endpoint,
                    language = AsrLanguage.of(binding.language),
                )
                SttProtocol.BailianStreaming -> SpeechChoice.Bailian(
                    model = model.id,
                    region = BailianRegion.entries.firstOrNull { it.name == binding.region }
                        ?: throw VoiceModelError.IncompleteConfig("Migrated Bailian region is invalid"),
                    workspace = binding.businessSpace,
                    language = AsrLanguage.of(binding.language),
                )
                else -> throw VoiceModelError.IncompleteConfig("Vendor STT has no legacy form")
            }
        }
    }
}

private fun VoiceCatalogSnapshot.providerId(serviceId: String): String {
    val account = services.firstOrNull { it.id == serviceId }?.account as? VoiceAccount.ExistingProvider
        ?: throw VoiceModelError.IncompleteConfig("Migrated provider service is missing")
    return account.providerId
}

private fun SttBinding.toTranscribeMethod(): TranscribeMethod = when (protocol) {
    SttProtocol.AudioTranscriptions -> TranscribeMethod.AudioTranscriptions
    SttProtocol.GeminiInline -> TranscribeMethod.GeminiInline
    SttProtocol.ChatInputAudio -> when {
        chatInputData == ChatInputAudioData.Base64 && includeTranscriptionInstruction -> TranscribeMethod.ChatInputAudio
        chatInputData == ChatInputAudioData.DataUri && includeTranscriptionInstruction -> TranscribeMethod.ChatInputAudioUri
        chatInputData == ChatInputAudioData.DataUri && !includeTranscriptionInstruction -> TranscribeMethod.ChatInputAudioOnly
        else -> throw VoiceModelError.IncompleteConfig("Migrated chat audio binding is invalid")
    }
    else -> throw VoiceModelError.IncompleteConfig("Migrated model is not provider STT")
}

private fun TranscribeMethod.binding(): SttBinding = when (this) {
    TranscribeMethod.AudioTranscriptions -> SttBinding(SttProtocol.AudioTranscriptions)
    TranscribeMethod.ChatInputAudio -> SttBinding(SttProtocol.ChatInputAudio)
    TranscribeMethod.GeminiInline -> SttBinding(SttProtocol.GeminiInline)
    TranscribeMethod.ChatInputAudioUri -> SttBinding(
        SttProtocol.ChatInputAudio,
        chatInputData = ChatInputAudioData.DataUri,
    )
    TranscribeMethod.ChatInputAudioOnly -> SttBinding(
        SttProtocol.ChatInputAudio,
        chatInputData = ChatInputAudioData.DataUri,
        includeTranscriptionInstruction = false,
    )
}

private fun SpeechChoice.toSttSelection(): SttSelection = when (this) {
    SpeechChoice.Auto -> SttSelection.Auto
    SpeechChoice.Off -> SttSelection.Off
    SpeechChoice.System -> SttSelection.Model(VoiceModelRef("android-system", "system-stt", VoiceCapability.STT))
    is SpeechChoice.Provider -> SttSelection.Model(
        VoiceModelRef(providerServiceId(providerId), model, VoiceCapability.STT),
    )
    is SpeechChoice.Doubao -> SttSelection.Model(
        VoiceModelRef("stt-doubao", "doubao", VoiceCapability.STT),
    )
    is SpeechChoice.FunAsr -> {
        val endpoint = FunAsrProtocol.normalize(url)
            ?: throw VoiceModelError.IncompleteConfig("Legacy Fun-ASR endpoint is invalid")
        SttSelection.Model(VoiceModelRef(funAsrServiceId(endpoint), "funasr", VoiceCapability.STT))
    }
    is SpeechChoice.Bailian -> SttSelection.Model(
        VoiceModelRef(bailianServiceId(region), model, VoiceCapability.STT),
    )
}

private fun VoiceChoice.ref() = VoiceModelRef(providerServiceId(providerId), model, VoiceCapability.TTS)

private fun realtimeRef(profile: com.wangzan101.mika.calls.CallVoiceProfile) =
    VoiceModelRef(realtimeServiceId(profile.id), profile.model, VoiceCapability.REALTIME)

private fun doubaoService() = VoiceService(
    id = "stt-doubao",
    name = "Doubao",
    account = VoiceAccount.VoiceOwned(migrationSecretId("stt-doubao")),
)

private fun providerService(providerId: String) = VoiceService(
    id = providerServiceId(providerId),
    name = providerId,
    account = VoiceAccount.ExistingProvider(providerId),
)

private fun providerServiceId(providerId: String) = "provider:$providerId"
private fun bailianServiceId(region: BailianRegion) = "stt-bailian:${region.name.lowercase()}"
private fun realtimeServiceId(profileId: String) = "realtime:$profileId"
private fun migrationSecretId(serviceId: String) = "legacy-migration:$serviceId"

private fun funAsrServiceId(normalizedUrl: String): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(normalizedUrl.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "stt-funasr:$hash"
}
