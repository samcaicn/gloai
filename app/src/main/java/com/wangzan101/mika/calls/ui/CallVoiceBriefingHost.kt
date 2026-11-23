package com.wangzan101.mika.calls.ui

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wangzan101.mika.calls.*
import com.wangzan101.mika.calls.briefing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wangzan101.mika.chat.ui.draft

internal class VoiceBriefingLauncher(val busy: Boolean, val start: (String, String?, String) -> Unit)

/** Permission, lifecycle, number and SIM ownership stay with the visible workspace. */
@Composable
internal fun rememberCallVoiceBriefing(
    runtime: CallRuntime,
    draft: CallTaskDraft,
    allowed: Boolean,
    profile: CallVoiceProfile,
    onAuthorized: (CallTaskSubmission) -> Unit,
): VoiceBriefingLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = remember(runtime, profile.endpoint) { VoiceBriefingController(context, scope, profile.endpoint) }
    val voice by controller.state.collectAsState()
    val currentAllowed by rememberUpdatedState(allowed)
    val currentAuthorized by rememberUpdatedState(onAuthorized)
    var open by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        detail = context.getString(if (granted) R.string.calls_voice_briefing_permission_granted else R.string.calls_voice_briefing_permission_denied)
    }
    fun cancel() { attempt++; starting = false; controller.cancel() }
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) { attempt++; starting = false; controller.cancel("已离开前台，未拨号") }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.close() }
    }
    LaunchedEffect(allowed, draft.number, draft.simOverride) {
        if (!allowed && (starting || voice.active)) cancel()
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { cancel(); open = false },
            title = { Text(stringResource(R.string.calls_voice_briefing_start)) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label)
                    Text(stringResource(when {
                        starting || voice.activity == VoiceBriefingActivity.CONNECTING -> R.string.calls_connecting
                        voice.activity == VoiceBriefingActivity.LISTENING -> R.string.calls_live_waiting
                        voice.activity == VoiceBriefingActivity.CLOSING -> if (voice.message != null) R.string.calls_voice_briefing_stopped else R.string.calls_disconnecting
                        voice.activity == VoiceBriefingActivity.AUTHORIZED -> R.string.calls_voice_briefing_authorized
                        voice.message != null -> R.string.calls_voice_briefing_stopped
                        else -> R.string.calls_voice_briefing_start
                    }))
                    Text(stringResource(R.string.calls_voice_briefing_note, VoiceBriefingAuthorization.PHRASE))
                    if (voice.assistantTranscript.isNotBlank()) Text("${profile.name}\n${voice.assistantTranscript}")
                    if (voice.ownerTranscript.isNotBlank() || voice.ownerPartial.isNotBlank())
                        Text("${stringResource(R.string.calls_record_brief)}\n${voice.ownerTranscript}\n${voice.ownerPartial}")
                    (detail ?: voice.message)?.let { Text(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = { cancel(); open = false }, modifier = Modifier.testTag("call-voice-cancel")) {
                    Text(stringResource(if (voice.active || starting) R.string.calls_cancel else R.string.back))
                }
            },
            modifier = Modifier.testTag("call-voice-briefing"),
        )
    }
    return VoiceBriefingLauncher(starting || voice.active) { number, simId, simLabel ->
        if (!currentAllowed || starting || voice.active) return@VoiceBriefingLauncher
        open = true; detail = null; label = "$number · $simLabel"
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            val token = ++attempt
            val capturedFields = draft.snapshotFields()
            val capturedRevision = runtime.voiceProfiles.revision.value
            val capturedProfile = profile
            val key = "$number|${simId.orEmpty()}"
            val valid = {
                token == attempt && currentAllowed &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    runtime.state.value.phase == CallPhase.IDLE && runtime.state.value.lockedDialRequestId == null &&
                    runtime.voiceProfiles.revision.value == capturedRevision &&
                    runtime.activeVoiceProfile() == capturedProfile &&
                    draft.snapshotFields() == capturedFields &&
                    CallPolicy.outgoingNumber(draft.number) == number &&
                    CallPolicy.chooseOutgoingSim(runtime.state.value.simOptions, draft.simOverride,
                        runtime.settings.state.value.defaultOutgoingSimId)?.id == simId
            }
            val initial = runCatching {
                if (listOf(draft.goal, draft.questions, draft.context, draft.constraints).all { it.isBlank() }) null
                else CallTaskBrief(draft.goal, draft.questions.lines().filter { it.isNotBlank() }, draft.context, draft.constraints).validatedSnapshot()
            }
            if (initial.isFailure) {
                detail = context.getString(R.string.calls_voice_briefing_incomplete)
            } else {
                starting = true
                scope.launch {
                    val credentials = try { withContext(Dispatchers.IO) { runtime.voiceProfiles.credentials(capturedProfile.id) } }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { null }
                    if (token == attempt) starting = false
                    if (valid()) {
                        if (credentials == null) detail = context.getString(R.string.calls_voice_briefing_configure)
                        else controller.start(key, credentials,
                            VolcRealtimeConfig(protocol = capturedProfile.protocol, speaker = capturedProfile.speaker, model = capturedProfile.model), initial.getOrNull(), valid) { brief ->
                            if (valid()) {
                                draft.fill(number, brief)
                                draft.simOverride = simId
                                open = false
                                currentAuthorized(CallTaskSubmission(number, simId, simLabel, capturedProfile.name, brief))
                            }
                        }
                    }
                }
            }
        }
    }
}
