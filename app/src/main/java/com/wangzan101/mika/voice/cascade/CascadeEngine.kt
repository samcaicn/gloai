package com.wangzan101.mika.voice.cascade

import android.content.Context
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.VoiceSettings
import com.wangzan101.mika.voice.Pcm16
import com.wangzan101.mika.voice.model.CallEngine
import com.wangzan101.mika.voice.model.SttSelection
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.ThinkModel
import com.wangzan101.mika.voice.model.VoiceErrorCategory
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.VoiceModelError
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceSelections
import com.wangzan101.mika.voice.model.adapters.SttHooks

/** Which call is asking: the in-app voice call or an AI phone call (which may have its own models). */
enum class CallUse { InApp, Phone }

/** Why a standard call cannot start; each one has a settings page that fixes it. */
enum class StandardBlocker { NoListenModel, ListenNotConfigured, NoSpeakModel, SpeakNotConfigured, NoThinkModel }

/**
 * Builds standard (listen → think → speak) calls from the voice selections. Device speech recognition cannot hear
 * a call's audio, so listening always uses a service model.
 */
class CascadeEngine(private val context: Context, private val environment: VoiceModelEnvironment = VoiceModelEnvironment.get(context)) {
    private val selections: VoiceSelections get() = environment.catalog.state.value.selections

    fun engine(use: CallUse): CallEngine = when (use) {
        CallUse.InApp -> selections.resolvedEngine
        CallUse.Phone -> selections.resolvedCallEngine
    }

    fun listenRef(use: CallUse): VoiceModelRef? {
        val shared = (selections.stt as? SttSelection.Model)?.ref?.takeIf { it != SttSelectionController.SYSTEM_STT_REF }
        return when (use) {
            CallUse.InApp -> shared
            CallUse.Phone -> selections.callStt ?: shared
        }
    }

    fun speakRef(use: CallUse): VoiceModelRef? = when (use) {
        CallUse.InApp -> selections.tts
        CallUse.Phone -> selections.callTts ?: selections.tts
    }

    /** The phone's own voice when calls have their own speak model, else the voice chosen for reading aloud. */
    private fun voice(use: CallUse): String? =
        selections.callTtsVoice.takeIf { use == CallUse.Phone && selections.callTts != null && it.isNotBlank() }
            ?: VoiceSettings.get(context).state.value.voice.takeIf { it.isNotBlank() && !(use == CallUse.Phone && selections.callTts != null) }

    private fun thinkModel(): ChatModel? {
        val store = ProviderStore.get(context)
        return when (selections.think) {
            ThinkModel.Light -> store.modelFor(ModelRole.Light) ?: store.modelFor(ModelRole.Main)
            ThinkModel.Main -> store.modelFor(ModelRole.Main)
        }
    }

    /** The first thing missing, or null when a standard call can start. Reads no secrets. */
    fun blocker(use: CallUse): StandardBlocker? {
        val listen = listenRef(use) ?: return StandardBlocker.NoListenModel
        if (!configured(listen)) return StandardBlocker.ListenNotConfigured
        val speak = speakRef(use) ?: return StandardBlocker.NoSpeakModel
        if (!configured(speak)) return StandardBlocker.SpeakNotConfigured
        if (thinkModel() == null) return StandardBlocker.NoThinkModel
        return null
    }

    private fun configured(ref: VoiceModelRef): Boolean = runCatching {
        val snapshot = environment.catalog.state.value
        val service = snapshot.services.firstOrNull { it.id == ref.serviceId } ?: return false
        snapshot.models.any { it.serviceId == ref.serviceId && it.id == ref.modelId } &&
            environment.accountResolver.credentialsConfigured(service)
    }.getOrDefault(false)

    /**
     * Opens a standard call. [instructions] are the call's own rules (phone policy, or the in-app voice rules);
     * [tools] are offered to the thinking model and surface as function calls. Throws [VoiceModelError] when
     * something is missing.
     */
    fun open(use: CallUse, instructions: String, tools: List<ToolSpec>, language: String?): CascadeVoiceSession {
        blocker(use)?.let { throw VoiceModelError.IncompleteConfig("Standard call is not ready: $it") }
        val listen = listenRef(use)!!
        val speakRef = speakRef(use)!!
        val think = thinkModel() ?: throw VoiceModelError.IncompleteConfig("No chat model")
        val tts = environment.tts.createTts(context, speakRef, TtsCapabilities())
        val voice = voice(use)
        val speed = VoiceSettings.get(context).state.value.speed.takeIf { it > 0f && it != 1f }
        val brain = LlmBrain(think, LlmBrain.SPOKEN_STYLE + "\n\n" + instructions, tools)
        val s = selections
        return CascadeVoiceSession(
            brain = brain,
            listen = { feed -> environment.stt.start(context, listen, language, SttHooks(recorder = feed)) },
            speak = { text ->
                val response = tts.synthesize(TtsRequest(text, voice = voice, speed = speed, format = "wav"))
                val wav = Pcm16.parseWav(response.bytes)
                    ?: throw VoiceModelError.Protocol(VoiceErrorCategory.UNSUPPORTED, "Speech is not WAV audio")
                CascadeVoiceSession.PcmAudioOut(Pcm16.toBytes(wav.samples), wav.sampleRate)
            },
            silenceMs = s.silenceMs.coerceIn(VoiceSelections.MIN_SILENCE_MS, VoiceSelections.MAX_SILENCE_MS),
            bargeIn = s.bargeIn,
        )
    }
}
