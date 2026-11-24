package com.wangzan101.mika.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.frontdesk.DeskTaskView
import com.wangzan101.mika.ui.components.GlassBackdrop
import com.wangzan101.mika.ui.components.GlassSurface
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 聊天页右上角 ⋯:新对话、通话记录、这个对话里的事、通话设置。 */
@Composable
internal fun ChatMoreMenu(
    backdrop: GlassBackdrop?,
    canSwitch: Boolean,
    onNew: () -> Unit,
    onRecords: () -> Unit,
    onTasks: () -> Unit,
    onCallSettings: () -> Unit,
) {
    val c = MikaTheme.colors
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.more)
    Box {
        GlassSurface(modifier = Modifier.size(MikaSize.IconButton), backdrop = backdrop, shape = CircleShape) {
            Box(
                Modifier.fillMaxSize().clip(CircleShape).clickable(onClickLabel = label) { open = true }.testTag("chat-more"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MikaIcons.More, label, Modifier.size(MikaSize.Icon), tint = c.ink)
            }
        }
        DropdownMenu(open, { open = false }) {
            MenuRow(R.string.chat_new, MikaIcons.New, "chat-menu-new", enabled = canSwitch) { open = false; onNew() }
            MenuRow(R.string.voicecall_records, MikaIcons.Clock, "chat-menu-records") { open = false; onRecords() }
            MenuRow(R.string.chat_menu_tasks, MikaIcons.ListChecks, "chat-menu-tasks") { open = false; onTasks() }
            MenuRow(R.string.voicecall_settings, MikaIcons.Handset, "chat-menu-call-settings") { open = false; onCallSettings() }
        }
    }
}

@Composable
private fun MenuRow(text: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(text)) },
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.testTag(tag),
    )
}

/** 前台在这个对话里派过的一件事:[goal] 是交给后台的原话,[view] 是它此刻还在前台名单上时的样子。 */
internal data class DeskTaskItem(val taskId: String, val goal: String, val view: DeskTaskView?)

/**
 * 这个对话里前台派过的每件事,新派的在上。派单记在每一轮的 [Exchange.deskTasks] 里
 * (汇报那一轮的第一件是被汇报的那件,不算新派);还在前台名单上的补上它此刻的样子。
 */
internal fun deskTaskItems(exchanges: List<Exchange>, active: List<DeskTaskView>): List<DeskTaskItem> {
    val goals = LinkedHashMap<String, String>()
    exchanges.forEach { ex -> ex.deskCards.forEach { goals.putIfAbsent(it, ex.goal) } }
    active.forEach { goals.putIfAbsent(it.taskId, it.goal.orEmpty()) }
    val views = active.associateBy { it.taskId }
    return goals.map { (id, goal) ->
        val view = views[id]
        DeskTaskItem(id, view?.goal?.takeIf(String::isNotBlank) ?: goal, view)
    }.asReversed()
}

/** 「这个对话里的事」:每件一行(名字、原话、还在办的标阶段);点一行进它的运行记录。[titles] 是各件会话起好的标题。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeskTasksSheet(items: List<DeskTaskItem>, titles: Map<String, String>, onPick: (DeskTaskItem) -> Unit, onDismiss: () -> Unit) {
    val c = MikaTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = MikaShapes.SheetTop,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp).testTag("desk-tasks-sheet")) {
            Text(stringResource(R.string.chat_menu_tasks), Modifier.padding(horizontal = 16.dp), style = MikaType.BodyStrong, color = c.ink)
            if (items.isEmpty()) {
                Text(
                    stringResource(R.string.desk_tasks_empty),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MikaType.Caption, color = c.ink3,
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                itemsIndexed(items, key = { _, it -> it.taskId }) { i, item ->
                    val title = item.view?.title?.takeIf(String::isNotBlank) ?: titles[item.taskId] ?: item.goal
                    val phase = item.view?.let { deskPhaseStyle(it) }
                    ListRow(
                        MikaIcons.ListChecks, title,
                        subtitle = item.goal.takeIf { it.isNotBlank() && it != title },
                        first = i == 0,
                        detail = phase?.label,
                        detailColor = phase?.fg ?: c.ink3,
                        onClick = { onPick(item) },
                        modifier = Modifier.testTag("desk-task-item"),
                    )
                }
            }
        }
    }
}
