package com.wangzan101.mika.voice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.components.ActionSheet
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.CapabilityTag
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.SheetAction
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.components.TagTone
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.VoicePlayer
import com.wangzan101.mika.voice.model.ProviderVoiceOffers
import com.wangzan101.mika.voice.model.adapters.SttHooks
import com.wangzan101.mika.voice.model.SttSelection
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.voice.model.VoiceAccount
import com.wangzan101.mika.voice.model.VoiceAccountManager
import com.wangzan101.mika.voice.model.CallEngine
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCatalogSnapshot
import com.wangzan101.mika.voice.model.VoiceModel
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.VoiceModelProbe
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceVoices
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.calls.ui.callModeLine
import com.wangzan101.mika.calls.ui.listenUnusableForCalls
import com.wangzan101.mika.settings.ui.models.ModelIdStyle
import com.wangzan101.mika.settings.ui.models.Page
import com.wangzan101.mika.settings.ui.models.listed
import com.wangzan101.mika.settings.ui.models.pick
import com.wangzan101.mika.settings.ui.models.use

/** Catalog state, probes and account actions shared by the voice pages; one per page, players stop with it. */
@Composable
internal fun rememberVoiceCatalogViewModel(voiceModels: VoiceModelEnvironment): VoiceCatalogViewModel {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember { VoicePlayer(context) }
    val viewModel = remember(voiceModels) {
        val manager = VoiceAccountManager(voiceModels.catalog, requireNotNull(voiceModels.credentialStore))
        val probe = VoiceModelProbe(
            environment = voiceModels,
            startStt = { ref -> voiceModels.stt.start(context, ref, null, SttHooks()) },
            synthesize = { ref, request ->
                voiceModels.tts.createTts(context, ref, TtsCapabilities()).synthesize(request)
            },
            connectRealtime = { ref ->
                val controller = voiceModels.realtime
                controller.provider(ref).connect(controller.profile(ref), controller.credentials(ref), "", false).close()
            },
        )
        VoiceCatalogViewModel(
            environment = voiceModels,
            manager = manager,
            probe = probe,
            scope = scope,
            playAudio = { bytes, mime -> player.play(bytes, mime) },
        )
    }
    DisposableEffect(viewModel) { onDispose { player.stop() } }
    return viewModel
}

/** Writes a chat-account offer into the catalog the first time it is picked or tested. */
internal fun VoiceCatalogViewModel.ensureInCatalog(choice: VoiceOption): VoiceModelRef {
    val provider = choice.provider ?: return choice.ref
    val offer = choice.offer ?: return choice.ref
    return ProviderVoiceOffers.adopt(catalog, provider, offer, choice.ref.capability)
}

/** Runs the capability's own test: a real sentence for speech input, a played sample for reading, a connection for calls. */
internal fun VoiceCatalogViewModel.tryChoice(choice: VoiceOption, sample: String) {
    val ref = ensureInCatalog(choice)
    when (ref.capability) {
        VoiceCapability.STT -> verifyStt(ref)
        VoiceCapability.TTS -> verifyTts(ref, TtsRequest(sample))
        VoiceCapability.REALTIME -> verifyRealtime(ref)
    }
}

@Composable
internal fun voiceSourceLabel(source: VoiceSource): String = when (source) {
    VoiceSource.Device -> stringResource(R.string.voice_source_device)
    is VoiceSource.ChatAccount -> stringResource(R.string.voice_source_account, source.name)
    is VoiceSource.Service -> stringResource(R.string.voice_source_service, source.name)
}

@Composable
private fun voiceTraitLabel(trait: VoiceTrait): String = stringResource(
    when (trait) {
        VoiceTrait.Live -> R.string.voice_trait_live
        VoiceTrait.AfterSpeech -> R.string.voice_trait_after
        VoiceTrait.Free -> R.string.voice_trait_free
        VoiceTrait.StreamingRead -> R.string.voice_trait_streaming_read
        VoiceTrait.WholeRead -> R.string.voice_trait_whole_read
        VoiceTrait.Interruptible -> R.string.voice_trait_interruptible
        VoiceTrait.PhoneOnly -> R.string.voice_trait_phone_only
    },
)

