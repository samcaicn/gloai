package com.wangzan101.mika.chat.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.capsule.previewFrames
import com.wangzan101.mika.capsule.sameFrame
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.task.TaskCenterState
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext

/**
 * 这一页列的一个任务和它的虚拟屏:[displayId] 为 -1 是还没建屏(排队中,或者纯对话、还没打开过 App)。
 * [foreground] 是那块屏最上面那个 App 的显示名;还没查到、或手机操作没连着时为 null。[web] 为 true 时在用内置浏览器。
 * 屏号只用来排序和取画面,不给用户看。
 */
data class ScreenRow(
    val key: String,
    val conversationId: String,
    val goal: String,
    val displayId: Int,
    val state: TaskState,
    val foreground: String? = null,
    val web: Boolean = false,
    /** 这个任务到此刻做过几件事;变了就重查一次最上面的 App。 */
    val acted: Int = 0,
)

/** 列表的顺序:建了屏的在前(按建屏先后),还没建屏的排在后面(保持原来的先后)。 */
fun orderedScreens(rows: List<ScreenRow>): List<ScreenRow> =
    rows.filter { it.displayId >= 0 }.sortedBy { it.displayId } + rows.filter { it.displayId < 0 }

/**
 * 侧栏「进行中」旁边进来的「后台屏」页:每个在跑的任务一张卡,写它在做什么、正在用哪个 App、状态,能展开看实时画面。
 * 只读——停任务、回对话还是在对话页和胶囊里做;点卡片回到任务所在的对话。
 *
 * 数据取自各任务自己的流(状态、屏、画面、做过的事),不是服务对外的「显示中的任务」那一份:
 * 并行时没在显示的那几块屏照样是实的。最上面的 App 只在任务又做了一件事时重查,不轮询。
 */
@Composable
fun ScreensScreen(taskState: TaskCenterState, onBack: () -> Unit, onOpenConversation: (String) -> Unit) {
    val context = LocalContext.current
    val runs by taskState.runs.collectAsState()
    var rows by remember { mutableStateOf<List<ScreenRow>>(emptyList()) }
    LaunchedEffect(runs) {
        val list = runs
        if (list.isEmpty()) {
            rows = emptyList()
            return@LaunchedEffect
        }
        // 每个任务的状态、屏、浏览器、做过的事各有自己的流:合起来,哪一个变了都跟着重排
        combine(list.map { run ->
            combine(run.state, run.displayId, run.webOpen, run.activities) { s, id, web, acts ->
                ScreenRow(run.key, run.conversationId, run.goal, id, s, web = web, acted = acts.size)
            }
        }) { it.toList() }.collect { rows = it }
    }

    // 有屏的任务和它们做过几件事:任何一个又做了一件事,才重问一次各块屏最上面的 App
    val probe = remember(rows) { rows.filter { it.displayId >= 0 }.map { it.displayId to it.acted } }
    var foreground by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    LaunchedEffect(probe) { foreground = foregroundOf(context, probe.map { it.first }) }

    ScreensContent(
        rows = orderedScreens(rows).map { r -> r.copy(foreground = foreground[r.displayId]) },
        onBack = onBack,
        onOpenConversation = onOpenConversation,
        frames = { key -> runs.firstOrNull { it.key == key }?.frames },
    )
}

/**
 * 不带状态的那一页,便于测试:[rows] 已经排好序、已经填好最上面的 App。
 * [frames] 按任务编号给出那块屏的画面;给了才在展开时订阅。
 */
@Composable
fun ScreensContent(
    rows: List<ScreenRow>,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    frames: (String) -> StateFlow<Bitmap?>? = { null },
) {
    PageScaffold(ScreensCopy.TITLE, onBack) {
        if (rows.isEmpty()) {
            EmptyNote(ScreensCopy.EMPTY)
            return@PageScaffold
        }
        rows.forEach { row -> ScreenCard(row, frames(row.key), onOpenConversation) }
        FooterNote(ScreensCopy.NOTE)
    }
}

/** 一个任务:在做什么、正在用哪个 App、状态;点这一行回到它的对话,点「看画面」展开实时画面。 */
@Composable
private fun ScreenCard(row: ScreenRow, frames: StateFlow<Bitmap?>?, onOpenConversation: (String) -> Unit) {
    val c = MikaTheme.colors
    var open by rememberSaveable(row.key) { mutableStateOf(false) }
    GroupCard(Modifier.padding(top = 8.dp)) {
        ListRow(
            MikaIcons.Screens, row.goal.oneLine(), ScreensCopy.place(row),
            first = true, onClick = { onOpenConversation(row.conversationId) },
        ) { RowValue(ScreensCopy.state(row.state)) }
        if (row.displayId >= 0 || row.web) {
            Text(
                if (open) ScreensCopy.HIDE_PREVIEW else ScreensCopy.PREVIEW,
                Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                    .clickable { open = !open }.testTag("screens-preview-${row.key}"),
                style = MikaType.Caption, color = c.accent,
            )
            if (open && frames != null) LiveFrame(frames)
        }
    }
}

