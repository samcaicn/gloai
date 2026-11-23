package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import android.app.Activity
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wangzan101.mika.core.chat.GeneratedPhoto
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.theme.MikaColors
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import kotlinx.coroutines.launch
import com.wangzan101.mika.browser.ui.findActivity
import com.wangzan101.mika.settings.ui.models.refresh

/** 对话页附件的说法。 */
object AttachCopy {
    val ADD: String get() = tr(R.string.attach_add)
    val CAMERA: String get() = tr(R.string.attach_camera)
    val ALBUM: String get() = tr(R.string.attach_album)
    val FILES: String get() = tr(R.string.attach_files)
    val RECENT: String get() = tr(R.string.attach_recent)
    val RECENT_PARTIAL: String get() = tr(R.string.attach_recent_partial)
    val ALL: String get() = tr(R.string.attach_all)
    val CHANGE: String get() = tr(R.string.attach_change)
    val NO_PHOTOS: String get() = tr(R.string.attach_no_photos)
    val NEED_ACCESS: String get() = tr(R.string.attach_need_access)
    val GRANT: String get() = tr(R.string.attach_grant)
    val GRANT_HINT: String get() = tr(R.string.attach_grant_hint)
    val FULL: String get() = tr(R.string.attach_full, AttachTray.LIMIT)
    val NO_CAMERA: String get() = tr(R.string.attach_no_camera)
    val GONE: String get() = tr(R.string.attach_gone)
    val IMPORTING: String get() = tr(R.string.attach_importing)

    fun remove(name: String) = tr(R.string.attach_remove, name)
    fun overLimit(n: Int) = tr(R.string.attach_over_limit, AttachTray.LIMIT, n)
}

// ---------- ＋ 里要系统界面的活 ----------

/**
 * 「＋」面板里要系统界面或照片的那几件活:相机、相册、文件、照片权限(第一次打开时问一次)和最近的照片。
 * 挑完、拍完的结果交给 [onPicked];最近的照片点一下交给 [onToggle],满了(返回 false)时 [onFull]。
 */
internal class AttachLaunchers(
    val camera: () -> Unit,
    val album: () -> Unit,
    val files: () -> Unit,
    val deviceFiles: (() -> Unit)?,
    val access: PhotoAccess,
    val recent: List<PendingAttachment>?,
    val grant: () -> Unit,
    val toggle: (PendingAttachment) -> Unit,
)