/**
 * The options card every use page shares: name, where it comes from, what it is like, and a test right beside it.
 * Picking never leaves the page, so the user can test what they just chose.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VoiceOptionList(
    choices: List<VoiceOption>,
    selected: VoiceModelRef?,
    testLabel: String,
    viewModel: VoiceCatalogViewModel,
    onPick: (VoiceOption) -> Unit,
    onTest: (VoiceOption) -> Unit,
    pickable: (VoiceOption) -> Boolean = { true },
    extraTop: (@Composable () -> Unit)? = null,
) {
    val probes by viewModel.probes.collectAsState()
    val c = MikaTheme.colors
    GroupCard {
        extraTop?.invoke()
        choices.forEachIndexed { index, choice ->
            val probe = if (choice.offer == null) viewModel.probeFingerprint(choice.ref)?.let { probes[it] } ?: VoiceProbeUi.Idle
            else VoiceProbeUi.Idle
            RadioRow(
                voiceModelDisplayName(choice.model),
                selected = choice.ref == selected,
                enabled = pickable(choice),
                onClick = { onPick(choice) },
                subtitle = voiceSourceLabel(choice.source),
                first = index == 0 && extraTop == null,
                titleStyle = ModelIdStyle,
                modifier = Modifier.testTag("voice-choice-${choice.ref.serviceId}-${choice.ref.modelId}"),
                below = {
                    Column {
                        if (choice.traits.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                choice.traits.forEach { trait ->
                                    CapabilityTag(voiceTraitLabel(trait), if (trait == VoiceTrait.Live || trait == VoiceTrait.StreamingRead || trait == VoiceTrait.Interruptible) TagTone.Accent else TagTone.Neutral)
                                }
                            }
                        }
                        when (probe) {
                            VoiceProbeUi.Idle -> if (choice.verified) Text(stringResource(R.string.voice_catalog_verified), Modifier.padding(top = 4.dp), style = MikaType.Hint, color = c.ok)
                            VoiceProbeUi.Running -> Text(stringResource(R.string.voice_catalog_testing), Modifier.padding(top = 4.dp), style = MikaType.Hint, color = c.ink3)
                            is VoiceProbeUi.Listening -> Text(probe.transcript.ifBlank { stringResource(R.string.voice_catalog_listening) }, Modifier.padding(top = 4.dp), style = MikaType.Hint, color = c.ink2)
                            is VoiceProbeUi.Failed -> Text(probe.error.detail, Modifier.padding(top = 4.dp), style = MikaType.Hint, color = c.bad)
                            VoiceProbeUi.Verified -> Text(stringResource(R.string.voice_catalog_verified), Modifier.padding(top = 4.dp), style = MikaType.Hint, color = c.ok)
                        }
                    }
                },
                trailing = {
                    MikaButton(
                        testLabel, onClick = { onTest(choice) }, kind = ButtonKind.Tonal, size = ButtonSize.Small,
                        enabled = probe !is VoiceProbeUi.Running && probe !is VoiceProbeUi.Listening,
                        modifier = Modifier.testTag("voice-try-${choice.ref.serviceId}-${choice.ref.modelId}"),
                    )
                },
            )
        }
    }
}

/**
 * A list of voices in a bottom sheet; the saved one is listed even when it is not a known voice. The last row takes
 * any voice id (a cloned voice, one newer than the list), so the list never limits what can be used.
 */
@Composable
internal fun VoicePickerSheet(voices: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit, model: VoiceModel? = null) {
    var typing by remember { mutableStateOf(false) }
    if (typing) {
        TextEntryDialog(
            title = stringResource(R.string.voice_enter_voice_id), label = stringResource(R.string.voice_voice_id),
            onDismiss = onDismiss, onConfirm = { onPick(it); onDismiss() }, tag = "voice-id-input",
        )
        return
    }
    val actions = voices.map { voice -> SheetAction(VoiceVoices.label(model, voice)) { onPick(voice) } } +
        SheetAction(stringResource(R.string.voice_enter_voice_id), MikaIcons.Edit) { typing = true }
    // 选「手动输入」时面板会顺手关掉,这里拦下,让输入框接着出来
    ActionSheet(stringResource(R.string.voice_voice), actions) { if (!typing) onDismiss() }
}

