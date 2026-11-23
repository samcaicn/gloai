package com.wangzan101.mika.calls.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.*
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCatalogSnapshot
import com.wangzan101.mika.voice.model.VoiceModelRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.wangzan101.mika.memory.ui.Row

@Composable
internal fun CallVoiceProfilesScreen(runtime: CallRuntime, onBack: () -> Unit) {
    val profiles by runtime.voiceProfiles.state.collectAsState()
    val config by runtime.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    val failureText = stringResource(R.string.calls_action_failed)
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<CallActionResult?>(null) }
    var editing by remember { mutableStateOf<CallVoiceProfile?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CallVoiceProfile?>(null) }

    fun perform(action: suspend () -> CallActionResult) {
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

    PageScaffold(stringResource(R.string.calls_voice_service), onBack, Modifier.testTag("call-voice-profiles")) {
        FooterNote(stringResource(R.string.calls_voice_profiles_intro))
        feedback?.let { CallFeedback(it) }
        profiles.forEach { profile ->
            val selected = profile.id == config.activeVoiceProfileId
            val audioVerified = runtime.voiceProfiles.isAudioVerified(profile.id, config.audioMode)
            MikaCard(Modifier.fillMaxWidth().testTag("call-voice-profile-${profile.id}")) {
                ListRow(
                    MikaIcons.Mic,
                    profile.name,
                    stringResource(R.string.calls_voice_profile_subtitle, profile.model,
                        if (audioVerified) stringResource(R.string.calls_voice_audio_verified) else stringResource(R.string.calls_voice_audio_unverified)),
                    first = true,
                    onClick = { editing = profile },
                ) {
                    if (selected) Icon(MikaIcons.Check, stringResource(R.string.calls_voice_selected), tint = MikaTheme.colors.accent)
                    else RowChevron()
                }
                Text(
                    stringResource(when {
                        !profile.credentialsConfigured -> R.string.calls_key_missing
                        !profile.connectionTested -> R.string.calls_voice_status_untested
                        else -> R.string.calls_voice_status_tested
                    }),
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (profile.connectionTested) MikaTheme.colors.ok else MikaTheme.colors.ink3,
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (!selected) TextButton(onClick = { perform { runtime.selectVoiceProfile(profile.id) } }, enabled = !busy,
                        modifier = Modifier.testTag("call-voice-select-${profile.id}")) {
                        Text(stringResource(R.string.calls_voice_select))
                    }
                    TextButton(onClick = { perform { runtime.testVoiceProfile(profile.id) } },
                        enabled = !busy && profile.credentialsConfigured,
                        modifier = Modifier.testTag("call-voice-test-${profile.id}")) {
                        Text(stringResource(if (busy) R.string.calls_voice_testing else R.string.calls_voice_test))
                    }
                    if (!selected) TextButton(onClick = { deleting = profile }, enabled = !busy) {
                        Text(stringResource(R.string.calls_voice_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        CallPrimaryButton(stringResource(R.string.calls_voice_add), !busy, { adding = true }, Modifier.testTag("call-voice-add"))
        FooterNote(stringResource(R.string.calls_voice_test_note))
    }

    if (adding) CallVoiceProfileDialog(null, busy, { adding = false }) { profile, credentials ->
        perform {
            val result = runtime.saveVoiceProfile(profile, credentials)
            if (result.ok) adding = false
            result
        }
    }
    editing?.let { profile ->
        CallVoiceProfileDialog(profile, busy, { editing = null }) { edited, credentials ->
            perform {
                val result = runtime.saveVoiceProfile(edited, credentials)
                if (result.ok) editing = null
                result
            }
        }
    }
    deleting?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.calls_voice_delete)) },
            text = { Text(stringResource(R.string.calls_voice_delete_confirm, profile.name)) },
            confirmButton = {
                TextButton(onClick = { perform { runtime.deleteVoiceProfile(profile.id).also { if (it.ok) deleting = null } } }, enabled = !busy) {
                    Text(stringResource(R.string.calls_voice_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.calls_cancel)) } },
        )
    }
}

@Composable
private fun CallVoiceProfileDialog(
    profile: CallVoiceProfile?,
    externalBusy: Boolean,
    onDismiss: () -> Unit,
    onSave: (CallVoiceProfile, VolcRealtimeCredentials?) -> Unit,
) {
    val defaultName = stringResource(R.string.calls_voice_default_name)
    var protocol by remember(profile) { mutableStateOf(profile?.protocol ?: CallVoiceProtocol.VOLC_DUPLEX) }
    val openAi = protocol == CallVoiceProtocol.OPENAI_REALTIME
    var name by remember(profile) { mutableStateOf(profile?.name ?: defaultName) }
    var endpoint by remember(profile) { mutableStateOf(profile?.endpoint ?: VolcRealtimeProtocol.ENDPOINT) }
    var model by remember(profile) { mutableStateOf(profile?.model ?: VolcRealtimeConfig.MODEL) }
    var speaker by remember(profile) { mutableStateOf(profile?.speaker ?: VolcRealtimeConfig.DEFAULT_SPEAKER) }
    var apiKey by remember(profile) { mutableStateOf("") }
    var appId by remember(profile) { mutableStateOf("") }
    var accessKey by remember(profile) { mutableStateOf("") }
    var legacy by remember(profile) { mutableStateOf(false) }
    val id = profile?.id.orEmpty()
    val configured = profile?.credentialsConfigured == true
    val valid = name.isNotBlank() && endpoint.startsWith("wss://") && model.isNotBlank() &&
        (!legacy || (appId.isBlank() && accessKey.isBlank()) || (appId.isNotBlank() && accessKey.isNotBlank()))
    AlertDialog(
        onDismissRequest = { if (!externalBusy) onDismiss() },
        title = { Text(stringResource(if (profile == null) R.string.calls_voice_add else R.string.calls_voice_edit)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CallVoiceProtocol.entries.forEach { choice ->
                        val label = if (choice == CallVoiceProtocol.OPENAI_REALTIME) "OpenAI Realtime" else "Doubao"
                        FilterChip(selected = protocol == choice, enabled = profile == null && !externalBusy,
                            onClick = {
                                if (protocol != choice) {
                                    protocol = choice
                                    val useOpenAi = choice == CallVoiceProtocol.OPENAI_REALTIME
                                    name = if (useOpenAi) label else defaultName
                                    endpoint = if (useOpenAi) OpenAiCallProtocol.ENDPOINT else VolcRealtimeProtocol.ENDPOINT
                                    model = if (useOpenAi) OpenAiCallProtocol.MODEL else VolcRealtimeConfig.MODEL
                                    speaker = if (useOpenAi) OpenAiCallProtocol.VOICE else VolcRealtimeConfig.DEFAULT_SPEAKER
                                    apiKey = ""
                                    appId = ""
                                    accessKey = ""
                                    legacy = false
                                }
                            }, label = { Text(label) })
                    }
                }
                Text(stringResource(R.string.calls_voice_protocol), style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3)
                OutlinedTextField(name, { name = it.take(CallVoiceProfile.MAX_NAME) }, Modifier.fillMaxWidth().testTag("call-voice-name"),
                    label = { Text(stringResource(R.string.calls_voice_name)) }, singleLine = true, enabled = !externalBusy)
                OutlinedTextField(endpoint, { endpoint = it.take(CallVoiceProfile.MAX_ENDPOINT) }, Modifier.fillMaxWidth().testTag("call-voice-endpoint"),
                    label = { Text(stringResource(R.string.calls_voice_endpoint)) }, singleLine = true, enabled = !externalBusy && !openAi)
                OutlinedTextField(model, { model = it.take(CallVoiceProfile.MAX_MODEL) }, Modifier.fillMaxWidth().testTag("call-voice-model"),
                    label = { Text(stringResource(R.string.calls_voice_model)) }, singleLine = true, enabled = !externalBusy)
                OutlinedTextField(speaker, { speaker = it.take(CallPolicy.MAX_SPEAKER_CHARS) }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.calls_speaker)) }, singleLine = true, enabled = !externalBusy,
                    supportingText = { if (!openAi) Text(stringResource(R.string.calls_speaker_edit_note)) })
                HorizontalDivider()
                if (!openAi) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(legacy, { legacy = it }, enabled = !externalBusy)
                    Text(stringResource(R.string.calls_legacy_credentials), style = MaterialTheme.typography.bodySmall)
                }
                if (legacy) OutlinedTextField(appId, { appId = it }, Modifier.fillMaxWidth(), label = { Text("App ID") },
                    singleLine = true, enabled = !externalBusy)
                OutlinedTextField(
                    if (legacy) accessKey else apiKey,
                    { if (legacy) accessKey = it else apiKey = it },
                    Modifier.fillMaxWidth().testTag("call-voice-credential"),
                    label = { Text(if (legacy) "Access Key" else "API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    enabled = !externalBusy,
                    supportingText = { if (!configured && openAi) Text("OpenAI API Key") else Text(stringResource(if (configured) R.string.calls_voice_credentials_keep else R.string.calls_credentials_note)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val credentials = when {
                        legacy && appId.isNotBlank() && accessKey.isNotBlank() -> VolcRealtimeCredentials(appId = appId, accessKey = accessKey)
                        !legacy && apiKey.isNotBlank() -> VolcRealtimeCredentials(apiKey = apiKey)
                        else -> null
                    }
                    onSave(CallVoiceProfile(id, name, protocol = protocol, endpoint = endpoint, model = model, speaker = speaker,
                        credentialsConfigured = configured, connectionTested = profile?.connectionTested == true), credentials)
                },
                enabled = valid && !externalBusy,
                modifier = Modifier.testTag("call-voice-save"),
            ) { Text(stringResource(R.string.calls_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !externalBusy) { Text(stringResource(R.string.calls_cancel)) } },
    )
}
