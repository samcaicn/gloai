package com.wangzan101.mika.voice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.voice.VoicePlayer
import com.wangzan101.mika.voice.model.RealtimeBinding
import com.wangzan101.mika.voice.model.RealtimeProtocol
import com.wangzan101.mika.voice.model.SttBinding
import com.wangzan101.mika.voice.model.SttProtocol
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.TtsBinding
import com.wangzan101.mika.voice.model.TtsProtocol
import com.wangzan101.mika.voice.model.TtsSelectionController
import com.wangzan101.mika.voice.model.VoiceAccount
import com.wangzan101.mika.voice.model.VoiceAccountManager
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCatalogSnapshot
import com.wangzan101.mika.voice.model.VoiceCredentials
import com.wangzan101.mika.voice.model.VoiceModel
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.VoiceModelProbe
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceService
import com.wangzan101.mika.voice.model.VoiceVerification
import com.wangzan101.mika.voice.model.adapters.SttHooks
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.SelectChip
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.components.SubPage
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.launch
import com.wangzan101.mika.settings.ui.models.edit

/**
 * 设置 › 模型 › 语音模型:统一管理语音转文字、文字转语音和实时通话的模型。
 * 服务商、账号、每个能力各自的协议与接口在此声明;语音输入、朗读和通话设置页只负责选择与使用偏好。
 */

/** Resource id for a built-in system model's localized name, or null for user-defined models. */
internal fun systemModelDisplayNameRes(model: VoiceModel): Int? = when {
    model.serviceId == SttSelectionController.SYSTEM_STT_MODEL.serviceId &&
        model.id == SttSelectionController.SYSTEM_STT_MODEL.id -> R.string.voice_catalog_system_stt_name
    model.serviceId == TtsSelectionController.SYSTEM_TTS_MODEL.serviceId &&
        model.id == TtsSelectionController.SYSTEM_TTS_MODEL.id -> R.string.voice_catalog_system_tts_name
    else -> null
}

/** Display name for a catalog model; the built-in system models render a localized resource. */
@Composable
internal fun voiceModelDisplayName(model: VoiceModel): String =
    systemModelDisplayNameRes(model)?.let { stringResource(it) } ?: model.displayName

@Composable
fun VoiceCatalogScreen(voiceModels: VoiceModelEnvironment, onBack: () -> Unit) {
    val viewModel = rememberVoiceCatalogViewModel(voiceModels)
    VoiceCatalogContent(viewModel, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceCatalogContent(viewModel: VoiceCatalogViewModel, onBack: () -> Unit) {
    val snapshot by viewModel.snapshot.collectAsState()
    val probes by viewModel.probes.collectAsState()
    val scope = rememberCoroutineScope()
    var sheetOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<VoiceModel?>(null) }
    var deleting by remember { mutableStateOf<VoiceModel?>(null) }
    var confirmingRepair by remember { mutableStateOf(false) }

    SubPage(stringResource(R.string.voice_catalog_title), onBack) {
        FooterNote(stringResource(R.string.voice_catalog_sub))

        viewModel.migrationError?.let { error ->
            Spacer(Modifier.height(8.dp))
            StatusPill(stringResource(R.string.voice_catalog_migration_failed), PillTone.Warn, Modifier.testTag("voice-catalog-migration-error"))
            FooterNote(error.detail)
        }

        if (snapshot.error != null) {
            Spacer(Modifier.height(12.dp))
            StatusPill(stringResource(R.string.voice_catalog_read_failed), PillTone.Warn, Modifier.testTag("voice-catalog-read-failed"))
            Spacer(Modifier.height(12.dp))
            MikaButton(
                stringResource(R.string.voice_catalog_repair),
                onClick = { confirmingRepair = true },
                modifier = Modifier.fillMaxWidth().testTag("voice-catalog-repair"),
            )
            return@SubPage
        }

        Spacer(Modifier.height(8.dp))
        MikaButton(
            stringResource(R.string.voice_catalog_add),
            onClick = {
                editing = null
                sheetOpen = true
            },
            modifier = Modifier.fillMaxWidth().testTag("voice-catalog-add"),
        )

        val groups = snapshot.models.groupBy { it.serviceId }
        if (snapshot.services.isEmpty() && snapshot.models.isEmpty()) {
            Spacer(Modifier.height(20.dp))
            FooterNote(stringResource(R.string.voice_catalog_empty), Modifier.testTag("voice-catalog-empty"))
        }

        snapshot.services.forEach { service ->
            val models = groups[service.id].orEmpty()
            ServiceGroup(
                service = service,
                models = models,
                snapshot = snapshot,
                onEdit = { model ->
                    editing = model
                    sheetOpen = true
                },
                onDelete = { model -> deleting = model },
                onTest = { ref, request -> viewModel.test(ref, request) },
                probeState = { ref -> viewModel.probeFingerprint(ref)?.let { probes[it] } ?: VoiceProbeUi.Idle },
            )
        }
    }

    if (sheetOpen) {
        VoiceCatalogSheet(
            viewModel = viewModel,
            editing = editing,
            onDismiss = { sheetOpen = false },
        )
    }

    deleting?.let { model ->
        MikaDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.voice_catalog_delete_model_title)) },
            text = { Text(model.displayName) },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteModel(VoiceModelRef(model.serviceId, model.id, VoiceCapability.STT))
                        deleting = null
                    },
                    modifier = Modifier.testTag("voice-catalog-delete-confirm"),
                ) { Text(stringResource(R.string.voice_catalog_delete)) }
            },
        )
    }

    if (confirmingRepair) {
        MikaDialog(
            onDismissRequest = { confirmingRepair = false },
            title = { Text(stringResource(R.string.voice_catalog_repair_title)) },
            text = { Text(stringResource(R.string.voice_catalog_repair_body)) },
            dismissButton = { TextButton(onClick = { confirmingRepair = false }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingRepair = false
                        scope.launch { viewModel.repairCorruptCatalog() }
                    },
                    modifier = Modifier.testTag("voice-catalog-repair-confirm"),
                ) { Text(stringResource(R.string.voice_catalog_repair)) }
            },
        )
    }
}

