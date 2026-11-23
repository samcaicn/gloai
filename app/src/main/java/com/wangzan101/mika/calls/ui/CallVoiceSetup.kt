package com.wangzan101.mika.calls.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallActionResult
import com.wangzan101.mika.calls.CallAudioMode
import com.wangzan101.mika.calls.CallConfig
import com.wangzan101.mika.calls.CallRuntime
import com.wangzan101.mika.calls.selectCallVoice
import com.wangzan101.mika.calls.selectPhoneVoice
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.model.CallEngine
import com.wangzan101.mika.voice.model.SttSelection
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.TtsSelectionController
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCatalogSnapshot
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceVoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.wangzan101.mika.settings.ui.models.use
import com.wangzan101.mika.voice.ui.VoiceCatalogViewModel
import com.wangzan101.mika.voice.ui.VoiceMoreServices
import com.wangzan101.mika.voice.ui.VoiceOptionList
import com.wangzan101.mika.voice.ui.VoicePickerSheet
import com.wangzan101.mika.voice.ui.ensureInCatalog
import com.wangzan101.mika.voice.ui.modelOf
import com.wangzan101.mika.voice.ui.nameOf
import com.wangzan101.mika.voice.ui.rememberVoiceCatalogViewModel
import com.wangzan101.mika.voice.ui.tryChoice
import com.wangzan101.mika.voice.ui.voiceOptions

/**
 * 「电话用的听和说」: phone calls follow the voice settings by default, or get their own call mode and models
 * (an 8 kHz phone recognizer, a calmer voice). Device recognition only hears the microphone, so it is never offered
 * for listening on a call.
 */
@Composable
internal fun CallVoiceScreen(runtime: CallRuntime, onBack: () -> Unit, onOpenServices: () -> Unit) {
    val context = LocalContext.current
    val voiceModels = remember { VoiceModelEnvironment.get(context) }
    val viewModel = rememberVoiceCatalogViewModel(voiceModels)
    val snapshot by runtime.realtimeCatalog.collectAsState()
    val providers by remember { ProviderStore.get(context) }.providers.collectAsState()
    val scope = rememberCoroutineScope()
    val failureText = stringResource(R.string.calls_action_failed)
    val sample = stringResource(R.string.voice_try_sample)
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<CallActionResult?>(null) }
    var voicesOpen by rememberSaveable { mutableStateOf(false) }
    val s = snapshot.selections
    val follows = s.callEngine == null && s.callStt == null && s.callTts == null && s.callRealtime == null
    val engine = s.resolvedCallEngine
    val sharedListen = (s.stt as? SttSelection.Model)?.ref?.takeIf { it != SttSelectionController.SYSTEM_STT_REF }

    fun run(action: suspend () -> CallActionResult) {
        if (busy) return
        busy = true
        feedback = null
        scope.launch {
            try {
                feedback = action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                feedback = CallActionResult(false, e.message ?: failureText)
            } finally {
                busy = false
            }
        }
    }

    PageScaffold(stringResource(R.string.calls_voice_title), onBack, Modifier.testTag("call-voice")) {
        Text(stringResource(R.string.calls_voice_phone_lead), Modifier.padding(bottom = 12.dp), style = MikaType.Body, color = MikaTheme.colors.ink2)
        feedback?.let { CallFeedback(it) }
        GroupCard {
            val followBad = s.resolvedEngine == CallEngine.Standard && sharedListen == null
            RadioRow(
                stringResource(R.string.calls_voice_follow_settings), selected = follows, enabled = !busy, first = true,
                subtitle = followLine(snapshot),
                below = if (followBad) { { Text(stringResource(R.string.calls_voice_device_warn), style = MikaType.Hint, color = MikaTheme.colors.warn) } } else null,
                onClick = { if (!follows) run { runtime.selectPhoneVoice(null, null, null) } },
                modifier = Modifier.testTag("call-voice-follow"),
            )
            RadioRow(
                stringResource(R.string.calls_voice_own_settings), selected = !follows, enabled = !busy,
                subtitle = stringResource(R.string.calls_voice_own_settings_sub),
                onClick = {
                    if (follows) run { runtime.selectPhoneVoice(engine, sharedListen, s.tts) }
                },
                modifier = Modifier.testTag("call-voice-own"),
            )
        }
        if (!follows) {
            GroupLabel(stringResource(R.string.calls_voice_for_calls))
            SegmentedControl(
                listOf(stringResource(R.string.call_mode_standard), stringResource(R.string.call_mode_realtime)),
                if (engine == CallEngine.Standard) 0 else 1,
                { i ->
                    val picked = if (i == 0) CallEngine.Standard else CallEngine.Realtime
                    if (picked != engine) run { runtime.selectPhoneVoice(picked, s.callStt, s.callTts, s.callTtsVoice) }
                },
                Modifier.padding(bottom = 12.dp), tags = listOf("call-engine-standard", "call-engine-realtime"),
            )
            if (engine == CallEngine.Standard) {
                val listen = s.callStt ?: sharedListen
                GroupLabel(stringResource(R.string.call_mode_listen))
                VoiceOptionList(
                    voiceOptions(VoiceCapability.STT, snapshot, providers).filter { it.ref != SttSelectionController.SYSTEM_STT_REF },
                    listen, stringResource(R.string.voice_try_stt), viewModel,
                    onPick = { option -> run { runtime.selectPhoneVoice(CallEngine.Standard, viewModel.ensureInCatalog(option), s.callTts, s.callTtsVoice) } },
                    onTest = { option -> viewModel.tryChoice(option, sample) },
                    pickable = { !busy },
                )
                val speak = s.callTts ?: s.tts
                GroupLabel(stringResource(R.string.call_mode_speak))
                VoiceOptionList(
                    voiceOptions(VoiceCapability.TTS, snapshot, providers).filter { it.ref != TtsSelectionController.SYSTEM_TTS_REF },
                    speak, stringResource(R.string.voice_try_tts), viewModel,
                    onPick = { option -> run { runtime.selectPhoneVoice(CallEngine.Standard, s.callStt, viewModel.ensureInCatalog(option), "") } },
                    onTest = { option -> viewModel.tryChoice(option, sample) },
                    pickable = { !busy },
                )
                val own = s.callTts
                val model = snapshot.modelOf(own)
                if (own != null && model != null) {
                    GroupCard(Modifier.padding(top = 8.dp)) {
                        ListRow(null, stringResource(R.string.voice_voice), first = true, onClick = { voicesOpen = true },
                            modifier = Modifier.testTag("call-voice-tts-voice")) {
                            RowValue(if (s.callTtsVoice.isBlank()) stringResource(R.string.voice_voice_default) else VoiceVoices.label(model, s.callTtsVoice))
                            RowChevron()
                        }
                    }
                    if (voicesOpen) {
                        VoicePickerSheet(
                            VoiceVoices.withCurrent(VoiceVoices.tts(model), s.callTtsVoice),
                            onPick = { picked -> run { runtime.selectPhoneVoice(CallEngine.Standard, s.callStt, own, picked) } },
                            onDismiss = { voicesOpen = false },
                            model = model,
                        )
                    }
                }
            } else {
                RealtimeForCalls(runtime, snapshot, providers, viewModel, voiceModels, busy, voicesOpen, { voicesOpen = it }, ::run)
            }
        }
        VoiceMoreServices(onOpenServices)
    }
}

