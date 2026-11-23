package com.wangzan101.mika.chat.ui


import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.ExperimentalFoundationApi
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import com.wangzan101.mika.chat.Project
import com.wangzan101.mika.chat.ProjectDeleteImpact
import com.wangzan101.mika.i18n.trPlural
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 侧栏「项目」段头与「新建项目」入口。 */
@Composable
internal fun ProjectsHeader(enabled: Boolean, onNewProject: () -> Unit) {
    val c = MikaTheme.colors
    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) { SectionLabel(stringResource(R.string.drawer_projects)) }
        Row(
            Modifier.clip(NavShape).clickable(enabled = enabled, role = Role.Button, onClick = onNewProject)
                .alpha(if (enabled) 1f else 0.4f)
                .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 4.dp).testTag("drawer-new-project"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(MikaIcons.Plus, null, Modifier.size(13.dp), tint = c.ink3)
            Text(stringResource(R.string.drawer_new_project), style = MikaType.Hint, color = c.ink2)
        }
    }
}

/** 侧栏里一个项目的可折叠分组头:点按展开/收起，右侧加号新建项目内对话，长按打开项目操作菜单。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProjectGroupHeader(
    project: Project,
    count: Int,
    collapsed: Boolean,
    canCreate: Boolean,
    onToggle: () -> Unit,
    onNewChat: () -> Unit,
    onActions: (Rect) -> Unit,
) {
    val c = MikaTheme.colors
    val haptic = LocalHapticFeedback.current
    val bounds = remember { mutableStateOf(Rect.Zero) }
    Row(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth()
            .onGloballyPositioned { bounds.value = Rect(it.positionInWindow(), it.size.toSize()) }
            .clip(NavShape)
            .combinedClickable(
                onLongClickLabel = stringResource(R.string.drawer_project_actions),
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onActions(bounds.value) },
                onClick = onToggle,
            )
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .testTag("project-header"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            MikaIcons.Chevron, null,
            Modifier.size(12.dp).rotate(if (collapsed) 0f else 90f),
            tint = c.ink3,
        )
        Icon(MikaIcons.Folder, null, Modifier.size(15.dp), tint = c.ink2)
        Text(project.name, Modifier.weight(1f), style = ConversationTitle, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count > 0) Text("$count", style = MikaType.Hint, color = c.ink3, maxLines = 1, softWrap = false)
        Box(
            Modifier.size(26.dp).clip(CircleShape)
                .clickable(enabled = canCreate, role = Role.Button, onClickLabel = stringResource(R.string.drawer_project_new_chat), onClick = onNewChat)
                .alpha(if (canCreate) 1f else 0.4f)
                .testTag("project-new-chat"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MikaIcons.Plus, stringResource(R.string.drawer_project_new_chat), Modifier.size(14.dp), tint = c.ink2)
        }
    }
}

/** 新建 / 重命名项目的输入弹窗。 */
@Composable
internal fun ProjectNameDialog(
    title: String,
    initial: String,
    confirmTag: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(initial) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val ok = value.text.isNotBlank()
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            KeyboardDismissArea {
                OutlinedTextField(
                    value, { value = it },
                    Modifier.fillMaxWidth().focusRequester(focus).keepsKeyboard().testTag("project-name-field"),
                    placeholder = { Text(stringResource(R.string.drawer_project_name_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ok) onSave(value.text.trim()) }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.text.trim()) }, Modifier.testTag(confirmTag), enabled = ok) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 删除项目的确认弹窗:写明会删除项目内全部对话与共享工作区文件。 */
@Composable
internal fun DeleteProjectDialog(
    project: Project,
    impact: ProjectDeleteImpact,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val body = listOfNotNull(
        stringResource(R.string.drawer_project_delete_body, project.name),
        if (impact.runningTasks > 0) trPlural(R.plurals.drawer_delete_many_running, impact.runningTasks, impact.runningTasks) else null,
        if (impact.schedules > 0) trPlural(R.plurals.drawer_delete_many_schedules, impact.schedules, impact.schedules) else null,
    ).joinToString("\n")
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_project_delete_title)) },
        text = { Text(body, Modifier.testTag("delete-project-body")) },
        confirmButton = {
            TextButton(onClick = onConfirm, Modifier.testTag("confirm-delete-project")) {
                Text(stringResource(R.string.confirm_delete), color = MikaTheme.colors.bad)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