private fun VoiceCatalogViewModel.test(ref: VoiceModelRef, request: TtsRequest?) {
    when (ref.capability) {
        VoiceCapability.STT -> verifyStt(ref)
        VoiceCapability.TTS -> verifyTts(ref, requireNotNull(request) { "TTS test requires sample text" })
        VoiceCapability.REALTIME -> verifyRealtime(ref)
    }
}

@Composable
private fun ServiceGroup(
    service: VoiceService,
    models: List<VoiceModel>,
    snapshot: VoiceCatalogSnapshot,
    onEdit: (VoiceModel) -> Unit,
    onDelete: (VoiceModel) -> Unit,
    onTest: (VoiceModelRef, TtsRequest?) -> Unit,
    probeState: (VoiceModelRef) -> VoiceProbeUi,
) {
    val system = service.id == SttSelectionController.SYSTEM_STT_SERVICE.id
    GroupCard(Modifier.padding(top = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(service.name, Modifier.weight(1f), style = MikaType.Body.copy(fontWeight = FontWeight.SemiBold), color = MikaTheme.colors.ink)
            Text(
                accountLabel(service.account),
                style = MikaType.Hint,
                color = MikaTheme.colors.ink3,
                modifier = Modifier.testTag("voice-service-account-${service.id}"),
            )
        }
        models.forEachIndexed { index, model ->
            ModelRow(
                model = model,
                first = index == 0,
                system = system && (model.id == SttSelectionController.SYSTEM_STT_MODEL.id || model.id == TtsSelectionController.SYSTEM_TTS_MODEL.id),
                snapshot = snapshot,
                onEdit = { onEdit(model) },
                onDelete = { onDelete(model) },
                onTest = onTest,
                probeState = probeState,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelRow(
    model: VoiceModel,
    first: Boolean,
    system: Boolean,
    snapshot: VoiceCatalogSnapshot,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: (VoiceModelRef, TtsRequest?) -> Unit,
    probeState: (VoiceModelRef) -> VoiceProbeUi,
) {
    val c = MikaTheme.colors
    val ttsSampleText = stringResource(R.string.voice_catalog_tts_sample)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("voice-model-${model.id}"),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(voiceModelDisplayName(model), style = MikaType.Body.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
                Text(model.id, style = MikaType.Hint, color = c.ink3)
            }
            if (!system) {
                TextButton(onClick = onEdit, modifier = Modifier.testTag("voice-model-edit-${model.id}")) {
                    Text(stringResource(R.string.voice_catalog_edit))
                }
                TextButton(onClick = onDelete, modifier = Modifier.testTag("voice-model-delete-${model.id}")) {
                    Text(stringResource(R.string.voice_catalog_delete))
                }
            }
        }

        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            model.stt?.let { binding ->
                CapabilityBadge(
                    ref = VoiceModelRef(model.serviceId, model.id, VoiceCapability.STT),
                    label = stringResource(R.string.voice_catalog_cap_stt),
                    protocol = sttProtocolLabel(binding.protocol),
                    ui = probeState(VoiceModelRef(model.serviceId, model.id, VoiceCapability.STT)),
                    verified = snapshot.verified(VoiceModelRef(model.serviceId, model.id, VoiceCapability.STT)),
                    testLabel = stringResource(R.string.voice_catalog_test),
                    tag = "voice-cap-stt-${model.id}",
                    onTest = { onTest(VoiceModelRef(model.serviceId, model.id, VoiceCapability.STT), null) },
                )
            }
            model.tts?.let { binding ->
                CapabilityBadge(
                    ref = VoiceModelRef(model.serviceId, model.id, VoiceCapability.TTS),
                    label = stringResource(R.string.voice_catalog_cap_tts),
                    protocol = ttsProtocolLabel(binding.protocol),
                    ui = probeState(VoiceModelRef(model.serviceId, model.id, VoiceCapability.TTS)),
                    verified = snapshot.verified(VoiceModelRef(model.serviceId, model.id, VoiceCapability.TTS)),
                    testLabel = stringResource(R.string.voice_catalog_try_tts),
                    tag = "voice-cap-tts-${model.id}",
                    onTest = { onTest(VoiceModelRef(model.serviceId, model.id, VoiceCapability.TTS), TtsRequest(ttsSampleText)) },
                )
            }
            model.realtime?.let { binding ->
                CapabilityBadge(
                    ref = VoiceModelRef(model.serviceId, model.id, VoiceCapability.REALTIME),
                    label = stringResource(R.string.voice_catalog_cap_realtime),
                    protocol = realtimeProtocolLabel(binding.protocol),
                    ui = probeState(VoiceModelRef(model.serviceId, model.id, VoiceCapability.REALTIME)),
                    verified = snapshot.verified(VoiceModelRef(model.serviceId, model.id, VoiceCapability.REALTIME)),
                    testLabel = stringResource(R.string.voice_catalog_test_realtime),
                    tag = "voice-cap-rt-${model.id}",
                    onTest = { onTest(VoiceModelRef(model.serviceId, model.id, VoiceCapability.REALTIME), null) },
                )
            }
        }

        val allVerified = model.capabilities.isNotEmpty() && model.capabilities.all { capability ->
            snapshot.verified(VoiceModelRef(model.serviceId, model.id, capability))
        }
        Spacer(Modifier.height(6.dp))
        StatusPill(
            stringResource(if (allVerified) R.string.voice_catalog_verified else R.string.voice_catalog_unverified),
            if (allVerified) PillTone.Ok else PillTone.Warn,
            Modifier.testTag("voice-status-${model.id}"),
        )
    }
}

@Composable
private fun CapabilityBadge(
    ref: VoiceModelRef,
    label: String,
    protocol: String,
    ui: VoiceProbeUi,
    verified: Boolean,
    testLabel: String,
    tag: String,
    onTest: () -> Unit,
) {
    val c = MikaTheme.colors
    Column(Modifier.testTag(tag)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MikaType.Hint.copy(fontWeight = FontWeight.SemiBold), color = c.ink2)
            Text(protocol, style = MikaType.Hint, color = c.ink3)
            if (ui !is VoiceProbeUi.Running) {
                TextButton(onClick = onTest) { Text(testLabel, style = MikaType.Hint) }
            }
        }
        when (ui) {
            VoiceProbeUi.Idle -> Unit
            VoiceProbeUi.Running -> StatusPill(stringResource(R.string.voice_catalog_testing), PillTone.Warn)
            is VoiceProbeUi.Listening -> Text(
                ui.transcript.ifBlank { stringResource(R.string.voice_catalog_listening) },
                style = MikaType.Hint, color = c.ink2,
            )
            is VoiceProbeUi.Failed -> Text(ui.error.detail, style = MikaType.Hint, color = c.bad)
            VoiceProbeUi.Verified -> StatusPill(stringResource(R.string.voice_catalog_verified), PillTone.Ok)
        }
        if (ui == VoiceProbeUi.Idle && verified) {
            StatusPill(stringResource(R.string.voice_catalog_verified), PillTone.Ok)
        }
    }
}