@Composable
private fun followLine(snapshot: VoiceCatalogSnapshot): String {
    val s = snapshot.selections
    val none = stringResource(R.string.voice_hub_none)
    return when (s.resolvedEngine) {
        CallEngine.Standard -> {
            val listen = (s.stt as? SttSelection.Model)?.let { snapshot.nameOf(it.ref) } ?: stringResource(R.string.voice_source_device)
            stringResource(R.string.calls_voice_listen_speak, listen, snapshot.nameOf(s.tts) ?: none)
        }
        CallEngine.Realtime -> callModeLine(snapshot, CallEngine.Realtime)
    }
}

/** Phone calls' own realtime model and its voice. */
@Composable
private fun RealtimeForCalls(
    runtime: CallRuntime, snapshot: VoiceCatalogSnapshot, providers: List<com.wangzan101.mika.settings.Provider>,
    viewModel: VoiceCatalogViewModel, voiceModels: VoiceModelEnvironment, busy: Boolean,
    voicesOpen: Boolean, setVoicesOpen: (Boolean) -> Unit, run: (suspend () -> CallActionResult) -> Unit,
) {
    val s = snapshot.selections
    val own = s.callRealtime
    val effective = own ?: s.realtime
    GroupLabel(stringResource(R.string.call_mode_realtime_model))
    VoiceOptionList(
        voiceOptions(VoiceCapability.REALTIME, snapshot, providers), effective, stringResource(R.string.voice_try_realtime), viewModel,
        onPick = { option -> run { runtime.selectCallVoice(viewModel.ensureInCatalog(option)) } },
        onTest = { option -> viewModel.tryChoice(option, "") },
        pickable = { !busy },
    )
    val model = snapshot.modelOf(effective)
    val binding = model?.realtime
    if (model != null && binding != null) {
        val voice = if (own != null) s.callSpeaker.ifBlank { binding.speaker } else binding.speaker
        GroupCard(Modifier.padding(top = 8.dp)) {
            ListRow(null, stringResource(R.string.voice_voice), first = true, onClick = { setVoicesOpen(true) },
                modifier = Modifier.testTag("call-voice-speaker")) {
                RowValue(if (voice.isBlank()) stringResource(R.string.voice_voice_default) else VoiceVoices.label(voice))
                RowChevron()
            }
        }
        if (own == null) FooterNote(stringResource(R.string.calls_voice_shared_note))
        if (voicesOpen) {
            VoicePickerSheet(
                VoiceVoices.withCurrent(VoiceVoices.realtime(binding.protocol), voice),
                onPick = { picked ->
                    if (own != null) run { runtime.selectCallVoice(own, picked) }
                    else voiceModels.catalog.upsertModel(model.copy(realtime = binding.copy(speaker = picked)))
                },
                onDismiss = { setVoicesOpen(false) },
            )
        }
    }
}

