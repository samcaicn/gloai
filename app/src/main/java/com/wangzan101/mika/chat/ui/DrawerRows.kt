package com.wangzan101.mika.chat.ui


import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.ExperimentalFoundationApi
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import com.wangzan101.mika.chat.DeleteImpact
import com.wangzan101.mika.i18n.trPlural
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.toSize
import com.wangzan101.mika.ui.components.keepsKeyboard
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.browser.ui.PageAskCopy
import com.wangzan101.mika.ui.Route

/**
 * 一条对话:点开它;长按(带一下振动)在原地弹出它的操作菜单(置顶、改名、删除)。换不了对话时(任务进行中、没开并行)点不开,长按照样能用。
 * 还没存进对话列表的(没有编号)没东西可改,不响应长按。置顶了的标题前有个小图钉。
 * 多选模式([selecting])下点一行是勾选/取消,左边出现勾选框;没存过的草稿不参与。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationEntry(
    item: DrawerConversation,
    onOpen: (DrawerConversation) -> Unit,
    onActions: (DrawerConversation, Rect) -> Unit,
    /** 搜索结果里高亮的关键词;普通列表为空。 */
    terms: List<String> = emptyList(),
    selecting: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: (DrawerConversation) -> Unit = {},
    /** 标题前显不显示归档小图标;归档视图里每一行都是归档的,不用再标。 */
    showArchivedMark: Boolean = true,
    /** 非项目分组列表(进行中/搜索/归档)里把所属项目用小文件夹标出来。 */
    showProjectMark: Boolean = false,
) {
    val c = MikaTheme.colors
    val deletable = item.id != null
    val selectable = selecting && deletable
    val haptic = LocalHapticFeedback.current
    // 这一行在窗口里的位置和大小(不裁掉滚出列表的部分),菜单照它摆出抬起的那一行;只在回调里读,不引起重组
    val bounds = remember { mutableStateOf(Rect.Zero) }
    val row: @Composable () -> Unit = {
        Column(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth()
                .onGloballyPositioned { bounds.value = Rect(it.positionInWindow(), it.size.toSize()) }
                // 侧滑时前景必须遮住归档底层，不能让按钮透过标题和时间。
                .background(c.bg, NavShape)
                .then(if (item.current) Modifier.cardSurface(c, NavShape) else Modifier)
                .clip(NavShape)
                .then(
                    when {
                        selectable -> Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggleSelect(item) })
                        // 多选模式里没存过的草稿不响应,免得点一下退出侧栏
                        selecting -> Modifier
                        deletable -> Modifier.combinedClickable(
                            onClickLabel = null,
                            onLongClickLabel = stringResource(R.string.drawer_actions),
                            // 和系统里长按一样振一下
                            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onActions(item, bounds.value) },
                            onClick = { if (item.enabled) onOpen(item) },
                        )
                        else -> Modifier.clickable(enabled = item.enabled, onClick = { onOpen(item) })
                    },
                )
                .testTag("conversation-row")
                .alpha(if (item.enabled || selectable) 1f else 0.45f)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (selectable) SelectMark(selected)
                Box(Modifier.weight(1f)) { ConversationRowContent(item, terms, showArchivedMark, showProjectMark) }
            }
        }
    }
    row()
}

/** 多选模式下每行左边的勾选框:没选是空心圆,选了是实心圆加一个白勾。 */
@Composable
private fun SelectMark(selected: Boolean) {
    val c = MikaTheme.colors
    Box(
        Modifier.size(20.dp).clip(CircleShape)
            .then(if (selected) Modifier.background(c.accent) else Modifier)
            .border(1.5.dp, if (selected) c.accent else c.edge, CircleShape)
            .testTag("conversation-check"),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(MikaIcons.Check, null, Modifier.size(13.dp), tint = c.onAccent)
    }
}

