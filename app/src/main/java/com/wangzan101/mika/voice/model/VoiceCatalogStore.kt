package com.wangzan101.mika.voice.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

interface VoiceCatalogBackend {
    fun read(): String?
    fun write(value: String): Boolean
    /** Removes the persisted snapshot; used only by the confirm-guarded catalog repair. */
    fun clear() {}
}

class VoiceCatalogStore(
    private val backend: VoiceCatalogBackend,
    migrated: Boolean = false,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutable = MutableStateFlow(readSnapshot(migrated))
    val state: StateFlow<VoiceCatalogSnapshot> = mutable.asStateFlow()

    @Synchronized
    fun upsertService(service: VoiceService) {
        commit { current ->
            val existing = current.services.firstOrNull { it.id == service.id }
            val services = if (existing == null) current.services + service
            else current.services.map { if (it.id == service.id) service else it }
            current.copy(
                services = services,
                verifications = if (existing == service) current.verifications
                else current.verifications.filterNot { it.ref.serviceId == service.id },
            )
        }
    }

    @Synchronized
    fun upsertModel(model: VoiceModel) {
        commit { current ->
            val existing = current.models.firstOrNull { it.sameIdentity(model.serviceId, model.id) }
            val models = if (existing == null) current.models + model
            else current.models.map { if (it.sameIdentity(model.serviceId, model.id)) model else it }
            current.copy(
                models = models,
                selections = pruneSelections(current.selections, models),
                verifications = current.verifications.filter { record ->
                    if (record.ref.serviceId != model.serviceId || record.ref.modelId != model.id) true
                    else verificationIdentity(existing?.binding(record.ref.capability)) == verificationIdentity(model.binding(record.ref.capability))
                },
            )
        }
    }

    /**
     * Replaces every model of [serviceId] with [models] in one snapshot (a list refresh). Verifications stay for
     * models whose binding is unchanged; selections of models that are gone fall back as on delete.
     */
    @Synchronized
    fun replaceModels(serviceId: String, models: List<VoiceModel>) {
        require(models.all { it.serviceId == serviceId })
        commit { current ->
            val before = current.models.filter { it.serviceId == serviceId }.associateBy { it.id }
            val all = current.models.filterNot { it.serviceId == serviceId } + models
            val after = models.associateBy { it.id }
            current.copy(
                models = all,
                selections = pruneSelections(current.selections, all),
                verifications = current.verifications.filter { record ->
                    if (record.ref.serviceId != serviceId) return@filter true
                    val next = after[record.ref.modelId] ?: return@filter false
                    verificationIdentity(before[record.ref.modelId]?.binding(record.ref.capability)) ==
                        verificationIdentity(next.binding(record.ref.capability))
                },
            )
        }
    }

    @Synchronized
    fun deleteModel(ref: VoiceModelRef) {
        commit { current ->
            val models = current.models.filterNot { it.sameIdentity(ref.serviceId, ref.modelId) }
            current.copy(
                models = models,
                selections = pruneSelections(current.selections, models),
                verifications = current.verifications.filterNot {
                    it.ref.serviceId == ref.serviceId && it.ref.modelId == ref.modelId
                },
            )
        }
    }

    /** Deletes one service with every dependent model, selection and verification in a single snapshot. */
    @Synchronized
    fun deleteService(serviceId: String) {
        deleteServices(setOf(serviceId))
    }

    /** Deletes several services atomically; each dependent model, selection and verification goes in the same snapshot. */
    @Synchronized
    fun deleteServices(serviceIds: Set<String>) {
        if (serviceIds.isEmpty()) return
        commit { current ->
            val services = current.services.filterNot { it.id in serviceIds }
            val models = current.models.filterNot { it.serviceId in serviceIds }
            current.copy(
                services = services,
                models = models,
                selections = pruneSelections(current.selections, models),
                verifications = current.verifications.filterNot { it.ref.serviceId in serviceIds },
            )
        }
    }

    /** Drops every verification under a service without touching its models; used when account configuration changes. */
    @Synchronized
    fun invalidateVerification(serviceId: String) {
        commit { current ->
            current.copy(verifications = current.verifications.filterNot { it.ref.serviceId == serviceId })
        }
    }

    @Synchronized
    fun setSttSelection(selection: SttSelection) {
        commit { it.copy(selections = it.selections.copy(stt = selection)) }
    }

    @Synchronized
    fun setTtsSelection(ref: VoiceModelRef?) {
        commit { it.copy(selections = it.selections.copy(tts = ref)) }
    }

    @Synchronized
    fun setRealtimeSelection(ref: VoiceModelRef?) {
        commit { it.copy(selections = it.selections.copy(realtime = ref)) }
    }

    /** Null makes calls follow the shared Realtime selection again and drops the call-only voice. */
    @Synchronized
    fun setCallRealtimeSelection(ref: VoiceModelRef?, speaker: String = "") {
        commit {
            it.copy(selections = it.selections.copy(callRealtime = ref, callSpeaker = if (ref == null) "" else speaker.trim()))
        }
    }

    /** Engine, think model, turn silence, barge-in and idle hang-up for voice calls. */
    @Synchronized
    fun setCallStyle(
        engine: CallEngine? = null,
        think: ThinkModel? = null,
        silenceMs: Int? = null,
        bargeIn: Boolean? = null,
        idleMinutes: Int? = null,
    ) {
        commit {
            val s = it.selections
            it.copy(selections = s.copy(
                engine = engine ?: s.engine,
                think = think ?: s.think,
                silenceMs = (silenceMs ?: s.silenceMs).coerceIn(VoiceSelections.MIN_SILENCE_MS, VoiceSelections.MAX_SILENCE_MS),
                bargeIn = bargeIn ?: s.bargeIn,
                idleMinutes = (idleMinutes ?: s.idleMinutes).let {
                    if (it <= VoiceSelections.NEVER_IDLE) VoiceSelections.NEVER_IDLE
                    else it.coerceIn(VoiceSelections.MIN_IDLE_MINUTES, VoiceSelections.MAX_IDLE_MINUTES)
                },
            ))
        }
    }

    /**
     * AI phone calls' own engine and listen/speak models. Every argument is the full new value: null follows the
     * voice-call choice. The call voice is dropped when calls follow the shared speak model.
     */
    @Synchronized
    fun setPhoneVoice(engine: CallEngine?, stt: VoiceModelRef?, tts: VoiceModelRef?, ttsVoice: String) {
        commit {
            it.copy(selections = it.selections.copy(
                callEngine = engine,
                callStt = stt,
                callTts = tts,
                callTtsVoice = if (tts == null) "" else ttsVoice.trim(),
            ))
        }
    }

    @Synchronized
    fun markVerification(ref: VoiceModelRef, status: VoiceVerification, scope: String = "") {
        commit { current ->
            val others = current.verifications.filterNot { it.ref == ref && it.scope == scope }
            current.copy(
                verifications = if (status == VoiceVerification.Unverified) others
                else others + VoiceVerificationRecord(ref, status, scope),
            )
        }
    }

    fun verification(ref: VoiceModelRef, scope: String = ""): VoiceVerification =
        state.value.verifications.firstOrNull { it.ref == ref && it.scope == scope }?.status ?: VoiceVerification.Unverified

    fun requireModel(ref: VoiceModelRef): VoiceModel {
        val model = state.value.models.firstOrNull { it.sameIdentity(ref.serviceId, ref.modelId) }
        requireNotNull(model) { "Voice model is not registered" }
        require(model.binding(ref.capability) != null) { "Voice model does not support ${ref.capability}" }
        return model
    }

    @Synchronized
    fun replaceAfterMigration(snapshot: VoiceCatalogSnapshot) {
        commit(allowPreMigrationReplacement = true) { snapshot }
    }

    /**
     * Clears a corrupt, unreadable snapshot and returns the catalog to a clean pre-migration state.
     * Refuses to touch a readable catalog; the repair affordance must never overwrite data silently.
     */
    @Synchronized
    fun resetForRepair() {
        val current = mutable.value
        check(current.error != null) { "Voice catalog is readable; refusing to reset" }
        runCatching { backend.clear() }
        mutable.value = VoiceCatalogSnapshot(legacyMigrationComplete = false)
    }

    private fun commit(
        allowPreMigrationReplacement: Boolean = false,
        transform: (VoiceCatalogSnapshot) -> VoiceCatalogSnapshot,
    ) {
        val current = mutable.value
        check(current.error == null || (allowPreMigrationReplacement && !current.legacyMigrationComplete)) {
            "Voice catalog is not readable"
        }
        val next = transform(current).copy(error = null, rawSnapshot = null)
        validate(next)
        val encoded = json.encodeToString(next)
        val written = runCatching { backend.write(encoded) }.getOrDefault(false)
        check(written) { "Voice catalog write failed" }
        mutable.value = next
    }

    private fun readSnapshot(migrated: Boolean): VoiceCatalogSnapshot {
        var raw: String? = null
        return try {
            raw = backend.read()
            if (raw == null) return VoiceCatalogSnapshot(legacyMigrationComplete = migrated)
            val root = json.parseToJsonElement(raw).jsonObject
            val unsupported = hasUnsupportedSchema(root)
            val decoded = json.decodeFromJsonElement<VoiceCatalogSnapshot>(
                if (unsupported) withoutUnknownProtocols(root) else root,
            )
            if (!unsupported) validate(decoded)
            decoded.copy(
                error = if (unsupported) VoiceCatalogError.UnsupportedSchema else null,
                rawSnapshot = if (unsupported) raw else null,
            )
        } catch (_: Exception) {
            VoiceCatalogSnapshot(
                legacyMigrationComplete = migrated,
                error = VoiceCatalogError.ReadFailed,
                rawSnapshot = raw,
            )
        }
    }

    private fun validate(snapshot: VoiceCatalogSnapshot) {
        require(snapshot.schemaVersion == 1) { "Unsupported voice catalog schema" }
        require(snapshot.services.all { it.id.isNotBlank() && it.name.isNotBlank() })
        require(snapshot.services.map { it.id }.distinct().size == snapshot.services.size)
        snapshot.services.forEach { service ->
            when (val account = service.account) {
                is VoiceAccount.ExistingProvider -> require(account.providerId.isNotBlank())
                is VoiceAccount.VoiceOwned -> require(account.secretId.isNotBlank())
                VoiceAccount.None -> Unit
            }
        }
        val serviceIds = snapshot.services.mapTo(HashSet()) { it.id }
        require(snapshot.models.all {
            it.serviceId.isNotBlank() && it.id.isNotBlank() && it.displayName.isNotBlank() && it.serviceId in serviceIds
        })
        require(snapshot.models.map { it.serviceId to it.id }.distinct().size == snapshot.models.size)
        validateSelection(snapshot.selections.stt, snapshot.models)
        validateOptionalSelection(snapshot.selections.tts, VoiceCapability.TTS, snapshot.models)
        validateOptionalSelection(snapshot.selections.realtime, VoiceCapability.REALTIME, snapshot.models)
        validateOptionalSelection(snapshot.selections.callRealtime, VoiceCapability.REALTIME, snapshot.models)
        validateOptionalSelection(snapshot.selections.callStt, VoiceCapability.STT, snapshot.models)
        validateOptionalSelection(snapshot.selections.callTts, VoiceCapability.TTS, snapshot.models)
        require(snapshot.verifications.map { it.ref to it.scope }.distinct().size == snapshot.verifications.size)
        snapshot.verifications.forEach { record -> validateRef(record.ref, record.ref.capability, snapshot.models) }
    }

    private fun validateSelection(selection: SttSelection, models: List<VoiceModel>) {
        if (selection is SttSelection.Model) validateRef(selection.ref, VoiceCapability.STT, models)
    }

    private fun validateOptionalSelection(ref: VoiceModelRef?, capability: VoiceCapability, models: List<VoiceModel>) {
        if (ref != null) validateRef(ref, capability, models)
    }

    private fun validateRef(ref: VoiceModelRef, capability: VoiceCapability, models: List<VoiceModel>) {
        require(ref.capability == capability && ref.serviceId.isNotBlank() && ref.modelId.isNotBlank())
        if (ref == SYSTEM_STT_REF || ref == SYSTEM_TTS_REF) return
        require(models.any { it.sameIdentity(ref.serviceId, ref.modelId) && it.binding(capability) != null }) {
            "Voice selection does not resolve to a configured capability"
        }
    }

    private fun pruneSelections(selections: VoiceSelections, models: List<VoiceModel>): VoiceSelections {
        val stt = (selections.stt as? SttSelection.Model)?.ref
        return selections.copy(
            stt = if (stt == null || stt == SYSTEM_STT_REF || stt.selectable(models)) selections.stt else SttSelection.Off,
            tts = selections.tts?.takeIf { it == SYSTEM_TTS_REF || it.selectable(models) },
            realtime = selections.realtime?.takeIf { it.selectable(models) },
            callRealtime = selections.callRealtime?.takeIf { it.selectable(models) },
            callSpeaker = if (selections.callRealtime?.selectable(models) == true) selections.callSpeaker else "",
            callStt = selections.callStt?.takeIf { it.selectable(models) },
            callTts = selections.callTts?.takeIf { it == SYSTEM_TTS_REF || it.selectable(models) },
            callTtsVoice = if (selections.callTts?.let { it == SYSTEM_TTS_REF || it.selectable(models) } == true) selections.callTtsVoice else "",
        )
    }

    private fun VoiceModelRef.selectable(models: List<VoiceModel>): Boolean =
        models.any { it.sameIdentity(serviceId, modelId) && it.binding(capability) != null }

    private fun VoiceModel.sameIdentity(serviceId: String, id: String): Boolean =
        this.serviceId == serviceId && this.id == id

    private fun VoiceModel.binding(capability: VoiceCapability): Any? = when (capability) {
        VoiceCapability.STT -> stt
        VoiceCapability.TTS -> tts
        VoiceCapability.REALTIME -> realtime
    }

    /** Binding fields that determine connection identity; cosmetic fields (Realtime speaker) are ignored. */
    private fun verificationIdentity(binding: Any?): Any? = when (binding) {
        is RealtimeBinding -> RealtimeBinding(binding.protocol, binding.endpoint, speaker = "")
        is TtsBinding -> binding.copy(voice = "", voices = emptyList(), voiceNames = emptyMap())
        else -> binding
    }

    private fun hasUnsupportedSchema(root: JsonObject): Boolean {
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: 1
        if (version != 1) return true
        val models = root["models"] as? JsonArray ?: return false
        return models.filterIsInstance<JsonObject>().any { model ->
            unknownProtocol(model, "stt", SttProtocol.entries.map { it.name }) ||
                unknownProtocol(model, "tts", TtsProtocol.entries.map { it.name }) ||
                unknownProtocol(model, "realtime", RealtimeProtocol.entries.map { it.name })
        }
    }

    private fun withoutUnknownProtocols(root: JsonObject): JsonObject {
        val models = root["models"] as? JsonArray ?: return root
        val sanitized = models.map { element ->
            val model = element as? JsonObject
            if (model == null) element else {
                val fields = listOf(
                    "stt" to SttProtocol.entries.map { it.name },
                    "tts" to TtsProtocol.entries.map { it.name },
                    "realtime" to RealtimeProtocol.entries.map { it.name },
                )
                JsonObject(model + fields.map { (field, protocols) ->
                    field to if (unknownProtocol(model, field, protocols)) JsonNull else model[field] ?: JsonNull
                })
            }
        }
        return JsonObject(root + ("models" to JsonArray(sanitized)))
    }

    private fun unknownProtocol(model: JsonObject, field: String, protocols: List<String>): Boolean {
        val binding = model[field] as? JsonObject ?: return false
        val protocol = (binding["protocol"] as? JsonPrimitive)?.contentOrNull ?: return false
        return protocol !in protocols
    }

    private companion object {
        val SYSTEM_STT_REF = VoiceModelRef("android-system", "system-stt", VoiceCapability.STT)
        val SYSTEM_TTS_REF = VoiceModelRef("android-system", "system-tts", VoiceCapability.TTS)
    }
}