/** Shown under each use page: where to connect more services. */
@Composable
internal fun VoiceMoreServices(onOpenServices: () -> Unit) {
    GroupLabel(stringResource(R.string.voice_more_label))
    GroupCard {
        ListRow(MikaIcons.Plus, stringResource(R.string.voice_more_services), first = true, onClick = onOpenServices) { RowChevron() }
    }
}

/** The model a reference points to, or null when it no longer resolves. */
internal fun VoiceCatalogSnapshot.modelOf(ref: VoiceModelRef?): VoiceModel? =
    ref?.let { r -> models.firstOrNull { it.serviceId == r.serviceId && it.id == r.modelId } }

/** The display name of a selected model, or null when the reference no longer resolves. */
@Composable
internal fun VoiceCatalogSnapshot.nameOf(ref: VoiceModelRef?): String? = modelOf(ref)?.let { voiceModelDisplayName(it) }

/** Number of accounts voice can use: the phone itself, connected chat accounts that offer voice, and voice-only services. */
internal fun voiceServiceCount(snapshot: VoiceCatalogSnapshot, providers: List<Provider>): Int {
    val accounts = providers.count { p ->
        ProviderVoiceOffers.offers(p).isNotEmpty() ||
            snapshot.services.any { (it.account as? VoiceAccount.ExistingProvider)?.providerId == p.id }
    }
    val owned = snapshot.services.count { it.account !is VoiceAccount.ExistingProvider && it.id != SttSelectionController.SYSTEM_STT_SERVICE.id }
    return 1 + accounts + owned
}

/**
 * The single 「语音」 group on the models page: three uses, each saying who does it now, plus the accounts row.
 * A use that cannot work yet says so and leads to its page instead of being greyed out.
 */
@Composable
internal fun VoiceHubSection(
    snapshot: VoiceCatalogSnapshot,
    providers: List<Provider>,
    readAloudOn: Boolean,
    onStt: () -> Unit,
    onTts: () -> Unit,
    onCallMode: () -> Unit,
    onServices: () -> Unit,
) {
    val selections = snapshot.selections
    val sttLine = when (val stt = selections.stt) {
        SttSelection.Off -> stringResource(R.string.voice_hub_off)
        SttSelection.Auto -> stringResource(R.string.voice_source_device)
        is SttSelection.Model -> snapshot.nameOf(stt.ref) ?: stringResource(R.string.voice_hub_none)
    }
    val ttsName = snapshot.nameOf(selections.tts)
    val realtimeName = snapshot.nameOf(selections.realtime)
    GroupLabel(stringResource(R.string.voice_hub_group))
    GroupCard(Modifier.testTag("voice-hub")) {
        ListRow(MikaIcons.Mic, stringResource(R.string.voice_hub_stt), sttLine, first = true, onClick = onStt,
            modifier = Modifier.testTag("voice-hub-stt")) { RowChevron() }
        ListRow(MikaIcons.Sound, stringResource(R.string.voice_hub_tts),
            if (readAloudOn) ttsName ?: stringResource(R.string.voice_hub_none) else stringResource(R.string.voice_hub_off),
            onClick = onTts, modifier = Modifier.testTag("voice-hub-tts")) { RowChevron() }
        val engine = selections.resolvedEngine
        val needsSetup = when (engine) {
            CallEngine.Standard -> selections.listenUnusableForCalls() || selections.tts == null
            CallEngine.Realtime -> realtimeName == null
        }
        ListRow(MikaIcons.Handset, stringResource(R.string.voice_hub_call_mode), callModeLine(snapshot, engine),
            onClick = onCallMode, modifier = Modifier.testTag("voice-hub-call-mode")) {
            if (needsSetup) StatusPill(stringResource(R.string.voice_hub_setup), PillTone.Warn)
            RowChevron()
        }
    }
    GroupCard(Modifier.padding(top = 8.dp)) {
        ListRow(MikaIcons.Key, stringResource(R.string.voice_hub_services),
            stringResource(R.string.voice_hub_services_sub, voiceServiceCount(snapshot, providers)),
            first = true, onClick = onServices, modifier = Modifier.testTag("voice-hub-services")) { RowChevron() }
    }
    FooterNote(stringResource(R.string.voice_hub_footer), Modifier.padding(top = 6.dp))
}

