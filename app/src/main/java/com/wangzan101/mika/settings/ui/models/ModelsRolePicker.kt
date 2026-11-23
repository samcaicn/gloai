package com.wangzan101.mika.settings.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ModelOption
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.SearchField
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

class RolePickerActions(
    /** (服务商 id, 模型名) */
    val pick: (String, String) -> Unit = { _, _ -> },
    /** 手填的模型名(服务商 id, 模型名):先试调用,通了才选。 */
    val pickManual: (String, String) -> Unit = { _, _ -> },
    /** 看图、轻量的固定选项(用主模型、不用)。 */
    val choose: (RoleChoice) -> Unit = {},
    /** 选一个模型组。 */
    val chooseGroup: (String) -> Unit = {},
    /** 打开模型组管理页。 */
    val manageGroups: () -> Unit = {},
    val addProvider: () -> Unit = {},
)

/** 选择页顶上的固定选项:看图「用主模型」「不用」,轻量「用主模型」;主模型没有。 */
internal fun fixedChoices(role: ModelRole): List<RoleChoice> = when (role) {
    ModelRole.Main -> emptyList()
    ModelRole.Vision -> listOf(RoleChoice.SameAsMain, RoleChoice.Off)
    ModelRole.Light -> listOf(RoleChoice.SameAsMain)
}

/**
 * 选择对话用途，按服务商分组展示可选模型。看图、轻量还提供固定选项。
 * 选主模型时先试调用(见 [ModelSetupViewModel.pick]),进度和原因显示在那一家下面。
 */
@Composable
fun RolePickerContent(ui: ModelSetupUi, role: ModelRole, actions: RolePickerActions, onBack: () -> Unit,
    titleOverride: String? = null, hintOverride: String? = null,
    pickedOverride: ((String, String) -> Boolean)? = null,
    exclude: ((String, String) -> Boolean)? = null,
    clearAction: (() -> Unit)? = null, clearSelected: Boolean = false,
) {
    val c = MikaTheme.colors
    val providers = ui.providers
    val groups = providers.map { p -> p to ui.options(p) }
    Page(titleOverride ?: ModelsCopy.roleName(role), onBack) {
        val hint = hintOverride ?: ModelsCopy.roleHint(role)
        if (hint.isNotEmpty()) {
            Text(hint, Modifier.padding(top = 4.dp, bottom = 12.dp), style = MikaType.Body, color = c.ink2)
        }
        if (ui.groups.isNotEmpty()) {
            GroupLabel(ModelsCopy.GROUPS)
            GroupCard {
                ui.groups.forEachIndexed { i, g ->
                    val ok = ui.usable(g, role)
                    RadioRow(
                        g.name, selected = ui.roles[role] == RoleChoice.Group(g.id),
                        onClick = { if (ok) actions.chooseGroup(g.id) }, first = i == 0, enabled = ok,
                        subtitle = if (ok) ModelsCopy.groupCount(g.enabledMembers.size)
                        else if (role == ModelRole.Vision) ModelsCopy.GROUP_VISION_NEEDS_EYES else ModelsCopy.GROUP_MAIN_NEEDS_TOOLS,
                    )
                }
            }
            GroupCard(Modifier.padding(top = 8.dp)) {
                ListRow(MikaIcons.Plus, ModelsCopy.GROUPS, first = true, titleColor = c.accent, tone = TileTone.Accent, onClick = actions.manageGroups)
            }
        }
        val fixed = fixedChoices(role)
        if (fixed.isNotEmpty()) {
            GroupCard {
                fixed.forEachIndexed { i, choice ->
                    RadioRow(
                        ModelsCopy.choiceTitle(choice), selected = ui.roles[role] == choice, onClick = { actions.choose(choice) },
                        subtitle = ModelsCopy.choiceNote(role, choice), first = i == 0,
                    )
                }
            }
        }
        if (clearAction != null) {
            GroupCard {
                RadioRow(ModelsCopy.FALLBACK_CLEAR, selected = clearSelected, onClick = clearAction, first = true)
            }
        }
        ModelPickerRows(
            groups = groups,
            pick = actions.pick,
            isSelected = { providerId, model -> pickedOverride?.invoke(providerId, model) ?: ui.picked(role, providerId, model) },
            forVision = role == ModelRole.Vision,
            exclude = exclude,
            pickManual = actions.pickManual,
            manualState = ui.manualPick,
            listing = ui.listing,
        )
        if (providers.isEmpty()) FooterNote(ModelsCopy.ROLE_EMPTY)
        GroupCard(Modifier.padding(top = 8.dp)) {
            ListRow(MikaIcons.Plus, ModelsCopy.ADD_PROVIDER, first = true, titleColor = c.accent, tone = TileTone.Accent, onClick = actions.addProvider)
        }
        FooterNote(ModelsCopy.PICKER_NOTE)
    }
}

