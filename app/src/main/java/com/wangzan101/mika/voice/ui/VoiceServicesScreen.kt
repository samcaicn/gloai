package com.wangzan101.mika.voice.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.voice.model.VoiceListRefresh
import com.wangzan101.mika.voice.model.VoiceModelLists
import com.wangzan101.mika.voice.model.VoiceModelOrigin
import com.wangzan101.mika.voice.model.VoiceModelRefresher
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.mapSaver
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
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
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
import com.wangzan101.mika.voice.model.VoicePresetPlan
import com.wangzan101.mika.voice.model.VoicePresets
import com.wangzan101.mika.voice.model.VoiceService
import com.wangzan101.mika.voice.model.VoiceModel
import com.wangzan101.mika.voice.model.VoiceVendor
import com.wangzan101.mika.voice.model.VoiceVendors
import com.wangzan101.mika.settings.ui.models.ModelsCopy
import com.wangzan101.mika.settings.ui.models.Page
import com.wangzan101.mika.settings.ui.models.pick
import com.wangzan101.mika.settings.ui.models.refresh
import com.wangzan101.mika.settings.ui.models.use

private enum class PresetKind { Compatible, SelfHosted }

/**
 * 「语音服务」: every account voice draws on. Built-in vendors ask for their own fields once and bring all their
 * listen, speak and realtime models; the OpenAI-compatible form, self-hosted recognition and the full catalog
 * form stay under Advanced.
 */
