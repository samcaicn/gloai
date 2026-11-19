package com.wangzan101.mika.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.components.SelectChip
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.AsrLanguage
import com.wangzan101.mika.voice.BailianProtocol
import com.wangzan101.mika.voice.BailianRegion
import com.wangzan101.mika.voice.FunAsrProtocol
import com.wangzan101.mika.voice.SpeechChoice

/** 设置页试连的状态:[testing] 时按钮转圈不可点,[error] 是没通的原因。 */
data class LiveProbeUi(val testing: Boolean = false, val error: String? = null)

/**
 * 「边说边转的语音服务」里的一行:没设置过时点它进设置页;设置过以后点圆圈就用它,右边「设置」进去改。
 * 豆包、自建 Fun-ASR、百炼都长这样。
 */
@Composable
internal fun LiveServiceRow(
    title: String, subtitle: String, selected: Boolean, configured: Boolean, first: Boolean, tag: String,
    onPick: () -> Unit, onOpen: () -> Unit,
) {
    val c = MikaTheme.colors
    RadioRow(
        title, selected = selected, first = first,
        onClick = { if (configured) onPick() else onOpen() },
        subtitle = subtitle,
        trailing = {
            if (configured) {
                Row(
                    Modifier.clip(MikaShapes.Pill).clickable(onClick = onOpen).padding(start = 8.dp, top = 6.dp, bottom = 6.dp).testTag(tag),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr(R.string.speech_doubao_edit), style = MikaType.Value, color = c.ink2)
                    RowChevron()
                }
            } else {
                StatusPill(tr(R.string.settings_not_set), PillTone.Neutral)
                RowChevron()
            }
        },
    )
}

/** 「千问实时语音识别」设置页上的三个分页:自建服务,百炼北京,百炼新加坡。 */
enum class FunAsrTab { Local, Beijing, Singapore;
    val region: BailianRegion? get() = when (this) { Local -> null; Beijing -> BailianRegion.Beijing; Singapore -> BailianRegion.Singapore }

    companion object {
        /** 打开时先停在正在用(或上次用)的那种。 */
        fun of(choice: SpeechChoice?): FunAsrTab = when (choice) {
            is SpeechChoice.Bailian -> if (choice.region == BailianRegion.Singapore) Singapore else Beijing
            else -> Local
        }
    }
}

/** 分页标签和行上的说法:「自建」「百炼·北京」「百炼·新加坡」。 */
internal fun funAsrTabName(tab: FunAsrTab): String = when (tab) {
    FunAsrTab.Local -> tr(R.string.speech_funasr_tab_local)
    FunAsrTab.Beijing -> tr(R.string.speech_bailian_tab, tr(R.string.speech_bailian_beijing))
    FunAsrTab.Singapore -> tr(R.string.speech_bailian_tab, tr(R.string.speech_bailian_singapore))
}

/** 设置页每个分页的已存内容:自建服务的地址,各地域有没有存 Key、上次的模型和业务空间 ID。 */
data class FunAsrSaved(
    val url: String? = null,
    val keys: Set<BailianRegion> = emptySet(),
    val models: Map<BailianRegion, String> = emptyMap(),
    val workspaces: Map<BailianRegion, String> = emptyMap(),
    val localLanguage: AsrLanguage = AsrLanguage.Auto,
    val languages: Map<BailianRegion, AsrLanguage> = emptyMap(),
)

/** 百炼分页「连接并使用」交上来的:Key 为空表示沿用已存的;[workspace] 已规范,空串用通用接入点。 */
data class BailianInput(val key: String, val model: String, val region: BailianRegion, val workspace: String, val language: AsrLanguage = AsrLanguage.Auto)

/** 识别语言的说法。 */
internal fun asrLanguageName(language: AsrLanguage): String = tr(
    when (language) {
        AsrLanguage.Auto -> R.string.speech_asr_language_auto
        AsrLanguage.Chinese -> R.string.speech_asr_language_zh
        AsrLanguage.English -> R.string.speech_asr_language_en
        AsrLanguage.Japanese -> R.string.speech_asr_language_ja
        AsrLanguage.Cantonese -> R.string.speech_asr_language_yue
        AsrLanguage.Korean -> R.string.speech_asr_language_ko
    },
)