/** One-line summary for the call settings row: how phone calls listen and speak. */
@Composable
internal fun callVoiceSummary(runtime: CallRuntime): String {
    val snapshot by runtime.realtimeCatalog.collectAsState()
    val s = snapshot.selections
    val follows = s.callEngine == null && s.callStt == null && s.callTts == null && s.callRealtime == null
    if (follows) return stringResource(R.string.calls_voice_follow_settings) + " · " + followLine(snapshot)
    val none = stringResource(R.string.voice_hub_none)
    return stringResource(R.string.calls_voice_own_settings) + " · " + when (s.resolvedCallEngine) {
        CallEngine.Standard -> stringResource(
            R.string.calls_voice_listen_speak,
            snapshot.nameOf(s.callStt ?: (s.stt as? SttSelection.Model)?.ref) ?: none,
            snapshot.nameOf(s.callTts ?: s.tts) ?: none,
        )
        CallEngine.Realtime -> snapshot.nameOf(s.callRealtime ?: s.realtime) ?: none
    }
}

/**
 * 「手机通话音频」: how call audio reaches Mika on this phone, and the user's confirmation that both directions work.
 * Auto-answer stays blocked until that confirmation, for the route in use.
 */
@Composable
internal fun CallAudioScreen(
    config: CallConfig, audioDetail: String?, canConfigure: Boolean, canVerify: Boolean,
    onMode: (CallAudioMode) -> Unit, onVerify: (Boolean) -> Unit, onBack: () -> Unit,
) {
    val c = MikaTheme.colors
    PageScaffold(stringResource(R.string.calls_audio_title), onBack, Modifier.testTag("call-audio")) {
        Text(stringResource(R.string.calls_audio_lead), Modifier.padding(bottom = 12.dp), style = MikaType.Body, color = c.ink2)
        GroupLabel(stringResource(R.string.calls_audio_route))
        GroupCard {
            CallAudioMode.entries.forEachIndexed { i, mode ->
                RadioRow(
                    stringResource(if (mode == CallAudioMode.DIRECT) R.string.calls_audio_direct else R.string.calls_audio_redirect),
                    selected = config.audioMode == mode, onClick = { if (config.audioMode != mode) onMode(mode) },
                    first = i == 0, enabled = canConfigure,
                    subtitle = stringResource(if (mode == CallAudioMode.DIRECT) R.string.calls_audio_direct_note else R.string.calls_audio_redirect_note),
                    modifier = Modifier.testTag("call-audio-${mode.name.lowercase()}"),
                )
            }
        }
        GroupLabel(stringResource(R.string.calls_audio_check))
        GroupCard {
            Row(
                Modifier.fillMaxWidth().toggleable(config.audioVerified, enabled = canVerify, role = Role.Checkbox, onValueChange = onVerify)
                    .padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 48.dp).testTag("call-audio-verified"),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(config.audioVerified, onCheckedChange = null, enabled = canVerify)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.calls_audio_verified), style = MikaType.Body)
                    Text(stringResource(R.string.calls_verify_note), style = MikaType.Hint, color = c.ink3)
                }
            }
        }
        audioDetail?.let { FooterNote(it) }
        FooterNote(stringResource(R.string.calls_audio_footer))
    }
}

/** The status shown on the audio row. */
@Composable
internal fun callAudioSummary(config: CallConfig): String = stringResource(
    if (config.audioMode == CallAudioMode.DIRECT) R.string.calls_audio_direct else R.string.calls_audio_redirect,
) + " · " + stringResource(if (config.audioVerified) R.string.calls_audio_checked else R.string.calls_audio_unchecked)
