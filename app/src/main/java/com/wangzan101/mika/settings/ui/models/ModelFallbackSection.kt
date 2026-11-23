package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.Hairline
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** Backup models for a role: seamless failover if the primary model hits limits or goes down. */
@Composable
internal fun FallbackSection(
    ui: ModelSetupUi,
    role: ModelRole = ModelRole.Main,
    onPickFallback: (ModelRole, Int) -> Unit,
    onClearFallback: (ModelRole, Int) -> Unit,
) {
    val shouldShow = when (role) {
        ModelRole.Main -> ui.roles.main != null
        ModelRole.Vision -> ui.roles.vision is RoleChoice.Model
        ModelRole.Light -> ui.roles.light is RoleChoice.Model
    }
    if (!shouldShow) return

    val fallbacks = ui.roles.fallbacks(role)
    val maxBackups = com.wangzan101.mika.settings.MAX_FALLBACKS
    val roleLabel = ModelsCopy.roleLabel(role)
    val hasAny = fallbacks.isNotEmpty()
    var expanded by rememberSaveable(role.name) { mutableStateOf(false) }
    var removingSlot by rememberSaveable(role.name) { mutableStateOf<Int?>(null) }
    val c = MikaTheme.colors

    GroupCard {
        // Collapsible Header Row - sleek and compact
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(if (hasAny) c.accent.copy(alpha = 0.12f) else c.neutralSoft, MikaShapes.Tile),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    ModelsCopy.FALLBACK_ICON,
                    contentDescription = null,
                    tint = if (hasAny) c.accent else c.ink3,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.model_fallback_chain_title, roleLabel),
                        style = MikaType.BodyStrong,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (hasAny) {
                        Box(
                            Modifier
                                .background(c.accent.copy(alpha = 0.12f), MikaShapes.Pill)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                stringResource(R.string.model_fallback_active_count, fallbacks.size),
                                style = MikaType.Micro.copy(fontWeight = FontWeight.SemiBold),
                                color = c.accent,
                                maxLines = 1,
                            )
                        }
                    }
                }
                val previewText = if (expanded) {
                    stringResource(R.string.model_fallback_collapse_hint)
                } else if (hasAny) {
                    val primarySnippet = ui.configFor(role)?.model?.substringAfterLast('/') ?: "Primary"
                    val fallbackSnippets = fallbacks.mapNotNull { id ->
                        ui.configs.firstOrNull { it.id == id }?.model?.substringAfterLast('/')
                    }
                    (listOf(primarySnippet) + fallbackSnippets).joinToString(" ➔ ")
                } else {
                    stringResource(R.string.model_fallback_empty_preview)
                }
                Text(
                    previewText,
                    Modifier.padding(top = 2.dp),
                    style = MikaType.Caption,
                    color = c.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                MikaIcons.Chevron,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = c.ink3,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(if (expanded) 90f else 0f),
            )
        }

        // Expanded Interactive Chain - nested inside an indented tray card
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .background(c.fill, MikaShapes.Card)
                    .border(0.5.dp, c.edge, MikaShapes.Card)
                    .clip(MikaShapes.Card),
            ) {
                val primaryCfg = ui.configFor(role)
                if (primaryCfg != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(c.accent.copy(alpha = 0.14f), MikaShapes.Tile),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("1", style = MikaType.Caption.copy(fontWeight = FontWeight.Bold), color = c.accent)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.model_fallback_primary_badge),
                                style = MikaType.Hint,
                                color = c.accent,
                                maxLines = 1,
                            )
                            Text(
                                primaryCfg.model,
                                Modifier.padding(top = 1.dp),
                                style = MikaType.BodyStrong,
                                fontFamily = FontFamily.Monospace,
                                color = c.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val providerName = ui.providerOf(primaryCfg)?.let(ModelsCopy::providerName) ?: primaryCfg.name
                            if (providerName.isNotEmpty()) {
                                Text(
                                    providerName,
                                    Modifier.padding(top = 1.dp),
                                    style = MikaType.Caption,
                                    color = c.ink2,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                fallbacks.forEachIndexed { slot, configId ->
                    Hairline(start = 14.dp)
                    val cfg = ui.configs.firstOrNull { it.id == configId }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPickFallback(role, slot) }
                            .testTag("role-fallback-${role.name.lowercase()}-$slot")
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(c.surface, MikaShapes.Tile),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${slot + 2}", style = MikaType.Caption.copy(fontWeight = FontWeight.Bold), color = c.ink2)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.model_fallback_backup_badge, slot + 1),
                                style = MikaType.Hint,
                                color = c.ink3,
                                maxLines = 1,
                            )
                            Text(
                                cfg?.model ?: ModelsCopy.UNSET,
                                Modifier.padding(top = 1.dp),
                                style = MikaType.BodyStrong,
                                fontFamily = FontFamily.Monospace,
                                color = if (cfg == null) c.ink3 else c.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val providerName = cfg?.let { ui.providerOf(it)?.let(ModelsCopy::providerName) ?: it.name } ?: ""
                            if (providerName.isNotEmpty()) {
                                Text(
                                    providerName,
                                    Modifier.padding(top = 1.dp),
                                    style = MikaType.Caption,
                                    color = c.ink2,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(MikaShapes.Pill)
                                .clickable { removingSlot = slot },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                MikaIcons.Close,
                                contentDescription = "Remove backup",
                                tint = c.ink3,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                if (fallbacks.size < maxBackups) {
                    Hairline(start = 14.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPickFallback(role, fallbacks.size) }
                            .testTag("role-fallback-${role.name.lowercase()}-${fallbacks.size}")
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(c.accent.copy(alpha = 0.08f), MikaShapes.Tile),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(MikaIcons.Plus, contentDescription = null, tint = c.accent, modifier = Modifier.size(15.dp))
                        }
                        Text(
                            if (fallbacks.isEmpty()) stringResource(R.string.model_fallback_add_first)
                            else stringResource(R.string.model_fallback_add_another, fallbacks.size + 1, maxBackups),
                            style = MikaType.BodyStrong,
                            color = c.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    val pendingRemoveSlot = removingSlot
    if (pendingRemoveSlot != null && pendingRemoveSlot in fallbacks.indices) {
        val targetCfgId = fallbacks[pendingRemoveSlot]
        val targetCfg = ui.configs.firstOrNull { it.id == targetCfgId }
        val modelName = targetCfg?.model ?: ModelsCopy.UNSET
        MikaDialog(
            onDismissRequest = { removingSlot = null },
            title = { Text(stringResource(R.string.model_fallback_remove_title)) },
            text = {
                Text(stringResource(R.string.model_fallback_remove_body, modelName, roleLabel))
            },
            confirmButton = {
                TextButton(onClick = {
                    val s = removingSlot
                    removingSlot = null
                    if (s != null) onClearFallback(role, s)
                }) {
                    Text(stringResource(R.string.model_fallback_remove_confirm), color = c.bad)
                }
            },
            dismissButton = {
                TextButton(onClick = { removingSlot = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (hasAny && expanded) {
        FooterNote(stringResource(R.string.model_fallback_footer_note, roleLabel))
    }
}