@Composable
internal fun rememberAttachLaunchers(
    onPicked: (List<String>) -> Unit,
    onToggle: (PendingAttachment) -> Boolean,
    onFull: () -> Unit,
    onDeviceFiles: (() -> Unit)? = null,
): AttachLaunchers {
    val context = LocalContext.current
    var access by remember { mutableStateOf(RecentPhotos.access(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    val recent by produceState<List<PendingAttachment>?>(null, access, refresh) {
        value = if (access == PhotoAccess.None) emptyList() else RecentPhotos.query(context)
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = RecentPhotos.access(context)
        refresh++
    }
    // 回到 App 时(可能刚在系统设置里改过照片权限、刚拍了照)重新看一眼;面板刚打开那一次上面已经在读
    var resumedOnce by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        val now = RecentPhotos.access(context)
        if (now != access) access = now else if (resumedOnce) refresh++
        resumedOnce = true
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        if (access == PhotoAccess.None && !RecentPhotos.asked(context)) {
            RecentPhotos.markAsked(context)
            permissions.launch(RecentPhotos.permissions())
        }
    }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri ?: return@rememberLauncherForActivityResult
        cameraUri = null
        if (ok) onPicked(listOf(uri)) else AttachFiles.discardCamera(context, Uri.parse(uri))
    }
    // 声明了 CAMERA 权限后,系统相机要求应用先拿到授权;没授权时 ACTION_IMAGE_CAPTURE 会抛 SecurityException
    fun startCamera() {
        val uri = AttachFiles.newCameraUri(context)
        cameraUri = uri.toString()
        try {
            camera.launch(uri)
        } catch (e: ActivityNotFoundException) {
            cameraUri = null
            AttachFiles.discardCamera(context, uri)
            Toast.makeText(context, AttachCopy.NO_CAMERA, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            cameraUri = null
            AttachFiles.discardCamera(context, uri)
            Toast.makeText(context, AttachCopy.NO_CAMERA, Toast.LENGTH_SHORT).show()
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera()
    }
    val album = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(AttachTray.LIMIT)) { uris ->
        onPicked(uris.map { it.toString() })
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onPicked(uris.map { it.toString() })
    }
    val openAlbum = { album.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    return AttachLaunchers(
        camera = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        },
        album = openAlbum,
        files = { files.launch(arrayOf("*/*")) },
        deviceFiles = onDeviceFiles,
        access = access,
        recent = recent,
        grant = {
            val activity = context.findActivity()
            val blocked = access == PhotoAccess.None && RecentPhotos.asked(context) && activity != null &&
                RecentPhotos.permissions().none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
            RecentPhotos.markAsked(context)
            // 用户选过「不再询问」:系统不会再弹窗,只能去 App 信息里开
            if (blocked) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            else permissions.launch(RecentPhotos.permissions())
        },
        toggle = { if (!onToggle(it)) onFull() },
    )
}

/** 最近的一张照片:方块缩略图;选中时蓝框、淡蓝罩、右上角蓝底序号,没选时右上角一个空心圈。 */
@Composable
internal fun RecentCell(photo: PendingAttachment, order: Int?, onClick: () -> Unit) {
    val c = MikaTheme.colors
    val thumb = rememberAttachImage(AttachImage.Picked(photo.uri), RECENT_PX)
    Box(
        Modifier.testTag("recent-photo").aspectRatio(1f).clip(CellShape).background(c.fill)
            .semantics { selected = order != null }
            .clickable(onClickLabel = if (order != null) stringResource(R.string.attach_deselect) else stringResource(R.string.attach_select), onClick = onClick),
    ) {
        thumb?.let { Image(it, photo.name, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
        if (order != null) Box(Modifier.matchParentSize().background(c.accent.copy(alpha = 0.12f)).border(2.5.dp, c.accent, CellShape))
        Box(
            Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp)
                .background(if (order != null) c.accent else Color.Black.copy(alpha = 0.18f), CircleShape)
                .border(1.5.dp, if (order != null) c.accent else Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (order != null) Text("$order", style = BadgeText, color = c.onAccent)
        }
    }
}

// ---------- 待发 ----------

/** 输入框上方一排待发的附件:照片是方块缩略图,文件是小卡片(类型、名字、大小),点一下预览或打开,右上角 × 去掉。 */
@Composable
internal fun PendingTray(
    items: List<PendingAttachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpen: ((PendingAttachment) -> Unit)? = null,
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(
        modifier.fillMaxWidth().testTag("attach-tray").horizontalScroll(rememberScrollState()).padding(start = 4.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (a in items) {
            val conversationId = a.workspaceConversationId
            val path = a.workspacePath
            val workspaceImage = if (conversationId != null && path != null) AttachImage.Sent(conversationId, path) else null
            val openModifier = Modifier.clickable(
                role = if (a.photo) Role.Image else Role.Button,
                onClickLabel = stringResource(if (a.photo) R.string.attach_view_large else R.string.chat_open),
            ) {
                if (onOpen != null) {
                    onOpen(a)
                } else {
                    scope.launch {
                        val opened = if (workspaceImage != null) {
                            AttachFiles.openSent(context, workspaceImage.conversationId, workspaceImage.path)
                        } else {
                            AttachFiles.openPending(context, a.uri, a.name)
                        }
                        if (!opened) Toast.makeText(context, AttachCopy.GONE, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Box {
                if (a.photo) {
                    val thumb = rememberAttachImage(workspaceImage ?: AttachImage.Picked(a.uri), TRAY_PX)
                    Box(Modifier.testTag("tray-photo").size(58.dp).clip(ChipShape).background(c.fill).then(openModifier)) {
                        thumb?.let { Image(it, a.name, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
                    }
                } else {
                    Row(
                        Modifier.testTag("tray-file").height(58.dp).widthIn(max = 200.dp).cardSurface(c, ChipShape)
                            .clip(ChipShape).then(openModifier).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TypeBadge(a.name, Modifier.size(28.dp, 34.dp), small = true)
                        Column(Modifier.widthIn(max = 130.dp)) {
                            Text(a.name, style = ChipName, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(ChatViewModel.sizeLabel(a.size), style = ChipMeta, color = c.ink3, maxLines = 1)
                        }
                    }
                }
                Box(
                    Modifier.align(Alignment.TopEnd).offset(6.dp, (-6).dp).size(20.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .clickable(role = Role.Button, onClickLabel = AttachCopy.remove(a.name)) { onRemove(a.uri) }
                        .semantics { contentDescription = AttachCopy.remove(a.name) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MikaIcons.Close, null, Modifier.size(12.dp), tint = Color.White)
                }
            }
        }
    }
}

// ---------- 发出去的 ----------

/**
 * 用户消息里带的附件:照片排成格子(见 [PhotoGrid];1 张保持比例,2 张并排,3 张起三列),文件是卡片(类型、名字、大小);
 * 点照片或卡片交给 [onOpen](在 App 里的查看页打开)。最宽 [maxWidth]。
 */
@Composable
internal fun SentAttachments(parsed: AttachmentMessage.Parsed, conversationId: String?, maxWidth: Dp, onOpen: (AttachmentMessage.Item) -> Unit) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (parsed.photos.isNotEmpty()) SentPhotos(parsed.photos, conversationId, maxWidth, onOpen)
        for (f in parsed.files) SentFileCard(f, maxWidth) { onOpen(f) }
    }
}

/** 回答里模型产出的 `/workspace/...` 引用:照片排格子、文件画卡片,和回答一起左对齐。 */
@Composable
internal fun OutputRefs(answer: String, conversationId: String?, onOpen: (AttachmentMessage.Item) -> Unit) {
    val parsed = remember(answer) { outputRefsParsed(answer) }
    if (!parsed.hasAttachments) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (parsed.photos.isNotEmpty()) SentPhotos(parsed.photos, conversationId, OutputRefMaxWidth, onOpen)
        for (f in parsed.files) SentFileCard(f, OutputRefMaxWidth) { onOpen(f) }
    }
}

private val OutputRefMaxWidth = 320.dp

@Composable
private fun SentPhotos(photos: List<AttachmentMessage.Item>, conversationId: String?, maxWidth: Dp, onOpen: (AttachmentMessage.Item) -> Unit) {
    val c = MikaTheme.colors
    val cols = PhotoGrid.columns(photos.size)
    if (cols == 1) {
        val p = photos.single()
        val thumb = conversationId?.let { rememberAttachImage(AttachImage.Sent(it, p.path), SINGLE_PX) }
        val ratio = thumb?.let { (it.width.toFloat() / it.height.coerceAtLeast(1)).coerceIn(0.56f, 1.8f) } ?: 0.75f
        Box(
            Modifier.testTag("sent-photo").width(minOf(SingleWidth, maxWidth)).aspectRatio(ratio).clip(GridPhotoShape).background(c.fill)
                .clickable(role = Role.Image, onClickLabel = stringResource(R.string.attach_view_large)) { onOpen(p) },
        ) {
            thumb?.let { Image(it, p.name, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
        }
        return
    }
    val gap = 4.dp
    val cell = minOf(if (cols == 2) 118.dp else 96.dp, (maxWidth - gap * (cols - 1)) / cols)
    val px = with(LocalDensity.current) { cell.roundToPx() }.coerceAtLeast(64)
    Column(Modifier.clip(GridPhotoShape), verticalArrangement = Arrangement.spacedBy(gap)) {
        photos.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { p ->
                    val thumb = conversationId?.let { rememberAttachImage(AttachImage.Sent(it, p.path), px) }
                    Box(
                        Modifier.testTag("sent-photo").size(cell).background(c.fill)
                            .clickable(role = Role.Image, onClickLabel = stringResource(R.string.attach_view_large)) { onOpen(p) },
                    ) {
                        thumb?.let { Image(it, p.name, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
                    }
                }
            }
        }
    }
}

/**
 * Mika 生成的照片:和用户附件一样的格子(1 张一大张,2 张并排,3 张起三列),点开在 App 的查看页看。
 * 路径来自工具结果,不解析回答文字;conversationId 为 null(旧记录没有对话)时不解码。
 */
@Composable
internal fun GeneratedPhotos(photos: List<GeneratedPhoto>, conversationId: String?, maxWidth: Dp) {
    if (conversationId == null || photos.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = MikaTheme.colors
    val cols = PhotoGrid.columns(photos.size)
    val gap = 4.dp
    val cell = minOf(if (cols == 1) SingleWidth else if (cols == 2) 118.dp else 96.dp, maxWidth)
    val px = with(LocalDensity.current) { cell.roundToPx() }.coerceAtLeast(64)
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
        photos.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { p ->
                    val thumb = rememberAttachImage(AttachImage.Sent(conversationId, p.path), px)
                    Box(
                        Modifier.testTag("generated-photo").size(cell).clip(GridPhotoShape).background(c.fill)
                            .clickable(role = Role.Image, onClickLabel = stringResource(R.string.attach_view_large)) {
                                scope.launch {
                                    if (!AttachFiles.openSent(context, conversationId, p.path)) {
                                        Toast.makeText(context, AttachCopy.GONE, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                    ) {
                        thumb?.let { Image(it, p.path.substringAfterLast('/'), Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SentFileCard(f: AttachmentMessage.Item, maxWidth: Dp, onClick: () -> Unit) {    val c = MikaTheme.colors
    Row(
        Modifier.testTag("sent-file").widthIn(min = minOf(220.dp, maxWidth), max = maxWidth).cardSurface(c, FileCardShape).clip(FileCardShape)
            .clickable(onClickLabel = stringResource(R.string.chat_open), role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TypeBadge(f.name, Modifier.size(38.dp, 44.dp), small = false)
        Column(Modifier.weight(1f, fill = false)) {
            Text(f.name, style = FileCardName, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(f.size, style = ChipMeta, color = c.ink3, maxLines = 1)
        }
    }
}

/** 文件类型角标:按扩展名配色,上面写扩展名。 */
@Composable
private fun TypeBadge(name: String, modifier: Modifier, small: Boolean) {
    val shape = RoundedCornerShape(if (small) MikaRadius.Micro else MikaRadius.Tile)
    Box(modifier.background(badgeColor(FileBadge.kind(name), MikaTheme.colors), shape), contentAlignment = Alignment.Center) {
        Text(FileBadge.text(name), style = if (small) BadgeSmall else BadgeLarge, color = Color.White, maxLines = 1)
    }
}

private fun badgeColor(kind: FileBadge.Kind, c: MikaColors): Color = when (kind) {
    FileBadge.Kind.Pdf -> c.bad
    FileBadge.Kind.Sheet -> c.ok
    FileBadge.Kind.Doc -> c.accent
    FileBadge.Kind.Slides -> c.warnSolid
    FileBadge.Kind.Archive, FileBadge.Kind.Other -> NeutralBadge
}

/** 缩略图解到这么大(像素):面板格子、待发的小方块、单张大图。 */
private const val RECENT_PX = 256
private const val TRAY_PX = 180
private const val SINGLE_PX = 640

private val SingleWidth = 200.dp
private val NeutralBadge = Color(0xFF8E8E93)
private val CellShape = RoundedCornerShape(MikaRadius.BubbleTail)
private val ChipShape = MikaShapes.Chip
private val GridPhotoShape = MikaShapes.Button
private val FileCardShape = MikaShapes.Button
private val BadgeText = MikaType.Hint.copy(fontWeight = FontWeight.SemiBold)
private val BadgeSmall = MikaType.Micro.copy(fontWeight = FontWeight.Bold)
private val BadgeLarge = MikaType.Micro.copy(fontWeight = FontWeight.Bold)
private val ChipName = MikaType.Hint.copy(fontWeight = FontWeight.Medium)
private val ChipMeta = MikaType.Micro
private val FileCardName = MikaType.Body.copy(fontWeight = FontWeight.Medium)
