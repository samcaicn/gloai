package com.wangzan101.mika.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaType
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.components.CapabilityTag
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SpeechOptions
import com.wangzan101.mika.voice.SystemSpeech
import com.wangzan101.mika.voice.DoubaoCredentials
import com.wangzan101.mika.voice.DoubaoProtocol
import com.wangzan101.mika.voice.BailianProtocol
import com.wangzan101.mika.voice.BailianRegion
import com.wangzan101.mika.voice.FunAsrProtocol
import com.wangzan101.mika.voice.LiveAsrTransport
import com.wangzan101.mika.voice.probeLiveAsr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 选择页上的动作。 */
class SpeechPickActions(
    /** 「手机自带」或「不用」:当场生效并返回。 */
    val pick: (SpeechChoice) -> Unit = {},
    /** (服务商 id, 模型名):先试转,通了才选上并返回;列表里点的和手填的都走这里。 */
    val pickModel: (String, String) -> Unit = { _, _ -> },
    /** 进豆包流式识别的设置页。 */
    val openDoubao: () -> Unit = {},
    /** (API Key, App ID, Access Token, 计费方式):加密存下凭据并选用;不联网。 */
    val saveDoubao: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    /** 沿用已存的凭据,换成这种计费方式并选用。 */
    val useDoubao: (String) -> Unit = {},
    val clearDoubao: () -> Unit = {},
    /** 进自建 Fun-ASR、百炼的设置页。 */
    /** 进「千问实时语音识别」的设置页(自建、百炼北京、百炼新加坡三个分页)。 */
    val openFunAsr: () -> Unit = {},
    /** 沿用上次那种(自建的地址,或百炼某个地域的模型),当场选用。 */
    val useFunAsr: () -> Unit = {},
)

/**
 * 设置 › 模型 › 语音转文字:自己带一个模型状态,可以从别处(例如输入框的「还不能语音输入」)直接进入。
 * 选好(手机自带、不用、试转通过的服务商模型)后回到进来的那一页。
 */
