package com.wangzan101.mika.calls.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import com.wangzan101.mika.ui.components.MikaDialog
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
import com.wangzan101.mika.settings.ui.models.Page
import com.wangzan101.mika.settings.ui.models.use
import com.wangzan101.mika.voice.ui.nameOf

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

/** 「想」那一行的提示:选了轻模型时说明没配轻模型会用主模型([deskModel] 就是这样退的);选主模型不用提示。 */
internal fun thinkFallbackHint(think: ThinkModel): Int? = when (think) {
    ThinkModel.Light -> R.string.call_mode_think_light_note
    ThinkModel.Main -> null
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
                    stringResource(R.string.call_mode_think_note),
                    detail = thinkFallbackHint(s.think)?.let { stringResource(it) },
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
        IdleHangup(s.idleMinutes) { catalog.setCallStyle(idleMinutes = it) }
    }
}

/** 「自动挂断」里选中的是哪一行:现成的分钟数、自定义、或不自动挂。 */
internal sealed interface IdleChoice {
    data class Preset(val minutes: Int) : IdleChoice
    data class Custom(val minutes: Int) : IdleChoice
    data object Never : IdleChoice
}

internal fun idleChoiceOf(minutes: Int): IdleChoice = when {
    minutes <= VoiceSelections.NEVER_IDLE -> IdleChoice.Never
    minutes in VoiceSelections.IDLE_PRESETS -> IdleChoice.Preset(minutes)
    else -> IdleChoice.Custom(minutes)
}

/** 自定义时长框里的字能不能存:1 到 120 分钟的整数。 */
internal fun customIdleMinutes(text: String): Int? =
    text.trim().toIntOrNull()?.takeIf { it in VoiceSelections.MIN_IDLE_MINUTES..VoiceSelections.MAX_IDLE_MINUTES }

/** 通话里多久没有新内容就挂断(标准、实时两种通话都用;AI 电话不受它管)。 */
@Composable
private fun IdleHangup(minutes: Int, onChange: (Int) -> Unit) {
    val choice = idleChoiceOf(minutes)
    var editing by rememberSaveable { mutableStateOf(false) }
    GroupLabel(stringResource(R.string.call_mode_idle))
    GroupCard {
        VoiceSelections.IDLE_PRESETS.forEachIndexed { i, preset ->
            RadioRow(
                stringResource(R.string.call_mode_idle_minutes, preset), choice == IdleChoice.Preset(preset),
                onClick = { onChange(preset) }, first = i == 0, modifier = Modifier.testTag("call-idle-$preset"),
            )
        }
        RadioRow(
            stringResource(R.string.call_mode_idle_custom), choice is IdleChoice.Custom,
            onClick = { editing = true },
            subtitle = (choice as? IdleChoice.Custom)?.let { stringResource(R.string.call_mode_idle_custom_sub, it.minutes) },
            modifier = Modifier.testTag("call-idle-custom"),
        ) { RowChevron() }
        RadioRow(
            stringResource(R.string.call_mode_idle_never), choice == IdleChoice.Never,
            onClick = { onChange(VoiceSelections.NEVER_IDLE) }, modifier = Modifier.testTag("call-idle-never"),
        )
    }
    FooterNote(stringResource(R.string.call_mode_idle_note), Modifier.padding(top = 6.dp))
    if (editing) {
        CustomIdleDialog(
            initial = (choice as? IdleChoice.Custom)?.minutes ?: (choice as? IdleChoice.Preset)?.minutes ?: VoiceSelections.DEFAULT_IDLE_MINUTES,
            onDismiss = { editing = false },
            onSave = { onChange(it); editing = false },
        )
    }
}

@Composable
private fun CustomIdleDialog(initial: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial.toString()) }
    val value = customIdleMinutes(text)
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.call_mode_idle_dialog_title)) },
        text = {
            OutlinedTextField(
                text, { text = it.filter(Char::isDigit).take(3) },
                modifier = Modifier.fillMaxWidth().testTag("call-idle-custom-input"),
                singleLine = true,
                supportingText = { Text(stringResource(R.string.call_mode_idle_dialog_hint)) },
                isError = text.isNotEmpty() && value == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = { value?.let(onSave) }, enabled = value != null, modifier = Modifier.testTag("call-idle-custom-save")) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