@Composable
fun VoiceServicesScreen(store: ProviderStore, onBack: () -> Unit, onOpenCustom: () -> Unit) {
    val context = LocalContext.current
    val voiceModels = remember { VoiceModelEnvironment.get(context) }
    val viewModel = rememberVoiceCatalogViewModel(voiceModels)
    val snapshot by voiceModels.catalog.state.collectAsState()
    val providers by store.providers.collectAsState()
    var vendorForm by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var preset by rememberSaveable { mutableStateOf<PresetKind?>(null) }
    var saved by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val results = saved?.mapNotNull(::decodeRef)
    val back: () -> Unit = {
        when {
            results != null -> { saved = null; preset = null; vendorForm = null }
            preset != null -> preset = null
            vendorForm != null -> vendorForm = null
            detail != null -> detail = null
            else -> onBack()
        }
    }
    fun apply(plan: VoicePresetPlan) {
        val manager = VoiceAccountManager(voiceModels.catalog, requireNotNull(voiceModels.credentialStore))
        saved = VoicePresets.apply(manager, voiceModels.catalog, plan).map(::encodeRef)
        // 新 key 下的模型列表可能不同,回到详情页时重新拉
        listedThisRun.remove(plan.service.id)
    }
    BackHandler(enabled = vendorForm != null || detail != null || preset != null || results != null) { back() }
    when {
        results != null -> PresetResult(snapshot, results, viewModel, voiceModels, onDone = {
            // 存完回到这家服务商的详情,看它带来的全部模型
            detail = results.firstOrNull()?.serviceId ?: detail
            saved = null; preset = null; vendorForm = null
        })
        preset != null -> PresetForm(requireNotNull(preset), back, ::apply)
        vendorForm != null -> VoiceVendors.byId(requireNotNull(vendorForm))?.let { VendorForm(it, back, ::apply) } ?: back()
        detail != null -> {
            val service = snapshot.services.firstOrNull { it.id == detail }
            if (service == null) back()
            else VendorDetail(snapshot, service, voiceModels, back,
                onChangeKey = VoiceVendors.forService(service.id)?.takeIf { it.builds(service.id) }?.let { vendor -> { vendorForm = vendor.id } } ?: onOpenCustom,
                onTest = { saved = listOf(encodeRef(it)) })
        }
        else -> ServiceList(
            snapshot, providers, back,
            onVendor = { vendorForm = it.id }, onService = { detail = it }, onPreset = { preset = it }, onOpenCustom = onOpenCustom,
        )
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

/** "Listen 2 · Speak 1 · Realtime 1": how many models of each kind, leaving out kinds with none. */
@Composable
private fun modelCountLine(models: List<VoiceModel>): String = VoiceCapability.entries.mapNotNull { cap ->
    val n = models.count { (cap in it.capabilities) }
    if (n == 0) null else stringResource(
        when (cap) {
            VoiceCapability.STT -> R.string.voice_vendor_cap_stt
            VoiceCapability.TTS -> R.string.voice_vendor_cap_tts
            VoiceCapability.REALTIME -> R.string.voice_vendor_cap_rt
        },
        n,
    )
}.joinToString(" · ")

@Composable
private fun ServiceList(
    snapshot: VoiceCatalogSnapshot, providers: List<Provider>, onBack: () -> Unit,
    onVendor: (VoiceVendor) -> Unit, onService: (String) -> Unit, onPreset: (PresetKind) -> Unit, onOpenCustom: () -> Unit,
) {
    val c = MikaTheme.colors
    val own = snapshot.services.filter {
        it.account !is VoiceAccount.ExistingProvider && it.id != SttSelectionController.SYSTEM_STT_SERVICE.id &&
            it.id != TtsSelectionController.SYSTEM_TTS_REF.serviceId
    }
    val unconnected = VoiceVendors.all.filter { vendor -> own.none { vendor.owns(it.id) } }
    Page(stringResource(R.string.voice_hub_services), onBack) {
        Text(stringResource(R.string.voice_vendors_lead), Modifier.padding(top = 4.dp, bottom = 12.dp), style = MikaType.Body, color = c.ink2)
        GroupLabel(stringResource(R.string.voice_vendors_connected))
        GroupCard {
            ListRow(
                MikaIcons.Mic, stringResource(R.string.voice_source_device),
                capabilityLine(setOf(VoiceCapability.STT, VoiceCapability.TTS)), first = true,
            ) { StatusPill(stringResource(R.string.voice_services_builtin), PillTone.Neutral) }
            own.forEach { service ->
                val models = snapshot.models.filter { it.serviceId == service.id }
                ListRow(MikaIcons.Key, service.name, modelCountLine(models), onClick = { onService(service.id) },
                    modifier = Modifier.testTag("voice-service-${service.id}")) { RowChevron() }
            }
        }
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
        if (unconnected.isNotEmpty()) {
            GroupLabel(stringResource(R.string.voice_vendors_builtin))
            GroupCard {
                unconnected.forEachIndexed { index, vendor ->
                    ListRow(MikaIcons.Plus, vendor.name, modelCountLine(vendor.preview), first = index == 0,
                        onClick = { onVendor(vendor) }, modifier = Modifier.testTag("vendor-${vendor.id}")) { RowChevron() }
                }
            }
        }
        GroupLabel(stringResource(R.string.voice_add_advanced))
        GroupCard {
            ListRow(MikaIcons.Key, stringResource(R.string.voice_preset_compat), stringResource(R.string.voice_preset_compat_sub), first = true,
                onClick = { onPreset(PresetKind.Compatible) }, modifier = Modifier.testTag("preset-compat")) { RowChevron() }
            ListRow(MikaIcons.Key, stringResource(R.string.voice_preset_selfhosted), stringResource(R.string.voice_preset_selfhosted_sub),
                onClick = { onPreset(PresetKind.SelfHosted) }, modifier = Modifier.testTag("preset-selfhosted")) { RowChevron() }
            ListRow(null, stringResource(R.string.voice_add_custom), stringResource(R.string.voice_add_custom_sub),
                onClick = onOpenCustom, modifier = Modifier.testTag("voice-service-add")) { RowChevron() }
        }
        FooterNote(stringResource(R.string.voice_services_footer), Modifier.padding(top = 6.dp))
    }
}

/** Services whose list was fetched this run; the page fetches on first open, the refresh button any time after. */
private val listedThisRun = mutableSetOf<String>()

private sealed interface ListUi {
    data object Idle : ListUi
    data object Running : ListUi
    data class Result(val outcome: VoiceListRefresh) : ListUi
}

/**
 * One connected service: its key and model list, then every model it brings grouped by what it does. Models the
 * vendor's list did not describe sit folded under 「其他模型」 in both the listen and speak groups; any model id can
 * be typed in. A fetched or typed-in model opens its connection test once chosen, so a model the protocol does not
 * fit shows its error right away.
 */
@Composable
private fun VendorDetail(
    snapshot: VoiceCatalogSnapshot, service: VoiceService, voiceModels: VoiceModelEnvironment,
    onBack: () -> Unit, onChangeKey: () -> Unit, onTest: (VoiceModelRef) -> Unit,
) {
    val c = MikaTheme.colors
    val models = snapshot.models.filter { it.serviceId == service.id }
    val vendor = VoiceVendors.forService(service.id)
    val refresher = remember { VoiceModelRefresher(voiceModels.catalog, voiceModels.accountResolver) }
    val scope = rememberCoroutineScope()
    var list by remember(service.id) { mutableStateOf<ListUi>(ListUi.Idle) }
    var adding by remember(service.id) { mutableStateOf<VoiceCapability?>(null) }
    val open = remember(service.id) { mutableStateMapOf<VoiceCapability, Boolean>() }
    fun refresh() {
        if (list == ListUi.Running) return
        list = ListUi.Running
        scope.launch { list = ListUi.Result(refresher.refresh(service.id)) }
    }
    LaunchedEffect(service.id) {
        if (vendor?.lister != null && listedThisRun.add(service.id)) refresh()
    }
    Page(service.name, onBack) {
        GroupCard(Modifier.padding(top = 8.dp)) {
            ListRow(MikaIcons.Key, service.name, stringResource(R.string.voice_vendor_key_saved), first = true) {
                MikaButton(stringResource(R.string.voice_vendor_change_key), onClick = onChangeKey,
                    kind = ButtonKind.Secondary, size = ButtonSize.Small, modifier = Modifier.testTag("vendor-change-key"))
            }
            val status = if (vendor?.lister == null) stringResource(R.string.voice_list_none) else when (val state = list) {
                ListUi.Idle -> stringResource(R.string.voice_list_idle)
                ListUi.Running -> stringResource(R.string.voice_list_running)
                is ListUi.Result -> when (val r = state.outcome) {
                    VoiceListRefresh.NoList -> stringResource(R.string.voice_list_none)
                    is VoiceListRefresh.Done -> if (r.voices > 0) stringResource(R.string.voice_list_done_voices, r.models, r.voices)
                        else stringResource(R.string.voice_list_done, r.models)
                    is VoiceListRefresh.Failed -> stringResource(R.string.voice_list_failed, r.error.detail)
                }
            }
            ListRow(MikaIcons.Refresh, stringResource(R.string.voice_list_title), status,
                modifier = Modifier.testTag("vendor-list-status")) {
                if (vendor?.lister != null) MikaButton(
                    stringResource(R.string.voice_list_refresh), onClick = ::refresh,
                    kind = ButtonKind.Tonal, size = ButtonSize.Small, enabled = list != ListUi.Running,
                    modifier = Modifier.testTag("vendor-list-refresh"),
                )
            }
        }
        if ((list as? ListUi.Result)?.outcome is VoiceListRefresh.Failed) {
            FooterNote(stringResource(R.string.voice_list_failed_note))
        }
        val templated = VoiceModelLists.templated(models)
        VoiceCapability.entries.forEach { cap ->
            val all = models.filter { (cap in it.capabilities) }
            if (all.isEmpty() && cap !in templated) return@forEach
            val known = all.filterNot { it.guessed }
            val others = all.filter { it.guessed }
            GroupLabel(stringResource(
                when (cap) {
                    VoiceCapability.STT -> R.string.voice_vendor_stt_models
                    VoiceCapability.TTS -> R.string.voice_vendor_tts_models
                    VoiceCapability.REALTIME -> R.string.voice_vendor_rt_models
                },
                known.size,
            ))
            GroupCard {
                known.forEachIndexed { index, model -> VendorModelRow(snapshot, model, cap, index == 0, voiceModels, onTest) }
                if (others.isNotEmpty()) {
                    val expanded = open[cap] == true
                    ListRow(
                        null, stringResource(R.string.voice_other_models, others.size), stringResource(R.string.voice_other_models_sub),
                        first = known.isEmpty(), onClick = { open[cap] = !expanded },
                        modifier = Modifier.testTag("vendor-others-${cap.name.lowercase()}"),
                    ) { Icon(if (expanded) MikaIcons.Up else MikaIcons.Down, null, tint = c.ink3) }
                    if (expanded) others.forEach { model -> VendorModelRow(snapshot, model, cap, false, voiceModels, onTest) }
                }
                if (cap in templated) ListRow(
                    MikaIcons.Plus, stringResource(R.string.voice_add_model_id), first = all.isEmpty(),
                    titleColor = c.accent, onClick = { adding = cap },
                    modifier = Modifier.testTag("vendor-add-${cap.name.lowercase()}"),
                )
            }
        }
    }
    adding?.let { cap ->
        TextEntryDialog(
            title = stringResource(R.string.voice_add_model_id), label = stringResource(R.string.voice_model_id),
            onDismiss = { adding = null },
            onConfirm = { id ->
                adding = null
                refresher.addManual(service.id, id, cap)?.let(onTest)
            },
            tag = "vendor-add-input",
        )
    }
}

/** One model under one capability: in use, or a button that makes it the one. */
@Composable
private fun VendorModelRow(
    snapshot: VoiceCatalogSnapshot, model: VoiceModel, cap: VoiceCapability, first: Boolean,
    voiceModels: VoiceModelEnvironment, onTest: (VoiceModelRef) -> Unit,
) {
    val ref = VoiceModelRef(model.serviceId, model.id, cap)
    val inUse = when (cap) {
        VoiceCapability.STT -> snapshot.selections.stt == SttSelection.Model(ref)
        VoiceCapability.TTS -> snapshot.selections.tts == ref
        VoiceCapability.REALTIME -> snapshot.selections.realtime == ref
    }
    ListRow(null, voiceModelDisplayName(model), model.id.takeIf { it != voiceModelDisplayName(model) }, first = first) {
        if (inUse) StatusPill(stringResource(R.string.voice_result_in_use), PillTone.Accent)
        else MikaButton(
            stringResource(
                when (cap) {
                    VoiceCapability.STT -> R.string.voice_vendor_set_stt
                    VoiceCapability.TTS -> R.string.voice_vendor_set_tts
                    VoiceCapability.REALTIME -> R.string.voice_vendor_set_rt
                },
            ),
            onClick = {
                when (cap) {
                    VoiceCapability.STT -> voiceModels.stt.select(ref)
                    VoiceCapability.TTS -> voiceModels.tts.select(ref)
                    VoiceCapability.REALTIME -> voiceModels.realtime.select(ref)
                }
                // 列表拉来的、手填的模型协议是借来的,选上就去测一次
                if (model.origin != VoiceModelOrigin.Builtin) onTest(ref)
            },
            kind = ButtonKind.Tonal, size = ButtonSize.Small,
            modifier = Modifier.testTag("vendor-use-${cap.name.lowercase()}-${model.id}"),
        )
    }
}

/** A one-line text prompt: a model id, a voice id. */
@Composable
internal fun TextEntryDialog(title: String, label: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit, tag: String) {
    var text by rememberSaveable { mutableStateOf("") }
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { text = it }, Modifier.fillMaxWidth().testTag(tag),
                singleLine = true, label = { Text(label) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("$tag-ok")) {
                Text(stringResource(R.string.voice_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun vendorFieldLabel(key: String): String = when (key) {
    VoiceVendors.F_API_KEY -> stringResource(R.string.voice_field_api_key)
    VoiceVendors.F_APP_ID -> stringResource(R.string.voice_field_app_id)
    VoiceVendors.F_ACCESS_TOKEN -> stringResource(R.string.voice_field_access_token)
    VoiceVendors.F_SECRET_ID -> stringResource(R.string.voice_field_secret_id)
    VoiceVendors.F_SECRET_KEY -> stringResource(R.string.voice_field_secret_key)
    VoiceVendors.F_API_SECRET -> stringResource(R.string.voice_field_api_secret)
    VoiceVendors.F_REGION -> stringResource(R.string.voice_field_region)
    VoiceVendors.F_WORKSPACE -> stringResource(R.string.voice_bailian_workspace)
    else -> key
}

@Composable
private fun regionLabel(choice: String): String = when (choice) {
    VoiceVendors.REGION_BEIJING -> stringResource(R.string.voice_region_beijing)
    VoiceVendors.REGION_SINGAPORE -> stringResource(R.string.voice_region_singapore)
    VoiceVendors.REGION_CN -> stringResource(R.string.voice_region_cn)
    VoiceVendors.REGION_INTL -> stringResource(R.string.voice_region_intl)
    else -> choice
}

/** Only the fields this vendor asks for; no model to pick, every model it has is added. */
@Composable
private fun VendorForm(vendor: VoiceVendor, onBack: () -> Unit, onSave: (VoicePresetPlan) -> Unit) {
    val c = MikaTheme.colors
    val values = rememberSaveable(vendor.id, saver = mapSaver(
        save = { it.toMap() },
        restore = { saved -> mutableStateMapOf<String, String>().apply { saved.forEach { (k, v) -> put(k, v as String) } } },
    )) { mutableStateMapOf<String, String>().apply { vendor.fields.forEach { f -> f.choices.firstOrNull()?.let { put(f.key, it) } } } }
    var error by remember { mutableStateOf<String?>(null) }
    val optional = stringResource(R.string.voice_field_optional)
    Page(vendor.name, onBack) {
        Text(modelCountLine(vendor.preview), Modifier.padding(top = 4.dp, bottom = 12.dp), style = MikaType.Body, color = c.ink2)
        GroupLabel(stringResource(R.string.voice_vendor_fields))
        val text = vendor.fields.filter { it.choices.isEmpty() }
        GroupCard {
            text.forEachIndexed { index, field ->
                InputRow(
                    vendorFieldLabel(field.key), values[field.key].orEmpty(), { values[field.key] = it },
                    if (field.optional) optional else if (field.key == VoiceVendors.F_REGION) "eastasia" else "",
                    first = index == 0, secret = field.secret, labelWidth = 104.dp,
                    modifier = Modifier.testTag("vendor-field-${field.key}"),
                )
            }
        }
        vendor.fields.filter { it.choices.isNotEmpty() }.forEach { field ->
            GroupLabel(vendorFieldLabel(field.key))
            SegmentedControl(
                field.choices.map { regionLabel(it) }, field.choices.indexOf(values[field.key]).coerceAtLeast(0),
                { values[field.key] = field.choices[it] }, Modifier.padding(bottom = 12.dp),
            )
        }
        if (vendor.id == VoicePresets.VOLC_SERVICE) FooterNote(stringResource(R.string.voice_volc_auth_note))
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MikaType.Hint, color = c.bad) }
        MikaButton(
            stringResource(R.string.voice_save_and_test),
            onClick = {
                try {
                    onSave(vendor.plan(values.toMap()))
                } catch (e: VoiceModelError) {
                    error = e.detail
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("vendor-save"),
        )
        FooterNote(stringResource(R.string.voice_vendor_form_note), Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun PresetForm(kind: PresetKind, onBack: () -> Unit, onSave: (VoicePresetPlan) -> Unit) {
    val c = MikaTheme.colors
    var apiKey by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var sttModel by rememberSaveable { mutableStateOf("") }
    var ttsModel by rememberSaveable { mutableStateOf("") }
    var rtModel by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val title = stringResource(
        when (kind) {
            PresetKind.Compatible -> R.string.voice_preset_compat
            PresetKind.SelfHosted -> R.string.voice_preset_selfhosted
        },
    )
    val optional = stringResource(R.string.voice_field_optional)
    Page(title, onBack) {
        Text(
            stringResource(
                when (kind) {
                    PresetKind.Compatible -> R.string.voice_compat_lead
                    PresetKind.SelfHosted -> R.string.voice_selfhosted_lead
                },
            ),
            Modifier.padding(top = 4.dp, bottom = 12.dp), style = MikaType.Body, color = c.ink2,
        )
        when (kind) {
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