private fun VoiceCatalogSnapshot.verified(ref: VoiceModelRef): Boolean =
    verifications.any { it.ref == ref && it.scope.isEmpty() && it.status == VoiceVerification.Verified }

@Composable
private fun accountLabel(account: VoiceAccount): String = when (account) {
    is VoiceAccount.ExistingProvider -> account.providerId
    is VoiceAccount.VoiceOwned -> account.secretId
    VoiceAccount.None -> stringResource(R.string.voice_catalog_account_none)
}

// ---------- add / edit sheet ----------

private enum class AccountSource { Existing, Owned, None }

private class CatalogForm {
    var error by mutableStateOf(false)
    var accountSource by mutableStateOf(AccountSource.None)
    var serviceName by mutableStateOf("")
    var providerId by mutableStateOf("")
    var secretId by mutableStateOf("")
    var apiKey by mutableStateOf("")
    var appId by mutableStateOf("")
    var accessKey by mutableStateOf("")
    var modelId by mutableStateOf("")
    var displayName by mutableStateOf("")
    var sttOn by mutableStateOf(false)
    var sttProtocol by mutableStateOf(SttProtocol.AudioTranscriptions)
    var sttEndpoint by mutableStateOf("")
    var ttsOn by mutableStateOf(false)
    var ttsProtocol by mutableStateOf(TtsProtocol.OpenAiSpeech)
    var ttsEndpoint by mutableStateOf("")
    var realtimeOn by mutableStateOf(false)
    var realtimeProtocol by mutableStateOf(RealtimeProtocol.VolcDuplex)
    var realtimeEndpoint by mutableStateOf("")
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun VoiceCatalogSheet(
    viewModel: VoiceCatalogViewModel,
    editing: VoiceModel?,
    onDismiss: () -> Unit,
) {
    val snapshot by viewModel.snapshot.collectAsState()
    val existingService = editing?.let { model ->
        snapshot.services.firstOrNull { it.id == model.serviceId }
    }
    val form = remember(editing) {
        CatalogForm().apply {
            existingService?.let { service ->
                accountSource = when (service.account) {
                    is VoiceAccount.ExistingProvider -> AccountSource.Existing
                    is VoiceAccount.VoiceOwned -> AccountSource.Owned
                    VoiceAccount.None -> AccountSource.None
                }
                serviceName = service.name
                providerId = (service.account as? VoiceAccount.ExistingProvider)?.providerId.orEmpty()
                secretId = (service.account as? VoiceAccount.VoiceOwned)?.secretId.orEmpty()
            }
            editing?.let { model ->
                modelId = model.id
                displayName = model.displayName
                sttOn = model.stt != null
                sttProtocol = model.stt?.protocol ?: SttProtocol.AudioTranscriptions
                sttEndpoint = model.stt?.endpoint.orEmpty()
                ttsOn = model.tts != null
                ttsProtocol = model.tts?.protocol ?: TtsProtocol.OpenAiSpeech
                ttsEndpoint = model.tts?.endpoint.orEmpty()
                realtimeOn = model.realtime != null
                realtimeProtocol = model.realtime?.protocol ?: RealtimeProtocol.VolcDuplex
                realtimeEndpoint = model.realtime?.endpoint.orEmpty()
            }
        }
    }
    val c = MikaTheme.colors
    val ttsSampleText = stringResource(R.string.voice_catalog_tts_sample)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                .testTag("voice-catalog-sheet"),
        ) {
            Text(
                stringResource(if (editing == null) R.string.voice_catalog_add else R.string.voice_catalog_edit),
                style = MikaType.Title, color = c.ink,
            )

            if (editing == null) {
                GroupLabel(stringResource(R.string.voice_catalog_account))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectChip(
                        stringResource(R.string.voice_catalog_account_existing), form.accountSource == AccountSource.Existing,
                        { form.accountSource = AccountSource.Existing }, Modifier.testTag("voice-catalog-account-existing"),
                    )
                    SelectChip(
                        stringResource(R.string.voice_catalog_account_owned), form.accountSource == AccountSource.Owned,
                        { form.accountSource = AccountSource.Owned }, Modifier.testTag("voice-catalog-account-owned"),
                    )
                    SelectChip(
                        stringResource(R.string.voice_catalog_account_none), form.accountSource == AccountSource.None,
                        { form.accountSource = AccountSource.None }, Modifier.testTag("voice-catalog-account-none"),
                    )
                }
                when (form.accountSource) {
                    AccountSource.Existing -> SheetField(stringResource(R.string.voice_catalog_provider_id), form.providerId, { form.providerId = it }, tag = "voice-catalog-provider-id")
                    AccountSource.Owned -> {
                        SheetField(stringResource(R.string.voice_catalog_account_owned), form.secretId, { form.secretId = it }, tag = "voice-catalog-owned-name")
                        SheetField(stringResource(R.string.voice_catalog_api_key), form.apiKey, { form.apiKey = it }, tag = "voice-catalog-api-key", secret = true)
                        SheetField(stringResource(R.string.voice_catalog_app_id), form.appId, { form.appId = it }, tag = "voice-catalog-app-id")
                        SheetField(stringResource(R.string.voice_catalog_access_key), form.accessKey, { form.accessKey = it }, tag = "voice-catalog-access-key", secret = true)
                    }
                    AccountSource.None -> SheetField(stringResource(R.string.voice_catalog_service_name), form.serviceName, { form.serviceName = it }, tag = "voice-catalog-service-name")
                }
            } else {
                Text(existingService?.name.orEmpty(), style = MikaType.Body, color = c.ink2)
            }

