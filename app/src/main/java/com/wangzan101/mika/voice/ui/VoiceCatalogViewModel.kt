package com.wangzan101.mika.voice.ui

import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import com.wangzan101.mika.voice.model.MigrationResult
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.TtsSelectionController
import com.wangzan101.mika.voice.model.VoiceAccountManager
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCatalogSnapshot
import com.wangzan101.mika.voice.model.VoiceCatalogStore
import com.wangzan101.mika.voice.model.VoiceCredentials
import com.wangzan101.mika.voice.model.VoiceModel
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.VoiceModelError
import com.wangzan101.mika.voice.model.VoiceModelProbe
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceProbeState
import com.wangzan101.mika.voice.model.VoiceService
import com.wangzan101.mika.voice.model.VoiceVerification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wangzan101.mika.settings.ui.models.providerVoiceUsage

/** A voice probe's outcome; only [Success] is gated on an unchanged configuration fingerprint. */
sealed interface ProbeResult {
    data object Success : ProbeResult
    data class Failed(val error: VoiceModelError) : ProbeResult
}

/** Per-fingerprint probe progress shown by the catalog page. */
sealed interface VoiceProbeUi {
    data object Idle : VoiceProbeUi
    data object Running : VoiceProbeUi
    data class Listening(val transcript: String) : VoiceProbeUi
    data class Failed(val error: VoiceModelError) : VoiceProbeUi
    data object Verified : VoiceProbeUi
}

/**
 * Catalog page state and actions: CRUD with explicit per-capability protocols, save-unverified, type-specific
 * probes and account rules. Probe state is keyed by the configuration fingerprint captured when the probe began;
 * a probe that finishes after the configuration changed cannot mark a stale configuration as verified.
 */