@Composable
fun SpeechModelScreen(store: ProviderStore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings = (context.applicationContext as? MikaApp)?.speech
    var doubaoConfigured by remember { mutableStateOf(settings?.hasDoubaoCredentials == true) }
    // 豆包的设置页叠在这一页上面;返回键先关它
    var doubaoOpen by remember { mutableStateOf(false) }
    // 「千问实时语音识别」三个分页的已存内容;存、删之后重读
    fun funAsrSaved() = settings?.let { s ->
        FunAsrSaved(
            s.funAsrUrl, BailianRegion.entries.filter(s::hasBailianKey).toSet(),
            BailianRegion.entries.associateWith(s::bailianModel), BailianRegion.entries.associateWith(s::bailianWorkspace),
            s.funAsrLanguage, BailianRegion.entries.associateWith(s::bailianLanguage),
        )
    } ?: FunAsrSaved()
    var funAsrSaved by remember { mutableStateOf(funAsrSaved()) }
    var funAsrLast by remember { mutableStateOf(settings?.lastLive) }
    var funAsrOpen by remember { mutableStateOf(false) }
    var liveProbe by remember { mutableStateOf(LiveProbeUi()) }
    // 先连一次走一遍开场和收尾,通了才存;失败留在设置页上说原因
    fun probeThen(protocol: com.wangzan101.mika.voice.LiveAsrProtocol, url: String, headers: Map<String, String>, save: () -> Unit) {
        if (liveProbe.testing) return
        liveProbe = LiveProbeUi(testing = true)
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { probeLiveAsr(protocol, { LiveAsrTransport().connect(url, headers) }) }.exceptionOrNull()
            }
            liveProbe = if (error == null) {
                runCatching(save).exceptionOrNull()?.let { LiveProbeUi(error = tr(R.string.speech_doubao_save_failed)) } ?: LiveProbeUi()
            } else LiveProbeUi(error = SpeechOptions.probeFailure(error))
        }
    }
    val vm = remember {
        ModelSetupViewModel(
            store, scope,
            speech = settings,
            systemSpeech = {
                val silent = settings?.systemSilent?.value ?: false
                SystemSpeech.capability(context.applicationContext, silent)
            },
        )
    }
    val ui by vm.state.collectAsState()
    val probe by vm.speechProbe.collectAsState()
    LaunchedEffect(Unit) { vm.ensureListed(); vm.clearSpeechProbe() }
    val actions = SpeechPickActions(
        pick = { vm.pickSpeech(it); onBack() },
        pickModel = { pid, model -> vm.pickSpeechModel(pid, model, onPicked = onBack) },
        openDoubao = { doubaoOpen = true },
        // 存好后回到语音转文字页,看得到它已选上
        saveDoubao = { key, appId, token, resource ->
            checkNotNull(settings).saveDoubao(DoubaoCredentials(key, appId, token), resource)
            doubaoConfigured = true
            vm.pickSpeech(SpeechChoice.Doubao(resource))
            doubaoOpen = false
        },
        useDoubao = { resource -> vm.pickSpeech(SpeechChoice.Doubao(resource)); doubaoOpen = false },
        clearDoubao = {
            settings?.clearDoubao()
            doubaoConfigured = false
            settings?.let { vm.pickSpeech(it.choice.value) }
            doubaoOpen = false
        },
        openFunAsr = { liveProbe = LiveProbeUi(); funAsrOpen = true },
        // 沿用上次那种(自建或百炼的某个地域);用不了时进设置页
        useFunAsr = {
            when (val last = settings?.lastLive) {
                is SpeechChoice.FunAsr -> { settings.saveFunAsr(last.url, last.language); vm.pickSpeech(settings.choice.value) }
                is SpeechChoice.Bailian -> { settings.saveBailian("", last); vm.pickSpeech(settings.choice.value) }
                else -> { liveProbe = LiveProbeUi(); funAsrOpen = true }
            }
        },
    )
    fun funAsrDone() {
        funAsrSaved = funAsrSaved(); funAsrLast = settings?.lastLive
        settings?.let { vm.pickSpeech(it.choice.value) }
    }
    when {
        doubaoOpen -> {
            BackHandler { doubaoOpen = false }
            DoubaoSetupPage(ui.speech, doubaoConfigured, actions) { doubaoOpen = false }
        }
        funAsrOpen -> {
            BackHandler { funAsrOpen = false }
            FunAsrSetupPage(
                FunAsrTab.of(ui.speech.takeIf { it is SpeechChoice.FunAsr || it is SpeechChoice.Bailian } ?: funAsrLast), funAsrSaved, liveProbe,
                onTab = { if (!liveProbe.testing) liveProbe = LiveProbeUi() },
                onSaveLocal = { url, language ->
                    probeThen(FunAsrProtocol(language), url, emptyMap()) {
                        checkNotNull(settings).saveFunAsr(url, language)
                        funAsrDone(); funAsrOpen = false
                    }
                },
                onSaveBailian = { input ->
                    val usable = input.key.ifBlank { settings?.bailianKey(input.region).orEmpty() }
                    if (usable.isBlank()) liveProbe = LiveProbeUi(error = tr(R.string.speech_live_auth))
                    else probeThen(BailianProtocol(input.model, input.language), input.region.endpoint(input.workspace), mapOf("Authorization" to "Bearer $usable")) {
                        checkNotNull(settings).saveBailian(input.key, SpeechChoice.Bailian(input.model, input.region, input.workspace, input.language))
                        funAsrDone(); funAsrOpen = false
                    }
                },
                onClearBailian = { region -> settings?.clearBailian(region); funAsrDone() },
            ) { funAsrOpen = false }
        }
        else -> SpeechModelContent(ui, probe, actions, onBack, doubaoConfigured, funAsrLast != null)
    }
}

