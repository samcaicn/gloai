package com.wangzan101.mika.voice.model

import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.voice.SpeechFailure
import com.wangzan101.mika.voice.SpeechSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One capability probe's progress; [Verified] carries the final transcript for STT. */
sealed interface VoiceProbeState {
    data object Capturing : VoiceProbeState
    data class Transcribing(val text: String) : VoiceProbeState
    data class Verified(val transcript: String) : VoiceProbeState
    data class Failed(val error: VoiceModelError) : VoiceProbeState
}

/**
 * Runs connection tests without a Context: STT captures real microphone speech through an injected session
 * factory (the production factory delegates to [SttSelectionController.start]), TTS returns synthesized audio
 * for the caller to start through the existing player, and Realtime records only the handshake outcome.
 */
class VoiceModelProbe(
    private val environment: VoiceModelEnvironment,
    private val startStt: (VoiceModelRef) -> SpeechSession,
    private val synthesize: suspend (VoiceModelRef, TtsRequest) -> TtsResponse,
    private val connectRealtime: suspend (VoiceModelRef) -> Unit,
) {
    /** The full resolved configuration fingerprint; probe success is accepted only while it stays unchanged. */
    fun connection(ref: VoiceModelRef): String {
        val snapshot = environment.catalog.state.value
        if (snapshot.error != null) throw VoiceModelError.IncompleteConfig("Voice catalog is not readable")
        val model = snapshot.models.firstOrNull { it.serviceId == ref.serviceId && it.id == ref.modelId }
            ?: throw VoiceModelError.IncompleteConfig("Voice model is not registered")
        if (ref.capability !in model.capabilities) throw VoiceModelError.Unsupported(ref.capability)
        val service = snapshot.services.firstOrNull { it.id == ref.serviceId }
            ?: throw VoiceModelError.IncompleteConfig("Voice service is not registered")
        return environment.accountResolver.configurationFingerprint(service, model)
    }

    fun verifyStt(ref: VoiceModelRef): Flow<VoiceProbeState> = flow {
        val session = try {
            startStt(ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(VoiceProbeState.Failed(VoiceModelError.from(e)))
            return@flow
        }
        try {
            session.events.collect { event -> emit(event.toState()) }
        } finally {
            runCatching { session.release() }
        }
    }

    suspend fun verifyTts(ref: VoiceModelRef, request: TtsRequest): TtsResponse = synthesize(ref, request)

    suspend fun verifyRealtimeConnection(ref: VoiceModelRef) {
        connectRealtime(ref)
    }

    private fun SpeechEvent.toState(): VoiceProbeState = when (this) {
        is SpeechEvent.Level -> VoiceProbeState.Capturing
        is SpeechEvent.Partial -> VoiceProbeState.Transcribing(stable + tail)
        SpeechEvent.Converting -> VoiceProbeState.Transcribing("")
        is SpeechEvent.Final -> VoiceProbeState.Verified(text)
        is SpeechEvent.Failed -> VoiceProbeState.Failed(why.toError())
    }
}

private fun SpeechFailure.toError(): VoiceModelError = when (this) {
    is SpeechFailure.Provider -> VoiceModelError.from(error)
    SpeechFailure.NoPermission -> VoiceModelError.Protocol(
        VoiceErrorCategory.SYSTEM_UNAVAILABLE, "Microphone permission is missing",
    )
    SpeechFailure.Busy -> VoiceModelError.Protocol(VoiceErrorCategory.SYSTEM_UNAVAILABLE, "Microphone is busy")
    SpeechFailure.Network -> VoiceModelError.Protocol(VoiceErrorCategory.NETWORK, "Network is unavailable")
    SpeechFailure.MicUnavailable -> VoiceModelError.Protocol(
        VoiceErrorCategory.SYSTEM_UNAVAILABLE, "Microphone is unavailable",
    )
    SpeechFailure.AudioError -> VoiceModelError.Protocol(
        VoiceErrorCategory.SYSTEM_UNAVAILABLE, "Microphone audio failed",
    )
    is SpeechFailure.ServiceNoPermission -> VoiceModelError.Protocol(
        VoiceErrorCategory.SYSTEM_UNAVAILABLE, "Recognition service lacks microphone permission",
    )
    is SpeechFailure.Unavailable -> VoiceModelError.Protocol(
        VoiceErrorCategory.SYSTEM_UNAVAILABLE, "Speech recognition is unavailable",
    )
}