/** 「识别语言」一组:一排可选的胶囊,默认自动。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LanguagePicker(selected: AsrLanguage, onSelect: (AsrLanguage) -> Unit) {
    GroupLabel(tr(R.string.speech_asr_language))
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().testTag("speech-asr-language"),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        AsrLanguage.entries.forEach { l -> SelectChip(asrLanguageName(l), l == selected, { onSelect(l) }) }
    }
}

/**
 * 「千问实时语音识别」的设置页:顶上三个分页并排——自建、百炼·北京、百炼·新加坡,各管各的设置。
 * 自建:填服务地址(只写「主机:端口」时按 ws、默认端口补全)。百炼:API Key(各地域一份,已存的不回填,留空沿用)和模型。
 * 「连接并使用」先连一次走一遍开场(不送音频),通了才存下并选用。
 */
@Composable
fun FunAsrSetupPage(
    start: FunAsrTab, saved: FunAsrSaved, probe: LiveProbeUi,
    onTab: () -> Unit,
    onSaveLocal: (url: String, language: AsrLanguage) -> Unit,
    onSaveBailian: (BailianInput) -> Unit,
    onClearBailian: (BailianRegion) -> Unit,
    onBack: () -> Unit,
) {
    var tab by remember { mutableStateOf(start) }
    Page(tr(R.string.speech_funasr_service), onBack) {
        SegmentedControl(
            FunAsrTab.entries.map(::funAsrTabName), tab.ordinal,
            onSelect = { tab = FunAsrTab.entries[it]; onTab() },
            Modifier.padding(top = 4.dp, bottom = 8.dp).testTag("speech-funasr-tabs"),
        )
        when (val region = tab.region) {
            null -> LocalFunAsr(saved.url, saved.localLanguage, probe, onSaveLocal)
            // 换地域时整块重建,输入框不把一个地域的字带到另一个
            else -> androidx.compose.runtime.key(region) {
                BailianFunAsr(
                    region, region in saved.keys, saved.models[region] ?: BailianProtocol.DEFAULT_MODEL, saved.workspaces[region].orEmpty(),
                    saved.languages[region] ?: AsrLanguage.Auto,
                    probe, onSaveBailian, onClearBailian,
                )
            }
        }
    }
}

@Composable
private fun LocalFunAsr(savedUrl: String?, savedLanguage: AsrLanguage, probe: LiveProbeUi, onSave: (String, AsrLanguage) -> Unit) {
    val c = MikaTheme.colors
    var address by remember { mutableStateOf(savedUrl.orEmpty()) }
    var language by remember { mutableStateOf(savedLanguage) }
    val url = FunAsrProtocol.normalize(address)
    Text(tr(R.string.speech_funasr_setup_hint), Modifier.padding(top = 4.dp, bottom = 4.dp), style = MikaType.Body, color = c.ink2)
    // 两种模型各怎么部署写在仓库文档里;没有能打开网页的应用时 openUri 会抛错,这时什么都不做
    val uri = LocalUriHandler.current
    Text(
        tr(R.string.speech_funasr_deploy_guide),
        Modifier.clip(MikaShapes.Pill).clickable { runCatching { uri.openUri(FunAsrProtocol.DEPLOY_GUIDE) } }
            .padding(vertical = 6.dp).testTag("speech-funasr-guide"),
        style = MikaType.Body, color = c.accent,
    )
    GroupLabel(tr(R.string.speech_funasr_server))
    GroupCard {
        InputRow(
            tr(R.string.speech_funasr_address), address, { address = it }, tr(R.string.speech_funasr_enter_address),
            first = true, keyboard = KeyboardType.Uri, labelWidth = 84.dp, modifier = Modifier.testTag("speech-funasr-address"),
        )
    }
    if (address.isNotBlank() && url == null) FooterNote(tr(R.string.speech_funasr_invalid), Modifier.padding(top = 8.dp), color = c.bad)
    else if (url != null && url != address.trim()) FooterNote(url, Modifier.padding(top = 8.dp))
    LanguagePicker(language) { language = it }
    FooterNote(tr(R.string.speech_funasr_privacy), Modifier.padding(top = 8.dp))
    MikaButton(
        tr(if (probe.testing) R.string.speech_live_testing else R.string.speech_live_save),
        onClick = { url?.let { onSave(it, language) } },
        Modifier.fillMaxWidth().padding(top = 8.dp).testTag("speech-funasr-save"),
        enabled = url != null && !probe.testing,
    )
    probe.error?.let { FooterNote(it, Modifier.padding(top = 8.dp).testTag("speech-live-failed"), color = c.bad) }
}