/** 不带状态的选择页,便于测试:顶上一段说明,「手机自带」「不用」,下面按服务商分组单选。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpeechModelContent(
    ui: ModelSetupUi, probe: Map<String, String>, actions: SpeechPickActions, onBack: () -> Unit, doubaoConfigured: Boolean = false,
    funAsrConfigured: Boolean = false,
) {
    val c = MikaTheme.colors
    val system = ui.systemSpeech
    val choice = ui.speech
    // 没选过时有手机自带就是在用它,所以「没选过」也把它标成选中
    // 记成不响应时不标选中;行仍可点,重新选它就清掉记号再试
    val systemSelected = system.usable && (choice == SpeechChoice.System || choice == SpeechChoice.Auto)
    Page(tr(R.string.role_speech), onBack) {
        Text(
            tr(R.string.speech_hint), Modifier.padding(top = 4.dp, bottom = 12.dp), style = MikaType.Body, color = c.ink2,
        )
        GroupCard {
            RadioRow(
                tr(R.string.speech_system), selected = systemSelected, enabled = system.available,
                onClick = { actions.pick(SpeechChoice.System) }, first = true,
                subtitle = tr(
                    when {
                        !system.available -> R.string.speech_system_none_note
                        !system.responsive -> R.string.speech_system_silent_note
                        system.onDevice -> R.string.speech_system_note
                        else -> R.string.speech_system_note_online
                    },
                ),
                trailing = {
                    when {
                        system.usable -> StatusPill(tr(R.string.speech_tag_ok), PillTone.Ok)
                        system.available -> StatusPill(tr(R.string.speech_tag_silent), PillTone.Neutral)
                        else -> StatusPill(tr(R.string.speech_tag_none), PillTone.Neutral)
                    }
                },
            )
            RadioRow(
                tr(R.string.models_off), selected = choice == SpeechChoice.Off, onClick = { actions.pick(SpeechChoice.Off) },
                subtitle = tr(R.string.speech_off_note),
            )
        }
        LiveRows(choice, doubaoConfigured, funAsrConfigured, actions)
        SpeechOptions.groups(ui.providers).forEach { g ->
            val p = g.provider
            val testing = ui.speechTesting == p.id
            GroupLabel(ModelsCopy.providerName(p))
            // 手填选上的模型不在列表里,也补一行标成选中,让人看得出现在用的是哪个
            val picked = (choice as? SpeechChoice.Provider)?.takeIf { it.providerId == p.id }?.model
            val rows = if (picked != null && picked !in g.models) g.models + picked else g.models
            GroupCard {
                rows.forEachIndexed { i, id ->
                    RadioRow(
                        id, selected = id == picked, onClick = { actions.pickModel(p.id, id) }, first = i == 0,
                        titleStyle = ModelIdStyle, enabled = ui.speechTesting == null,
                        trailing = { CapabilityTag(tr(R.string.speech_tag_speech)) },
                    )
                }
                // 列表里没有的(列表不全、或这家不给列表)手填
                Box(Modifier.testTag("speech-manual-${p.name}")) {
                    ManualModel(p, if (testing) ListState.Running else null, first = rows.isEmpty()) { actions.pickModel(p.id, it) }
                }
            }
            when {
                probe[p.id] != null -> {
                    // 列表长时原因落在屏幕外,点了像没反应:出来时滚到看得见
                    val why = probe.getValue(p.id)
                    val into = remember { BringIntoViewRequester() }
                    LaunchedEffect(why) { into.bringIntoView() }
                    FooterNote(why, Modifier.bringIntoViewRequester(into).testTag("speech-pick-failed"), color = c.bad)
                }
                testing && rows.isNotEmpty() -> FooterNote(ModelSetupCopy.TESTING, Modifier.testTag("speech-pick-running"))
            }
        }
        FooterNote(tr(R.string.speech_footer), Modifier.padding(top = 8.dp))
    }
}

/**
 * 「边说边转的语音服务」一组:豆包和「千问实时语音识别」(自建服务、百炼北京、百炼新加坡三选一,在它的设置页里分页)各一行。
 * 没设置过时点行进设置页;设置过以后点圆圈就用它(Fun-ASR 沿用上次那种),右边「设置」进去改或删掉。
 * 凭据和聊天服务商分开存,不列模型、不试转。
 */
@Composable
private fun LiveRows(choice: SpeechChoice, doubao: Boolean, funAsr: Boolean, actions: SpeechPickActions) {
    GroupLabel(tr(R.string.speech_doubao_group))
    GroupCard {
        val plan = (choice as? SpeechChoice.Doubao)?.resourceId ?: DoubaoProtocol.DURATION
        val doubaoSelected = doubao && choice is SpeechChoice.Doubao
        LiveServiceRow(
            tr(R.string.speech_doubao_model),
            if (doubaoSelected) tr(R.string.speech_doubao_configured, tr(planName(plan))) else tr(R.string.speech_doubao_note),
            selected = doubaoSelected, configured = doubao, first = true, tag = "speech-doubao-edit",
            onPick = { actions.pick(SpeechChoice.Doubao(plan)) }, onOpen = actions.openDoubao,
        )
        val using = when (choice) {
            is SpeechChoice.FunAsr -> funAsrTabName(FunAsrTab.Local) + " · " + FunAsrProtocol.hostOf(choice.url)
            is SpeechChoice.Bailian -> funAsrTabName(FunAsrTab.of(choice)) + " · " + choice.model
            else -> null
        }
        LiveServiceRow(
            tr(R.string.speech_funasr_service), using ?: tr(R.string.speech_funasr_choose_note),
            selected = using != null, configured = funAsr, first = false, tag = "speech-funasr-edit",
            onPick = actions.useFunAsr, onOpen = actions.openFunAsr,
        )
    }
}

