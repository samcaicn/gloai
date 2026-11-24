package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import android.view.Display
import android.util.DisplayMetrics
import android.hardware.display.DisplayManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.wangzan101.mika.ui.theme.MikaType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.chat.ui.PipPhysics.Corner
import com.wangzan101.mika.cap.phone.display.PipInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.components.floatShadow
import androidx.compose.ui.unit.Dp

/** 小窗宽度:收起 / 展开。高度按画面(没有画面时按主屏)的长宽比算,整屏完整显示、不裁底部。 */
private val CollapsedWidth = 96.dp
private val ExpandedWidth = 220.dp

/** 主屏的高宽比(按真实像素,含系统栏):虚拟屏按主屏尺寸建,还没有画面时小窗先按它定大小。 */
private fun mainScreenAspect(context: Context): Float {
    val m = DisplayMetrics()
    context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(m)
    return if (m.widthPixels > 0 && m.heightPixels > 0) m.heightPixels.toFloat() / m.widthPixels else DEFAULT_ASPECT
}

private const val DEFAULT_ASPECT = 2.2f
private val Ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * 悬浮画中画:盖在对话上,只显示 Agent 虚拟屏的实时画面,只看不点;上面不放文字和按钮,要你做的事都在对话卡片里。
 * 状态只靠边框光:干活中细绿边,需要你时橙色呼吸光晕(接管开始时由 [ChatViewModel] 自动展开)。
 * 平时停在右下角、输入栏上方,不挡顶栏和最新的消息。
 * 拖动 1:1 跟手;松手按速度投射落点吸到最近的角(临界阻尼弹簧,见 [PipPhysics]);
 * 点一下展开,点 × 或外面收起;需要你时点一下交给 [onAttention](收起并滚到那张卡片)。
 * 展开时左上角「关掉小画面」([onClose]):这个任务结束前只剩右下角一个小按钮([PipUi.closed]),
 * 点它再打开([onReopen]);要你处理时小按钮变橙色、呼吸,点它同样交给 [onAttention]。
 * [reduceMotion] 时(系统关了动画)不做弹簧、缩放和呼吸,直接到位。
 * [topInset]/[bottomInset] 是顶栏和输入框占的像素高度,小窗不会停在它们下面。
 */
@Composable
fun AgentPip(
    pip: PipUi,
    frame: Bitmap?,
    webContent: Boolean,
    /** 小窗直接操作的口子(点/滑/返回注入到虚拟屏);屏没建好或任务结束时为 null。 */
    pipInput: PipInput? = null,
    /** 目标 App 的声音被用户恢复过:按钮显示「静音」,再按一次静回去;否则「恢复声音」。 */
    soundOn: Boolean = false,
    /** 用户在设置里开了「允许我在小窗里直接操作」。 */
    operateEnabled: Boolean = false,
    reduceMotion: Boolean,
    topInset: Float,
    bottomInset: Float,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onAttention: () -> Unit,
    onClose: () -> Unit = {},
    onReopen: () -> Unit = {},
    onToggleSound: () -> Unit = {},
    /** 画面左下角写的事情名字(前台同时办着几件时说明这是哪件);为 null 不写。 */
    label: String? = null,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fade = if (reduceMotion) tween<Float>(150) else tween(300)
        // 关掉了:只留右下角的小按钮(任务做完那几秒不留)
        AnimatedVisibility(
            pip.visible && pip.closed && pip.mode != PipMode.Done, Modifier.align(Alignment.BottomEnd),
            enter = fadeIn(fade), exit = fadeOut(fade),
        ) {
            val bottom = with(LocalDensity.current) { bottomInset.toDp() }
            ClosedPip(pip.mode == PipMode.NeedsYou, reduceMotion, Modifier.padding(end = 12.dp, bottom = bottom + 12.dp), onReopen, onAttention)
        }
        AnimatedVisibility(pip.visible && !pip.closed && pip.expanded, enter = fadeIn(fade), exit = fadeOut(fade)) {
            Box(Modifier.fillMaxSize().background(MikaTheme.colors.floatShadow.copy(alpha = 0.12f)).pointerInput(Unit) { detectTapGestures { onCollapse() } })
        }
        AnimatedVisibility(pip.visible && !pip.closed, enter = fadeIn(fade), exit = fadeOut(if (reduceMotion) tween(150) else tween(500))) {
            PipWindow(
                pip, frame, webContent, pipInput, soundOn, operateEnabled, reduceMotion,
                areaWidth = constraints.maxWidth.toFloat(), areaHeight = constraints.maxHeight.toFloat(),
                topInset = topInset, bottomInset = bottomInset, onExpand = onExpand, onCollapse = onCollapse, onAttention = onAttention,
                onClose = onClose,
                onToggleSound = onToggleSound,
                label = label,
            )
        }
    }
}

