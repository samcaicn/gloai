package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ModelContextSettings
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.settings.VoiceChoice
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.Hairline
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 服务商:每家一行(写明在给哪些用途干活),加「添加服务商」;没有服务商或点了添加时,表单就摊在这里。 */
@Composable
internal fun ProvidersSection(ui: ModelSetupUi, actions: ModelSetupActions) {
    val c = MikaTheme.colors
    if (ui.providers.isNotEmpty()) {
        GroupCard {
            ui.providers.forEachIndexed { i, p ->
                val line = if (ui.signedOut(p)) ModelsCopy.CHATGPT_SIGNED_OUT else ModelsCopy.providerLine(p, ui.listing[p.id], ui.rolesOf(p))
                // 有用着却没试通过的模型、或跳过测试存下且已经在用:这一行就提醒「没测试通过」
                val warning = ModelsCopy.UNVERIFIED.takeIf {
                    ui.unverified(p).isNotEmpty() || (ui.untested(p) && ui.rolesOf(p).isNotEmpty())
                }
                ProviderRow(p, line, first = i == 0, warning = warning) { actions.openProvider(p.id) }
            }
            if (!ui.showForm) {
                ListRow(
                    MikaIcons.Plus, ModelsCopy.ADD_PROVIDER, titleColor = c.accent, tone = TileTone.Accent,
                    onClick = actions.openForm,
                )
            }
        }
    }
    ProbeNote(ui, actions)
    if (ui.providers.isEmpty()) AddProviderForm(ui, actions)
    FooterNote(ModelsCopy.KEY_NOTE)
}

/** 存下服务商时的说明;推荐的模型没试通过、还没有主模型时,给「选主模型」(选的时候先试调用)。 */
@Composable
internal fun ProbeNote(ui: ModelSetupUi, actions: ModelSetupActions) {
    val c = MikaTheme.colors
    // 首次上手:服务商一存下,摊开的表单就收起了,推荐的模型还在试调用,这里接着说一声
    if (ui.form.test == ListState.Running && !ui.formOpen && ui.providers.isNotEmpty()) {
        FooterNote(ModelSetupCopy.TESTING, Modifier.testTag("setup-probe-running"))
    }
    ui.probeNote?.let { FooterNote(it, Modifier.testTag("setup-probe-note"), color = c.warn) }
    // 刚存下的那家一个模型都没试通过:直接去选主模型(选的时候先试调用)
    if (ui.pickToProbe != null && !ui.roles.hasMain) {
        Text(
            ModelSetupCopy.PICK_MAIN,
            Modifier.padding(start = 16.dp, bottom = 12.dp).clickable { actions.pickRole(ModelRole.Main) }.testTag("setup-pick-main").padding(vertical = 4.dp),
            style = MikaType.Label, color = c.accent,
        )
    }
}

/** 对话和声音模型共用一组用途卡。 */
@Composable
internal fun RolesSection(ui: ModelSetupUi, onPick: (ModelRole) -> Unit, onReasoning: (ModelRole, String?) -> Unit,
    onContext: ((String, ModelContextSettings) -> Boolean)? = null,
    onVoice: (() -> Unit)? = null, voiceChoice: VoiceChoice? = null,
) {
    GroupCard {
        ModelRole.entries.forEachIndexed { i, role ->
            RoleCard(ModelsCopy.roleIcon(role), ModelsCopy.roleLabel(role), ModelsCopy.roleLine(ui, role), first = i == 0, tag = "role-${role.name.lowercase()}") {
                onPick(role)
            }
            if (ui.roles[role] == RoleChoice.SameAsMain) {
                ReasoningControl(ui, role) { onReasoning(role, it) }
            } else {
                val reasoning = hasReasoningAction(ui, role)
                val context = onContext != null && hasModelContextControl(ui, role)
                if (reasoning || context) {
                    Column(Modifier.padding(start = 70.dp, end = 16.dp)) {
                        Hairline(start = 0.dp)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (reasoning) ReasoningControl(ui, role, Modifier.weight(1f)) { onReasoning(role, it) }
                            if (reasoning && context) Box(Modifier.width(0.5.dp).height(14.dp).background(MikaTheme.colors.hair))
                            if (onContext != null && context) ModelContextControl(ui, role, onContext, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        if (onVoice != null) {
            RoleCard(
                ModelsCopy.VOICE_ICON, stringResource(R.string.role_voice), ModelsCopy.voiceLine(ui.providers, voiceChoice),
                tag = "role-voice", onClick = onVoice,
            )
        }
    }
}

/** 语音转文字独立设置。 */
@Composable
internal fun SpeechSection(ui: ModelSetupUi, onSpeech: (() -> Unit)?) {
    if (onSpeech == null) return
    GroupCard {
        ListRow(ModelsCopy.SPEECH_ICON, stringResource(R.string.role_speech), first = true, onClick = onSpeech) {
            RowValue(ModelsCopy.speechLine(ui).title)
            RowChevron()
        }
    }
}

/** 图片模型:生成/改图用的模型,和语音一样单独一行。 */
@Composable
internal fun ImageModelSection(ui: ModelSetupUi, onImage: (() -> Unit)?) {
    if (onImage == null) return
    GroupCard {
        ListRow(ModelsCopy.IMAGE_ICON, stringResource(R.string.role_image), subtitle = stringResource(R.string.role_image_what), first = true, onClick = onImage) {
            RowValue(ModelsCopy.imageLine(ui).title)
            RowChevron()
        }
    }
}

/** 统一语音模型目录入口:能力与协议在此声明;语音输入、朗读和通话页只负责选择与使用偏好。 */
@Composable
internal fun VoiceCatalogSection(onOpenVoiceCatalog: (() -> Unit)?) {
    if (onOpenVoiceCatalog == null) return
    GroupCard {
        ListRow(
            ModelsCopy.VOICE_ICON, stringResource(R.string.models_voice_catalog),
            subtitle = stringResource(R.string.models_voice_catalog_sub),
            first = true, onClick = onOpenVoiceCatalog,
        ) { RowChevron() }
    }
}
