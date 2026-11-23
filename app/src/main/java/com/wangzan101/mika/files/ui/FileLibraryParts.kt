package com.wangzan101.mika.files.ui

import android.text.format.DateFormat as AndroidDateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.wangzan101.mika.chat.ui.sourceLabel
import com.wangzan101.mika.memory.ui.Row

internal val LibraryTitleStyle = MikaType.LargeTitle.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)

/** 文件库顶栏:返回、左对齐大标题、右边「多选」胶囊。 */
@Composable
internal fun LibraryTopBar(canSelect: Boolean, working: Boolean, onBack: () -> Unit, onSelect: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 64.dp).padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIconButton(MikaIcons.Back, stringResource(R.string.back), onBack)
        Text(
            stringResource(R.string.file_library_title), Modifier.weight(1f).padding(start = 4.dp),
            style = LibraryTitleStyle, color = c.ink, maxLines = 1,
        )
        Row(
            Modifier.clip(MikaShapes.Chip).background(c.fill)
                .clickable(enabled = canSelect && !working, role = Role.Button, onClick = onSelect)
                .testTag("file-library-select")
                .alpha(if (canSelect && !working) 1f else 0.4f)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(MikaIcons.ListChecks, null, Modifier.size(17.dp), tint = c.ink2)
            Text(stringResource(R.string.file_library_select), style = MikaType.Label, color = c.ink2)
        }
    }
}

/** 多选顶栏:左边取消、中间已选数量、右边全选 / 取消全选。 */
@Composable
internal fun LibrarySelectionTopBar(
    count: Int,
    allSelected: Boolean,
    canSelectAll: Boolean,
    working: Boolean,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
) {
    val c = MikaTheme.colors
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(60.dp)) {
        Text(
            if (count == 0) stringResource(R.string.file_library_select_title) else stringResource(R.string.file_library_selected_count, count),
            Modifier.align(Alignment.Center).padding(horizontal = 92.dp).testTag("file-library-selected-count"),
            style = MikaType.Title, color = c.ink, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.fillMaxSize().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                onClick = onCancel, enabled = !working,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.testTag("file-library-select-cancel"),
            ) {
                Text(stringResource(R.string.cancel), style = MikaType.Body, color = if (working) c.ink3 else c.accent)
            }
            TextButton(
                onClick = onSelectAll, enabled = !working && canSelectAll,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.testTag("file-library-select-all"),
            ) {
                Text(
                    stringResource(if (allSelected) R.string.file_library_select_none else R.string.file_library_select_all),
                    style = MikaType.Body, color = if (working || !canSelectAll) c.ink3 else c.accent, maxLines = 1,
                )
            }
        }
    }
}

/** 筛选胶囊:描边圆角、内容宽度;展开时浮出一张圆角菜单,选中项用强调色和勾标出。 */
@Composable
internal fun LibraryFilterPill(
    label: String,
    active: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = MikaTheme.colors
    val shape = RoundedCornerShape(MikaRadius.Bubble)
    Box(modifier) {
        Row(
            Modifier.heightIn(min = 40.dp).clip(shape)
                .border(1.dp, if (active || expanded) c.accent.copy(alpha = 0.55f) else c.edge, shape)
                .background(if (expanded) c.fill else Color.Transparent)
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                label, Modifier.weight(1f, fill = false),
                style = MikaType.Caption, color = if (active) c.accent else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Icon(MikaIcons.Chevron, null, Modifier.size(13.dp).rotate(90f), tint = if (active) c.accent else c.ink3)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            offset = DpOffset(0.dp, 6.dp),
            shape = MikaShapes.Card,
            containerColor = c.surface,
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(0.5.dp, c.edge),
        ) {
            Column(Modifier.padding(6.dp).widthIn(min = 200.dp, max = 280.dp), content = content)
        }
    }
}