/**
 * 小画面关掉后剩下的小按钮:平时写「显示画面」,点了再打开;要你处理时橙色呼吸、写「等你处理」,
 * 点了滚到要你处理的那张卡片(小画面关着也不漏掉要你做的事)。
 */
@Composable
private fun ClosedPip(needsYou: Boolean, reduceMotion: Boolean, modifier: Modifier, onReopen: () -> Unit, onAttention: () -> Unit) {
    val c = MikaTheme.colors
    val shape = MikaShapes.Pill
    val breath = if (needsYou && !reduceMotion) {
        rememberInfiniteTransition(label = "closedBreathe").animateFloat(
            1f, 0.45f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "closedBreathe",
        ).value
    } else 1f
    val label = stringResource(if (needsYou) R.string.pip_closed_needs_you else R.string.pip_reopen)
    Row(
        modifier.floatShadow(c, shape).background(c.glass, shape)
            .border(if (needsYou) 2.dp else 0.5.dp, if (needsYou) c.warnSolid.copy(alpha = breath) else c.glassEdge, shape)
            .clip(shape)
            .clickable(onClickLabel = label, role = Role.Button) { if (needsYou) onAttention() else onReopen() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("pip-closed"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (needsYou) Box(Modifier.size(7.dp).alpha(breath).background(c.warnSolid, CircleShape))
        else Icon(MikaIcons.Screens, null, Modifier.size(14.dp), tint = c.ink2)
        Text(label, style = MikaType.Hint, color = if (needsYou) c.warn else c.ink2)
    }
}

@Composable
private fun PipWindow(
    pip: PipUi,
    frame: Bitmap?,
    webContent: Boolean,
    pipInput: PipInput?,
    soundOn: Boolean,
    operateEnabled: Boolean,
    reduceMotion: Boolean,
    areaWidth: Float,
    areaHeight: Float,
    topInset: Float,
    bottomInset: Float,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onAttention: () -> Unit,
    onClose: () -> Unit,
    onToggleSound: () -> Unit,
    label: String?,
) {
    val density = LocalDensity.current
    var expandedScale by rememberSaveable { mutableStateOf(1f) }
    var pinching by remember { mutableStateOf(false) }
    val sizeSpec = if (reduceMotion || pinching) snap() else tween<androidx.compose.ui.unit.Dp>(350, easing = Ease)
    // 大小跟着画面的长宽比走:手机游戏和桌面网页都可能横向,按固定的宽度定高度,
    // 画面整张放得下;放不下时按比例整体缩小
    val context = LocalContext.current
    val screenAspect = remember { mainScreenAspect(context) }
    val landscapeFrame = frame != null && frame.width > frame.height
    val aspect = frame?.let { it.height.toFloat() / it.width } ?: screenAspect
    val target = run {
        val margin = with(density) { 24.dp.toPx() }
        val availableWidth = with(density) { (areaWidth - margin).coerceAtLeast(1f).toDp() }
        val availableHeight = with(density) { (areaHeight - topInset - bottomInset - margin).coerceAtLeast(1f).toDp() }
        val base = when {
            landscapeFrame -> if (pip.expanded) 320.dp else 220.dp
            else -> if (pip.expanded) ExpandedWidth else CollapsedWidth
        }
        val inset = if (pip.expanded) 8.dp else 6.dp
        val requested = base * if (pip.expanded) PipSizing.scale(expandedScale) else 1f
        val fitted = with(density) { PipSizing.fit(requested.toPx(), aspect, availableWidth.toPx(), availableHeight.toPx(), inset.toPx()) }
        with(density) { fitted.width.toDp() to fitted.height.toDp() }
    }
    val w by animateDpAsState(target.first, sizeSpec, label = "pipW")
    val h by animateDpAsState(target.second, sizeSpec, label = "pipH")
    val wPx = with(density) { w.toPx() }
    val hPx = with(density) { h.toPx() }
    // 收起的小窗让开顶栏和输入栏(别挡住打字和顶栏的按钮);展开时整块背景已经压暗、它们本来也点不到,
    // 小窗改放到整屏、只让开系统栏,不再挤在中间一小块里挪。展开收起途中按同一个时长插值,跟着大小一起挪,不跳。
    val collapsedShare by animateFloatAsState(
        if (pip.expanded) 0f else 1f,
        if (reduceMotion) snap() else tween<Float>(350, easing = Ease),
        label = "pipInset",
    )
    val (restTop, restBottom) = PipPhysics.restingInsets(
        collapsedTop = topInset,
        collapsedBottom = bottomInset,
        safeTop = WindowInsets.statusBars.getTop(density).toFloat(),
        safeBottom = WindowInsets.navigationBars.getBottom(density).toFloat(),
        collapsedShare = collapsedShare,
    )
    val bounds = PipPhysics.Bounds(areaWidth, areaHeight, wPx, hPx, with(density) { 12.dp.toPx() }, restTop, restBottom)
    val currentBounds by rememberUpdatedState(bounds)
    val motionOff by rememberUpdatedState(reduceMotion)
    val expanded by rememberUpdatedState(pip.expanded)
    val expand by rememberUpdatedState(onExpand)
    val needsYou by rememberUpdatedState(pip.mode == PipMode.NeedsYou)
    val attend by rememberUpdatedState(onAttention)

    var corner by rememberSaveable { mutableStateOf(Corner.BottomEnd) }
    // 拖动或弹簧途中的位置;为空表示贴在 corner 上(展开收起时跟着大小一起贴角)
    var free by remember { mutableStateOf<Offset?>(null) }
    val scope = rememberCoroutineScope()
    var settle by remember { mutableStateOf<Job?>(null) }

    /** 从 [start] 以速度 (vx, vy) 弹到角 [c];关了动画时直接到位。 */
    fun springTo(c: Corner, start: Offset, vx: Float, vy: Float) {
        settle?.cancel()
        corner = c
        if (motionOff) {
            free = null
            return
        }
        settle = scope.launch {
            val spring = PipPhysics.Spring()
            var mx = PipPhysics.Motion(start.x, vx)
            var my = PipPhysics.Motion(start.y, vy)
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dt = (now - last) / 1e9f
                last = now
                val (tx, ty) = currentBounds.position(c)
                mx = spring.step(mx, tx, dt)
                my = spring.step(my, ty, dt)
                free = Offset(mx.position, my.position)
                if (spring.settled(mx, tx) && spring.settled(my, ty)) break
            }
            free = null
        }
    }

    fun release(vx: Float, vy: Float) {
        val start = free ?: return
        springTo(PipPhysics.snapCorner(start.x, start.y, vx, vy, currentBounds), start, vx, vy)
    }

    // 完成时如果停在下方两个角,先挪到同侧上方,免得盖住刚出来的结果卡片
    LaunchedEffect(pip.mode) {
        if (pip.mode != PipMode.Done) return@LaunchedEffect
        val up = when (corner) {
            Corner.BottomStart -> Corner.TopStart
            Corner.BottomEnd -> Corner.TopEnd
            else -> return@LaunchedEffect
        }
        springTo(up, free ?: currentBounds.position(corner).let { (x, y) -> Offset(x, y) }, 0f, 0f)
    }

    val pos = free ?: bounds.position(corner).let { (x, y) -> Offset(x, y) }
    val outer = RoundedCornerShape(if (pip.expanded) MikaRadius.Sheet else MikaRadius.Card)
    val inner = RoundedCornerShape(if (pip.expanded) MikaRadius.Bubble else MikaRadius.Chip)
    val insetDp = if (pip.expanded) 4.dp else 3.dp
    // 直接操作:开了开关、小窗展开、有画面、口子还活着时才把点/滑注入到虚拟屏
    val operate = operateEnabled && pip.expanded && frame != null && pipInput != null
    val operateInput = pipInput.takeIf { operate }
    val breath = if (pip.mode == PipMode.NeedsYou && !reduceMotion) {
        rememberInfiniteTransition(label = "breathe").animateFloat(
            1f, 0.55f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "breathe",
        ).value
    } else 1f

    Box(Modifier.offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }) {
        if (pip.mode == PipMode.NeedsYou) {
            // 橙色光晕:比窗口大一圈,模糊后透出边缘(安卓 12 以下不模糊,只是一圈淡橙色)
            Box(
                Modifier.offset((-5).dp, (-5).dp).size(w + 10.dp, h + 10.dp).alpha(breath)
                    .blur(10.dp).background(MikaTheme.colors.warnSolid.copy(alpha = 0.55f), outer),
            )
        }
        Box(
            Modifier.testTag("pip-window").size(w, h)
                .alpha(if (pip.mode == PipMode.Done) 0.85f else 1f)
                .floatShadow(MikaTheme.colors, outer)
                .background(MikaTheme.colors.glass, outer)
                .border(0.5.dp, MikaTheme.colors.glassEdge, outer)
                .then(if (operate) Modifier else Modifier.pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    // 自己数位移而不用 detectDragGestures:越过触摸阈值时把阈值内的那段也补上,
                    // 窗口始终保持按下时的抓取位置,真正 1:1 跟手
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val origin = free ?: currentBounds.position(corner).let { (x, y) -> Offset(x, y) }
                        var total = Offset.Zero
                        var dragging = false
                        var resized = false
                        tracker.resetTracking()
                        tracker.addPosition(down.uptimeMillis, origin + down.position)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (expanded && event.changes.count { it.pressed } >= 2) {
                                    resized = true
                                    pinching = true
                                    settle?.cancel()
                                    free = null
                                    expandedScale = PipSizing.scale(expandedScale * event.calculateZoom())
                                    event.changes.forEach { it.consume() }
                                    continue
                                }
                                // Once a pinch starts, no remaining finger can become a drag or tap.
                                if (resized) {
                                    event.changes.forEach { it.consume() }
                                    if (event.changes.none { it.pressed }) break
                                    continue
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                total += change.positionChange()
                                if (!dragging && total.getDistance() > viewConfiguration.touchSlop) {
                                    dragging = true
                                    settle?.cancel()
                                }
                                if (dragging) {
                                    change.consume()
                                    free = origin + total
                                    tracker.addPosition(change.uptimeMillis, origin + down.position + total)
                                }
                            }
                        } finally {
                            pinching = false
                        }
                        if (dragging && !resized) tracker.calculateVelocity().let { v -> release(v.x, v.y) }
                    }
                })
                .then(if (operate) Modifier else Modifier.pointerInput(Unit) { detectTapGestures { if (needsYou) attend() else if (!expanded) expand() } })
                .padding(insetDp),
        ) {
            // 画面按长宽比塞满整个内框;直接操作时把点/滑按内框归一化坐标换算成虚拟屏坐标注入
            Box(
                Modifier.fillMaxSize().clip(inner).background(MikaTheme.colors.surface),
                contentAlignment = Alignment.Center,
            ) {
                if (frame != null) {
                    val image = remember(frame) { frame.asImageBitmap() }
                    val fit = fittedSize(frame.width, frame.height, w - insetDp * 2, h - insetDp * 2)
                    Box(
                        Modifier.size(fit.first, fit.second).testTag("pip-frame")
                            .then(if (operateInput != null) operateInputModifier(operateInput, scope) else Modifier),
                    ) {
                        Image(image, stringResource(R.string.pip_screen), Modifier.fillMaxSize(), alignment = Alignment.Center,
                            contentScale = ContentScale.Fit)
                    }
                }
            }
            if (label != null) {
                Text(
                    label,
                    Modifier.align(Alignment.BottomStart).padding(6.dp).widthIn(max = if (pip.expanded) w * 0.5f else w - 12.dp)
                        .background(MikaTheme.colors.glass, MikaShapes.Pill).padding(horizontal = 7.dp, vertical = 1.dp)
                        .testTag("pip-label"),
                    style = MikaType.Micro, color = MikaTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (pip.expanded) {
                Row(Modifier.align(Alignment.BottomEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PipSizeButton("−", stringResource(R.string.pip_size_smaller), "pip-size-smaller") {
                        expandedScale = PipSizing.scale(expandedScale / 1.2f)
                    }
                    PipSizeButton("+", stringResource(R.string.pip_size_larger), "pip-size-larger") {
                        expandedScale = PipSizing.scale(expandedScale * 1.2f)
                    }
                    PipSizeButton("↺", stringResource(R.string.pip_size_reset), "pip-size-reset") { expandedScale = 1f }
                }
                // 左上角:返回(直接操作时)、声音开关(恢复/再静,方向跟着任务里的真实音频记录)、关掉小画面;不伪造与真实音频无关的开关状态
                Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (operateInput != null) {
                        PipButton(
                            icon = MikaIcons.Back,
                            label = stringResource(R.string.pip_back),
                            onClick = { scope.launch { operateInput.back() } },
                            tag = "pip-back",
                        )
                    }
                    PipButton(
                        icon = if (soundOn) MikaIcons.Sound else MikaIcons.Mute,
                        label = stringResource(if (soundOn) R.string.pip_mute else R.string.pip_unmute),
                        onClick = onToggleSound,
                        tag = if (soundOn) "pip-mute" else "pip-unmute",
                    )
                    PipButton(
                        icon = MikaIcons.EyeOff,
                        label = stringResource(R.string.pip_close),
                        onClick = onClose,
                        tag = "pip-close",
                    )
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp)
                        .floatShadow(MikaTheme.colors, CircleShape).background(MikaTheme.colors.glass, CircleShape).clip(CircleShape)
                        .clickable(onClickLabel = stringResource(R.string.collapse), onClick = onCollapse),
                    contentAlignment = Alignment.Center,
                ) { Icon(MikaIcons.Close, stringResource(R.string.collapse), Modifier.size(12.dp), tint = MikaTheme.colors.ink) }
            }
        }
        // 边框光画在玻璃框外沿
        val glow = when (pip.mode) {
            PipMode.Running -> MikaTheme.colors.ok to 2.dp
            PipMode.NeedsYou -> MikaTheme.colors.warnSolid.copy(alpha = breath) to 2.dp
            PipMode.Done -> null
        }
        if (glow != null) Box(Modifier.size(w, h).border(glow.second, glow.first, outer))
    }
}

