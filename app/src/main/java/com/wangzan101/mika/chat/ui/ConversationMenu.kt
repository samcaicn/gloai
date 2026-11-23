package com.wangzan101.mika.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.popup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.Project
import com.wangzan101.mika.ui.components.GlassBlurSupported
import com.wangzan101.mika.ui.components.floatShadow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 长按菜单摆在哪:抬起的那一行的顶边(可能为了放下菜单挪过)、菜单的顶边、菜单是不是在那一行上面。 */
internal data class MenuPlacement(val rowTop: Float, val menuTop: Float, val above: Boolean)

/**
 * 长按菜单的位置。[top]、[bottom] 是能放东西的上下边(去掉状态栏、导航栏、键盘)。
 * 那一行先挪进这个范围;下面放得下菜单就放下面,不然上面放得下就放上面;两边都放不下(键盘占了大半屏)时把那一行往上挪,
 * 菜单接在它下面,还不够就让那一行顶到最上面。
 */
internal fun placeMenu(rowTop: Float, rowHeight: Float, menuHeight: Float, gap: Float, top: Float, bottom: Float): MenuPlacement {
    val row = rowTop.coerceIn(top, max(top, bottom - rowHeight))
    if (row + rowHeight + gap + menuHeight <= bottom) return MenuPlacement(row, row + rowHeight + gap, above = false)
    if (row - gap - menuHeight >= top) return MenuPlacement(row, row - gap - menuHeight, above = true)
    val moved = max(top, bottom - menuHeight - gap - rowHeight)
    return MenuPlacement(moved, moved + rowHeight + gap, above = false)
}

/**
 * 侧栏里长按一条对话后的菜单(见 [LongPressMenu]):那一行抬起成一张白卡,菜单里是置顶 / 取消置顶、
 * 归档 / 取消归档、改名、红字删除。归档了的对话不在主列表里,没有置顶可谈;还没存过的草稿和正在跑任务的不能归档。
 * [bounds] 是那一行在窗口里的位置和大小。
 */
@Composable
internal fun ConversationMenu(
    item: DrawerConversation,
    bounds: Rect,
    progress: () -> Float,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onRename: () -> Unit,
    onTerminal: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MikaTheme.colors
    LongPressMenu(
        bounds, progress, pane = stringResource(R.string.drawer_actions), tag = "drawer-menu",
        entries = listOfNotNull(
            MenuEntry(stringResource(R.string.drawer_select), MikaIcons.ListChecks, "drawer-menu-select", onClick = onSelect),
            if (item.archived) null else MenuEntry(stringResource(if (item.pinned) R.string.unpin else R.string.pin), MikaIcons.Pin, "drawer-menu-pin", onClick = onPin),
            when {
                item.archived -> MenuEntry(stringResource(R.string.drawer_unarchive), MikaIcons.Archive, "drawer-menu-unarchive", onClick = onUnarchive)
                item.saved && !item.running -> MenuEntry(stringResource(R.string.drawer_archive), MikaIcons.Archive, "drawer-menu-archive", onClick = onArchive)
                else -> null
            },
            MenuEntry(stringResource(R.string.drawer_rename), MikaIcons.Edit, "drawer-menu-rename", onClick = onRename),
            MenuEntry(stringResource(R.string.terminal_open), MikaIcons.Terminal, "drawer-menu-terminal", onClick = onTerminal),
            MenuEntry(stringResource(R.string.export_conversation), MikaIcons.Share, "drawer-menu-export", onClick = onExport),
            MenuEntry(stringResource(R.string.confirm_delete), MikaIcons.Trash, "drawer-menu-delete", c.bad, onDelete),
        ),
        onDismiss = onDismiss,
    ) { LiftedRow(item) }
}