/**
 * 「语音通话」: the realtime model in-app voice calls use, which AI phone calls follow unless they pick their own.
 * The voice comes from the model's own list.
 */
@Composable
fun RealtimeVoiceScreen(store: ProviderStore, onBack: () -> Unit, onOpenCalls: () -> Unit, onOpenServices: () -> Unit) {
    val context = LocalContext.current
    val voiceModels = remember { VoiceModelEnvironment.get(context) }
    val viewModel = rememberVoiceCatalogViewModel(voiceModels)
    val snapshot by voiceModels.catalog.state.collectAsState()
    val providers by store.providers.collectAsState()
    val choices = voiceOptions(VoiceCapability.REALTIME, snapshot, providers)
    val selected = snapshot.selections.realtime
    val selectedModel = snapshot.modelOf(selected)
    var voicesOpen by rememberSaveable { mutableStateOf(false) }
    val c = MikaTheme.colors
    Page(stringResource(R.string.voice_hub_realtime), onBack) {
        Text(stringResource(R.string.realtime_lead), Modifier.padding(top = 4.dp, bottom = 12.dp), style = MikaType.Body, color = c.ink2)
        if (selected == null) {
            GroupCard {
                ListRow(null, stringResource(R.string.realtime_empty), stringResource(R.string.realtime_empty_note), first = true)
            }
        }
        if (choices.isEmpty()) {
            FooterNote(stringResource(R.string.realtime_none_available), Modifier.padding(top = 8.dp))
        } else {
            GroupLabel(stringResource(R.string.voice_choices_label))
            VoiceOptionList(
                choices, selected, stringResource(R.string.voice_try_realtime), viewModel,
                onPick = { choice -> voiceModels.realtime.select(viewModel.ensureInCatalog(choice)) },
                onTest = { choice -> viewModel.tryChoice(choice, "") },
                // 应用内语音通话只接得住可打断的双向协议;只能打电话的模型留在列表里说明,选它去 AI 接打电话那页
                pickable = { VoiceTrait.PhoneOnly !in it.traits },
            )
        }
        val binding = selectedModel?.realtime
        if (selectedModel != null && binding != null) {
            GroupLabel(stringResource(R.string.voice_settings_of, voiceModelDisplayName(selectedModel)))
            GroupCard {
                val speaker = binding.speaker
                ListRow(null, stringResource(R.string.voice_voice), first = true, onClick = { voicesOpen = true },
                    modifier = Modifier.testTag("realtime-voice")) {
                    RowValue(if (speaker.isBlank()) stringResource(R.string.voice_voice_default) else VoiceVoices.label(speaker))
                    RowChevron()
                }
            }
            if (voicesOpen) {
                VoicePickerSheet(
                    VoiceVoices.withCurrent(VoiceVoices.realtime(binding.protocol), binding.speaker),
                    onPick = { voice -> voiceModels.catalog.upsertModel(selectedModel.copy(realtime = binding.copy(speaker = voice))) },
                    onDismiss = { voicesOpen = false },
                )
            }
        }
        GroupLabel(stringResource(R.string.realtime_also_used))
        GroupCard {
            val own = snapshot.selections.callRealtime
            ListRow(
                MikaIcons.Phone, stringResource(R.string.calls_title),
                if (own == null) stringResource(R.string.realtime_calls_follow)
                else stringResource(R.string.realtime_calls_own, snapshot.nameOf(own) ?: own.modelId),
                first = true, onClick = onOpenCalls,
            ) { RowChevron() }
        }
        VoiceMoreServices(onOpenServices)
        FooterNote(stringResource(R.string.realtime_footer), Modifier.padding(top = 8.dp))
    }
}

/** Status pill helper kept here so pages agree on wording. */
@Composable
internal fun VoiceUsablePill(usable: Boolean) {
    if (usable) RowValue(stringResource(R.string.voice_hub_ready), ValueTone.Ok)
    else StatusPill(stringResource(R.string.voice_hub_setup), PillTone.Warn)
}