private fun planName(resource: String): Int =
    if (resource == DoubaoProtocol.CONCURRENT) R.string.speech_doubao_concurrent else R.string.speech_doubao_duration

/**
 * 豆包流式识别的设置页:填凭据(新版控制台的 API Key,或旧版的 App ID + Access Token)、选计费方式。
 * 保存不联网;已存的凭据不回填,输入框也不进可恢复的状态。只改计费方式、不重填凭据时沿用已存的凭据。
 */
@Composable
fun DoubaoSetupPage(choice: SpeechChoice, configured: Boolean, actions: SpeechPickActions, onBack: () -> Unit) {
    val c = MikaTheme.colors
    var legacy by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var resource by remember { mutableStateOf((choice as? SpeechChoice.Doubao)?.resourceId ?: DoubaoProtocol.DURATION) }
    var failed by remember { mutableStateOf(false) }
    val credentials = DoubaoCredentials(if (legacy) "" else key.trim(), if (legacy) appId.trim() else "", if (legacy) token.trim() else "")
    val unchanged = key.isBlank() && appId.isBlank() && token.isBlank()
    Page(tr(R.string.speech_doubao_model), onBack) {
        Text(tr(R.string.speech_doubao_setup_hint), Modifier.padding(top = 4.dp, bottom = 4.dp), style = MikaType.Body, color = c.ink2)
        GroupLabel(tr(R.string.speech_doubao_credentials))
        SegmentedControl(
            listOf(tr(R.string.speech_doubao_new), tr(R.string.speech_doubao_legacy)), if (legacy) 1 else 0,
            onSelect = { legacy = it == 1; key = ""; appId = ""; token = "" },
            Modifier.padding(bottom = 8.dp),
        )
        GroupCard {
            if (legacy) {
                InputRow("App ID", appId, { appId = it }, tr(R.string.speech_doubao_enter_app), first = true, labelWidth = 110.dp)
                InputRow("Access Token", token, { token = it }, tr(R.string.speech_doubao_enter_key), secret = true, labelWidth = 110.dp)
            } else {
                InputRow("API Key", key, { key = it }, tr(R.string.speech_doubao_enter_key), first = true, secret = true, labelWidth = 110.dp)
            }
        }
        GroupLabel(tr(R.string.speech_doubao_plan))
        GroupCard {
            RadioRow(
                tr(R.string.speech_doubao_duration), selected = resource == DoubaoProtocol.DURATION, first = true,
                onClick = { resource = DoubaoProtocol.DURATION }, subtitle = tr(R.string.speech_doubao_duration_note),
            )
            RadioRow(
                tr(R.string.speech_doubao_concurrent), selected = resource == DoubaoProtocol.CONCURRENT,
                onClick = { resource = DoubaoProtocol.CONCURRENT }, subtitle = tr(R.string.speech_doubao_concurrent_note),
            )
        }
        FooterNote(tr(R.string.speech_doubao_privacy), Modifier.padding(top = 8.dp))
        MikaButton(
            tr(R.string.speech_doubao_save),
            onClick = {
                failed = runCatching {
                    if (configured && unchanged) actions.useDoubao(resource)
                    else actions.saveDoubao(credentials.apiKey, credentials.appId, credentials.accessKey, resource)
                }.isFailure
                key = ""; appId = ""; token = ""
            },
            Modifier.fillMaxWidth().padding(top = 8.dp).testTag("speech-doubao-save"),
            enabled = credentials.valid || configured && unchanged,
        )
        if (failed) FooterNote(tr(R.string.speech_doubao_save_failed), Modifier.padding(top = 8.dp), color = c.bad)
        if (configured) {
            Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                Text(
                    tr(R.string.speech_doubao_remove),
                    Modifier.clip(MikaShapes.Pill).clickable(onClick = actions.clearDoubao).padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("speech-doubao-remove"),
                    style = MikaType.Body, color = c.bad,
                )
            }
        }
    }
}
