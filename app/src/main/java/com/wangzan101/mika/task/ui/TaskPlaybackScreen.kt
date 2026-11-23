package com.wangzan101.mika.task.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.mika.R
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.run.record.PlaybackMark
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import com.wangzan101.mika.memory.ui.Row
import com.wangzan101.mika.ui.ToolLabels

@Composable
private fun stepLabel(step: PlaybackStep): String {
    val labels = remember { ToolLabels(knownTools()) }
    return step.tool?.let(labels::label) ?: stringResource(R.string.step_number, step.step)
}

@Composable
fun TaskPlaybackScreen(vm: TaskReportViewModel, initialScreenshotId: Int?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val (loaded, report) = rememberReport(vm)
    val c = MikaTheme.colors
    var position by rememberSaveable(vm.runId, initialScreenshotId) { mutableIntStateOf(-1) }
    var speedIndex by rememberSaveable(vm.runId) { mutableIntStateOf(1) }
    val speeds = listOf(.5f, 1f, 2f)
    var playing by remember(vm) { mutableStateOf(false) }
    var ready by remember(vm) { mutableIntStateOf(-1) }
    var fullscreen by remember { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) playing = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val frames = report?.frames.orEmpty()
    LaunchedEffect(frames, initialScreenshotId) {
        if (position < 0 && frames.isNotEmpty()) {
            // 关键页直接打开对应截图；普通回放从首张可用画面开始。
            position = TaskReportViewModel.framePosition(frames, initialScreenshotId)
        }
    }
    val current = position.coerceIn(0, frames.lastIndex.coerceAtLeast(0))
    LaunchedEffect(playing, speedIndex, current, ready) {
        if (playing && ready == current && frames.isNotEmpty()) {
            delay((TaskReportViewModel.frameDuration(frames[current]) / speeds[speedIndex]).toLong())
            if (current < frames.lastIndex) position = current + 1 else playing = false
        }
    }
    fun seek(frame: Int) { playing = false; position = frame.coerceIn(0, frames.lastIndex.coerceAtLeast(0)) }
    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(stringResource(R.string.playback_title), MikaIcons.Back, onBack, stringResource(R.string.back))
            val r = report
            if (r == null || frames.isEmpty()) {
                if (loaded) EmptyNote(stringResource(if (r == null) R.string.timeline_expired else if (r.missingFrames > 0) R.string.playback_missing else R.string.playback_empty), Modifier.padding(24.dp))
            } else {
                val frame = frames[current]
                val step = r.playback[frame.stepIndex]
                val finalResult = frame.resultOnly && current == frames.lastIndex && frame.stepIndex == r.playback.lastIndex
                val stage = r.stages.lastOrNull { it.stepIndex <= frame.stepIndex }
                val stageScroll = rememberLazyListState()
                LaunchedEffect(stage) { stageScroll.animateScrollToItem(r.stages.indexOf(stage).coerceAtLeast(0)) }
                val controls: @Composable () -> Unit = {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(MikaRadius.Bubble)).background(c.surface).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(when {
                                finalResult -> stringResource(R.string.report_final_page)
                                frame.resultOnly -> stringResource(R.string.playback_result)
                                else -> stepLabel(step)
                            }, Modifier.weight(1f), style = MikaType.BodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${current + 1} / ${frames.size}", style = MikaType.Hint, color = c.ink2)
                        }
                        val progressLabel = stringResource(R.string.playback_progress)
                        Slider(current.toFloat(), { seek(it.toInt()) }, valueRange = 0f..frames.lastIndex.coerceAtLeast(1).toFloat(), enabled = frames.size > 1,
                            modifier = Modifier.fillMaxWidth().height(28.dp).semantics { contentDescription = progressLabel },
                            colors = SliderDefaults.colors(activeTrackColor = c.accent, inactiveTrackColor = c.fill, thumbColor = c.accent))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton({ seek(current - 1) }, enabled = current > 0, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) {
                                Text(stringResource(R.string.playback_previous), style = MikaType.Caption, maxLines = 1)
                            }
                            MikaButton(stringResource(if (playing) R.string.playback_pause else R.string.playback_play), {
                                if (playing) playing = false else { if (current == frames.lastIndex) position = 0; playing = true }
                            }, Modifier.weight(1.2f), kind = ButtonKind.Primary, size = ButtonSize.Small)
                            TextButton({ seek(current + 1) }, enabled = current < frames.lastIndex, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) {
                                Text(stringResource(R.string.playback_next), style = MikaType.Caption, maxLines = 1)
                            }
                            TextButton({ speedIndex = (speedIndex + 1) % speeds.size }, contentPadding = PaddingValues(2.dp), modifier = Modifier.width(42.dp)) {
                                Text(listOf("0.5×", "1×", "2×")[speedIndex], style = MikaType.Caption)
                            }
                        }
                        step.failure?.let { Text(it, style = MikaType.Hint, color = c.bad) }
                        if (r.stages.isNotEmpty()) LazyRow(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp), state = stageScroll, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            itemsIndexed(r.stages) { i, s ->
                                Text("${i + 1} · ${s.title}", Modifier.widthIn(max = 180.dp).clip(MikaShapes.Tile)
                                    .background(if (s == stage) c.accentSoft else c.bg).clickable { seek(TaskReportViewModel.framePosition(frames, s.screenshotId, s.stepIndex)) }.padding(9.dp, 7.dp),
                                    style = MikaType.Hint, color = if (s == stage) c.accent else c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                val picture: @Composable (Modifier) -> Unit = { modifier ->
                    Box(modifier.testTag("playback-viewport").playbackGestures(
                        onTap = { playing = false; fullscreen = true }, onDrag = { playing = false }, onSwipe = { seek(current + it) }
                    ), contentAlignment = Alignment.Center) {
                        AnimatedPlaybackImage(vm, frame, Modifier.fillMaxSize()) { ready = current }
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    if (maxWidth > maxHeight) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        picture(Modifier.weight(1f).fillMaxHeight())
                        Column(Modifier.width(300.dp)) { controls() }
                    } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        picture(Modifier.weight(1f).fillMaxWidth())
                        Text(stringResource(when {
                            finalResult -> R.string.report_final_page
                            frame.resultOnly -> R.string.playback_result
                            step.elementClick || (frame.mark?.kind == "tap" && frame.mark.approximate) -> R.string.playback_element_click
                            step.legacy -> R.string.playback_legacy
                            frame.mark?.approximate == true -> R.string.playback_approximate
                            frame.mark != null -> R.string.playback_marks
                            step.tool in listOf("phone_tap", "phone_swipe", "phone_scroll") -> R.string.playback_no_mark
                            else -> R.string.playback_image
                        }),
                            Modifier.align(Alignment.CenterHorizontally), style = MikaType.Hint, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        controls()
                        if (r.missingFrames > 0) Text(stringResource(R.string.playback_skipped, r.missingFrames), style = MikaType.Hint, color = c.ink2)
                    }
                }
                if (fullscreen) Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding().padding(12.dp)) {
                        TextButton({ fullscreen = false }, Modifier.align(Alignment.End)) { Text(stringResource(R.string.report_close)) }
                        AnimatedPlaybackImage(vm, frame, Modifier.weight(1f).fillMaxWidth().testTag("playback-fullscreen").playbackGestures(
                            onTap = {}, onDrag = { playing = false }, onSwipe = { seek(current + it) }
                        )) {}
                    }
                }
            }
        }
    }
}

