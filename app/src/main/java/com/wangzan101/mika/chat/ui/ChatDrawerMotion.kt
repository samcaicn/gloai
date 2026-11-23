@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.wangzan101.mika.chat.ui

import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DragScope
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaMotion
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.max

internal const val CHAT_DRAWER_OPEN_FRACTION = 0.4f
private const val MIN_FLING_FRACTION = 0.15f

/** Material 抽屉没有可配置的距离阈值;保留同样的锚点拖动,以展开行程的 40% 为分界。 */
@Stable
internal class ChatDrawerState(initial: DrawerValue, density: Density) {
    var density by mutableStateOf(density)
    var widthPx by mutableIntStateOf(0)
        private set
    val drag: AnchoredDraggableState<DrawerValue> = AnchoredDraggableState(
        initialValue = initial,
        positionalThreshold = { distance ->
            // Foundation 会在拖动中途更新 currentValue。反向距离须用 60%,
            // 才能始终以绝对展开位置 40% 分界,避免 45% 展开、55% 反而回弹。
            distance * if (isCurrentAnchorOpen()) 1f - CHAT_DRAWER_OPEN_FRACTION else CHAT_DRAWER_OPEN_FRACTION
        },
        velocityThreshold = { with(this.density) { 400.dp.toPx() } },
        snapAnimationSpec = tween<Float>(256, easing = MikaMotion.Decelerate),
        decayAnimationSpec = exponentialDecay(),
    )
    private fun isCurrentAnchorOpen(): Boolean = drag.currentValue == DrawerValue.Open
    val currentOffset get() = drag.offset
    val targetValue get() = drag.targetValue
    val isOpen get() = drag.settledValue == DrawerValue.Open
    private var gestureStartOffset = 0f

    // 拖动仍使用同一份锚点状态和互斥锁,抓住正在播放的动画时可以原地接手。
    // 松手不走 animateToWithDecay:当前 Foundation 对零点终点的惯性判定会直接结束首帧。
    val touch = object : DraggableState {
        override suspend fun drag(dragPriority: MutatePriority, block: suspend DragScope.() -> Unit) {
            this@ChatDrawerState.drag.anchoredDrag(dragPriority) { anchors ->
                val anchoredScope = this
                gestureStartOffset = currentOffset
                block(object : DragScope {
                    override fun dragBy(pixels: Float) {
                        anchoredScope.dragTo((currentOffset + pixels).coerceIn(anchors.minAnchor(), anchors.maxAnchor()))
                    }
                })
            }
        }

        override fun dispatchRawDelta(delta: Float) {
            this@ChatDrawerState.drag.dispatchRawDelta(delta)
        }
    }

    suspend fun settle(velocity: Float) {
        val reveal = (widthPx + currentOffset).coerceIn(0f, widthPx.toFloat())
        val minFlingDistance = max(widthPx * MIN_FLING_FRACTION, with(density) { 24.dp.toPx() })
        val intentionalFling = abs(velocity) >= with(density) { 400.dp.toPx() } &&
            abs(currentOffset - gestureStartOffset) >= minFlingDistance
        val target = when {
            intentionalFling -> if (velocity > 0f) DrawerValue.Open else DrawerValue.Closed
            reveal >= widthPx * CHAT_DRAWER_OPEN_FRACTION -> DrawerValue.Open
            else -> DrawerValue.Closed
        }
        // Tween 不把甩动速度带成冲刺,剩余路程始终有可见的减速过程。
        drag.animateTo(target)
    }

    fun resize(width: Int) {
        widthPx = width
        drag.updateAnchors(DraggableAnchors {
            DrawerValue.Closed at -width.toFloat()
            DrawerValue.Open at 0f
        })
    }

    suspend fun open() = drag.animateTo(DrawerValue.Open)
    suspend fun close() = drag.animateTo(DrawerValue.Closed)
    suspend fun snapTo(value: DrawerValue) = drag.snapTo(value)
}

@Composable
internal fun rememberChatDrawerState(initial: DrawerValue = DrawerValue.Closed): ChatDrawerState {
    val density = LocalDensity.current
    val state = remember { ChatDrawerState(initial, density) }
    SideEffect { state.density = density }
    return state
}

/** 侧栏在下、聊天卡片在上;手势、RTL、返回可访问性和点击收起共用同一份位置。 */
@Composable
internal fun ChatNavigationDrawer(
    drawerContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    drawerState: ChatDrawerState,
    gesturesEnabled: Boolean,
    contentOffset: () -> Float,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val title = stringResource(R.string.chat_menu)
    val collapse = stringResource(R.string.collapse)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier.fillMaxSize().draggable(
        state = drawerState.touch,
        orientation = Orientation.Horizontal,
        enabled = gesturesEnabled,
        reverseDirection = rtl,
        startDragImmediately = drawerState.drag.isAnimationRunning,
        onDragStopped = { drawerState.settle(it) },
    )) {
        Box(Modifier.onSizeChanged { drawerState.resize(it.width) }.offset {
            val offset = drawerState.currentOffset
            IntOffset(if (offset.isFinite()) offset.roundToInt() else if (drawerState.isOpen) 0 else -drawerState.widthPx, 0)
        }.semantics {
            paneTitle = title
            if (drawerState.isOpen) dismiss {
                onDismiss()
                true
            }
        }) { drawerContent() }
        content()
        if (drawerState.isOpen) {
            Box(Modifier.fillMaxSize().offset {
                IntOffset(contentOffset().roundToInt(), 0)
            }.clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                enabled = gesturesEnabled, role = Role.Button, onClickLabel = collapse,
            ) { onDismiss() }.semantics { contentDescription = collapse })
        }
    }
}