/** 筛选菜单里的一行:选中时浅强调底、文字强调色、右端一个勾。 */
@Composable
internal fun FilterMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(MikaShapes.Chip)
            .background(if (selected) c.accentSoft else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label, Modifier.weight(1f), style = MikaType.Body,
            color = if (selected) c.accent else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (selected) Icon(MikaIcons.Check, null, Modifier.size(16.dp), tint = c.accent)
        else Spacer(Modifier.size(16.dp))
    }
}

@Composable
internal fun LibraryDayHeader(label: String) {
    Text(
        label, Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 13.dp, bottom = 5.dp),
        style = MikaType.Label, color = MikaTheme.colors.ink3,
    )
}

/** 日期分组标题,按系统语言显示月日。 */
@Composable
internal fun dayLabel(at: Long?): String {
    if (at == null) return stringResource(R.string.file_library_unknown_time)
    val locale = Locale.getDefault()
    val formatter = remember(locale) {
        DateTimeFormatter.ofPattern(AndroidDateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
    }
    return formatter.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
}

/**
 * 文件行:左边类型块(多选时换成勾选框),中间名称、类型/大小/来源、会话,
 * 右边「…」更多操作(多选时是「预览」)。长按进入多选。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryFileRow(
    item: LibraryRow,
    available: Boolean?,
    selected: Boolean,
    selecting: Boolean,
    working: Boolean,
    conversationLabel: String,
    /** 这个文件至少有一个来源对话已经归档:会话名旁边标出来。 */
    fromArchived: Boolean,
    sourceLabel: String,
    divider: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggle: () -> Unit,
    onMore: () -> Unit,
) {
    val c = MikaTheme.colors
    val shape = MikaShapes.Chip
    Column(Modifier.fillMaxWidth()) {
        if (divider && !selected) {
            Box(Modifier.fillMaxWidth().padding(start = 70.dp, end = 4.dp).height(0.5.dp).background(c.hair))
        }
        Row(
            Modifier.fillMaxWidth().clip(shape)
                .then(if (selected) Modifier.background(c.accentSoft) else Modifier)
                .then(
                    if (selecting) Modifier.toggleable(
                        value = selected, enabled = !working, role = Role.Checkbox, onValueChange = { onToggle() },
                    ) else Modifier.combinedClickable(
                        enabled = !working, onClick = onOpen, onLongClick = onLongPress,
                    )
                )
                .testTag("file-library-row")
                .padding(start = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Box(Modifier.size(width = 48.dp, height = 54.dp), contentAlignment = Alignment.Center) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = null,
                        modifier = Modifier.size(23.dp).testTag("file-library-check"),
                        colors = CheckboxDefaults.colors(
                            checkedColor = c.accent,
                            uncheckedColor = c.ink3,
                            checkmarkColor = c.onAccent,
                        ),
                    )
                }
            } else {
                FileTypeTile(item.name)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                Text(item.name, style = MikaType.BodyStrong, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        fileBadge(item.name).second,
                        item.size.takeIf { it.isNotBlank() },
                        sourceLabel,
                        if (available == false) stringResource(R.string.file_library_missing) else null,
                    ).joinToString(" · "),
                    Modifier.padding(top = 3.dp),
                    style = MikaType.Hint, color = if (available == false) c.bad else c.ink3,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (conversationLabel.isNotBlank()) {
                    Row(
                        Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            conversationLabel, Modifier.weight(1f, fill = false),
                            style = MikaType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (fromArchived) ArchivedTag()
                    }
                }
            }
            if (selecting) {
                Box(
                    Modifier.width(56.dp).height(48.dp).clip(MikaShapes.Tile).clickable(enabled = !working, onClick = onOpen),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.file_library_preview), style = MikaType.Label, color = c.accent)
                }
            } else {
                Box(
                    Modifier.width(56.dp).height(48.dp).clip(MikaShapes.Tile).clickable(enabled = !working, onClick = onMore),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MikaIcons.More, stringResource(R.string.more), Modifier.size(20.dp), tint = c.ink3)
                }
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

