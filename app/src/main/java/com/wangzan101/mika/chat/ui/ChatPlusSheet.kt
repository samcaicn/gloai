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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.testTag
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.learn.knowledge.FrontMatter
import com.wangzan101.mika.core.learn.skills.SkillStatus
import com.wangzan101.mika.settings.RecommendedTask
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.DirectionalIcon
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.PlainListTheme
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
 * 分三层:最上面一排四个按钮和最近照片,往这条消息里放东西;中间两张大卡片(教 Mika、技能)决定这一轮怎么做;
 * 最下面「常用」一行,进去是常用说法、已存清单和流程。相册、技能、常用在面板里翻到二级页。
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
    tasks: List<RecommendedTask>,
    onPickTask: (RecommendedTask) -> Unit,
    onPickSkill: (PlusSkill) -> Unit,
) {
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
    // 和设置页一样的简约列表配色:浅灰底上放白块,不描边
    PlainListTheme {
    val c = MikaTheme.colors
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
                    ) {
                        // 第一块:往这条消息里放东西。四个按钮和最近照片放在同一张白卡片里
                        MikaCard {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp)) {
                                QuickButton(MikaIcons.PhotoCamera, AttachCopy.CAMERA, "plus-camera", attach.camera)
                                QuickButton(MikaIcons.Photo, AttachCopy.ALBUM, "plus-album") { view = PlusView.Photos }
                                QuickButton(MikaIcons.Paperclip, AttachCopy.FILES, "plus-files", attach.files)
                                QuickButton(MikaIcons.Copy, stringResource(R.string.plus_clipboard), "plus-clipboard", onPaste)
                            }
                            val recent = attach.recent.orEmpty()
                            if (attach.access != PhotoAccess.None && recent.isNotEmpty()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    recent.take(4).forEach { p ->
                                        Box(Modifier.weight(1f)) { RecentCell(p, AttachTray.order(selected, p.uri)) { attach.toggle(p) } }
                                    }
                                    Box(
                                        Modifier.weight(1f).aspectRatio(1f).clip(MikaShapes.Tile).background(c.fill)
                                            .clickable(role = Role.Button) { view = PlusView.Photos },
                                        contentAlignment = Alignment.Center,
                                    ) { Text(AttachCopy.ALL, style = MikaType.Caption, color = c.ink2) }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        // 第二块:这一轮怎么做、存下来的拿来再用。和设置页一样的列表
                        MikaCard {
                            var first = true
                            onTeach?.let { teach ->
                                PlusRow(MikaIcons.Hand, stringResource(R.string.teach_menu), stringResource(R.string.plus_sub_teach), first = true, onClick = teach, tag = "plus-teach")
                                first = false
                            }
                            PlusRow(MikaIcons.Book, stringResource(R.string.memory_skills), null, first = first, onClick = { view = PlusView.Skills }, tag = "plus-skills", trailing = { RowChevron() })
                            PlusRow(
                                MikaIcons.Star, stringResource(R.string.plus_reuse), null,
                                onClick = { view = PlusView.Tasks }, tag = "plus-reuse",
                                trailing = {
                                    Text(stringResource(R.string.plus_reuse_hint), style = MikaType.Caption, color = c.ink3)
                                    RowChevron()
                                },
                            )
                            attach.deviceFiles?.let { open ->
                                PlusRow(MikaIcons.Folder, stringResource(R.string.device_browser_title), null, onClick = open, trailing = { RowChevron() })
                            }
                        }
                        Spacer(Modifier.height(16.dp))
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
                    PlusView.Tasks -> PlusReusePage(
                        tasks = tasks,
                        onPick = onPickTask,
                        onChecklists = onChecklists,
                        onWorkflows = onWorkflows,
                        onBack = { view = PlusView.Main },
                    )
                }
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
    tag: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .then(if (first) Modifier else Modifier.drawBehind {
                val x = 50.dp.toPx()
                drawLine(c.hair, Offset(x, 0f), Offset(size.width, 0f), strokeWidth = 0.5.dp.toPx())
            })
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = c.ink2)
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

/** 「常用」的二级页:上面去已存清单和流程,下面是常用说法(点一条填进输入框,不自动发)。 */
@Composable
private fun PlusReusePage(
    tasks: List<RecommendedTask>,
    onPick: (RecommendedTask) -> Unit,
    onChecklists: () -> Unit,
    onWorkflows: () -> Unit,
    onBack: () -> Unit,
) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = PlusInset)) {
        SheetHead(title = stringResource(R.string.plus_reuse), onBack = onBack)
        MikaCard {
            PlusRow(MikaIcons.ListChecks, stringResource(R.string.checklists_title), null, first = true, onClick = onChecklists, trailing = { RowChevron() })
            PlusRow(MikaIcons.Route, stringResource(R.string.workflows_title), null, onClick = onWorkflows, trailing = { RowChevron() })
        }
        Spacer(Modifier.height(16.dp))
        PlusLabel(stringResource(R.string.plus_reuse_phrases))
        if (tasks.isEmpty()) {
            Text(
                stringResource(R.string.recommendations_empty),
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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

@Composable
private fun PlusLabel(text: String) {
    Text(text, Modifier.padding(start = 16.dp, bottom = 6.dp), style = MikaType.Caption, color = MikaTheme.colors.ink3)
}

/** 第一排的按钮:图标和下面一行字,整块可点。 */
@Composable
private fun RowScope.QuickButton(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Column(
        Modifier.weight(1f).clip(MikaShapes.Tile).clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(26.dp), tint = c.ink)
        Text(label, style = MikaType.Caption, color = c.ink2, maxLines = 1)
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
