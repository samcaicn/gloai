package com.wangzan101.mika.chat.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.frontdesk.DeskStep
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.frontdesk.DeskTaskView
import com.wangzan101.mika.ui.components.GlassBackdrop
import com.wangzan101.mika.ui.components.GlassSurface
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

private val TrayShape = RoundedCornerShape(16.dp)

/** 在办的几件按先后排:要你处理的在最前,再是在做的(最近动过的在前),排队的最后。 */
internal fun trayOrder(tasks: List<DeskTaskView>): List<DeskTaskView> =
    tasks.filter { it.inProgress }.sortedWith(
        compareBy<DeskTaskView> {
            when (it.state) {
                DeskTaskPhase.NeedsYou -> 0
                DeskTaskPhase.Running -> 1
                else -> 2
            }
        }.thenByDescending { it.updatedAt },
    )

/**
 * 输入栏上方的一条玻璃进度条:前台派出去的事有在做、排队或要你处理时才出现。
 * 收起时一行:状态点(在做时蓝圈外扩、要你处理时橙点呼吸)、最前那件的名字、此刻这一步、用时、还有几件、箭头。
 * 展开是每件一段:名字和阶段、交给后台的原话、一条竖线的步骤(做完的灰点、此刻的蓝点、在等你的橙点,各带用时),
 * 最下面「停止」和「查看详细过程」。
 */
@Composable
internal fun DeskTray(
    tasks: List<DeskTaskView>, reduceMotion: Boolean, backdrop: GlassBackdrop?,
    onStop: (String) -> Unit, onDetail: (String) -> Unit,
) {
    val active = trayOrder(tasks)
    if (active.isEmpty()) return
    val c = MikaTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    val head = active.first()
    val needsYou = head.state == DeskTaskPhase.NeedsYou
    val now by produceState(System.currentTimeMillis(), active.any { it.startedAt != null }) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    GlassSurface(Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("desk-tray"), backdrop, TrayShape) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().height(46.dp)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(if (expanded) R.string.desk_tray_collapse else R.string.desk_tray_expand),
                    ) { expanded = !expanded }
                    .padding(start = 14.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusDot(head.state, reduceMotion)
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        head.title, Modifier.widthIn(max = 150.dp), style = TrayText, fontWeight = FontWeight.Medium, color = c.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        currentStep(head), style = TrayText, color = if (needsYou) c.warn else c.ink2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (active.size > 1) {
                    Text(
                        "+${active.size - 1}",
                        Modifier.background(c.neutralSoft, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp),
                        style = MikaType.Micro, color = c.ink2, maxLines = 1,
                    )
                }
                head.startedAt?.let { Text(ChatCopy.clock((now - it).coerceAtLeast(0L)), style = ClockText, color = c.ink3, maxLines = 1) }
                Icon(MikaIcons.Chevron, null, Modifier.size(16.dp).rotate(if (expanded) 90f else -90f), tint = c.ink3)
            }
            if (expanded) {
                HorizontalDivider(thickness = 0.5.dp, color = c.hair)
                Column(Modifier.fillMaxWidth().heightIn(max = 330.dp).verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 6.dp)) {
                    active.forEachIndexed { i, view ->
                        if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = c.hair)
                        TrayTask(view, { onStop(view.taskId) }, { onDetail(view.taskId) })
                    }
                }
            }
        }
    }
}

private val TrayText = MikaType.Caption.copy(fontSize = 13.sp, lineHeight = 18.sp)

/** 此刻这一步:任务报的这一句,没有就是记下的最后一步,再没有就是阶段名。 */
@Composable
private fun currentStep(view: DeskTaskView): String =
    view.line ?: view.steps.lastOrNull()?.text ?: deskPhaseStyle(view).label