@Composable
private fun BailianFunAsr(
    region: BailianRegion, configured: Boolean, savedModel: String, savedWorkspace: String, savedLanguage: AsrLanguage, probe: LiveProbeUi,
    onSave: (BailianInput) -> Unit, onClear: (BailianRegion) -> Unit,
) {
    val c = MikaTheme.colors
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(savedModel) }
    var workspaceText by remember { mutableStateOf(savedWorkspace) }
    var language by remember { mutableStateOf(savedLanguage) }
    val workspace = BailianProtocol.workspace(workspaceText)
    val modelOk = BailianProtocol.validModel(model)
    Text(tr(R.string.speech_bailian_setup_hint), Modifier.padding(top = 4.dp, bottom = 4.dp), style = MikaType.Body, color = c.ink2)
    GroupLabel(tr(R.string.speech_doubao_credentials))
    GroupCard {
        InputRow(
            "API Key", key, { key = it },
            tr(if (configured) R.string.speech_bailian_keep_key else R.string.speech_bailian_enter_key),
            first = true, secret = true, labelWidth = 116.dp, modifier = Modifier.testTag("speech-bailian-key"),
        )
        InputRow(
            tr(R.string.speech_bailian_workspace), workspaceText, { workspaceText = it }, tr(R.string.speech_bailian_workspace_hint),
            labelWidth = 116.dp, modifier = Modifier.testTag("speech-bailian-workspace"),
        )
    }
    when {
        workspace == null -> FooterNote(tr(R.string.speech_bailian_workspace_invalid), Modifier.padding(top = 8.dp), color = c.bad)
        else -> FooterNote(
            if (workspace.isEmpty()) tr(R.string.speech_bailian_workspace_note) else region.endpoint(workspace).removeSuffix("/api-ws/v1/inference"),
            Modifier.padding(top = 8.dp),
        )
    }
    GroupLabel(tr(R.string.speech_bailian_model_label))
    GroupCard {
        val listed = BailianProtocol.MODELS
        listed.forEachIndexed { i, id ->
            RadioRow(id, selected = model == id, first = i == 0, onClick = { model = id }, titleStyle = ModelIdStyle)
        }
        InputRow(
            tr(R.string.speech_bailian_other_model), if (model in listed) "" else model,
            { model = it.trim().ifEmpty { BailianProtocol.DEFAULT_MODEL } }, BailianProtocol.DEFAULT_MODEL, labelWidth = 116.dp,
        )
    }
    LanguagePicker(language) { language = it }
    FooterNote(tr(R.string.speech_bailian_privacy), Modifier.padding(top = 8.dp))
    MikaButton(
        tr(if (probe.testing) R.string.speech_live_testing else R.string.speech_live_save),
        onClick = { workspace?.let { onSave(BailianInput(key.trim(), model.trim(), region, it, language)) }; key = "" },
        Modifier.fillMaxWidth().padding(top = 8.dp).testTag("speech-bailian-save"),
        enabled = !probe.testing && modelOk && workspace != null && (key.isNotBlank() || configured),
    )
    probe.error?.let { FooterNote(it, Modifier.padding(top = 8.dp).testTag("speech-live-failed"), color = c.bad) }
    if (configured) {
        Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
            Text(
                tr(R.string.speech_bailian_remove),
                Modifier.clip(MikaShapes.Pill).clickable(onClick = { onClear(region) }).padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("speech-bailian-remove"),
                style = MikaType.Body, color = c.bad,
            )
        }
    }
}
