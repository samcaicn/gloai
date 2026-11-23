package com.wangzan101.mika.chat.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.learn.knowledge.FrontMatter
import com.wangzan101.mika.core.learn.skills.SkillStatus
import com.wangzan101.mika.settings.RecommendedTask
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.DirectionalIcon
import com.wangzan101.mika.ui.components.IconTile
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wangzan101.mika.ui.Route

/** 技能列表里的一项:界面用 SKILL.md 头部写的显示名和说明。 */
internal data class PlusSkill(val name: String, val display: String, val description: String)

/** 「＋」面板里正在看的那一页。 */
private enum class PlusView { Main, Photos, Skills, Tasks }

/** 弹窗顶部把手占的高度;整屏的 82% 里要把它扣掉。 */
private val PlusHandleHeight = 22.dp
private val PlusInset = 12.dp

/** 剪贴板里的文字;没有、或者只有非文字内容时为 null。 */
internal fun readClipboardText(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

/**
 * 「＋」打开的功能面板:从底部升起,先占半屏;往上拖(或滚)到 82%,往下拖、点遮罩或返回关掉。
 * 内容是一张张卡片、每张卡片里每行一件事:上面那张往这一轮里放东西,下面那张是让 Mika 做事或去别处。
 * 相册、技能、常用任务在面板里翻到二级页;拍照、文件、清单这些直接交给系统或对话页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatPlusSheet(
    selected: List<PendingAttachment>,
    onDismiss: () -> Unit,
    onPicked: (List<String>) -> Unit,
    onToggle: (PendingAttachment) -> Boolean,
    onFull: () -> Unit,
    onDeviceFiles: (() -> Unit)?,
    onPaste: () -> Unit,
    /** 只有空着的时候才能开始示范;任务在跑时为 null,这一行不出现。 */
    onTeach: (() -> Unit)?,
    onChecklists: () -> Unit,
    onWorkflows: () -> Unit,
    onBrowser: () -> Unit,
    tasks: List<RecommendedTask>,
    onPickTask: (RecommendedTask) -> Unit,
    onPickSkill: (PlusSkill) -> Unit,
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val attach = rememberAttachLaunchers(onPicked, onToggle, onFull, onDeviceFiles)
    var view by remember { mutableStateOf(PlusView.Main) }
    // 技能要读盘;真到「技能」那一页才读,面板开合不额外读
    val skills by produceState<List<PlusSkill>?>(null, view) {
        if (view == PlusView.Skills && value == null) {
            value = withContext(Dispatchers.IO) { loadSkills(context) }
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.bg,
        shape = MikaShapes.SheetTop,
        dragHandle = { PlusHandle() },
        scrimColor = Color.Black.copy(alpha = 0.28f),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 半屏→展开到整屏的 82%:把手加上内容正好这么高
            val body = (maxHeight * 0.82f - PlusHandleHeight).coerceAtLeast(0.dp)
            Column(Modifier.fillMaxWidth().height(body)) {
                when (view) {
                    PlusView.Main -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = PlusInset, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MikaCard {
                            PlusRow(MikaIcons.PhotoCamera, AttachCopy.CAMERA, null, first = true, onClick = attach.camera)
                            PlusRow(MikaIcons.Photo, AttachCopy.ALBUM, stringResource(R.string.plus_sub_album), onClick = { view = PlusView.Photos }, trailing = { RowChevron() })
                            PlusRow(MikaIcons.Paperclip, AttachCopy.FILES, stringResource(R.string.plus_sub_files), onClick = attach.files, trailing = { RowChevron() })
                            attach.deviceFiles?.let { open ->
                                PlusRow(MikaIcons.Folder, stringResource(R.string.device_browser_title), stringResource(R.string.plus_sub_device), onClick = open, trailing = { RowChevron() })
                            }
                            PlusRow(MikaIcons.Copy, stringResource(R.string.plus_clipboard), stringResource(R.string.plus_sub_clipboard), onClick = onPaste)
                        }
                        MikaCard {
                            onTeach?.let { teach ->
                                PlusRow(MikaIcons.Hand, stringResource(R.string.teach_menu), stringResource(R.string.plus_sub_teach), first = true, onClick = teach)
                            }
                            PlusRow(MikaIcons.Book, stringResource(R.string.memory_skills), stringResource(R.string.plus_sub_skills), first = onTeach == null, onClick = { view = PlusView.Skills }, trailing = { RowChevron() })
                            PlusRow(MikaIcons.ListChecks, stringResource(R.string.checklists_title), null, onClick = onChecklists)
                            PlusRow(MikaIcons.Route, stringResource(R.string.workflows_title), null, onClick = onWorkflows)
                            PlusRow(MikaIcons.Star, stringResource(R.string.recommendations_title), stringResource(R.string.plus_sub_tasks), onClick = { view = PlusView.Tasks }, trailing = { RowChevron() })
                            PlusRow(MikaIcons.Globe, stringResource(R.string.drawer_browser), stringResource(R.string.plus_sub_browser), onClick = onBrowser, trailing = { RowChevron() })
                        }
                    }
                    PlusView.Photos -> PlusPhotosPage(
                        access = attach.access,
                        recent = attach.recent,
                        selected = selected,
                        onToggle = attach.toggle,
                        onGrant = attach.grant,
                        onAlbum = attach.album,
                        onBack = { view = PlusView.Main },
                    )
                    PlusView.Skills -> PlusSkillsPage(
                        skills = skills,
                        onPick = onPickSkill,
                        onBack = { view = PlusView.Main },
                    )
                    PlusView.Tasks -> PlusTasksPage(
                        tasks = tasks,
                        onPick = onPickTask,
                        onBack = { view = PlusView.Main },
                    )
                }
            }
        }
    }
}