/** 一条对话里面的东西:进行中的小圆点、图钉、标题、时间,有副标题时下面一行。侧栏里那一行和长按抬起的那一张都用它。 */
@Composable
internal fun ConversationRowContent(
    item: DrawerConversation,
    terms: List<String> = emptyList(),
    showArchivedMark: Boolean = true,
    showProjectMark: Boolean = false,
) {
    val c = MikaTheme.colors
    val title = remember(item.title, terms, c.accent) { highlighted(item.title, terms, c.accent) }
    val showProjectName = showProjectMark && !item.projectName.isNullOrBlank()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (item.running) Box(Modifier.size(7.dp).background(c.accent, CircleShape))
            if (item.pinned) Icon(MikaIcons.Pin, stringResource(R.string.drawer_pinned_mark), Modifier.size(14.dp).testTag("conversation-pin"), tint = c.ink3)
            if (showProjectMark && item.projectId != null && !showProjectName) Icon(MikaIcons.Folder, stringResource(R.string.chat_project_shared), Modifier.size(14.dp).testTag("conversation-project"), tint = c.ink3)
            if (item.branch) Icon(MikaIcons.Route, stringResource(R.string.chat_branch_origin), Modifier.size(14.dp).testTag("conversation-branch"), tint = c.ink3)
            if (item.archived && showArchivedMark) Icon(MikaIcons.Archive, stringResource(R.string.drawer_archived), Modifier.size(14.dp).testTag("conversation-archived"), tint = c.ink3)
            Text(title, Modifier.weight(1f, fill = item.site == null), style = ConversationTitle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.site != null) {
                Text(
                    PageAskCopy.WEB, Modifier.background(c.accentSoft, RoundedCornerShape(MikaRadius.BubbleTail)).padding(horizontal = 6.dp).testTag("conversation-web"),
                    style = ConversationTag, color = c.accent, maxLines = 1,
                )
                Box(Modifier.weight(1f))
            }
            if (item.time.isNotEmpty()) {
                Text(item.time, Modifier.padding(start = 5.dp).testTag("conversation-time"), style = ConversationTime, color = c.ink3, maxLines = 1, softWrap = false)
            }
        }
        val sub = item.subtitle.ifEmpty { item.site.orEmpty() }
        if (showProjectName || sub.isNotEmpty()) {
            Row(
                Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (showProjectName) {
                    Icon(MikaIcons.Folder, stringResource(R.string.chat_project_shared), Modifier.size(12.dp).testTag("conversation-project"), tint = c.ink2)
                    Text(
                        item.projectName!!,
                        Modifier.weight(1f, fill = false),
                        style = ConversationTime, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (sub.isNotEmpty()) Text("·", style = ConversationTime, color = c.ink3)
                }
                if (sub.isNotEmpty()) {
                    val styled = remember(sub, terms, c.accent) { highlighted(sub, terms, c.accent) }
                    Text(styled, Modifier.weight(1f, fill = !showProjectName), style = ConversationTime, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 给对话改名:输入框里是现在的标题(整段选中,直接打字就替换);空的存不了,两头空白去掉。 */
@Composable
internal fun RenameConversationDialog(title: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember(title) { mutableStateOf(TextFieldValue(title, TextRange(0, title.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val ok = value.text.isNotBlank()
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_rename_title)) },
        text = {
            KeyboardDismissArea {
                OutlinedTextField(
                    value, { value = it }, Modifier.fillMaxWidth().focusRequester(focus).keepsKeyboard().testTag("rename-conversation-field"),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ok) onSave(value.text.trim()) }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.text.trim()) }, Modifier.testTag("confirm-rename-conversation"), enabled = ok) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * 删对话前的确认:写明删的是哪个对话;有任务在跑会停掉,绑着定时任务则写明计划会保留。
 * 和别处删东西的确认一样:右边红字「删除」,左边「取消」。
 */
@Composable
internal fun DeleteConversationDialog(title: String, impact: DeleteImpact, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val body = listOfNotNull(
        stringResource(R.string.drawer_delete_body, title),
        if (impact.runningTask) stringResource(R.string.drawer_delete_running) else null,
        if (impact.schedules > 0) trPlural(R.plurals.drawer_delete_schedules, impact.schedules) else null,
    ).joinToString("\n")
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_delete_title)) },
        text = { Text(body, Modifier.testTag("delete-conversation-body")) },
        confirmButton = {
            TextButton(onClick = onConfirm, Modifier.testTag("confirm-delete-conversation")) {
                Text(stringResource(R.string.confirm_delete), color = MikaTheme.colors.bad)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * 批量删对话前的确认:写明将删除几个对话;其中有任务在跑的会停掉,绑着定时任务的写明计划会保留。
 * 和单条删除的确认一样:右边红字「删除」,左边「取消」。
 */
@Composable
internal fun DeleteManyConversationsDialog(
    count: Int, runningTasks: Int, schedules: Int,
    onConfirm: () -> Unit, onDismiss: () -> Unit,
) {
    val body = listOfNotNull(
        trPlural(R.plurals.drawer_delete_many_body, count, count),
        if (runningTasks > 0) trPlural(R.plurals.drawer_delete_many_running, runningTasks, runningTasks) else null,
        if (schedules > 0) trPlural(R.plurals.drawer_delete_many_schedules, schedules, schedules) else null,
    ).joinToString("\n")
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_delete_many_title)) },
        text = { Text(body, Modifier.testTag("delete-many-conversation-body")) },
        confirmButton = {
            TextButton(onClick = onConfirm, Modifier.testTag("confirm-delete-many-conversation")) {
                Text(stringResource(R.string.confirm_delete), color = MikaTheme.colors.bad)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, Modifier.testTag("dismiss-delete-many-conversation")) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