/** 展开后的一件。 */
@Composable
private fun TrayTask(view: DeskTaskView, onStop: () -> Unit, onDetail: () -> Unit) {
    val c = MikaTheme.colors
    val style = deskPhaseStyle(view)
    Column(Modifier.fillMaxWidth().testTag("desk-tray-row").padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                view.title, Modifier.weight(1f), style = MikaType.BodyStrong.copy(fontSize = 15.sp, lineHeight = 20.sp), color = c.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                style.label, Modifier.background(style.bg, RoundedCornerShape(7.dp)).padding(horizontal = 7.dp),
                style = MikaType.Micro, fontWeight = FontWeight.Medium, color = style.fg, maxLines = 1,
            )
        }
        view.goal?.takeIf { it.isNotBlank() }?.let {
            Text(it, Modifier.padding(top = 2.dp), style = MikaType.Hint, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (view.steps.isNotEmpty()) {
            Column(Modifier.padding(top = 8.dp)) {
                view.steps.forEachIndexed { i, step ->
                    val last = i == view.steps.lastIndex
                    val kind = when {
                        !last -> StepKind.Done
                        view.state == DeskTaskPhase.NeedsYou -> StepKind.Waiting
                        else -> StepKind.Current
                    }
                    StepRow(step, view.startedAt, kind, last)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.desk_tray_stop),
                Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button, onClick = onStop).padding(vertical = 6.dp),
                style = TrayText, fontWeight = FontWeight.Medium, color = c.bad,
            )
            Text(
                stringResource(R.string.desk_tray_detail),
                Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button, onClick = onDetail).padding(vertical = 6.dp),
                style = TrayText, fontWeight = FontWeight.Medium, color = c.accent,
            )
        }
    }
}

private enum class StepKind { Done, Current, Waiting }

/** 竖线上的一步:左边圆点(下面一截竖线连到下一步),中间这一步的话,右边从开始到这一步的用时。 */
@Composable
private fun StepRow(step: DeskStep, startedAt: Long?, kind: StepKind, last: Boolean) {
    val c = MikaTheme.colors
    val (dot, halo, ink) = when (kind) {
        StepKind.Done -> Triple(c.ink3.copy(alpha = 0.55f), Color.Transparent, c.ink2)
        StepKind.Current -> Triple(c.accent, c.accentSoft, c.ink)
        StepKind.Waiting -> Triple(c.warnSolid, c.warnSoft, c.warn)
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 3.dp, bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.width(8.dp).fillMaxHeight().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(7.dp).drawBehind { if (halo != Color.Transparent) drawCircle(halo, radius = size.minDimension / 2 + 3.dp.toPx()) }
                    .background(dot, CircleShape),
            )
            if (!last) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.width(1.dp).weight(1f).background(c.hair))
            }
        }
        Text(step.text, Modifier.weight(1f), style = TrayText, color = ink)
        startedAt?.let {
            Text(ChatCopy.clock((step.atMs - it).coerceAtLeast(0L)), style = ClockText.copy(fontSize = 11.5.sp, lineHeight = 18.sp), color = c.ink3)
        }
    }
}

/** 进度条最前面的状态点:在做时蓝点外一圈往外扩散,要你处理时橙点带一圈呼吸,排队时灰点;关了动画时静止。 */
@Composable
private fun StatusDot(phase: DeskTaskPhase, reduceMotion: Boolean) {
    val c = MikaTheme.colors
    val color = when (phase) {
        DeskTaskPhase.NeedsYou -> c.warnSolid
        DeskTaskPhase.Running -> c.accent
        else -> c.ink3
    }
    val t = if (reduceMotion || phase == DeskTaskPhase.Queued) 0f else {
        val spec = if (phase == DeskTaskPhase.Running) infiniteRepeatable<Float>(tween(1600, easing = LinearEasing))
        else infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse)
        rememberInfiniteTransition(label = "tray-dot").animateFloat(0f, 1f, spec, label = "tray-dot-t").value
    }
    Box(
        Modifier.size(8.dp).drawBehind {
            val r = size.minDimension / 2
            when {
                reduceMotion -> Unit
                phase == DeskTaskPhase.Running -> drawCircle(
                    color.copy(alpha = 0.7f * (1f - t)), radius = (r + 4.dp.toPx()) * (0.5f + 0.9f * t), style = Stroke(2.dp.toPx()),
                )
                phase == DeskTaskPhase.NeedsYou -> drawCircle(color.copy(alpha = 0.25f - 0.2f * t), radius = (r + 4.dp.toPx()) * (1f + 0.3f * t))
            }
        }.background(color, CircleShape),
    )
}
