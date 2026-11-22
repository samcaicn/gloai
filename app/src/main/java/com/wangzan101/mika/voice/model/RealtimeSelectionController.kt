package com.wangzan101.mika.voice.model

import android.os.Build
import com.wangzan101.mika.calls.CallAudioMode
import com.wangzan101.mika.calls.CallRealtimeProvider
import com.wangzan101.mika.calls.CallVoiceProfile
import com.wangzan101.mika.calls.VolcRealtimeCredentials
import com.wangzan101.mika.voice.model.adapters.realtimeProfile

/** Reads and writes only Realtime selection; resolution and connection work happen through the registry. */
class RealtimeSelectionController(private val environment: VoiceModelEnvironment) {
    internal val catalog: VoiceCatalogStore get() = environment.catalog
    val migrated: Boolean get() = environment.catalog.state.value.legacyMigrationComplete

    fun current(): VoiceModelRef? = environment.catalog.state.value.selections.realtime

    fun select(ref: VoiceModelRef?) {
        environment.catalog.setRealtimeSelection(ref)
    }

    /** True while AI phone calls use the shared Realtime selection. */
    val callFollows: Boolean get() = environment.catalog.state.value.selections.callRealtime == null

    /** The Realtime model AI phone calls use: their own choice, else the shared one. */
    fun callCurrent(): VoiceModelRef? = environment.catalog.state.value.selections.let { it.callRealtime ?: it.realtime }

    /** Null follows the shared selection; otherwise calls use [ref], optionally with their own [speaker]. */
    fun selectForCalls(ref: VoiceModelRef?, speaker: String = "") {
        if (ref != null) requireBinding(ref)
        environment.catalog.setCallRealtimeSelection(ref, speaker)
    }

    /** The voice calls use on [ref]: the call-only voice when calls have their own model, else the model's. */
    private fun forCalls(model: VoiceModel): VoiceModel {
        val selections = environment.catalog.state.value.selections
        val speaker = selections.callSpeaker.takeIf { selections.callRealtime != null && it.isNotBlank() } ?: return model
        val binding = model.realtime ?: return model
        return model.copy(realtime = binding.copy(speaker = speaker))
    }

    fun callProvider(): CallRealtimeProvider {
        val ref = callCurrent() ?: throw VoiceModelError.IncompleteConfig("No Realtime model is selected")
        val resolved = requireModel(ref).let { it.copy(model = forCalls(it.model)) }
        return environment.registry.realtime(resolved).create(resolved)
    }

    fun callProfile(): CallVoiceProfile {
        val ref = callCurrent() ?: throw VoiceModelError.IncompleteConfig("No Realtime model is selected")
        val (service, model) = requireBinding(ref)
        return forCalls(model).realtimeProfile(
            credentialsConfigured = environment.accountResolver.credentialsConfigured(service),
            connectionTested = connectionTested(ref),
        )
    }

    /** Catalog lookup + capability validation; no account resolution or secret decryption. */
    private fun requireBinding(ref: VoiceModelRef): Pair<VoiceService, VoiceModel> {
        val snapshot = environment.catalog.state.value
        if (snapshot.error != null) throw VoiceModelError.IncompleteConfig("Voice catalog is not readable")
        if (ref.capability != VoiceCapability.REALTIME) throw VoiceModelError.Unsupported(VoiceCapability.REALTIME)
        val model = snapshot.models.firstOrNull { it.serviceId == ref.serviceId && it.id == ref.modelId }
            ?: throw VoiceModelError.IncompleteConfig("Selected Realtime model is not registered")
        if (model.realtime == null) throw VoiceModelError.Unsupported(VoiceCapability.REALTIME)
        val service = snapshot.services.firstOrNull { it.id == model.serviceId }
            ?: throw VoiceModelError.IncompleteConfig("Selected Realtime service is not registered")
        return service to model
    }

    /** Full account resolution (decrypts owned secrets); connection-time only. */
    fun requireModel(ref: VoiceModelRef): ResolvedVoiceModel {
        val (service, model) = requireBinding(ref)
        return environment.accountResolver.resolve(service, model)
    }

    /** Catalog model without account resolution; for profile display and speaker edits. */
    fun model(ref: VoiceModelRef): VoiceModel = requireBinding(ref).second

    fun provider(ref: VoiceModelRef): CallRealtimeProvider {
        val model = requireModel(ref)
        return environment.registry.realtime(model).create(model)
    }

    fun provider(): CallRealtimeProvider {
        val ref = current() ?: throw VoiceModelError.IncompleteConfig("No Realtime model is selected")
        return provider(ref)
    }

    fun profile(ref: VoiceModelRef): CallVoiceProfile {
        val (service, model) = requireBinding(ref)
        return model.realtimeProfile(
            credentialsConfigured = environment.accountResolver.credentialsConfigured(service),
            connectionTested = connectionTested(ref),
        )
    }

    fun profile(): CallVoiceProfile {
        val ref = current() ?: throw VoiceModelError.IncompleteConfig("No Realtime model is selected")
        return profile(ref)
    }

    /** Decrypted only at connection time; never returned through StateFlow or catalog state. */
    fun credentials(ref: VoiceModelRef): VolcRealtimeCredentials {
        val credentials = requireModel(ref).account.credentials
        return VolcRealtimeCredentials(credentials.apiKey, credentials.appId, credentials.accessKey)
    }

    fun credentials(): VolcRealtimeCredentials {
        val ref = current() ?: throw VoiceModelError.IncompleteConfig("No Realtime model is selected")
        return credentials(ref)
    }

    fun credentialsConfigured(ref: VoiceModelRef): Boolean = runCatching {
        environment.accountResolver.credentialsConfigured(requireBinding(ref).first)
    }.getOrDefault(false)

    fun connectionTested(ref: VoiceModelRef): Boolean =
        catalog.verification(ref) == VoiceVerification.Verified

    fun markConnectionTested(ref: VoiceModelRef, tested: Boolean) {
        catalog.markVerification(ref, if (tested) VoiceVerification.Verified else VoiceVerification.Unverified)
    }

    fun isAudioVerified(ref: VoiceModelRef, mode: CallAudioMode, fingerprint: String = deviceFingerprint()): Boolean =
        catalog.verification(ref, verificationKey(mode, fingerprint)) == VoiceVerification.Verified

    fun markAudioVerified(ref: VoiceModelRef, mode: CallAudioMode, verified: Boolean, fingerprint: String = deviceFingerprint()) {
        catalog.markVerification(ref, if (verified) VoiceVerification.Verified else VoiceVerification.Unverified, verificationKey(mode, fingerprint))
    }

    companion object {
        /** Deterministic migration identity: the legacy profile id is the service id suffix. */
        fun legacyProfileRef(profileId: String, model: String): VoiceModelRef =
            VoiceModelRef("realtime:$profileId", model, VoiceCapability.REALTIME)

        fun verificationKey(mode: CallAudioMode, fingerprint: String): String = "${mode.name}:$fingerprint"

        private fun deviceFingerprint(): String = Build.FINGERPRINT ?: "unknown"
    }
}
