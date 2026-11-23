package com.wangzan101.mika.chat.ui


import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.components.DirectionalIcon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.TextButton
import com.wangzan101.mika.chat.DeleteBatch
import com.wangzan101.mika.i18n.trPlural
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.about.ui.UpdateDot

/** 侧栏顶上的小按钮:平时的「多选」和多选中的「取消」。 */
@Composable
internal fun HeaderAction(text: String, tag: String, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(tag), contentPadding = PaddingValues(horizontal = 10.dp)) {
        Text(text, style = MikaType.Label, color = color)
    }
}

/** 归档视图顶上的一行:返回普通侧栏,右边写着收起来了几条。 */
@Composable
internal fun ArchiveHeader(count: Int, onBack: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(NavShape).clickable(role = Role.Button, onClick = onBack)
            .padding(start = 12.dp, end = 20.dp, top = 8.dp, bottom = 4.dp)
            .testTag("drawer-archive-back"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(MikaIcons.Back, null, Modifier.size(16.dp), tint = c.ink2)
        Text(stringResource(R.string.drawer_archived), Modifier.weight(1f), style = SectionText, color = c.ink3)
        if (count > 0) {
            Text(stringResource(R.string.drawer_archived_count, count), style = MikaType.Hint, color = c.ink3, maxLines = 1, softWrap = false)
        }
    }
}

/** 归档视图里一条都没有。 */
@Composable
internal fun ArchiveEmpty() {
    Text(
        stringResource(R.string.drawer_archive_empty),
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 16.dp),
        style = MikaType.Body, color = MikaTheme.colors.ink3, textAlign = TextAlign.Center,
    )
}

/** 侧栏底部的「已归档」入口:进去看收起来的对话。 */
@Composable
internal fun ArchiveEntry(count: Int, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp)
            .clip(NavShape).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("drawer-archive"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(MikaIcons.Archive, null, Modifier.size(16.dp), tint = c.ink3)
        Text(stringResource(R.string.drawer_archived), Modifier.weight(1f), style = MikaType.Body, color = c.ink2, maxLines = 1)
        Text("$count", Modifier.testTag("drawer-archive-count"), style = MikaType.Hint, color = c.ink3, maxLines = 1, softWrap = false)
        DirectionalIcon(MikaIcons.Chevron, null, Modifier.size(10.dp), tint = c.ink3)
    }
}

/** 多选时的底部操作条:已选几条、全选 / 取消全选、删除;普通侧栏多一个「归档」,归档视图里换成「取消归档」。 */
@Composable
internal fun SelectBar(
    selectedCount: Int, allSelected: Boolean, onSelectAll: () -> Unit,
    archived: Boolean, onArchive: () -> Unit, onUnarchive: () -> Unit, onDelete: () -> Unit,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
            .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp).testTag("drawer-select-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            tr(R.string.drawer_selected_count, selectedCount), Modifier.weight(1f),
            style = MikaType.Label, color = c.ink2, maxLines = 1, softWrap = false,
        )
        TextButton(onClick = onSelectAll, modifier = Modifier.testTag("drawer-select-all")) {
            Text(stringResource(if (allSelected) R.string.drawer_select_none else R.string.drawer_select_all), style = MikaType.Label, color = c.accent)
        }
        if (archived) {
            TextButton(onClick = onUnarchive, enabled = selectedCount > 0, modifier = Modifier.testTag("drawer-unarchive-selected")) {
                Text(stringResource(R.string.drawer_unarchive), style = MikaType.Label, color = if (selectedCount > 0) c.accent else c.ink3)
            }
        } else {
            TextButton(onClick = onArchive, enabled = selectedCount > 0, modifier = Modifier.testTag("drawer-archive-selected")) {
                Text(stringResource(R.string.drawer_archive), style = MikaType.Label, color = if (selectedCount > 0) c.accent else c.ink3)
            }
        }
        TextButton(onClick = onDelete, enabled = selectedCount > 0, modifier = Modifier.testTag("drawer-delete-selected")) {
            Text(stringResource(R.string.confirm_delete), style = MikaType.Label, color = if (selectedCount > 0) c.bad else c.ink3)
        }
    }
}