/** 拖动超过阈值后按主方向翻一帧；短拖动、取消和边界不误触放大或跳多帧。 */
internal fun replaySwipeDirection(dx: Float, dy: Float, threshold: Float): Int {
    val distance = if (abs(dx) >= abs(dy)) dx else dy
    return when { abs(distance) < threshold -> 0; distance < 0 -> 1; else -> -1 }
}

@Composable
private fun Modifier.playbackGestures(onTap: () -> Unit, onDrag: () -> Unit, onSwipe: (Int) -> Unit): Modifier {
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    val drag by rememberUpdatedState(onDrag)
    val swipe by rememberUpdatedState(onSwipe)
    return pointerInput(threshold) {
        var total = Offset.Zero
        detectDragGestures(
            onDragStart = { total = Offset.Zero; drag() },
            onDrag = { change, amount -> change.consume(); total += amount },
            onDragCancel = { total = Offset.Zero },
            onDragEnd = { replaySwipeDirection(total.x, total.y, threshold).takeIf { it != 0 }?.let(swipe); total = Offset.Zero },
        )
    }.clickable(onClick = onTap)
}

private data class LoadedPlayback(val frame: ReplayFrame, val bitmap: ImageBitmap?)

@Composable
private fun AnimatedPlaybackImage(vm: TaskReportViewModel, frame: ReplayFrame, modifier: Modifier, onReady: () -> Unit) {
    var displayed by remember(vm) { mutableStateOf<LoadedPlayback?>(null) }
    val ready by rememberUpdatedState(onReady)
    LaunchedEffect(vm, frame) {
        val bitmap = withContext(Dispatchers.IO) { runCatching { vm.screen(frame.frame.screenshotId)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }.getOrNull() }
        displayed = LoadedPlayback(frame, bitmap)
        ready()
    }
    // 已解码的图片及其自身标记一起交叉淡入，手动/自动走同一路径，不闪空、不串标记。
    Crossfade(displayed, modifier, animationSpec = tween(TaskReportViewModel.TRANSITION_MS), label = "replay-frame") { loaded ->
        if (loaded?.bitmap != null) Box(Modifier.fillMaxSize().testTag("playback-frame-${loaded.frame.frame.screenshotId}")) {
            FittedPlaybackImage(loaded.bitmap, loaded.frame.mark, Modifier.fillMaxSize())
        }
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loaded != null) Text(stringResource(R.string.playback_missing), style = MikaType.Caption, color = MikaTheme.colors.ink2)
        }
    }
}

