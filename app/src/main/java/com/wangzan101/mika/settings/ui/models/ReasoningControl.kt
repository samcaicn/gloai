package com.wangzan101.mika.settings.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ReasoningOptions
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.SelectChip
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 每个用途自己的档位;跟随主模型时说明继承,显式选同一个模型仍能独立设置。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReasoningControl(ui: ModelSetupUi, role: ModelRole, modifier: Modifier = Modifier, onSelect: (String?) -> Unit) {
    val cfg = ui.configFor(role) ?: return
    val listed = ui.providerOf(cfg)?.models?.firstOrNull { it.id == cfg.model }
    val options = ReasoningOptions.of(cfg, listed) ?: return
    val selected = ui.roles.reasoningEffort(role)
    if (options.levels.isEmpty() && selected == null) return
    if (ui.roles[role] == RoleChoice.SameAsMain) {
        FooterNote(stringResource(R.string.model_reasoning_inherit), modifier)
        return
    }
    var open by remember(role, cfg.providerId, cfg.model) { mutableStateOf(false) }
    ModelOptionButton(
        stringResource(R.string.model_reasoning_title, reasoningLabel(selected)),
        "reasoning-${role.name.lowercase()}", modifier,
    ) { open = true }
    if (open) MikaDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.model_reasoning_title, ModelsCopy.roleName(role))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.model_reasoning_note))
                if (!options.fromProvider) Text(stringResource(R.string.model_reasoning_unknown))
                if (selected != null && selected !in options.levels) Text(stringResource(R.string.model_reasoning_unavailable))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf<String?>(null) + options.levels).forEach { effort ->
                        SelectChip(reasoningLabel(effort), selected == effort, {
                            onSelect(effort)
                            open = false
                        }, Modifier.testTag("reasoning-option-${effort ?: "default"}"))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 卡片下方的轻量设置入口：没有额外表格或底色，保留足够的点击高度。 */
@Composable
internal fun ModelOptionButton(text: String, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(44.dp).clickable(onClick = onClick).testTag(tag).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text, Modifier.weight(1f), style = MikaType.Hint, color = MikaTheme.colors.ink2,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        RowChevron()
    }
}

internal fun hasReasoningAction(ui: ModelSetupUi, role: ModelRole): Boolean {
    if (ui.roles[role] == RoleChoice.SameAsMain) return false
    val cfg = ui.configFor(role) ?: return false
    val listed = ui.providerOf(cfg)?.models?.firstOrNull { it.id == cfg.model }
    val options = ReasoningOptions.of(cfg, listed) ?: return false
    return options.levels.isNotEmpty() || ui.roles.reasoningEffort(role) != null
}

@Composable
private fun reasoningLabel(effort: String?): String = when (effort) {
    null -> stringResource(R.string.model_reasoning_default)
    "none" -> stringResource(R.string.model_reasoning_none)
    "minimal" -> stringResource(R.string.model_reasoning_minimal)
    "low" -> stringResource(R.string.model_reasoning_low)
    "medium" -> stringResource(R.string.model_reasoning_medium)
    "high" -> stringResource(R.string.model_reasoning_high)
    "xhigh" -> stringResource(R.string.model_reasoning_xhigh)
    "max" -> stringResource(R.string.model_reasoning_max)
    else -> effort
}
