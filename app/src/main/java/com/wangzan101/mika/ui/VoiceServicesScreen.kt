package com.wangzan101.mika.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.BailianProtocol
import com.wangzan101.mika.voice.BailianRegion
import com.wangzan101.mika.voice.model.ProviderVoiceOffers
import com.wangzan101.mika.voice.model.SttSelection
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.TtsSelectionController
import com.wangzan101.mika.voice.model.VoiceAccount
import com.wangzan101.mika.voice.model.VoiceAccountManager
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCatalogSnapshot
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.VoiceModelError
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoicePreset
import com.wangzan101.mika.voice.model.VoicePresets
import com.wangzan101.mika.voice.model.VolcSttPlan

private enum class PresetKind { Volc, Bailian, Compatible, SelfHosted }

/**
 * 「语音服务」: every account voice draws on, split by where it comes from, and the place to connect another one.
 * Known services are presets that ask only for their own fields; the full catalog form stays under Advanced.
 */
@Composable
fun VoiceServicesScreen(store: ProviderStore, onBack: () -> Unit, onOpenCustom: () -> Unit) {
    val context = LocalContext.current
    val voiceModels = remember { VoiceModelEnvironment.get(context) }
    val viewModel = rememberVoiceCatalogViewModel(voiceModels)
    val snapshot by voiceModels.catalog.state.collectAsState()
    val providers by store.providers.collectAsState()
    var adding by rememberSaveable { mutableStateOf(false) }
    var preset by rememberSaveable { mutableStateOf<PresetKind?>(null) }
    var saved by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val results = saved?.mapNotNull(::decodeRef)
    val back: () -> Unit = {
        when {
            results != null -> { saved = null; preset = null; adding = false }
            preset != null -> preset = null
            adding -> adding = false
            else -> onBack()
        }
    }
    BackHandler(enabled = adding || preset != null || results != null) { back() }
    when {
        results != null -> PresetResult(snapshot, results, viewModel, voiceModels, onDone = back)
        preset != null -> PresetForm(requireNotNull(preset), back) { plan ->
            val manager = VoiceAccountManager(voiceModels.catalog, requireNotNull(voiceModels.credentialStore))
            saved = VoicePresets.apply(manager, voiceModels.catalog, plan).map(::encodeRef)
        }
        adding -> AddVoiceService(back, onPick = { preset = it }, onOpenCustom = onOpenCustom)
        else -> ServiceList(snapshot, providers, back, onAdd = { adding = true }, onOpenCustom = onOpenCustom)
    }
}

// 列表要能存进 rememberSaveable,引用编码成一行字
private fun encodeRef(ref: VoiceModelRef) = listOf(ref.serviceId, ref.modelId, ref.capability.name).joinToString("\u0001")
private fun decodeRef(text: String): VoiceModelRef? = text.split("\u0001").takeIf { it.size == 3 }?.let { (s, m, c) ->
    runCatching { VoiceModelRef(s, m, VoiceCapability.valueOf(c)) }.getOrNull()
}

@Composable
private fun capabilityLine(caps: Collection<VoiceCapability>): String = VoiceCapability.entries.filter { it in caps }.map {
    stringResource(
        when (it) {
            VoiceCapability.STT -> R.string.voice_cap_stt
            VoiceCapability.TTS -> R.string.voice_cap_tts
            VoiceCapability.REALTIME -> R.string.voice_cap_realtime
        },
    )
}.joinToString(" · ")