            if (editing == null) {
                SheetField(stringResource(R.string.voice_catalog_model_id), form.modelId, { form.modelId = it }, tag = "voice-catalog-model-id")
            }
            SheetField(stringResource(R.string.voice_catalog_display_name), form.displayName, { form.displayName = it }, tag = "voice-catalog-display-name")

            GroupLabel(stringResource(R.string.voice_catalog_capabilities))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MikaSwitch(form.sttOn, { form.sttOn = it }, Modifier.testTag("voice-catalog-cap-stt"))
                Text(stringResource(R.string.voice_catalog_cap_stt), style = MikaType.Body, color = c.ink)
            }
            if (form.sttOn) {
                ProtocolBlock(
                    stringResource(R.string.voice_catalog_stt_protocol),
                    SttProtocol.entries.map { it to sttProtocolLabel(it) },
                    form.sttProtocol.name,
                    { name -> form.sttProtocol = SttProtocol.valueOf(name) },
                    "voice-catalog-stt-protocol",
                )
                if (form.sttProtocol != SttProtocol.AndroidSystem) {
                    SheetField(stringResource(R.string.voice_catalog_endpoint), form.sttEndpoint, { form.sttEndpoint = it }, tag = "voice-catalog-stt-endpoint")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MikaSwitch(form.ttsOn, { form.ttsOn = it }, Modifier.testTag("voice-catalog-cap-tts"))
                Text(stringResource(R.string.voice_catalog_cap_tts), style = MikaType.Body, color = c.ink)
            }
            if (form.ttsOn) {
                ProtocolBlock(
                    stringResource(R.string.voice_catalog_tts_protocol),
                    TtsProtocol.entries.map { it to ttsProtocolLabel(it) },
                    form.ttsProtocol.name,
                    { name -> form.ttsProtocol = TtsProtocol.valueOf(name) },
                    "voice-catalog-tts-protocol",
                )
                if (form.ttsProtocol != TtsProtocol.AndroidSystem) {
                    SheetField(stringResource(R.string.voice_catalog_endpoint), form.ttsEndpoint, { form.ttsEndpoint = it }, tag = "voice-catalog-tts-endpoint")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MikaSwitch(form.realtimeOn, { form.realtimeOn = it }, Modifier.testTag("voice-catalog-cap-rt"))
                Text(stringResource(R.string.voice_catalog_cap_realtime), style = MikaType.Body, color = c.ink)
            }
            if (form.realtimeOn) {
                ProtocolBlock(
                    stringResource(R.string.voice_catalog_realtime_protocol),
                    RealtimeProtocol.entries.map { it to realtimeProtocolLabel(it) },
                    form.realtimeProtocol.name,
                    { name -> form.realtimeProtocol = RealtimeProtocol.valueOf(name) },
                    "voice-catalog-rt-protocol",
                )
                SheetField(stringResource(R.string.voice_catalog_endpoint), form.realtimeEndpoint, { form.realtimeEndpoint = it }, tag = "voice-catalog-rt-endpoint")
            }

            Spacer(Modifier.height(20.dp))
            if (form.error) {
                Text(
                    stringResource(R.string.voice_catalog_incomplete),
                    style = MikaType.Hint, color = c.bad,
                    modifier = Modifier.testTag("voice-catalog-incomplete"),
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MikaButton(
                    stringResource(R.string.cancel), onDismiss, Modifier.weight(1f),
                    kind = ButtonKind.Secondary, size = com.wangzan101.mika.ui.components.ButtonSize.Large,
                )
                MikaButton(
                    stringResource(R.string.voice_catalog_save_unverified),
                    { if (save(form, editing, existingService, viewModel, test = false, sampleText = ttsSampleText)) onDismiss() },
                    Modifier.weight(1f).testTag("voice-catalog-save-unverified"),
                )
            }
            Spacer(Modifier.height(8.dp))
            MikaButton(
                stringResource(R.string.voice_catalog_save_test),
                { if (save(form, editing, existingService, viewModel, test = true, sampleText = ttsSampleText)) onDismiss() },
                Modifier.fillMaxWidth().testTag("voice-catalog-save-test"),
                kind = ButtonKind.Tonal,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProtocolBlock(
    title: String,
    options: List<Pair<Enum<*>, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    tag: String,
) {
    GroupLabel(title)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (option, label) ->
            SelectChip(
                label,
                option.name == selected,
                { onSelect(option.name) },
                Modifier.testTag("$tag-${option.name}"),
                small = true,
            )
        }
    }
}

/*
 * 表单只露出协议和接口地址。编辑时协议没换,就在原绑定上只改地址,表单之外的参数
 * (计费方案、地域、工作空间、识别语言、音色)原样保留;换了协议,旧协议的参数不再适用,从头建。
 */
internal fun editedSttBinding(saved: SttBinding?, protocol: SttProtocol, endpoint: String): SttBinding =
    if (saved?.protocol == protocol) saved.copy(endpoint = endpoint) else SttBinding(protocol, endpoint = endpoint)

internal fun editedTtsBinding(saved: TtsBinding?, protocol: TtsProtocol, endpoint: String): TtsBinding =
    if (saved?.protocol == protocol) saved.copy(endpoint = endpoint) else TtsBinding(protocol, endpoint = endpoint)

internal fun editedRealtimeBinding(saved: RealtimeBinding?, protocol: RealtimeProtocol, endpoint: String): RealtimeBinding =
    if (saved?.protocol == protocol) saved.copy(endpoint = endpoint) else RealtimeBinding(protocol, endpoint = endpoint)

private fun save(
    form: CatalogForm,
    editing: VoiceModel?,
    existingService: VoiceService?,
    viewModel: VoiceCatalogViewModel,
    test: Boolean,
    sampleText: String,
): Boolean {
    form.error = false
    if (editing == null) {
        val accountBlank = when (form.accountSource) {
            AccountSource.Existing -> form.providerId.isBlank()
            AccountSource.Owned -> form.secretId.isBlank()
            AccountSource.None -> form.serviceName.isBlank()
        }
        if (accountBlank) {
            form.error = true
            return false
        }
    }
    val service = existingService ?: when (form.accountSource) {
        AccountSource.Existing -> VoiceService(
            "provider:${form.providerId.trim()}", form.providerId.trim(), VoiceAccount.ExistingProvider(form.providerId.trim()),
        )
        AccountSource.Owned -> VoiceService(
            "owned:${form.secretId.trim()}", form.secretId.trim(), VoiceAccount.VoiceOwned(form.secretId.trim()),
        )
        AccountSource.None -> VoiceService(
            "local:${form.serviceName.trim()}", form.serviceName.trim(), VoiceAccount.None,
        )
    }
    val credentials = if (form.accountSource == AccountSource.Owned && editing == null) {
        VoiceCredentials(apiKey = form.apiKey, appId = form.appId, accessKey = form.accessKey)
            .takeIf { it.configured }
    } else null
    val model = VoiceModel(
        serviceId = service.id,
        id = if (editing == null) form.modelId.trim() else editing.id,
        displayName = form.displayName.trim().ifBlank { if (editing == null) form.modelId.trim() else editing.displayName },
        stt = if (form.sttOn) editedSttBinding(editing?.stt, form.sttProtocol, form.sttEndpoint.trim()) else null,
        tts = if (form.ttsOn) editedTtsBinding(editing?.tts, form.ttsProtocol, form.ttsEndpoint.trim()) else null,
        realtime = if (form.realtimeOn) {
            editedRealtimeBinding(editing?.realtime, form.realtimeProtocol, form.realtimeEndpoint.trim())
        } else null,
    )
    if (model.displayName.isBlank() || model.id.isBlank()) {
        form.error = true
        return false
    }
    viewModel.saveService(service, credentials)
    viewModel.saveUnverified(model)
    if (test) {
        model.capabilities.forEach { capability ->
            val ref = VoiceModelRef(service.id, model.id, capability)
            viewModel.test(ref, if (capability == VoiceCapability.TTS) TtsRequest(sampleText) else null)
        }
    }
    return true
}

@Composable
private fun SheetField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    tag: String,
    secret: Boolean = false,
) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(label, style = MikaType.Hint, color = c.ink3)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag(tag),
            singleLine = true,
            textStyle = MikaType.Body.copy(color = c.ink),
            cursorBrush = SolidColor(c.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { inner ->
                if (value.isEmpty()) Text(stringResource(R.string.voice_catalog_model_id_hint), style = MikaType.Body, color = c.ink3)
                inner()
            },
        )
    }
}

