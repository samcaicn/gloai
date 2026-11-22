package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.VoiceSettings
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.model.CallEngine
import com.wangzan101.mika.voice.model.SttSelection
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.ThinkModel
import com.wangzan101.mika.voice.model.VoiceCatalogSnapshot
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.VoiceSelections
import com.wangzan101.mika.voice.model.VoiceVoices
import java.util.Locale
import kotlin.math.roundToInt

/** True when a call could not hear with this listen choice: device recognition only hears the microphone. */
internal fun VoiceSelections.listenUnusableForCalls(): Boolean = when (val s = stt) {
    is SttSelection.Model -> s.ref == SttSelectionController.SYSTEM_STT_REF
    else -> true
}

/** One line for the hub row and the phone page: which engine calls use. */
@Composable
internal fun callModeLine(snapshot: VoiceCatalogSnapshot, engine: CallEngine): String = when (engine) {
    CallEngine.Standard -> stringResource(R.string.voice_call_mode_standard_line)
    CallEngine.Realtime -> stringResource(
        R.string.voice_call_mode_realtime_line,
        snapshot.nameOf(snapshot.selections.realtime) ?: stringResource(R.string.voice_hub_none),
    )
}

internal fun silenceSeconds(ms: Int): String = String.format(Locale.getDefault(), "%.1f", ms / 1000f)

/**
 * 「通话方式」: standard calls listen, think and speak with the chosen models; realtime is the advanced choice.
 * Applies to in-app voice calls, and to phone calls unless they have their own setting.
 */
@Composable
fun CallModeScreen(onBack: () -> Unit, onOpenStt: () -> Unit, onOpenTts: () -> Unit, onOpenRealtime: () -> Unit) {
    val context = LocalContext.current
    val voiceModels = remember { VoiceModelEnvironment.get(context) }
    val catalog = voiceModels.catalog
    val snapshot by catalog.state.collectAsState()
    val voice by remember { VoiceSettings.get(context) }.state.collectAsState()
    val s = snapshot.selections
    val engine = s.resolvedEngine
    val c = MikaTheme.colors
    Page(stringResource(R.string.voice_hub_call_mode), onBack) {
        Text(stringResource(R.string.call_mode_lead), Modifier.padding(top = 4.dp, bottom = 12.dp), style = MikaType.Body, color = c.ink2)
        GroupCard {
            RadioRow(
                stringResource(R.string.call_mode_standard), engine == CallEngine.Standard,
                onClick = { catalog.setCallStyle(engine = CallEngine.Standard) },
                subtitle = stringResource(R.string.call_mode_standard_sub), first = true,
                modifier = Modifier.testTag("call-mode-standard"),
            ) { StatusPill(stringResource(R.string.call_mode_recommended), PillTone.Ok) }
            RadioRow(
                stringResource(R.string.call_mode_realtime), engine == CallEngine.Realtime,
                onClick = { catalog.setCallStyle(engine = CallEngine.Realtime) },
                subtitle = stringResource(R.string.call_mode_realtime_sub),
                modifier = Modifier.testTag("call-mode-realtime"),
            )
        }
        if (engine == CallEngine.Standard) {
            val listenBad = s.listenUnusableForCalls()
            GroupLabel(stringResource(R.string.call_mode_steps))
            GroupCard {
                ListRow(
                    MikaIcons.Mic, stringResource(R.string.call_mode_listen),
                    (s.stt as? SttSelection.Model)?.let { snapshot.nameOf(it.ref) } ?: stringResource(R.string.voice_source_device),
                    first = true, onClick = onOpenStt,
                    detail = if (listenBad) stringResource(R.string.call_mode_listen_device) else null, detailColor = c.warn,
                    modifier = Modifier.testTag("call-mode-listen"),
                ) { RowChevron() }
                ListRow(
                    MikaIcons.Spark, stringResource(R.string.call_mode_think),
                    if (s.think == ThinkModel.Light) stringResource(R.string.call_mode_think_light_note) else null,
                ) {}
                SegmentedControl(
                    listOf(stringResource(R.string.call_mode_think_light), stringResource(R.string.call_mode_think_main)),
                    if (s.think == ThinkModel.Light) 0 else 1,
                    { catalog.setCallStyle(think = if (it == 0) ThinkModel.Light else ThinkModel.Main) },
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), tags = listOf("call-think-light", "call-think-main"),
                )
                val speakName = snapshot.nameOf(s.tts) ?: stringResource(R.string.voice_hub_none)
                ListRow(
                    MikaIcons.Sound, stringResource(R.string.call_mode_speak),
                    if (s.tts != null && voice.voice.isNotBlank()) "$speakName · ${VoiceVoices.label(voice.voice)}" else speakName,
                    onClick = onOpenTts, modifier = Modifier.testTag("call-mode-speak"),
                ) {
                    if (s.tts == null) StatusPill(stringResource(R.string.voice_hub_setup), PillTone.Warn)
                    RowChevron()
                }
            }
            GroupLabel(stringResource(R.string.call_mode_rhythm))
            GroupCard {
                // 拖动时只改本地值,松手才写进目录,免得每一帧都落盘
                var dragging by remember(s.silenceMs) { mutableFloatStateOf(s.silenceMs.toFloat()) }
                ListRow(null, stringResource(R.string.call_mode_silence),
                    stringResource(R.string.call_mode_silence_sub, silenceSeconds(dragging.roundToInt())), first = true)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Slider(
                        value = dragging,
                        onValueChange = { dragging = (it / 250f).roundToInt() * 250f },
                        onValueChangeFinished = { catalog.setCallStyle(silenceMs = dragging.roundToInt()) },
                        valueRange = VoiceSelections.MIN_SILENCE_MS.toFloat()..VoiceSelections.MAX_SILENCE_MS.toFloat(),
                        modifier = Modifier.testTag("call-silence"),
                    )
                }
                ListRow(null, stringResource(R.string.call_mode_barge), stringResource(R.string.call_mode_barge_sub)) {
                    MikaSwitch(s.bargeIn, { catalog.setCallStyle(bargeIn = it) }, Modifier.testTag("call-barge-in"))
                }
            }
        } else {
            GroupLabel(stringResource(R.string.call_mode_realtime_model))
            GroupCard {
                val name = snapshot.nameOf(s.realtime)
                ListRow(MikaIcons.Handset, stringResource(R.string.call_mode_realtime_model), name ?: stringResource(R.string.voice_hub_none),
                    first = true, onClick = onOpenRealtime, modifier = Modifier.testTag("call-mode-realtime-model")) {
                    if (name == null) StatusPill(stringResource(R.string.voice_hub_setup), PillTone.Warn)
                    RowChevron()
                }
            }
            FooterNote(stringResource(R.string.call_mode_realtime_note), Modifier.padding(top = 6.dp))
        }
    }
}