@Composable
private fun ServiceList(
    snapshot: VoiceCatalogSnapshot, providers: List<Provider>, onBack: () -> Unit, onAdd: () -> Unit, onOpenCustom: () -> Unit,
) {
    val c = MikaTheme.colors
    Page(stringResource(R.string.voice_hub_services), onBack) {
        Text(stringResource(R.string.voice_services_lead), Modifier.padding(top = 4.dp, bottom = 12.dp), style = MikaType.Body, color = c.ink2)
        if (providers.isNotEmpty()) {
            GroupLabel(stringResource(R.string.voice_services_from_chat))
            GroupCard {
                providers.forEachIndexed { index, provider ->
                    val inCatalog = snapshot.models.filter { it.serviceId == ProviderVoiceOffers.serviceId(provider.id) }
                    val caps = (ProviderVoiceOffers.offers(provider) + inCatalog).flatMap { it.capabilities }.toSet()
                    ListRow(
                        MikaIcons.Key, ModelsCopy.providerName(provider),
                        if (caps.isEmpty()) stringResource(R.string.voice_services_no_voice) else capabilityLine(caps),
                        first = index == 0,
                    ) { if (caps.isNotEmpty()) StatusPill(stringResource(R.string.voice_services_auto), PillTone.Neutral) }
                }
            }
        }
        GroupLabel(stringResource(R.string.voice_services_voice_only))
        GroupCard {
            ListRow(
                MikaIcons.Mic, stringResource(R.string.voice_source_device),
                capabilityLine(setOf(VoiceCapability.STT, VoiceCapability.TTS)), first = true,
            ) { StatusPill(stringResource(R.string.voice_services_builtin), PillTone.Neutral) }
            snapshot.services.filter {
                it.account !is VoiceAccount.ExistingProvider && it.id != SttSelectionController.SYSTEM_STT_SERVICE.id &&
                    it.id != TtsSelectionController.SYSTEM_TTS_REF.serviceId
            }.forEach { service ->
                val caps = snapshot.models.filter { it.serviceId == service.id }.flatMap { it.capabilities }.toSet()
                ListRow(MikaIcons.Key, service.name, capabilityLine(caps), onClick = onOpenCustom,
                    modifier = Modifier.testTag("voice-service-${service.id}")) { RowChevron() }
            }
            ListRow(MikaIcons.Plus, stringResource(R.string.voice_services_add), onClick = onAdd,
                modifier = Modifier.testTag("voice-service-add")) { RowChevron() }
        }
        FooterNote(stringResource(R.string.voice_services_footer), Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun AddVoiceService(onBack: () -> Unit, onPick: (PresetKind) -> Unit, onOpenCustom: () -> Unit) {
    val c = MikaTheme.colors
    Page(stringResource(R.string.voice_services_add), onBack) {
        Text(stringResource(R.string.voice_add_lead), Modifier.padding(top = 4.dp, bottom = 12.dp), style = MikaType.Body, color = c.ink2)
        GroupLabel(stringResource(R.string.voice_add_common))
        GroupCard {
            ListRow(MikaIcons.Key, stringResource(R.string.voice_preset_volc), stringResource(R.string.voice_preset_volc_sub), first = true,
                onClick = { onPick(PresetKind.Volc) }, modifier = Modifier.testTag("preset-volc")) { RowChevron() }
            ListRow(MikaIcons.Key, stringResource(R.string.voice_preset_bailian), stringResource(R.string.voice_preset_bailian_sub),
                onClick = { onPick(PresetKind.Bailian) }, modifier = Modifier.testTag("preset-bailian")) { RowChevron() }
            ListRow(MikaIcons.Key, stringResource(R.string.voice_preset_compat), stringResource(R.string.voice_preset_compat_sub),
                onClick = { onPick(PresetKind.Compatible) }, modifier = Modifier.testTag("preset-compat")) { RowChevron() }
        }
        GroupLabel(stringResource(R.string.voice_add_self_hosted))
        GroupCard {
            ListRow(MikaIcons.Key, stringResource(R.string.voice_preset_selfhosted), stringResource(R.string.voice_preset_selfhosted_sub),
                first = true, onClick = { onPick(PresetKind.SelfHosted) }, modifier = Modifier.testTag("preset-selfhosted")) { RowChevron() }
        }
        GroupLabel(stringResource(R.string.voice_add_advanced))
        GroupCard {
            ListRow(null, stringResource(R.string.voice_add_custom), stringResource(R.string.voice_add_custom_sub), first = true,
                onClick = onOpenCustom) { RowChevron() }
        }
    }
}

@Composable
private fun PresetForm(kind: PresetKind, onBack: () -> Unit, onSave: (com.wangzan101.mika.voice.model.VoicePresetPlan) -> Unit) {
    val c = MikaTheme.colors
    var apiKey by rememberSaveable { mutableStateOf("") }
    var appId by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var volcStt by rememberSaveable { mutableStateOf(true) }
    var volcPlan by rememberSaveable { mutableStateOf(VolcSttPlan.Duration) }
    var volcRealtime by rememberSaveable { mutableStateOf(true) }
    var region by rememberSaveable { mutableStateOf(BailianRegion.Beijing) }
    var workspace by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf(BailianProtocol.DEFAULT_MODEL) }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var sttModel by rememberSaveable { mutableStateOf("") }
    var ttsModel by rememberSaveable { mutableStateOf("") }
    var rtModel by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val title = stringResource(
        when (kind) {
            PresetKind.Volc -> R.string.voice_preset_volc
            PresetKind.Bailian -> R.string.voice_preset_bailian
            PresetKind.Compatible -> R.string.voice_preset_compat
            PresetKind.SelfHosted -> R.string.voice_preset_selfhosted
        },
    )
    val optional = stringResource(R.string.voice_field_optional)
    Page(title, onBack) {
        Text(
            stringResource(
                when (kind) {
                    PresetKind.Volc -> R.string.voice_volc_lead
                    PresetKind.Bailian -> R.string.voice_bailian_lead
                    PresetKind.Compatible -> R.string.voice_compat_lead
                    PresetKind.SelfHosted -> R.string.voice_selfhosted_lead
                },
            ),
            Modifier.padding(top = 4.dp, bottom = 12.dp), style = MikaType.Body, color = c.ink2,
        )
        when (kind) {
            PresetKind.Volc -> {
                GroupCard {
                    InputRow(stringResource(R.string.voice_field_api_key), apiKey, { apiKey = it }, optional, first = true, secret = true)
                    InputRow(stringResource(R.string.voice_field_app_id), appId, { appId = it }, optional)
                    InputRow(stringResource(R.string.voice_field_access_token), token, { token = it }, optional, secret = true)
                }
                FooterNote(stringResource(R.string.voice_volc_auth_note))
                GroupLabel(stringResource(R.string.voice_volc_unlocks))
                GroupCard {
                    ListRow(MikaIcons.Mic, stringResource(R.string.voice_volc_use_stt), stringResource(R.string.voice_volc_use_stt_note), first = true) {
                        MikaSwitch(volcStt, { volcStt = it })
                    }
                    if (volcStt) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(stringResource(R.string.voice_volc_plan), style = MikaType.Hint, color = c.ink3)
                            SegmentedControl(
                                listOf(stringResource(R.string.voice_volc_plan_duration), stringResource(R.string.voice_volc_plan_concurrent)),
                                volcPlan.ordinal, { volcPlan = VolcSttPlan.entries[it] }, Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    ListRow(MikaIcons.Handset, stringResource(R.string.voice_volc_use_rt), stringResource(R.string.voice_volc_use_rt_note)) {
                        MikaSwitch(volcRealtime, { volcRealtime = it })
                    }
                }
            }
            PresetKind.Bailian -> {
                GroupCard {
                    InputRow(stringResource(R.string.voice_field_api_key), apiKey, { apiKey = it }, "sk-…", first = true, secret = true)
                    InputRow(stringResource(R.string.voice_bailian_workspace), workspace, { workspace = it }, optional)
                    InputRow(stringResource(R.string.voice_field_model), model, { model = it }, BailianProtocol.DEFAULT_MODEL)
                }
                GroupLabel(stringResource(R.string.voice_bailian_region))
                SegmentedControl(
                    listOf(stringResource(R.string.voice_region_beijing), stringResource(R.string.voice_region_singapore)),
                    region.ordinal, { region = BailianRegion.entries[it] }, Modifier.padding(bottom = 12.dp),
                )
            }
            PresetKind.Compatible -> {
                GroupCard {
                    InputRow(stringResource(R.string.voice_field_url), url, { url = it }, "https://…/v1", first = true, keyboard = KeyboardType.Uri)
                    InputRow(stringResource(R.string.voice_field_api_key), apiKey, { apiKey = it }, "sk-…", secret = true)
                    InputRow(stringResource(R.string.voice_field_name), name, { name = it }, optional)
                }
                GroupLabel(stringResource(R.string.voice_compat_models))
                GroupCard {
                    InputRow(stringResource(R.string.voice_cap_stt), sttModel, { sttModel = it }, "gpt-4o-transcribe", first = true)
                    InputRow(stringResource(R.string.voice_cap_tts), ttsModel, { ttsModel = it }, "gpt-4o-mini-tts")
                    InputRow(stringResource(R.string.voice_cap_realtime), rtModel, { rtModel = it }, "gpt-realtime")
                }
                FooterNote(stringResource(R.string.voice_compat_models_note))
            }
            PresetKind.SelfHosted -> {
                GroupCard {
                    InputRow(stringResource(R.string.voice_field_url), url, { url = it }, "192.168.1.5:10095", first = true, keyboard = KeyboardType.Uri)
                }
            }
        }
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MikaType.Hint, color = c.bad) }
        MikaButton(
            stringResource(R.string.voice_save_and_test),
            onClick = {
                val preset = when (kind) {
                    PresetKind.Volc -> VoicePreset.Volc(apiKey, appId, token, volcStt, volcPlan, volcRealtime)
                    PresetKind.Bailian -> VoicePreset.Bailian(apiKey, region, workspace, model)
                    PresetKind.Compatible -> VoicePreset.OpenAiCompatible(name, url, apiKey, sttModel, ttsModel, rtModel)
                    PresetKind.SelfHosted -> VoicePreset.SelfHostedStt(url)
                }
                try {
                    onSave(VoicePresets.plan(preset))
                } catch (e: VoiceModelError) {
                    error = e.detail
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("preset-save"),
        )
        FooterNote(stringResource(R.string.voice_preset_cred_note), Modifier.padding(top = 8.dp))
    }
}

/** After saving: each new capability with its own test and a button to start using it right here. */
@Composable
private fun PresetResult(
    snapshot: VoiceCatalogSnapshot, refs: List<VoiceModelRef>, viewModel: VoiceCatalogViewModel,
    voiceModels: VoiceModelEnvironment, onDone: () -> Unit,
) {
    val c = MikaTheme.colors
    val probes by viewModel.probes.collectAsState()
    val sample = stringResource(R.string.voice_try_sample)
    Page(stringResource(R.string.voice_result_title), onDone) {
        Text(stringResource(R.string.voice_result_lead), Modifier.padding(top = 4.dp, bottom = 12.dp), style = MikaType.Body, color = c.ink2)
        GroupCard {
            refs.forEachIndexed { index, ref ->
                val model = snapshot.modelOf(ref) ?: return@forEachIndexed
                val probe = viewModel.probeFingerprint(ref)?.let { probes[it] } ?: VoiceProbeUi.Idle
                val inUse = when (ref.capability) {
                    VoiceCapability.STT -> snapshot.selections.stt == SttSelection.Model(ref)
                    VoiceCapability.TTS -> snapshot.selections.tts == ref
                    VoiceCapability.REALTIME -> snapshot.selections.realtime == ref
                }
                ListRow(null, voiceModelDisplayName(model), capabilityLine(setOf(ref.capability)), first = index == 0) {
                    MikaButton(
                        stringResource(
                            when (ref.capability) {
                                VoiceCapability.STT -> R.string.voice_try_stt
                                VoiceCapability.TTS -> R.string.voice_try_tts
                                VoiceCapability.REALTIME -> R.string.voice_try_realtime
                            },
                        ),
                        onClick = {
                            when (ref.capability) {
                                VoiceCapability.STT -> viewModel.verifyStt(ref)
                                VoiceCapability.TTS -> viewModel.verifyTts(ref, com.wangzan101.mika.core.model.TtsRequest(sample))
                                VoiceCapability.REALTIME -> viewModel.verifyRealtime(ref)
                            }
                        },
                        kind = ButtonKind.Tonal, size = ButtonSize.Small,
                        enabled = probe !is VoiceProbeUi.Running && probe !is VoiceProbeUi.Listening,
                    )
                }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val line = when (probe) {
                        VoiceProbeUi.Idle -> null
                        VoiceProbeUi.Running -> stringResource(R.string.voice_catalog_testing)
                        is VoiceProbeUi.Listening -> probe.transcript.ifBlank { stringResource(R.string.voice_catalog_listening) }
                        is VoiceProbeUi.Failed -> probe.error.detail
                        VoiceProbeUi.Verified -> stringResource(R.string.voice_catalog_verified)
                    }
                    Text(
                        line.orEmpty(), Modifier.weight(1f).padding(top = 6.dp), style = MikaType.Hint,
                        color = if (probe is VoiceProbeUi.Failed) c.bad else if (probe == VoiceProbeUi.Verified) c.ok else c.ink3,
                    )
                    MikaButton(
                        stringResource(
                            if (inUse) R.string.voice_result_in_use
                            else when (ref.capability) {
                                VoiceCapability.STT -> R.string.voice_use_for_stt
                                VoiceCapability.TTS -> R.string.voice_use_for_tts
                                VoiceCapability.REALTIME -> R.string.voice_use_for_realtime
                            },
                        ),
                        onClick = {
                            when (ref.capability) {
                                VoiceCapability.STT -> voiceModels.stt.select(ref)
                                VoiceCapability.TTS -> voiceModels.tts.select(ref)
                                VoiceCapability.REALTIME -> voiceModels.realtime.select(ref)
                            }
                        },
                        kind = ButtonKind.Secondary, size = ButtonSize.Small, enabled = !inUse,
                        modifier = Modifier.testTag("use-${ref.capability.name.lowercase()}"),
                    )
                }
            }
        }
        MikaButton(stringResource(R.string.voice_result_done), onClick = onDone, kind = ButtonKind.Secondary,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}