@Composable
private fun PipButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, tag: String) {
    Box(
        Modifier.size(26.dp)
            .floatShadow(MikaTheme.colors, CircleShape).background(MikaTheme.colors.glass, CircleShape).clip(CircleShape)
            .clickable(onClickLabel = label, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(13.dp), tint = MikaTheme.colors.ink) }
}

/** 画面按长宽比塞进 innerW×innerH 后的尺寸(不裁、不拉伸),没画面或尺寸异常时退回整框。 */
private fun fittedSize(frameW: Int, frameH: Int, innerW: Dp, innerH: Dp): Pair<Dp, Dp> {
    if (frameW <= 0 || frameH <= 0) return innerW to innerH
    val a = frameW.toFloat() / frameH
    val w = if (innerW / a <= innerH) innerW else innerH * a
    return w to w / a
}

/** 直接操作时小窗画面的触摸:按下没动就点一下,动了就按走过的点滑一笔;坐标按这个框归一化到 0..1。 */
private fun operateInputModifier(input: PipInput, scope: CoroutineScope): Modifier = Modifier.pointerInput(input) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val points = mutableListOf(down.position)
        var lastUptime = down.uptimeMillis
        var moved = false
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            change.consume()
            points += change.position
            lastUptime = change.uptimeMillis
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
        }
        val pts = points.map { p ->
            (p.x / size.width).coerceIn(0f, 1f) to (p.y / size.height).coerceIn(0f, 1f)
        }
        if (!moved) {
            val (fx, fy) = pts.last()
            scope.launch { input.tap(fx, fy) }
        } else {
            val duration = (lastUptime - down.uptimeMillis).coerceIn(80L, 600L)
            scope.launch { input.swipe(pts, duration) }
        }
    }
}

/** Discrete controls also let a screen-reader user resize without a two-finger gesture. */
@Composable
private fun PipSizeButton(mark: String, label: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.size(26.dp).floatShadow(MikaTheme.colors, CircleShape)
        .background(MikaTheme.colors.glass, CircleShape).clip(CircleShape)
        .clickable(role = Role.Button, onClickLabel = label, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center) {
        Text(mark, style = MikaType.Hint, color = MikaTheme.colors.ink)
    }
}