/** 顶部把手:一条 36×5 的短横线;拖动和点按的语义由 Material 的弹窗壳子给。 */
@Composable
private fun PlusHandle() {
    Box(Modifier.fillMaxWidth().height(PlusHandleHeight), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 36.dp, height = 5.dp).background(MikaTheme.colors.ink3.copy(alpha = 0.35f), CircleShape))
    }
}

/**
 * 面板里的一行:图标块、标题、一句说明,右边可以带箭头或别的。
 * [first] 为卡片首行时不画上分隔线;分隔线从文字处开始。
 */
@Composable
private fun PlusRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    first: Boolean = false,
    onClick: () -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (first) Modifier else Modifier.drawBehind {
                val x = 58.dp.toPx()
                drawLine(c.hair, Offset(x, 0f), Offset(size.width, 0f), strokeWidth = 0.5.dp.toPx())
            })
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MikaType.Body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MikaType.Caption, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke(this)
    }
}

/** 二级页顶上的一行:返回、标题,右边放链接。 */
@Composable
private fun SheetHead(title: String, onBack: () -> Unit, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            DirectionalIcon(MikaIcons.Back, stringResource(R.string.back), Modifier.size(16.dp), tint = c.ink)
        }
        Text(title, Modifier.weight(1f), style = MikaType.Label, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke(this)
    }
}

/** 二级页里的小链接(「全部照片」「更改范围」)。 */
@Composable
private fun SheetLink(text: String, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(MikaShapes.Tile).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        style = MikaType.Hint, color = MikaTheme.colors.accent,
    )
}

/** 相册的二级页:四列最近的照片(点一下选上,右上角是序号;再点取消)。[onGrant] 是没给照片权限时。 */
@Composable
internal fun PlusPhotosPage(
    access: PhotoAccess,
    recent: List<PendingAttachment>?,
    selected: List<PendingAttachment>,
    onToggle: (PendingAttachment) -> Unit,
    onGrant: () -> Unit,
    onAlbum: () -> Unit,
    onBack: () -> Unit,
) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxSize().padding(horizontal = PlusInset)) {
        SheetHead(
            title = if (access == PhotoAccess.Partial) AttachCopy.RECENT_PARTIAL else AttachCopy.RECENT,
            onBack = onBack,
            trailing = {
                if (access == PhotoAccess.Partial) SheetLink(AttachCopy.CHANGE, onGrant)
                SheetLink(AttachCopy.ALL, onAlbum)
            },
        )
        when {
            access == PhotoAccess.None -> Column(
                Modifier.fillMaxSize().padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                Text(AttachCopy.NEED_ACCESS, style = MikaType.Caption, color = c.ink2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                MikaButton(AttachCopy.GRANT, onGrant, kind = ButtonKind.Primary, size = ButtonSize.Medium)
                Text(AttachCopy.GRANT_HINT, style = MikaType.Hint, color = c.ink3, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            recent == null -> Box(Modifier.fillMaxSize())
            recent.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(AttachCopy.NO_PHOTOS, style = MikaType.Caption, color = c.ink3)
            }
            else -> LazyVerticalGrid(
                GridCells.Fixed(4),
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp),
            ) {
                items(recent, key = { it.uri }) { p -> RecentCell(p, AttachTray.order(selected, p.uri)) { onToggle(p) } }
            }
        }
    }
}

/** 技能的二级页:点一个把「用这个技能」写进输入框,回对话页。 */
@Composable
private fun PlusSkillsPage(skills: List<PlusSkill>?, onPick: (PlusSkill) -> Unit, onBack: () -> Unit) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = PlusInset)) {
        SheetHead(title = stringResource(R.string.memory_skills), onBack = onBack)
        when {
            skills == null -> Box(Modifier.fillMaxWidth())
            skills.isEmpty() -> Text(
                stringResource(R.string.plus_skills_empty),
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                style = MikaType.Caption, color = c.ink3,
            )
            else -> MikaCard {
                skills.forEachIndexed { i, skill ->
                    PlusRow(MikaIcons.Book, skill.display, skill.description, first = i == 0, onClick = { onPick(skill) })
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 常用任务的二级页:点一条把它的说法填进输入框(不自动发)。 */
@Composable
private fun PlusTasksPage(tasks: List<RecommendedTask>, onPick: (RecommendedTask) -> Unit, onBack: () -> Unit) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = PlusInset)) {
        SheetHead(title = stringResource(R.string.recommendations_title), onBack = onBack)
        if (tasks.isEmpty()) {
            Text(
                stringResource(R.string.recommendations_empty),
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                style = MikaType.Caption, color = c.ink3,
            )
        } else {
            MikaCard {
                tasks.forEachIndexed { i, task ->
                    PlusRow(MikaIcons.Star, task.title, task.prompt, first = i == 0, onClick = { onPick(task) })
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 已收下的个人技能;停用的不列(它已经不用了),显示名和说明取 SKILL.md 头部。 */
private fun loadSkills(context: Context): List<PlusSkill> {
    val files = (context.applicationContext as? MikaApp)?.memory?.skills() ?: return emptyList()
    return files.list()
        .filter { it.status != SkillStatus.suspended }
        .map { s ->
            val meta = FrontMatter.parse(s.markdown).first
            PlusSkill(
                name = s.name,
                display = (meta["display"] as? String)?.takeIf { it.isNotBlank() } ?: s.name,
                description = (meta["description"] as? String).orEmpty(),
            )
        }
}