class VoiceCatalogViewModel(
    private val environment: VoiceModelEnvironment,
    private val manager: VoiceAccountManager,
    private val probe: VoiceModelProbe,
    private val scope: CoroutineScope,
    private val playAudio: suspend (ByteArray, String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val catalog: VoiceCatalogStore = environment.catalog
    val snapshot: StateFlow<VoiceCatalogSnapshot> = catalog.state

    /** The migration failure, if the one-time legacy migration did not complete. */
    val migrationError: VoiceModelError? get() = environment.migrationError

    /** Clears a corrupt snapshot and re-runs the legacy migration from retained rollback data. */
    suspend fun repairCorruptCatalog(): MigrationResult = withContext(io) { environment.repairCorruptCatalog() }

    private val _probes = MutableStateFlow<Map<String, VoiceProbeUi>>(emptyMap())
    val probes: StateFlow<Map<String, VoiceProbeUi>> = _probes.asStateFlow()

    // ---------- accounts, models and services ----------

    fun saveService(service: VoiceService, credentials: VoiceCredentials? = null) {
        manager.saveVoiceAccount(service, credentials)
    }

    fun upsertModel(model: VoiceModel) {
        catalog.upsertModel(model)
    }

    /** Save without probing; the model stays unverified until a type-specific test succeeds. */
    fun saveUnverified(model: VoiceModel) {
        catalog.upsertModel(model)
    }

    fun deleteModel(ref: VoiceModelRef) {
        if (ref == SttSelectionController.SYSTEM_STT_REF) return
        if (ref == TtsSelectionController.SYSTEM_TTS_REF) return
        catalog.deleteModel(ref)
    }

    fun deleteService(serviceId: String) {
        if (serviceId == SttSelectionController.SYSTEM_STT_SERVICE.id) return
        catalog.deleteService(serviceId)
    }

    fun deleteProvider(providerId: String): List<VoiceModelRef> = manager.deleteProviderWithVoiceUsage(providerId)

    fun providerVoiceUsage(providerId: String): List<VoiceModelRef> = manager.providerVoiceUsage(providerId)

    /** Edits one capability's endpoint; also invalidates that capability's verification in the catalog. */
    fun updateEndpoint(ref: VoiceModelRef, endpoint: String) {
        val model = catalog.requireModel(ref)
        val updated = when (ref.capability) {
            VoiceCapability.STT -> model.copy(stt = model.stt?.copy(endpoint = endpoint))
            VoiceCapability.TTS -> model.copy(tts = model.tts?.copy(endpoint = endpoint))
            VoiceCapability.REALTIME -> model.copy(realtime = model.realtime?.copy(endpoint = endpoint))
        }
        catalog.upsertModel(updated)
    }

    /** The current (non-decrypting) configuration fingerprint for [ref], or null while the catalog is unreadable. */
    fun probeFingerprint(ref: VoiceModelRef): String? = runCatching { probe.connection(ref) }.getOrNull()

    /** Current probe progress for [ref], looked up by its configuration fingerprint. */
    fun probeState(ref: VoiceModelRef): VoiceProbeUi {
        val fingerprint = probeFingerprint(ref) ?: return VoiceProbeUi.Idle
        return _probes.value[fingerprint] ?: VoiceProbeUi.Idle
    }

    // ---------- probes ----------

    /**
     * Starts an async probe, capturing the current configuration fingerprint. Completing the returned deferred
     * with [ProbeResult.Success] verifies the model only while that fingerprint still matches; cancellation and
     * late completions leave the verification unchanged.
     */
    fun probeAsync(ref: VoiceModelRef): CompletableDeferred<ProbeResult> = startProbe(ref).first

    private fun startProbe(ref: VoiceModelRef): Pair<CompletableDeferred<ProbeResult>, String> {
        val fingerprint = probe.connection(ref)
        setProbe(fingerprint, VoiceProbeUi.Running)
        val pending = CompletableDeferred<ProbeResult>()
        pending.invokeOnCompletion {
            if (pending.isCancelled) return@invokeOnCompletion
            when (val result = pending.getCompleted()) {
                is ProbeResult.Success -> {
                    val stillCurrent = runCatching { probe.connection(ref) == fingerprint }.getOrDefault(false)
                    if (stillCurrent) catalog.markVerification(ref, VoiceVerification.Verified)
                    setProbe(fingerprint, if (stillCurrent) VoiceProbeUi.Verified else VoiceProbeUi.Idle)
                }
                is ProbeResult.Failed -> setProbe(fingerprint, VoiceProbeUi.Failed(result.error))
            }
        }
        return pending to fingerprint
    }

    private fun setProbe(fingerprint: String, state: VoiceProbeUi) {
        _probes.update { it + (fingerprint to state) }
    }

    /** Holds a real microphone capture through [VoiceModelProbe.verifyStt] and records a passing probe. */
    fun verifyStt(ref: VoiceModelRef) {
        val (pending, fingerprint) = startProbe(ref)
        scope.launch {
            try {
                probe.verifyStt(ref).collect { state ->
                    when (state) {
                        VoiceProbeState.Capturing -> setProbe(fingerprint, VoiceProbeUi.Listening(""))
                        is VoiceProbeState.Transcribing -> setProbe(fingerprint, VoiceProbeUi.Listening(state.text))
                        is VoiceProbeState.Verified -> pending.complete(ProbeResult.Success)
                        is VoiceProbeState.Failed -> pending.complete(ProbeResult.Failed(state.error))
                    }
                }
            } catch (e: CancellationException) {
                pending.cancel()
                throw e
            } catch (e: Exception) {
                pending.complete(ProbeResult.Failed(VoiceModelError.from(e)))
            }
        }
    }

    /** Synthesizes once, then starts the returned audio through the injected player before reporting success. */
    fun verifyTts(ref: VoiceModelRef, request: TtsRequest) {
        val (pending, _) = startProbe(ref)
        scope.launch {
            val response = try {
                withContext(io) { probe.verifyTts(ref, request) }
            } catch (e: CancellationException) {
                pending.cancel()
                throw e
            } catch (e: Exception) {
                pending.complete(ProbeResult.Failed(VoiceModelError.from(e)))
                return@launch
            }
            try {
                withContext(io) { playAudio(response.bytes, response.mime) }
            } catch (e: CancellationException) {
                pending.cancel()
                throw e
            } catch (e: Exception) {
                pending.complete(
                    ProbeResult.Failed(
                        VoiceModelError.Protocol(
                            com.wangzan101.mika.voice.model.VoiceErrorCategory.SYSTEM_UNAVAILABLE,
                            "Playback failed: ${e.message.orEmpty()}",
                        ),
                    ),
                )
                return@launch
            }
            pending.complete(ProbeResult.Success)
        }
    }

    /** Records a Realtime handshake outcome; success is gated on an unchanged fingerprint like every probe. */
    fun verifyRealtime(ref: VoiceModelRef) {
        val (pending, _) = startProbe(ref)
        scope.launch {
            try {
                withContext(io) { probe.verifyRealtimeConnection(ref) }
            } catch (e: CancellationException) {
                pending.cancel()
                throw e
            } catch (e: Exception) {
                pending.complete(ProbeResult.Failed(VoiceModelError.from(e)))
                return@launch
            }
            pending.complete(ProbeResult.Success)
        }
    }
}
