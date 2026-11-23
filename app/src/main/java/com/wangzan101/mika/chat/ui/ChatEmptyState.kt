package com.wangzan101.mika.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.settings.RecommendedTask
import com.wangzan101.mika.ui.theme.MikaColors
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import java.time.LocalTime

/**
 * 空对话:问候 + 一句价值主张 + 推荐任务卡片。点一条把完整提示词填进输入框,改好了再发;
 * 一条都没有时不显示。放不下一屏时可以滚动。
 */
@Composable
internal fun EmptyState(conversation: String?, tasks: List<RecommendedTask>, modifier: Modifier = Modifier, onPick: (RecommendedTask) -> Unit) {
    val c = MikaTheme.colors
    val hour = remember(conversation) { LocalTime.now().hour }
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().padding(horizontal = ComposerInset, vertical = 24.dp)) {
            Text(ChatCopy.greeting(hour), style = MikaType.LargeTitle, color = c.ink)
            Text(ChatCopy.EMPTY_VALUE, Modifier.padding(top = 11.dp), style = EmptyLead, color = c.ink2)
            if (tasks.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tasks.forEach { task -> TaskCard(task, onPick) }
                }
            }
        }
    }
}

/** 推荐任务卡片:左边类型图标,右边标题和提示词首行。整张可点,点它把完整提示词填进输入框。 */
@Composable
private fun TaskCard(task: RecommendedTask, onPick: (RecommendedTask) -> Unit) {
    val c = MikaTheme.colors
    val look = taskLook(ChatCopy.kindOf(task.prompt), c)
    val subtitle = task.prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    Row(
        Modifier.fillMaxWidth().clip(MikaShapes.Card).background(c.surface).border(0.5.dp, c.edge, MikaShapes.Card)
            .clickable(role = Role.Button) { onPick(task) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(MikaShapes.Chip)
                .background(look.tint.copy(alpha = if (c.isDark) 0.18f else 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(look.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = look.tint)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(task.title, style = MikaType.BodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty() && subtitle != task.title) {
                Text(
                    subtitle, Modifier.padding(top = 2.dp),
                    style = MikaType.Caption, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 推荐任务的类型 → 图标和颜色;自己加的任务没有类型,用星光配中性色。 */
private data class TaskLook(val icon: ImageVector, val tint: Color)

private fun taskLook(kind: ChatCopy.ExampleKind?, c: MikaColors): TaskLook = when (kind) {
    ChatCopy.ExampleKind.Web -> TaskLook(MikaIcons.Globe, c.series[0])
    ChatCopy.ExampleKind.Follow -> TaskLook(MikaIcons.Person, c.series[1])
    ChatCopy.ExampleKind.GroupChat -> TaskLook(MikaIcons.Message, c.series[2])
    ChatCopy.ExampleKind.Alarm -> TaskLook(MikaIcons.Alarm, c.ink)
    ChatCopy.ExampleKind.Schedule -> TaskLook(MikaIcons.Calendar, c.accent)
    null -> TaskLook(MikaIcons.Spark, c.ink2)
}

// ---------- 一问一答 ----------
