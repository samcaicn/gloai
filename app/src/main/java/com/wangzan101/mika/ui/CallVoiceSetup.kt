package com.wangzan101.mika.ui

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
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceVoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 「电话语音」: phone calls follow the Voice call choice by default, or use their own realtime model and voice.
 * Following shares the voice too; changing it here changes it for voice calls as well, and the page says so.
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
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<CallActionResult?>(null) }
    var voicesOpen by rememberSaveable { mutableStateOf(false) }
    val selections = snapshot.selections
    val own = selections.callRealtime
    val effective = own ?: selections.realtime
    val effectiveModel = snapshot.modelOf(effective)

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
        feedback?.let { CallFeedback(it) }
        GroupCard {
            RadioRow(
                stringResource(R.string.calls_voice_follow), selected = own == null, enabled = !busy, first = true,
                subtitle = snapshot.nameOf(selections.realtime) ?: stringResource(R.string.calls_voice_follow_none),
                onClick = { if (own != null) run { runtime.selectCallVoice(null) } },
                modifier = Modifier.testTag("call-voice-follow"),
            )
            RadioRow(
                stringResource(R.string.calls_voice_own), selected = own != null, enabled = !busy,
                subtitle = stringResource(R.string.calls_voice_own_note),
                onClick = {
                    if (own != null) return@RadioRow
                    val first = selections.realtime ?: voiceOptions(VoiceCapability.REALTIME, snapshot, providers).firstOrNull()
                        ?.let { viewModel.ensureInCatalog(it) } ?: return@RadioRow
                    run { runtime.selectCallVoice(first) }
                },
                modifier = Modifier.testTag("call-voice-own"),
            )
        }
        if (own != null) {
            val options = voiceOptions(VoiceCapability.REALTIME, snapshot, providers)
            GroupLabel(stringResource(R.string.voice_choices_label))
            VoiceOptionList(
                options, own, stringResource(R.string.voice_try_realtime), viewModel,
                onPick = { option -> run { runtime.selectCallVoice(viewModel.ensureInCatalog(option)) } },
                onTest = { option -> viewModel.tryChoice(option, "") },
                pickable = { !busy },
            )
        }
        val binding = effectiveModel?.realtime
        if (effectiveModel != null && binding != null) {
            val voice = if (own != null) selections.callSpeaker.ifBlank { binding.speaker } else binding.speaker
            GroupLabel(stringResource(R.string.voice_settings_of, voiceModelDisplayName(effectiveModel)))
            GroupCard {
                ListRow(null, stringResource(R.string.voice_voice), first = true, onClick = { voicesOpen = true },
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
                        else voiceModels.catalog.upsertModel(effectiveModel.copy(realtime = binding.copy(speaker = picked)))
                    },
                    onDismiss = { voicesOpen = false },
                )
            }
        }
        VoiceMoreServices(onOpenServices)
    }
}

/** One-line summary for the call settings row: who speaks on the phone. */
@Composable
internal fun callVoiceSummary(runtime: CallRuntime): String {
    val snapshot by runtime.realtimeCatalog.collectAsState()
    val own = snapshot.selections.callRealtime
    return when {
        own != null -> stringResource(R.string.realtime_calls_own, snapshot.nameOf(own) ?: own.modelId)
        snapshot.selections.realtime != null ->
            stringResource(R.string.calls_voice_follow_summary, snapshot.nameOf(snapshot.selections.realtime).orEmpty())
        else -> stringResource(R.string.voice_hub_none)
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
