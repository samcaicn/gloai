package com.wangzan101.mika.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.GroupLimits
import com.wangzan101.mika.core.model.GroupMember
import com.wangzan101.mika.core.model.GroupStrategy
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.settings.ModelContextSettings
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.Hairline
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.launch
import java.util.UUID

/** 组成员列表要跟着编辑页一起扛过进程重建:每项压成一行,恢复时还原。 */
private val GroupMembersSaver = listSaver<List<GroupMember>, String>(
    save = { members ->
        members.map { m ->
            listOf(
                m.configId, m.priority.toString(), m.weight.toString(), m.enabled.toString(),
                m.reasoningEffort.orEmpty(), m.costRank?.toString().orEmpty(),
            ).joinToString("\u001f")
        }
    },
    restore = { rows ->
        rows.mapNotNull { row ->
            val p = row.split('\u001f')
            if (p.size < 6 || p[0].isEmpty()) null else GroupMember(
                configId = p[0],
                priority = p[1].toIntOrNull() ?: 0,
                weight = (p[2].toIntOrNull() ?: 1).coerceIn(1, 100),
                enabled = p[3].toBooleanStrictOrNull() ?: true,
                reasoningEffort = p[4].ifBlank { null },
                costRank = p[5].toIntOrNull(),
            )
        }
    },
)

