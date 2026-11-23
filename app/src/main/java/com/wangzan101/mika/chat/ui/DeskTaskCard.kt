package com.wangzan101.mika.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.frontdesk.DeskTaskView
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 一个阶段的画法:小胶囊里的字、字色、胶囊底色,列表和小标签里的圆点颜色。 */
internal data class DeskPhaseStyle(val label: String, val fg: Color, val bg: Color, val dot: Color)

@Composable
internal fun deskPhaseStyle(view: DeskTaskView): DeskPhaseStyle {
    val c = MikaTheme.colors
    return when (view.state) {
        DeskTaskPhase.Running -> DeskPhaseStyle(stringResource(R.string.desk_phase_running), c.accent, c.accentSoft, c.accent)
        DeskTaskPhase.Queued -> DeskPhaseStyle(
            view.position?.let { stringResource(R.string.desk_phase_queued_at, it) } ?: stringResource(R.string.desk_phase_queued),
            c.ink2, c.fill, c.ink3,
        )
        DeskTaskPhase.NeedsYou -> DeskPhaseStyle(stringResource(R.string.desk_phase_needs_you), c.warn, c.warnSoft, c.warn)
        DeskTaskPhase.Done -> DeskPhaseStyle(stringResource(R.string.desk_phase_done), c.ok, c.okSoft, c.ok)
        DeskTaskPhase.Failed -> DeskPhaseStyle(stringResource(R.string.desk_phase_failed), c.bad, c.badSoft, c.bad)
        DeskTaskPhase.Cancelled -> DeskPhaseStyle(stringResource(R.string.desk_phase_cancelled), c.ink3, c.fill, c.ink3)
    }
}

/** 还算「在办」的阶段:在做、排队、要你处理。 */
internal val DeskTaskView.inProgress: Boolean
    get() = state == DeskTaskPhase.Running || state == DeskTaskPhase.Queued || state == DeskTaskPhase.NeedsYou

/**
 * 前台派出去的一件任务在对话里的卡片:标题、阶段、当前一句、最近几步;收尾了只留结果一句和「查看详情」。
 * 点卡片进这件任务自己的对话(要确认、要回答、看全部步骤都在那里)。[view] 为 null 时前台还没接回这件,只给能点开的一行。
 */
@Composable
internal fun DeskTaskCard(view: DeskTaskView?, reduceMotion: Boolean, onOpen: () -> Unit) {
    val c = MikaTheme.colors
    val finished = view != null && !view.inProgress
    Box(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(
            Modifier.fillMaxWidth().testTag("desk-card")
                .clip(MikaShapes.Card).background(c.surface).border(1.dp, c.hair, MikaShapes.Card)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.desk_card_open), onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = if (finished) 8.dp else 10.dp),
        ) {
            if (view == null) {
                Text(stringResource(R.string.desk_card_open), style = MikaType.Label, color = c.ink2)
                return@Column
            }
            val style = deskPhaseStyle(view)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (view.state == DeskTaskPhase.Running) Spinner(Modifier.size(12.dp), !reduceMotion, c.accentSoft, c.accent)
                Text(
                    view.title, Modifier.weight(1f), style = MikaType.Label, fontWeight = FontWeight.SemiBold, color = c.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                PhasePill(style)
            }
            view.line?.let { Text(it, Modifier.padding(top = 4.dp), style = MikaType.Caption, color = c.ink2, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            if (finished) {
                Text(stringResource(R.string.desk_card_details), Modifier.padding(top = 4.dp), style = MikaType.Hint, color = c.accent)
            } else {
                // 最近几步里最新的一条就是上面那句,不重复
                val earlier = view.recent.filter { it != view.line }
                if (earlier.isNotEmpty()) Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    earlier.forEach { Text(it, style = MikaType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

@Composable
private fun PhasePill(style: DeskPhaseStyle) {
    Text(
        style.label, Modifier.background(style.bg, MikaShapes.Pill).padding(horizontal = 8.dp, vertical = 1.dp),
        style = MikaType.Micro, color = style.fg, maxLines = 1,
    )
}

/** 汇报那一行下面的小标签「标题 · 阶段 ›」:点了滚到这件任务的卡片。 */
@Composable
internal fun DeskRefChip(view: DeskTaskView, onJump: () -> Unit) {
    val c = MikaTheme.colors
    val style = deskPhaseStyle(view)
    Row(
        Modifier.padding(top = 6.dp).testTag("desk-ref").clip(MikaShapes.Chip).background(c.fill)
            .clickable(role = Role.Button, onClick = onJump).padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(7.dp).background(style.dot, CircleShape))
        Text(
            view.title + " · " + style.label + " ›", Modifier.weight(1f, fill = false),
            style = MikaType.Hint, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