/** 批量归档的结果:归档了几个;有因为在跑任务或是当前对话没动的,照实说几个没归档。 */
@Composable
internal fun ArchiveStatusBar(batch: ArchiveBatch) {
    val c = MikaTheme.colors
    val text = when {
        batch.archived <= 0 -> trPlural(R.plurals.drawer_archive_many_skipped, batch.skipped, batch.skipped)
        batch.skipped <= 0 -> trPlural(R.plurals.drawer_archive_many_done, batch.archived, batch.archived)
        else -> trPlural(R.plurals.drawer_archive_many_done, batch.archived, batch.archived) + " · " +
            trPlural(R.plurals.drawer_archive_many_skipped, batch.skipped, batch.skipped)
    }
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
            .padding(horizontal = 20.dp, vertical = 10.dp).testTag("drawer-archive-status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(MikaIcons.Archive, null, Modifier.size(14.dp), tint = if (batch.archived > 0) c.ok else c.ink3)
        Text(text, style = MikaType.Hint, color = c.ink2, maxLines = 2)
    }
}

/** 刚归档一条:底部写清收起了哪个对话,几秒内可以撤销。 */
@Composable
internal fun ArchiveUndoBar(undo: ArchiveUndo, onUndo: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
            .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp).testTag("drawer-archive-undo"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(MikaIcons.Archive, null, Modifier.size(14.dp), tint = c.ink3)
        Text(
            tr(R.string.drawer_archive_done, undo.title), Modifier.weight(1f),
            style = MikaType.Hint, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onUndo, modifier = Modifier.testTag("drawer-archive-undo-action")) {
            Text(stringResource(R.string.undo), style = MikaType.Label, color = c.accent)
        }
    }
}

/** 批量删除的进度和结果:删的时候是「正在删除 N 个对话…」,删完写成功几条、失败几条。 */
@Composable
internal fun DeleteStatusBar(batch: DeleteBatch) {
    val c = MikaTheme.colors
    val deleted = batch.done - batch.failed
    val text = when {
        !batch.finished -> trPlural(R.plurals.drawer_delete_progress, batch.total, batch.total)
        batch.failed == 0 -> trPlural(R.plurals.drawer_delete_done, deleted, deleted)
        else -> trPlural(R.plurals.drawer_delete_partial, deleted, deleted, batch.failed)
    }
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
            .padding(horizontal = 20.dp, vertical = 10.dp).testTag("drawer-delete-status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (batch.finished) {
            Icon(
                if (batch.failed == 0) MikaIcons.Check else MikaIcons.Exclaim, null, Modifier.size(14.dp),
                tint = if (batch.failed == 0) c.ok else c.bad,
            )
        }
        Text(text, style = MikaType.Hint, color = c.ink2, maxLines = 2)
    }
}

@Composable
internal fun NavEntry(
    icon: ImageVector, text: String, modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true, dot: Boolean = false, onClick: () -> Unit,
) {
    val c = MikaTheme.colors
    Row(
        modifier.height(46.dp).clip(NavShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, Modifier.size(MikaSize.Icon), tint = c.ink2)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = MikaType.Body, color = c.ink, maxLines = 1, softWrap = false)
            if (dot) UpdateDot()
        }
    }
}

/** 侧栏搜索框右边的新对话按钮:中性圆底 + 深色加号,和顶栏 ✚ 一个样子。 */
@Composable
internal fun NewConversationButton(enabled: Boolean, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(c.fill)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = stringResource(R.string.chat_new), onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.New, stringResource(R.string.chat_new), Modifier.size(18.dp), tint = c.ink)
    }
}

/** 「进行中」的段标题,右边一个「后台屏 ›」进后台屏页(只有有任务在跑时才有这一段)。 */
@Composable
internal fun RunningLabel(onScreens: () -> Unit) {
    val c = MikaTheme.colors
    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) { SectionLabel(stringResource(R.string.drawer_running)) }
        Row(
            Modifier.clip(NavShape).clickable(role = Role.Button, onClick = onScreens)
                .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 4.dp).testTag("drawer-screens"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(MikaIcons.Screens, null, Modifier.size(14.dp), tint = c.ink3)
            Text(stringResource(R.string.drawer_screens), style = MikaType.Hint, color = c.ink2)
            DirectionalIcon(MikaIcons.Chevron, null, Modifier.size(10.dp), tint = c.ink3)
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text, Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 6.dp),
        style = SectionText, color = MikaTheme.colors.ink3,
    )
}