/** 按服务商分组的单选列表。 */
@Composable
internal fun ModelPickerRows(
    groups: List<Pair<Provider, List<ModelOption>>>,
    pick: (String, String) -> Unit,
    isSelected: (String, String) -> Boolean,
    forVision: Boolean = false,
    exclude: ((String, String) -> Boolean)? = null,
    pickManual: ((String, String) -> Unit)? = null,
    manualState: Map<String, ListState> = emptyMap(),
    listing: Map<String, ListState> = emptyMap(),
) {
    val c = MikaTheme.colors
    var filter by rememberSaveable { mutableStateOf("") }
    val q = filter.trim()
    if (groups.sumOf { it.second.size } > FILTER_FROM) {
        GroupLabel(ModelsCopy.PICK_FROM, Modifier.padding(top = 8.dp))
        Box(Modifier.padding(bottom = 4.dp)) {
            SearchField(filter, { filter = it }, ModelsCopy.FILTER_HINT, Modifier.testTag("model-filter"))
        }
    }
    var anyShown = false
    groups.forEach { (provider, options) ->
        val shown = options.filter { (q.isEmpty() || it.id.contains(q, ignoreCase = true)) && exclude?.invoke(provider.id, it.id) != true }
        if (shown.isEmpty() && (q.isNotEmpty() || pickManual == null)) return@forEach
        anyShown = true
        GroupLabel(ModelsCopy.providerName(provider))
        val manual = manualState[provider.id]
        GroupCard {
            shown.take(PICK_AT_MOST).forEachIndexed { i, option ->
                RadioRow(
                    option.id, selected = isSelected(provider.id, option.id),
                    onClick = { pick(provider.id, option.id) }, first = i == 0,
                    enabled = manual != ListState.Running, titleStyle = ModelIdStyle,
                    below = { Tags(option, forVision = forVision) },
                )
            }
            if (pickManual != null) {
                ManualModel(provider, manual, first = shown.isEmpty()) { pickManual(provider.id, it) }
            }
        }
        val state = listing[provider.id]
        when {
            manual is ListState.Failed -> FooterNote(manual.message, Modifier.testTag("manual-model-failed"), color = c.bad)
            // 选主模型先试调用;手填那一行自己显示进度
            manual == ListState.Running && shown.isNotEmpty() -> FooterNote(ModelSetupCopy.TESTING, Modifier.testTag("pick-probe-running"))
            state == ListState.Running -> FooterNote(ModelsCopy.LISTING)
            state is ListState.Failed -> FooterNote(ModelSetupCopy.failed(state.message), color = c.bad)
            shown.size > PICK_AT_MOST -> FooterNote(ModelsCopy.more(shown.size - PICK_AT_MOST))
        }
    }
    if (q.isNotEmpty() && !anyShown) FooterNote(ModelsCopy.NO_MATCH)
}

/** 手填列表里找不到的模型名，由调用方处理提交;[state] 是进度(设主模型时先试调用)。 */
@Composable
internal fun ManualModel(p: Provider, state: ListState?, first: Boolean, onUse: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val running = state == ListState.Running
    val enabled = name.isNotBlank() && !running
    val example = ModelSetupCopy.modelExample(ProviderPresets.forHost(p.baseUrl)?.kind ?: p.kind)
    InputRow(ModelsCopy.MODEL_NAME, name, { name = it }, example, first = first, modifier = Modifier.testTag("manual-model")) {
        Text(
            if (running) ModelSetupCopy.TESTING else ModelsCopy.USE, Modifier.clickable(enabled = enabled) { onUse(name.trim()) }.testTag("manual-model-use"),
            style = MikaType.Label, color = if (enabled) MikaTheme.colors.accent else MikaTheme.colors.ink3,
        )
    }
}

private const val FILTER_FROM = 8
private const val PICK_AT_MOST = 80

/** 服务商详情里最多放几个模型;其余进「全部模型」页。 */
internal const val MODEL_PREVIEW = 3

// ---------- 一个服务商 ----------
