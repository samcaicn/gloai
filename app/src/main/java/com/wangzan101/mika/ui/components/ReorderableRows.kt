package com.wangzan101.mika.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shared drag preview, edge scrolling and accessible move actions for settings lists. */
class ReorderableRowsState internal constructor(
    internal val scroll: ScrollState,
    internal val scope: CoroutineScope,
) {
    internal val heights = mutableStateMapOf<String, Int>()
    var viewport by mutableStateOf(Rect.Zero)
    internal var pointerY by mutableFloatStateOf(0f)
    internal var draggedId by mutableStateOf<String?>(null)
    internal var dragDistance by mutableFloatStateOf(0f)
    internal var startScroll by mutableIntStateOf(0)
    internal var previewOrder by mutableStateOf<List<String>?>(null)
    internal var saving by mutableStateOf(false)
    internal var ids: List<String> = emptyList()
    internal var onStart: () -> Unit = {}
    internal var onSave: suspend (List<String>) -> Boolean = { true }
    val busy: Boolean get() = saving || draggedId != null

    internal fun updatePreview() {
        val id = draggedId ?: return
        val originalIndex = ids.indexOf(id)
        if (originalIndex < 0) return
        val center = ids.take(originalIndex).sumOf { heights[it] ?: 0 } +
            (heights[id] ?: 0) / 2f + dragDistance + scroll.value - startScroll
        val current = previewOrder ?: ids
        var top = 0f
        var target = current.lastIndex
        for ((index, item) in current.withIndex()) {
            val height = (heights[item] ?: 0).toFloat()
            if (center < top + height / 2f) { target = index; break }
            top += height
        }
        val from = current.indexOf(id)
        if (from >= 0 && target != from) {
            previewOrder = current.toMutableList().apply { add(target, removeAt(from)) }
        }
    }
}

@Composable
fun rememberReorderableRowsState(
    ids: List<String>,
    scroll: ScrollState,
    onStart: () -> Unit,
    onSave: suspend (List<String>) -> Boolean,
): ReorderableRowsState {
    val scope = rememberCoroutineScope()
    val state = remember(scroll) { ReorderableRowsState(scroll, scope) }
    state.ids = ids
    state.onStart = onStart
    state.onSave = onSave
    val density = LocalDensity.current.density
    LaunchedEffect(ids, state.draggedId, state.saving, state.previewOrder) {
        if (state.draggedId == null && !state.saving && state.previewOrder == ids) state.previewOrder = null
    }
    LaunchedEffect(state.draggedId) {
        while (state.draggedId != null) {
            val edge = 72f * density
            val amount = when {
                state.pointerY < state.viewport.top + edge -> -12f * density
                state.pointerY > state.viewport.bottom - edge -> 12f * density
                else -> 0f
            }
            if (amount != 0f) {
                scroll.scrollTo((scroll.value + amount).toInt())
                state.updatePreview()
            }
            delay(16)
        }
    }
    return state
}

@Composable
fun ReorderableRow(
    state: ReorderableRowsState,
    id: String,
    index: Int,
    dragTag: String,
    content: @Composable (first: Boolean, dragHandle: @Composable () -> Unit) -> Unit,
) {
    val ids = state.ids
    val order = state.previewOrder ?: ids
    val destination = order.indexOf(id).coerceAtLeast(0)
    val oldTop = ids.take(index).sumOf { state.heights[it] ?: 0 }
    val newTop = order.take(destination).sumOf { state.heights[it] ?: 0 }
    val moving = state.draggedId == id
    var handleTop by remember { mutableFloatStateOf(0f) }
    val offset by key(ids) {
        animateFloatAsState(
            if (moving) state.dragDistance + state.scroll.value - state.startScroll else (newTop - oldTop).toFloat(),
            tween(if (moving) 0 else 180), label = "reorder position",
        )
    }
    fun save(orderToSave: List<String>) {
        state.saving = true
        state.onStart()
        state.previewOrder = orderToSave
        state.draggedId = null
        state.scope.launch {
            delay(180)
            if (!state.onSave(orderToSave)) state.previewOrder = null
            state.saving = false
        }
    }
    val moveUp = stringResource(R.string.recommendations_move_up)
    val moveDown = stringResource(R.string.recommendations_move_down)
    val colors = MikaTheme.colors
    Box(
        Modifier.fillMaxWidth().zIndex(if (moving) 1f else 0f)
            .graphicsLayer { translationY = offset; shadowElevation = if (moving) 8.dp.toPx() else 0f }
            .background(colors.surface)
            .onSizeChanged { state.heights[id] = it.height }
            .semantics {
                customActions = buildList {
                    if (index > 0) add(CustomAccessibilityAction(moveUp) {
                        if (!state.busy) save(ids.toMutableList().apply { add(index - 1, removeAt(index)) })
                        true
                    })
                    if (index < ids.lastIndex) add(CustomAccessibilityAction(moveDown) {
                        if (!state.busy) save(ids.toMutableList().apply { add(index + 1, removeAt(index)) })
                        true
                    })
                }
            },
    ) {
        content(destination == 0) {
            Box(
                Modifier.size(MikaSize.IconButton)
                    .onGloballyPositioned { if (moving || state.draggedId == null) handleTop = it.boundsInWindow().top }
                    .testTag(dragTag)
                    .pointerInput(id, ids, state.saving) {
                        detectDragGestures(
                            onDragStart = { position ->
                                if (!state.saving) {
                                    state.draggedId = id
                                    state.dragDistance = 0f
                                    state.startScroll = state.scroll.value
                                    state.pointerY = handleTop + position.y
                                    state.previewOrder = ids
                                    state.onStart()
                                }
                            },
                            onDragEnd = { if (state.draggedId == id) save(state.previewOrder ?: ids) },
                            onDragCancel = { if (state.draggedId == id) { state.draggedId = null; state.previewOrder = null } },
                        ) { change, amount ->
                            if (state.draggedId == id) {
                                change.consume()
                                state.dragDistance += amount.y
                                state.pointerY = handleTop + change.position.y
                                state.updatePreview()
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(MikaIcons.Menu, stringResource(R.string.recommendations_drag), Modifier.size(MikaSize.Icon), tint = colors.ink3)
            }
        }
    }
}
