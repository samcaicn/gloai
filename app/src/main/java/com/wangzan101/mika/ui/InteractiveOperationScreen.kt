package com.wangzan101.mika.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.IntSize
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.theme.MikaIcons
import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.run.ManualControl
import com.wangzan101.mika.task.ScreenControl
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 触点只送给传入的那一次运行；首次触摸只申请接管，不重放在等待期间可能已失效的触点。 */
@Composable
internal fun InteractiveOperationScreen(run: ScreenControl, frame: Bitmap?, mode: PipMode, onConversations: () -> Unit, onClose: () -> Unit) {
    val control by run.manualControl.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember(run.key) { mutableStateOf(false) }
    var text by remember(run.key) { mutableStateOf("") }
    val manual = control == ManualControl.State.Manual
    var viewport by remember(run.key) { mutableStateOf(IntSize.Zero) }
    var resizing by remember(run.key) { mutableStateOf(false) }
    LaunchedEffect(run.key, viewport, frame != null) {
        if (viewport.width < 64 || viewport.height < 64 || frame == null || control == ManualControl.State.Closed) return@LaunchedEffect
        resizing = true
        try {
            delay(180)
            run.resizeScreen(viewport.width, viewport.height)
        } finally { resizing = false }
    }
    fun perform(action: suspend () -> Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                if (!action()) Toast.makeText(context, R.string.screen_control_failed, Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(context, R.string.screen_control_failed, Toast.LENGTH_SHORT).show() }
            finally { busy = false }
        }
    }
    val w = frame?.width ?: 0
    val h = frame?.height ?: 0
    val touch = Modifier.pointerInput(run.key, control, busy, resizing, viewport, w, h) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = previewPoint(down.position.x, down.position.y, size.width, size.height, w, h)
            if (start == null || busy || resizing || control == ManualControl.State.Requesting || control == ManualControl.State.Closed) return@awaitEachGesture
            down.consume()
            if (!manual) { perform { run.beginScreenControl() }; return@awaitEachGesture }
            var end = down
            var multiple = false
            var moved = false
            do {
                val event = awaitPointerEvent()
                if (event.changes.size != 1) multiple = true
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                // 取消/被别的手势接走时不注入一个补造的点击。
                if (change.isConsumed) { multiple = true; break }
                end = change
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                change.consume()
            } while (end.pressed)
            if (!multiple && !end.pressed) {
                val finish = previewPoint(end.position.x, end.position.y, size.width, size.height, w, h)
                if (finish != null) perform {
                    run.touchScreen(w, h, start.first, start.second, finish.first, finish.second,
                        end.uptimeMillis - down.uptimeMillis, swipe = moved)
                }
            }
        }
    }
    val toggleEnabled = !busy && !resizing && (manual || frame != null) && control != ManualControl.State.Requesting && control != ManualControl.State.Closed
    val toggleLabel = stringResource(if (manual) R.string.screen_control_resume else R.string.screen_control_start)
    OperationScreenPane(frame, mode, onConversations, onClose, imageModifier = touch,
        titleModifier = Modifier.fillMaxHeight().testTag("screen-control-toggle").clickable(enabled = toggleEnabled, onClickLabel = toggleLabel) {
            perform { if (manual) { run.continueAfterScreenControl(); true } else run.beginScreenControl() }
        },
        statusText = stringResource(when (control) {
            ManualControl.State.Automatic -> R.string.screen_control_hint
            ManualControl.State.Requesting -> R.string.screen_control_wait
            ManualControl.State.Manual -> R.string.screen_control_active
            ManualControl.State.Closed -> R.string.task_done
        }),
        onViewport = { width, height -> viewport = IntSize(width, height) },
        controls = if (!manual) null else ({
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BarIconButton(MikaIcons.Back, stringResource(R.string.back), { perform { run.screenBack(w, h) } }, enabled = !busy && !resizing)
                OutlinedTextField(text, { text = it }, Modifier.weight(1f).keepsKeyboard().testTag("screen-control-text"),
                    enabled = !busy, textStyle = MikaType.Body,
                    label = { Text(stringResource(R.string.screen_control_text), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MikaType.Hint) },
                    maxLines = 3)
                TextButton(enabled = !busy && !resizing && text.isNotEmpty(), onClick = {
                    val entered = text
                    perform { run.screenText(w, h, entered).also { if (it) text = "" } }
                }) { Text(stringResource(R.string.screen_control_send_text)) }
            }
        }),
    )
}