@Composable
internal fun StaticPlaybackImage(vm: TaskReportViewModel, id: Int, modifier: Modifier) {
    var image by remember(vm, id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(vm, id) { image = withContext(Dispatchers.IO) { runCatching { vm.screen(id)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }.getOrNull() } }
    Box(modifier, contentAlignment = Alignment.Center) { image?.let { Image(it, stringResource(R.string.playback_image), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } }
}

@Composable
private fun FittedPlaybackImage(bitmap: ImageBitmap, mark: PlaybackMark?, modifier: Modifier) {
    val c = MikaTheme.colors
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = bitmap.width.toFloat() / bitmap.height
        val width = minOf(maxWidth, maxHeight * ratio)
        Box(Modifier.size(width, width / ratio).clip(MikaShapes.Tile).testTag("playback-image")) {
            Image(bitmap, stringResource(R.string.playback_image), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            mark?.takeIf { it.valid() }?.let { m ->
                Canvas(Modifier.matchParentSize().testTag("playback-mark-${m.kind}")) {
                    fun point(x: Int, y: Int) = Offset(x / 999f * size.width, y / 999f * size.height)
                    val start = point(m.x, m.y)
                    if (m.kind == "tap") {
                        drawCircle(c.bad.copy(alpha = .18f), 11.dp.toPx(), start)
                        drawCircle(c.bad, 9.dp.toPx(), start, style = Stroke(2.dp.toPx()))
                    } else {
                        val end = point(m.endX!!, m.endY!!)
                        drawLine(c.bad, start, end, 2.5.dp.toPx())
                        val angle = atan2(end.y - start.y, end.x - start.x)
                        val length = 9.dp.toPx()
                        val path = Path().apply {
                            moveTo(end.x, end.y)
                            lineTo(end.x - length * cos(angle - .5f), end.y - length * sin(angle - .5f))
                            lineTo(end.x - length * cos(angle + .5f), end.y - length * sin(angle + .5f)); close()
                        }
                        drawPath(path, c.bad)
                    }
                }
            }
        }
    }
}