/** 长按侧栏项目分组头后的菜单:在项目里新对话、重命名、打开终端、删除项目。 */
@Composable
internal fun ProjectMenu(
    project: Project,
    bounds: Rect,
    progress: () -> Float,
    onNewChat: () -> Unit,
    onRename: () -> Unit,
    onTerminal: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MikaTheme.colors
    LongPressMenu(
        bounds, progress, pane = stringResource(R.string.drawer_project_actions), tag = "drawer-project-menu",
        entries = listOf(
            MenuEntry(stringResource(R.string.drawer_project_new_chat), MikaIcons.New, "project-menu-new-chat", onClick = onNewChat),
            MenuEntry(stringResource(R.string.drawer_project_rename_title), MikaIcons.Edit, "project-menu-rename", onClick = onRename),
            MenuEntry(stringResource(R.string.terminal_open), MikaIcons.Terminal, "project-menu-terminal", onClick = onTerminal),
            MenuEntry(stringResource(R.string.confirm_delete), MikaIcons.Trash, "project-menu-delete", c.bad, onDelete),
        ),
        onDismiss = onDismiss,
    ) {
        Box(
            Modifier
                .floatShadow(c, NavShape)
                .background(c.surface, NavShape)
                .clip(NavShape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Icon(MikaIcons.Folder, null, Modifier.size(16.dp), tint = c.ink2)
                Text(project.name, style = MikaType.Body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 长按菜单里的一项:文字、右边的图标、测试标签、颜色(为 null 是正文色)。 */
internal class MenuEntry(val text: String, val icon: ImageVector, val tag: String, val color: Color? = null, val onClick: () -> Unit)

/**
 * 长按某样东西后原地弹出的菜单(侧栏里的对话、对话页里的消息共用):后面整屏变暗(安卓 12 起还模糊,由外面给后面的内容加,见 [MenuBlur]),
 * 那样东西原地「抬起」([lifted],自己画成白卡之类,略微放大),下面(放不下就上面)一张窄一点的白卡列出 [entries],文字在左、图标在右。
 * 点外面、按返回都收起。[progress] 是出现的进度(0 到 1),外面按关没关动画决定是动着过去还是直接到位;
 * 只在绘制、摆放时读,动画时不重组。[bounds] 是那样东西在窗口里的位置和大小:抬起的那张和它一样宽,高按自己的内容,
 * 最高半屏(很长的回答只抬起开头那一截,由 [lifted] 自己裁掉)。[alignEnd] 时菜单和它的右边对齐(用户自己的话靠右)。
 */
@Composable
internal fun LongPressMenu(
    bounds: Rect,
    progress: () -> Float,
    pane: String,
    tag: String,
    entries: List<MenuEntry>,
    onDismiss: () -> Unit,
    alignEnd: Boolean = false,
    lifted: @Composable () -> Unit,
) {
    val c = MikaTheme.colors
    val insets = WindowInsets.safeDrawing
    val view = LocalView.current
    val close = stringResource(R.string.collapse)
    // 能模糊时后面已经糊了,暗一点就够;不能模糊时只靠变暗把后面压下去
    val dim = c.floatShadow.copy(alpha = if (GlassBlurSupported) 0.16f else 0.32f)
    BackHandler(onBack = onDismiss)
    Layout(
        content = {
            lifted()
            MenuCard {
                entries.forEachIndexed { i, e ->
                    if (i > 0) MenuDivider(c.hair)
                    MenuItem(e.text, e.icon, e.tag, e.color ?: c.ink, e.onClick)
                }
            }
        },
        modifier = Modifier.fillMaxSize()
            .drawBehind { drawRect(dim.copy(alpha = dim.alpha * progress())) }
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .semantics {
                paneTitle = pane
                isTraversalGroup = true
                dismiss(close) { onDismiss(); true }
            }
            .testTag(tag),
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val rowW = bounds.width.roundToInt().coerceIn(0, w)
        val lifted = measurables[0].measure(Constraints(minWidth = rowW, maxWidth = rowW, maxHeight = (h / 2).coerceAtLeast(0)))
        val menuW = min(MenuWidth.roundToPx(), w).coerceAtLeast(0)
        val menu = measurables[1].measure(Constraints(minWidth = menuW, maxWidth = menuW, maxHeight = h))
        layout(w, h) {
            // 那一行的位置是窗口坐标;这一层不一定从窗口左上角开始(没有铺到系统栏底下时)
            val origin = coordinates?.positionInWindow() ?: Offset.Zero
            val top = max(0f, insets.getTop(this@Layout) - origin.y)
            val bottom = min(h.toFloat(), view.rootView.height - insets.getBottom(this@Layout) - origin.y)
            val at = placeMenu(bounds.top - origin.y, lifted.height.toFloat(), menu.height.toFloat(), MenuGap.toPx(), top, bottom)
            val x = (bounds.left - origin.x).roundToInt()
            val menuX = (if (alignEnd) x + rowW - menuW else x).coerceIn(0, max(0, w - menuW))
            lifted.placeWithLayer(x, at.rowTop.roundToInt()) {
                scaleX = 1f + LiftScale * progress()
                scaleY = scaleX
            }
            menu.placeWithLayer(menuX, at.menuTop.roundToInt()) {
                val p = progress()
                alpha = p
                scaleX = 0.92f + 0.08f * p
                scaleY = scaleX
                transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, if (at.above) 1f else 0f)
            }
        }
    }
}

/** 抬起的那一行:和侧栏里那一行一样的内容,换成白底圆角、带阴影。 */
@Composable
private fun LiftedRow(item: DrawerConversation) {
    val c = MikaTheme.colors
    Box(
        Modifier
            .floatShadow(c, NavShape)
            .background(c.surface, NavShape)
            .clip(NavShape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        ConversationRowContent(item)
    }
}

@Composable
private fun MenuCard(content: @Composable () -> Unit) {
    val c = MikaTheme.colors
    Column(
        Modifier.floatShadow(c, MikaShapes.Card).clip(MikaShapes.Card)
            .background(c.surface)
            .border(0.5.dp, c.edge, MikaShapes.Card)
            .semantics { popup() },
    ) { content() }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, tag: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MikaType.Body, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(icon, null, Modifier.padding(start = 12.dp).size(MikaSize.Icon), tint = color)
    }
}

@Composable
private fun MenuDivider(color: Color) {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(color))
}

/** 菜单出现时后面那层内容模糊到多少(安卓 12 起)。 */
internal val MenuBlur = 14.dp
private val MenuWidth = 220.dp
private val MenuGap = 8.dp
private const val LiftScale = 0.03f