/** 模型组列表:名称、成员数、用于哪些用途。点一行编辑。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelGroupsScreen(
    ui: ModelSetupUi, onBack: () -> Unit, onNew: () -> Unit, onEdit: (String) -> Unit,
) {
    val c = MikaTheme.colors
    Page(ModelsCopy.GROUPS, onBack) {
        Text(ModelsCopy.groupLead, Modifier.padding(top = 4.dp, bottom = 12.dp), fontSize = 14.5.sp, lineHeight = 22.sp, color = c.ink2)
        if (ui.groups.isEmpty()) {
            FooterNote(ModelsCopy.groupEmpty)
        } else {
            GroupCard {
                ui.groups.forEachIndexed { i, g ->
                    ListRow(null, g.name, first = i == 0, onClick = { onEdit(g.id) }) {
                        RowValue(ModelsCopy.groupCount(g.enabledMembers.size))
                        RowChevron()
                    }
                    val roles = androidx.compose.runtime.remember(g.id, ui.roles) { ModelsCopy.groupRoles(ui.roles, g.id) }
                    if (roles.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                            Text(roles, style = MikaType.Caption, color = c.ink3)
                        }
                    }
                    if (i != ui.groups.lastIndex) Hairline()
                }
            }
        }
        GroupCard(Modifier.padding(top = 8.dp)) {
            ListRow(MikaIcons.Plus, ModelsCopy.NEW_GROUP, first = true, titleColor = c.accent, tone = TileTone.Accent, onClick = onNew)
        }
        FooterNote(ModelsCopy.groupFooter)
    }
}

/**
 * 组编辑页。[groupId] 为 null 是新建。保存失败(成员配置没了、主模型组不满足全员支持工具)时留在页上说明;
 * 删除是另一个确认按钮,删完返回列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelGroupEditorScreen(
    ui: ModelSetupUi, groupId: String?, onBack: () -> Unit,
    onSave: suspend (ModelGroup) -> Boolean,
    onDelete: suspend (String) -> Unit,
    ensureConfig: suspend (String, String) -> String?,
    onSetContext: (String, ModelContextSettings) -> Boolean = { _, _ -> false },
) {
    val c = MikaTheme.colors
    val scope = rememberCoroutineScope()
    val existing = remember(groupId, ui.groups) { groupId?.let { id -> ui.groups.firstOrNull { it.id == id } } }
    var name by rememberSaveable(groupId) { mutableStateOf(existing?.name.orEmpty()) }
    var strategy by rememberSaveable(groupId) { mutableStateOf((existing?.strategy ?: GroupStrategy.Failover).wire) }
    var sticky by rememberSaveable(groupId) { mutableStateOf(existing?.sticky ?: true) }
    var members by rememberSaveable(groupId, stateSaver = GroupMembersSaver) { mutableStateOf(existing?.members ?: emptyList()) }
    var adding by remember { mutableStateOf(false) }
    var contextFor by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val group = ModelGroup(
        id = existing?.id ?: "",
        name = name.trim(),
        strategy = GroupStrategy.of(strategy),
        members = members.mapIndexed { i, m -> m.copy(priority = i) },
        sticky = sticky,
        // 编辑页还没有这两项的控件;保存时保留已存的值,不要静默重置
        raceReadOnly = existing?.raceReadOnly ?: false,
        limits = existing?.limits ?: GroupLimits(),
    )
    val validMain = group.usableFor(com.wangzan101.mika.core.model.ModelRole.Main) { id -> ui.configOf(id)?.capabilities }
    val validVision = group.usableFor(com.wangzan101.mika.core.model.ModelRole.Vision) { id -> ui.configOf(id)?.capabilities }
    // 已经用做主模型的组不能失去工具支持;看图/轻量组不受这条限制
    val usedForMain = existing != null && ui.roles.resolveGroup(com.wangzan101.mika.core.model.ModelRole.Main) == existing.id
    val canSave = name.isNotBlank() && members.isNotEmpty() && !saving && (!usedForMain || validMain)

    Page(if (existing == null) ModelsCopy.NEW_GROUP else ModelsCopy.editGroup(name.ifBlank { existing.name }), onBack) {
        InputRow(ModelsCopy.GROUP_NAME, name, { name = it; error = null }, ModelsCopy.GROUP_NAME_HINT, first = true)
        GroupLabel(ModelsCopy.GROUP_STRATEGY, Modifier.padding(top = 8.dp))
        GroupCard {
            // 费用档位还没有编辑界面,v1 先不露出 cost-first;文件和核心已经能读
            GroupStrategy.entries.filter { it != GroupStrategy.CostFirst }.forEachIndexed { i, s ->
                RadioRow(ModelsCopy.strategyTitle(s), selected = strategy == s.wire, onClick = { strategy = s.wire }, first = i == 0, subtitle = ModelsCopy.strategyNote(s))
            }
        }
        GroupCard(Modifier.padding(top = 8.dp)) {
            ListRow(null, ModelsCopy.GROUP_STICKY, first = true) {
                MikaSwitch(sticky, { sticky = it })
            }
        }
        FooterNote(ModelsCopy.GROUP_STICKY_NOTE)

        GroupLabel(ModelsCopy.GROUP_MEMBERS, Modifier.padding(top = 8.dp))
        if (members.isEmpty()) FooterNote(ModelsCopy.GROUP_NO_MEMBERS)
        GroupCard {
            members.forEachIndexed { i, m ->
                val cfg = ui.configOf(m.configId)
                val p = cfg?.let { ui.providerOf(it) }
                ListRow(null, cfg?.model ?: m.configId, first = i == 0) {
                    RowValue(p?.name.orEmpty())
                    MikaSwitch(m.enabled, { on -> members = members.toMutableList().also { it[i] = m.copy(enabled = on) } })
                }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(ModelsCopy.groupPriority(i + 1), style = MikaType.Caption, color = c.ink3, modifier = Modifier.weight(1f))
                    TextButton(onClick = { members = members.swapped(i, i - 1) }, enabled = i > 0, modifier = Modifier.testTag("group-up-$i")) { Text(ModelsCopy.GROUP_UP) }
                    TextButton(onClick = { members = members.swapped(i, i + 1) }, enabled = i < members.lastIndex, modifier = Modifier.testTag("group-down-$i")) { Text(ModelsCopy.GROUP_DOWN) }
                    TextButton(onClick = { contextFor = m.configId }, modifier = Modifier.testTag("group-context-$i")) {
                        Text(ModelsCopy.GROUP_CONTEXT)
                    }
                    TextButton(onClick = { members = members.filterIndexed { j, _ -> j != i } }, modifier = Modifier.testTag("group-remove-$i")) {
                        Text(ModelsCopy.GROUP_REMOVE, color = c.bad)
                    }
                }
            }
        }
        GroupCard(Modifier.padding(top = 8.dp)) {
            ListRow(MikaIcons.Plus, ModelsCopy.GROUP_ADD_MEMBER, first = true, titleColor = c.accent, tone = TileTone.Accent, onClick = { adding = true })
        }
        if (!validMain && members.isNotEmpty()) FooterNote(ModelsCopy.GROUP_MAIN_NEEDS_TOOLS, color = c.bad)
        if (!validVision && members.isNotEmpty()) FooterNote(ModelsCopy.GROUP_VISION_NEEDS_EYES, color = c.warn)
        FooterNote(ModelsCopy.GROUP_PRIVACY)
        error?.let { FooterNote(it, Modifier.testTag("group-error"), color = c.bad) }

        MikaButton(
            ModelsCopy.SAVE, {
                saving = true
                error = null
                scope.launch {
                    val id = existing?.id ?: UUID.randomUUID().toString()
                    val ok = onSave(group.copy(id = id))
                    saving = false
                    if (ok) onBack() else error = ModelsCopy.GROUP_SAVE_FAILED
                }
            },
            Modifier.fillMaxWidth().padding(top = 8.dp).testTag("group-save"),
            kind = ButtonKind.Primary, size = ButtonSize.Medium, enabled = canSave,
        )
        if (existing != null) {
            GroupCard(Modifier.padding(top = 8.dp)) {
                ListRow(MikaIcons.Trash, ModelsCopy.GROUP_DELETE, titleColor = c.bad, tone = TileTone.Bad, first = true, onClick = { confirmDelete = true })
            }
        }
    }

    if (adding) {
        AddMemberDialog(
            ui = ui, existing = members,
            onDismiss = { adding = false },
            onAdd = { providerId, model ->
                scope.launch {
                    val id = ensureConfig(providerId, model)
                    if (id == null) error = ModelsCopy.GROUP_SAVE_FAILED
                    else if (members.none { it.configId == id }) members = members + GroupMember(id, priority = members.size)
                    adding = false
                }
            },
        )
    }
    contextFor?.let { id ->
        ui.configOf(id)?.let { cfg ->
            val saved = ui.providerOf(cfg)?.contextSettings?.get(cfg.model) ?: ModelContextSettings()
            ModelContextDialog(cfg, saved, "group-context", { settings -> onSetContext(cfg.id, settings) }) { contextFor = null }
        }
    }
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(ModelsCopy.groupDeleteTitle(existing.name)) },
            text = { Text(ModelsCopy.GROUP_DELETE_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { onDelete(existing.id); onBack() }
                }) { Text(ModelsCopy.GROUP_DELETE, color = c.bad) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(ModelsCopy.CANCEL) } },
        )
    }
}

private fun List<GroupMember>.swapped(i: Int, j: Int): List<GroupMember> {
    if (i !in indices || j !in indices) return this
    return toMutableList().also { it[i] = this[j]; it[j] = this[i] }
}

/** 添加成员:列出所有服务商的模型,已经在组里的不再列。 */
@Composable
private fun AddMemberDialog(
    ui: ModelSetupUi, existing: List<GroupMember>, onDismiss: () -> Unit, onAdd: (String, String) -> Unit,
) {
    val c = MikaTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ModelsCopy.GROUP_ADD_MEMBER) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (ui.providers.isEmpty()) FooterNote(ModelsCopy.ROLE_EMPTY)
                for (p in ui.providers) {
                    GroupLabel(ModelsCopy.providerName(p))
                    for (o in ui.options(p)) {
                        val used = existing.any { m -> ui.configOf(m.configId)?.let { it.providerId == p.id && it.model == o.id } == true }
                        if (used) continue
                        Row(
                            Modifier.fillMaxWidth().clickable { onAdd(p.id, o.id) }.padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(o.id, style = MikaType.Body.copy(fontFamily = FontFamily.Monospace), color = c.ink, modifier = Modifier.weight(1f))
                            Text(ModelsCopy.ADD, color = c.accent, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(ModelsCopy.CANCEL) } },
    )
}