/** 文件来自已归档的对话:会话名旁边的小标签,和侧栏里的归档图标一个意思。 */
@Composable
private fun ArchivedTag() {
    val c = MikaTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(MikaRadius.BubbleTail)).background(c.accentSoft).padding(horizontal = 5.dp, vertical = 1.dp)
            .testTag("file-library-archived"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(MikaIcons.Archive, null, Modifier.size(10.dp), tint = c.ink3)
        Text(stringResource(R.string.drawer_archived), style = MikaType.Hint, color = c.ink3, maxLines = 1)
    }
}

/** 文件类型块:上面一个图形,下面扩展名。 */
@Composable
internal fun FileTypeTile(name: String) {
    val c = MikaTheme.colors
    val (icon, label) = fileBadge(name)
    Column(
        Modifier.size(width = 48.dp, height = 54.dp).clip(MikaShapes.Chip).background(c.fill),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = c.ink2)
        Text(
            label, Modifier.padding(top = 2.dp),
            style = MikaType.Micro.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
            color = c.ink2, maxLines = 1,
        )
    }
}

/** 文件块的图形和扩展名;图片、代码、文档各一种图形。 */
internal fun fileBadge(name: String): Pair<ImageVector, String> {
    val ext = name.substringAfterLast('.', "").uppercase(Locale.ROOT)
    val icon = if (LibraryFileType.of(name) == LibraryFileType.Image) MikaIcons.Photo else when (ext) {
        "HTML", "HTM", "CSS", "JS", "JSX", "TS", "TSX", "JSON", "KT", "KTS", "JAVA", "PY", "SH",
        "XML", "YAML", "YML", "TOML", "GO", "RS", "RB", "C", "CPP", "H" -> MikaIcons.Code
        else -> MikaIcons.Doc
    }
    return icon to ext.take(4).ifBlank { "FILE" }
}

/** 多选底部操作条:次按钮分享,主按钮把所选加入待发栏;进行中显示进度。 */
@Composable
internal fun LibraryBatchBar(
    count: Int,
    working: Boolean,
    sharing: Boolean,
    done: Int,
    total: Int,
    onShare: () -> Unit,
    onAdd: () -> Unit,
) {
    val c = MikaTheme.colors
    val enabled = count > 0 && !working
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.hair))
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)
                .testTag("file-library-select-bar"),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.height(48.dp).clip(MikaShapes.Button).background(c.fill)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onShare)
                    .testTag("file-library-share-selected")
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(MikaIcons.Share, null, Modifier.size(18.dp), tint = if (enabled) c.ink else c.ink3)
                Text(
                    stringResource(R.string.file_library_share), style = MikaType.BodyStrong,
                    color = if (enabled) c.ink else c.ink3,
                )
            }
            Row(
                Modifier.weight(1f).height(48.dp).clip(MikaShapes.Button)
                    .background(if (enabled) c.accent else c.accent.copy(alpha = 0.35f))
                    .clickable(enabled = enabled, role = Role.Button, onClick = onAdd)
                    .testTag("file-library-add-selected")
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (working) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.onAccent)
                    Text(
                        stringResource(if (sharing) R.string.file_library_sharing else R.string.file_library_adding, done, total),
                        Modifier.padding(start = 8.dp),
                        style = MikaType.Label, color = c.onAccent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Icon(
                        MikaIcons.ListPlus, null, Modifier.size(18.dp),
                        tint = if (enabled) c.onAccent else c.onAccent.copy(alpha = 0.6f),
                    )
                    Text(
                        if (count > 0) stringResource(R.string.file_library_add_selected, count) else stringResource(R.string.file_library_add),
                        Modifier.padding(start = 7.dp),
                        style = MikaType.BodyStrong,
                        color = if (enabled) c.onAccent else c.onAccent.copy(alpha = 0.6f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
