package com.wangzan101.mika.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallConfig
import com.wangzan101.mika.calls.CallRuntimeState
import com.wangzan101.mika.calls.CallVoiceStage
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.IconTile
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.chat.ui.readiness
import com.wangzan101.mika.memory.ui.Link
import com.wangzan101.mika.settings.ui.models.Page

/** What an AI phone call needs on this phone, in the order a person sets it up. */
internal enum class CallSetupStep { Voice, Phone, Helper, Audio, Standby }

/** The steps still to do; standby only counts once auto-answer is wanted. */
internal fun callSetupMissing(
    config: CallConfig, state: CallRuntimeState, device: CallDeviceReadiness, voiceReady: Boolean,
): List<CallSetupStep> = CallSetupStep.entries.filter { step ->
    when (step) {
        CallSetupStep.Voice -> !voiceReady
        CallSetupStep.Phone -> !config.enabled || !device.phoneAllowed || !device.callNotificationsAllowed
        CallSetupStep.Helper -> !state.shellConnected
        CallSetupStep.Audio -> !config.audioVerified
        CallSetupStep.Standby -> config.autoAnswer &&
            (!device.standbyNotificationsAllowed || device.batteryPolicy == CallBatteryPolicy.Restricted)
    }
}

private fun CallSetupStep.icon() = when (this) {
    CallSetupStep.Voice -> MikaIcons.Mic
    CallSetupStep.Phone -> MikaIcons.Phone
    CallSetupStep.Helper -> MikaIcons.Link
    CallSetupStep.Audio -> MikaIcons.Sound
    CallSetupStep.Standby -> MikaIcons.Shield
}

private fun CallSetupStep.title(): Int = when (this) {
    CallSetupStep.Voice -> R.string.calls_step_voice
    CallSetupStep.Phone -> R.string.calls_step_phone
    CallSetupStep.Helper -> R.string.calls_helper_name
    CallSetupStep.Audio -> R.string.calls_step_audio
    CallSetupStep.Standby -> R.string.calls_step_standby
}

/** Top of the receive and call tabs: ready, or how many steps are left, one tap from the checklist. */
@Composable
internal fun CallReadyCard(missing: List<CallSetupStep>, config: CallConfig, standby: Boolean, onOpen: () -> Unit) {
    val c = MikaTheme.colors
    val done = missing.isEmpty()
    MikaCard(Modifier.padding(bottom = 10.dp).testTag("call-ready-card")) {
        Row(
            Modifier.fillMaxWidth().background(if (done) c.surface else c.warnSoft).padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (done) IconTile(MikaIcons.Check, TileTone.Ok)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    if (done) stringResource(R.string.calls_ready_title) else stringResource(R.string.calls_ready_missing, missing.size),
                    style = MikaType.Title, color = if (done) c.ink else c.warn,
                )
                Text(
                    when {
                        !done -> missing.map { step ->
                            stringResource(if (step == CallSetupStep.Voice) R.string.calls_step_voice_missing else step.title())
                        }.joinToString(" · ")
                        standby -> stringResource(R.string.calls_ready_standby, config.autoAnswerDelaySeconds)
                        else -> stringResource(R.string.calls_ready_manual)
                    },
                    style = MikaType.Caption, color = c.ink2,
                )
            }
            MikaButton(
                stringResource(if (done) R.string.calls_ready_view else R.string.calls_ready_finish), onOpen,
                Modifier.testTag("call-ready-open"), kind = if (done) ButtonKind.Tonal else ButtonKind.Primary, size = ButtonSize.Small,
            )
        }
    }
}