// ---------- protocol display labels ----------

@Composable
private fun sttProtocolLabel(protocol: SttProtocol): String = vendorProtocolName(protocol.name) ?: stringResource(
    when (protocol) {
        SttProtocol.AndroidSystem -> R.string.voice_catalog_protocol_stt_android_system
        SttProtocol.AudioTranscriptions -> R.string.voice_catalog_protocol_stt_audio_transcriptions
        SttProtocol.ChatInputAudio -> R.string.voice_catalog_protocol_stt_chat_input_audio
        SttProtocol.GeminiInline -> R.string.voice_catalog_protocol_stt_gemini_inline
        SttProtocol.DoubaoStreaming -> R.string.voice_catalog_protocol_stt_doubao_streaming
        SttProtocol.FunAsrStreaming -> R.string.voice_catalog_protocol_stt_funasr_streaming
        SttProtocol.BailianStreaming -> R.string.voice_catalog_protocol_stt_bailian_streaming
        else -> R.string.voice_catalog_protocol_stt_audio_transcriptions
    },
)

@Composable
private fun ttsProtocolLabel(protocol: TtsProtocol): String = vendorProtocolName(protocol.name) ?: stringResource(
    when (protocol) {
        TtsProtocol.AndroidSystem -> R.string.voice_catalog_protocol_tts_android_system
        TtsProtocol.OpenAiSpeech -> R.string.voice_catalog_protocol_tts_openai_speech
        TtsProtocol.GeminiTts -> R.string.voice_catalog_protocol_tts_gemini_tts
        else -> R.string.voice_catalog_protocol_tts_openai_speech
    },
)

/** Vendor-specific protocols are labelled by the vendor's own (untranslated) product name. */
private fun vendorProtocolName(name: String): String? = when (name) {
    "ElevenLabsUpload", "ElevenLabsSpeech" -> "ElevenLabs"
    "DeepgramUpload", "DeepgramSpeak" -> "Deepgram"
    "AzureShortAudio", "AzureSpeech" -> "Azure Speech"
    "TencentSentence", "TencentVoice" -> "Tencent Cloud"
    "XfyunDictation", "XfyunSynthesis" -> "iFlytek"
    "VolcSpeech" -> "Volcano Engine TTS"
    "BailianQwenTts" -> "Qwen TTS"
    "MiniMaxT2a" -> "MiniMax T2A"
    else -> null
}

@Composable
private fun realtimeProtocolLabel(protocol: RealtimeProtocol): String = stringResource(
    when (protocol) {
        RealtimeProtocol.VolcDuplex -> R.string.voice_catalog_protocol_realtime_volc_duplex
        RealtimeProtocol.OpenAiRealtime -> R.string.voice_catalog_protocol_realtime_openai_realtime
    },
)
