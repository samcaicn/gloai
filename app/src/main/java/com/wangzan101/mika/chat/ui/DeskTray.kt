package com.wangzan101.mika.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.frontdesk.DeskTaskView
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * 输入栏上方的「在办」一条:有任务在做、排队或要你处理时才出现。要你处理的排在最前、圆点变橙;
 * 点开是这个对话里所有派过的事,点一件滚到它的卡片。
 */
@Composable
internal fun DeskTray(tasks: List<DeskTaskView>, reduceMotion: Boolean, onJump: (String) -> Unit) {
    val active = tasks.filter { it.inProgress }
    if (active.isEmpty()) return
    val c = MikaTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    val head = active.firstOrNull { it.state == DeskTaskPhase.NeedsYou } ?: active.first()
    val asking = active.any { it.state == DeskTaskPhase.NeedsYou }
    val headLine = head.line ?: head.recent.lastOrNull() ?: deskPhaseStyle(head).label
    val summary = stringResource(R.string.desk_report_line, head.title, headLine)
    val text = if (active.size > 1) trPlural(R.plurals.desk_tray_count, active.size, active.size) + " · " + summary else summary
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().testTag("desk-tray-sheet").heightIn(max = 280.dp)
                    .clip(MikaShapes.Card).background(c.surface).border(1.dp, c.hair, MikaShapes.Card)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.desk_tray_caption), style = MikaType.Hint, color = c.ink3)
                tasks.forEach { view -> TrayRow(view) { expanded = false; onJump(view.taskId) } }
            }
        }
        Row(
            Modifier.testTag("desk-tray").clip(MikaShapes.Pill).background(c.surface).border(1.dp, c.hair, MikaShapes.Pill)
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(if (expanded) R.string.desk_tray_collapse else R.string.desk_tray_expand),
                ) { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (asking) Box(Modifier.size(8.dp).background(c.warn, CircleShape))
            else Spinner(Modifier.size(12.dp), !reduceMotion, c.accentSoft, c.accent)
            Text(text, Modifier.weight(1f, fill = false), style = MikaType.Hint, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(MikaIcons.Chevron, null, Modifier.size(12.dp).rotate(if (expanded) 90f else -90f), tint = c.ink3)
        }
    }
}

/** 展开后的一件:圆点、标题、阶段,下面最近几步(收尾了只有结果一句),最后「查看详细进展」。 */
@Composable
private fun TrayRow(view: DeskTaskView, onJump: () -> Unit) {
    val c = MikaTheme.colors
    val style = deskPhaseStyle(view)
    val steps = if (view.inProgress) (view.recent + listOfNotNull(view.line)).distinct().takeLast(3) else listOfNotNull(view.line)
    Column(
        Modifier.fillMaxWidth().testTag("desk-tray-row").clip(MikaShapes.Micro)
            .clickable(role = Role.Button, onClick = onJump).padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).background(style.dot, CircleShape))
            Text(
                view.title, Modifier.weight(1f), style = MikaType.Caption, fontWeight = FontWeight.SemiBold, color = c.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(style.label, style = MikaType.Hint, color = c.ink3, maxLines = 1)
        }
        steps.forEach { Text(it, Modifier.padding(start = 14.dp), style = MikaType.Hint, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        Text(stringResource(R.string.desk_tray_progress), Modifier.padding(start = 14.dp), style = MikaType.Hint, color = c.accent)
    }
}