/** ⑬: every step in one list; each undone step has its own button. */
@Composable
internal fun CallChecklistPage(
    missing: List<CallSetupStep>, config: CallConfig, state: CallRuntimeState, device: CallDeviceControls,
    voiceSummary: String, canConfigure: Boolean, canStartStandby: Boolean,
    onBack: () -> Unit, onVoice: () -> Unit, onPrepare: () -> Unit, onHelper: () -> Unit, onAudio: () -> Unit,
    onStartStandby: () -> Unit,
) {
    val checks = device.readiness
    Page(stringResource(R.string.calls_checklist_title), onBack) {
        Text(stringResource(R.string.calls_checklist_lead), Modifier.padding(top = 4.dp, bottom = 12.dp),
            style = MikaType.Body, color = MikaTheme.colors.ink2)
        GroupCard(Modifier.testTag("call-checklist")) {
            CallSetupStep.entries.forEachIndexed { index, step ->
                val todo = step in missing
                val subtitle = when (step) {
                    CallSetupStep.Voice -> if (todo) stringResource(R.string.calls_step_voice_missing) else voiceSummary
                    CallSetupStep.Phone -> stringResource(R.string.calls_step_phone_sub)
                    CallSetupStep.Helper -> stringResource(R.string.calls_step_helper_sub)
                    CallSetupStep.Audio -> stringResource(R.string.calls_step_audio_sub)
                    CallSetupStep.Standby -> stringResource(R.string.calls_step_standby_sub)
                }
                // 每一步自己的「去做」:权限缺哪个开哪个;都给了还没启用就走准备流程
                val action: (() -> Unit)? = when (step) {
                    CallSetupStep.Voice -> onVoice
                    CallSetupStep.Phone -> when {
                        !checks.phoneAllowed -> device.phoneAction
                        !checks.callNotificationsAllowed -> device.notificationAction
                        todo -> onPrepare
                        else -> null
                    }
                    CallSetupStep.Helper -> onHelper
                    CallSetupStep.Audio -> onAudio
                    CallSetupStep.Standby -> when {
                        !checks.standbyNotificationsAllowed -> device.notificationAction
                        checks.batteryPolicy == CallBatteryPolicy.Restricted -> device.batteryAction
                        else -> null
                    }
                }?.takeIf { canConfigure }
                val label = when {
                    !todo -> if (step == CallSetupStep.Voice || step == CallSetupStep.Audio) R.string.calls_step_change else null
                    step == CallSetupStep.Audio -> R.string.calls_step_test
                    else -> R.string.calls_step_go
                }
                ListRow(
                    step.icon(), stringResource(step.title()), subtitle,
                    first = index == 0, tone = if (todo) TileTone.Warn else TileTone.Ok,
                    modifier = Modifier.testTag("call-step-" + step.name.lowercase()),
                ) {
                    if (label != null && action != null) MikaButton(
                        stringResource(label), action, Modifier.testTag("call-step-" + step.name.lowercase() + "-go"),
                        kind = if (todo) ButtonKind.Primary else ButtonKind.Tonal, size = ButtonSize.Small,
                    )
                }
            }
        }
        FooterNote(stringResource(R.string.calls_checklist_note), Modifier.padding(top = 6.dp))
        if (config.enabled) {
            GroupLabel(stringResource(R.string.calls_background_settings))
            GroupCard {
                if (config.autoAnswer) CallBackgroundStandbyRow(config, state, canStartStandby, onStartStandby)
                CallBatterySettingsRow(device, canConfigure)
                if (checks.callNotificationsAllowed) ListRow(
                    MikaIcons.Bell, stringResource(R.string.calls_notification_settings),
                    onClick = device.callChannelAction.takeIf { canConfigure },
                )
            }
            if (!state.backgroundStandbyStarting) state.backgroundStandbyBlockedReason?.let { FooterNote(it) }
            FooterNote(stringResource(R.string.calls_background_lock_screen_note))
        }
    }
}

/** ⑫: three cells, the lit one is what Mika is doing now, so a pause never looks like a hang. */
@Composable
internal fun CallVoiceStageRow(stage: CallVoiceStage, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val now = stringResource(R.string.calls_stage_now, stringResource(stage.label()))
    Row(modifier.testTag("call-voice-stage").semantics(mergeDescendants = true) { contentDescription = now },
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CallVoiceStage.entries.forEach { s ->
            val lit = s == stage
            Text(
                stringResource(s.label()),
                Modifier.background(if (lit) c.okSoft else c.fill, MikaShapes.Pill).padding(horizontal = 10.dp, vertical = 3.dp)
                    .then(if (lit) Modifier.testTag("call-voice-stage-now") else Modifier),
                style = MikaType.Caption, color = if (lit) c.ok else c.ink3,
            )
        }
    }
}

private fun CallVoiceStage.label(): Int = when (this) {
    CallVoiceStage.Listening -> R.string.calls_stage_listen
    CallVoiceStage.Thinking -> R.string.calls_stage_think
    CallVoiceStage.Speaking -> R.string.calls_stage_speak
}