/**
 * 这块屏的实时画面:每秒最多 5 张,和上一张一样就不换(见 [previewFrames])。收起时不订阅。
 * 点一下放大到整屏,可以捏合缩放、拖动——字小的界面在这一小块里看不清。
 */
@Composable
private fun LiveFrame(source: StateFlow<Bitmap?>) {
    val frames = remember(source) { source.filterNotNull().previewFrames(same = ::sameFrame) }
    val frame by frames.collectAsState(initial = null)
    val bmp = frame ?: return
    var zoom by remember { mutableStateOf(false) }
    Image(
        bmp.asImageBitmap(), stringResource(R.string.screens_zoom),
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .height(260.dp).clip(MikaShapes.Tile)
            .clickable { zoom = true }.testTag("screens-zoom"),
        contentScale = ContentScale.Fit,
    )
    if (zoom) FrameZoom(bmp) { zoom = false }
}

/** 放大的那块屏:整屏、捏合缩放(1–6 倍)、拖动看边角;点外面或按返回关掉。 */
@Composable
private fun FrameZoom(frame: Bitmap, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(MikaTheme.colors.bg.copy(alpha = 0.96f)).clickable { onDismiss() }
                .testTag("screens-zoom-dialog"),
            contentAlignment = Alignment.Center,
        ) {
            ZoomableFrame(frame, null, Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp))
        }
    }
}

@Composable
internal fun ZoomableFrame(frame: Bitmap, description: String?, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale <= 1f) Offset.Zero else offset + panChange
    }
    Image(
        remember(frame) { frame.asImageBitmap() }, description,
        modifier.transformable(state)
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        contentScale = ContentScale.Fit,
    )
}

/** 问内置助手每块屏最上面是哪个 App,再换成显示名。助手没连着就都不查(这只影响前景那一行)。 */
private suspend fun foregroundOf(context: Context, ids: List<Int>): Map<Int, String> {
    if (ids.isEmpty()) return emptyMap()
    val hub = runCatching { PhoneServices.of(context).hub }.getOrNull() ?: return emptyMap()
    return withContext(Dispatchers.IO) {
        ids.mapNotNull { id ->
            val pkg = runCatching {
                val shell = hub.shell.value ?: return@runCatching null
                val task = shell.topTaskId(id)
                if (task < 0) null else shell.packageOfTask(task)
            }.getOrNull()
            pkg?.let { id to ConfirmationNotifier.appLabel(context, it) }
        }.toMap()
    }
}

/** 目标在列表里只占一行:换行和连续空白并成一个空格。 */
private fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim()

/** 页面上的全部文字:不写屏号、像素、密度这些开发用的东西,只说任务在做什么、在用什么、到哪一步了。 */
object ScreensCopy {
    val TITLE: String get() = tr(R.string.screens_title)
    val EMPTY: String get() = tr(R.string.screens_empty)
    val NOTE: String get() = tr(R.string.screens_note)
    val PREVIEW: String get() = tr(R.string.screens_preview)
    val HIDE_PREVIEW: String get() = tr(R.string.screens_hide_preview)

    /** 卡片第二行:正在用哪个 App(或浏览器);还没打开 App、或暂时看不到时各有一句。 */
    fun place(row: ScreenRow): String = when {
        row.web -> tr(R.string.screens_using_browser)
        row.displayId < 0 -> tr(R.string.screens_no_app)
        row.foreground != null -> tr(R.string.screens_using_app, row.foreground)
        else -> tr(R.string.screens_app_unknown)
    }

    fun state(state: TaskState): String = when (state) {
        TaskState.Idle -> tr(R.string.screens_waiting)
        is TaskState.Running -> tr(R.string.task_running)
        // 只是等条件(锁屏、助手重连)的不算要你处理;要你在手机上动一下手的才算
        is TaskState.Held -> if (state.reason.needsYou) tr(R.string.screens_needs_you) else tr(R.string.screens_waiting)
        is TaskState.WaitingTakeover, is TaskState.WaitingInput, is TaskState.WaitingConfirmation -> tr(R.string.screens_needs_you)
        is TaskState.Succeeded -> tr(R.string.task_done)
        is TaskState.Failed -> tr(R.string.task_not_done)
        TaskState.Cancelled -> tr(R.string.task_stopped)
    }
}
