package com.wangzan101.mika.capsule




import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.chat.ui.FollowUpMode
import com.wangzan101.mika.chat.ui.ScreenCopy
import com.wangzan101.mika.chat.ui.ScreenThumb
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme

/**
 * 底部输入框:平时是一行提示(浮层不抢键盘),点了打开成能打字的框并弹出输入法;回车或右边的按钮发出。
 * 有 [stop] 时右边一直有黑色的停止键(同对话页输入栏),写了字再多一个发送。
 */
@Composable
internal fun InputRow(field: CardInput, ui: CardInputUi, actions: CardActions, stop: (() -> Unit)? = null) {
    ui.screen?.let { ScreenChip(it, actions::dropScreen) }
    // 选了「排队」时输入框的灰字换成「下一件事」,别还写着补充当前任务
    val shown = if (field.kind == CardInputKind.Steer && ui.followUpMode == FollowUpMode.Queue)
        field.copy(hint = tr(R.string.chat_placeholder_queue)) else field
    Column(Modifier.padding(top = 10.dp).fillMaxWidth()) {
        // 只有「补充/改要求」才需要选交给当前任务还是排下一件;回答、接着说、开始新任务不显示
        if (field.kind == CardInputKind.Steer) CapsuleFollowUpMode(ui.followUpMode, actions::setFollowUpMode)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CameraKey(actions::captureScreen)
            Box(Modifier.weight(1f)) { InputField(shown, ui, actions, stop) }
        }
    }
}

/** 输入框左边的截屏按钮:圆钮里一块屏。 */
@Composable
private fun CameraKey(onClick: () -> Unit) {
    val c = MikaTheme.colors
    Box(
        Modifier.testTag("capsule-camera").size(38.dp).clip(MikaShapes.Pill).background(c.ink.copy(alpha = 0.05f))
            .clickable(role = Role.Button, onClickLabel = ScreenCopy.CAMERA, onClick = onClick)
            .semantics { contentDescription = ScreenCopy.CAMERA },
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.Camera, null, Modifier.size(18.dp), tint = c.ink2)
    }
}

/** 输入框上面那一条:缩略图、「你此刻的屏幕」、App · 时刻,右边 × 不要它;不让截图的页面写「没截到」。 */
@Composable
private fun ScreenChip(p: PendingScreen, onRemove: () -> Unit) {
    val c = MikaTheme.colors
    val s = p.shot.screen
    Row(
        Modifier.testTag("capsule-attachment").padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(c.ink.copy(alpha = 0.05f)).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (s.blocked) Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { Icon(MikaIcons.Lock, null, Modifier.size(16.dp), tint = c.ink2) }
        else ScreenThumb(p.thumb, 34.dp)
        Column(Modifier.weight(1f)) {
            Text(if (s.blocked) ScreenCopy.BLOCKED else ScreenCopy.TAG, style = Strong.copy(fontSize = 12.5.sp), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ScreenCopy.detail(s), style = Sub, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.testTag("capsule-attachment-remove").size(26.dp).clip(MikaShapes.Pill)
                .clickable(role = Role.Button, onClickLabel = ScreenCopy.REMOVE, onClick = onRemove)
                .semantics { contentDescription = ScreenCopy.REMOVE },
            contentAlignment = Alignment.Center,
        ) {
            Icon(MikaIcons.Close, null, Modifier.size(15.dp), tint = c.ink3)
        }
    }
}

/** 发送键:有东西可发时是蓝底,否则灰的点不了。 */
@Composable
private fun SendKey(enabled: Boolean, onSend: () -> Unit, start: Dp = 0.dp) {
    val c = MikaTheme.colors
    Box(
        Modifier.testTag("capsule-send").padding(start = start).size(KeySize).clip(MikaShapes.Pill)
            .background(if (enabled) c.accent else c.ink3.copy(alpha = 0.3f))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = CardCopy.SEND, onClick = onSend)
            .semantics { contentDescription = CardCopy.SEND },
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.Up, null, Modifier.size(17.dp), tint = Color.White)
    }
}

@Composable
private fun InputField(field: CardInput, ui: CardInputUi, actions: CardActions, stop: (() -> Unit)?) {
    val c = MikaTheme.colors
    val shape = RoundedCornerShape(14.dp)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (!ui.open) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(shape).background(c.ink.copy(alpha = 0.05f))
                .padding(end = if (stop != null || ui.screen != null) 4.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.testTag("capsule-input").weight(1f).heightIn(min = 42.dp)
                    .clickable(role = Role.Button, onClickLabel = field.hint, onClick = actions::openInput).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(ui.draft.ifEmpty { field.hint }, style = Body, color = if (ui.draft.isEmpty()) c.ink3 else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            stop?.let { StopKey(it) }
            // 挂着一屏时不用打开输入框也能直接发
            if (ui.screen != null) SendKey(true, actions::send, start = if (stop != null) 4.dp else 0.dp)
        }
        return
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    // 和对话页一样:打开时、外面的字一变(说完接上来),光标放到末尾,接着打都在后面
    var fieldValue by remember { mutableStateOf(TextFieldValue(ui.draft, TextRange(ui.draft.length))) }
    val shownValue = if (fieldValue.text == ui.draft) fieldValue else TextFieldValue(ui.draft, TextRange(ui.draft.length))
    Row(
        Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(shape).background(c.ink.copy(alpha = 0.05f))
            .border(1.dp, c.accent.copy(alpha = 0.5f), shape).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
            if (ui.draft.isEmpty()) Text(field.hint, style = Body, color = c.ink3, maxLines = 1)
            BasicTextField(
                shownValue, { v -> fieldValue = v; if (v.text != ui.draft) actions.draft(v.text) },
                Modifier.testTag("capsule-field").fillMaxWidth().focusRequester(focus).semantics { contentDescription = field.hint },
                textStyle = Body.copy(color = c.ink, textDirection = TextDirection.Content), maxLines = 4, cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (ui.canSend) actions.send() }),
            )
        }
        stop?.let { StopKey(it) }
        if (stop == null || ui.canSend) SendKey(ui.canSend, actions::send, start = if (stop != null) 4.dp else 0.dp)
    }
}

/** 胶囊卡片里的紧凑二选一:引导当前任务 / 排队下一件。 */
@Composable
private fun CapsuleFollowUpMode(mode: FollowUpMode, onMode: (FollowUpMode) -> Unit) {
    val c = MikaTheme.colors
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag("capsule-follow-up-mode"), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        CapsuleModePill(FollowUpMode.Steer, mode, onMode, tr(R.string.follow_up_steer), "capsule-follow-up-steer")
        CapsuleModePill(FollowUpMode.Queue, mode, onMode, tr(R.string.follow_up_queue), "capsule-follow-up-queue")
    }
}

@Composable
private fun CapsuleModePill(value: FollowUpMode, selected: FollowUpMode, onMode: (FollowUpMode) -> Unit, label: String, tag: String) {
    val c = MikaTheme.colors
    val on = value == selected
    Box(
        Modifier.testTag(tag).heightIn(min = 26.dp).clip(MikaShapes.Pill)
            .background(if (on) c.accent else c.fill)
            .clickable(role = Role.Tab, onClickLabel = label) { onMode(value) }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Sub, color = if (on) c.onAccent else c.ink2, maxLines = 1)
    }
}

/** 胶囊卡片底部的一行「排队中 N 件」;点它到对话页管理。 */
@Composable
internal fun QueuedLine(count: Int, onClick: (() -> Unit)?) {
    if (count <= 0) return
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(MikaShapes.Pill).background(c.fill)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp).testTag("capsule-queued"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(MikaIcons.Clock, null, Modifier.size(14.dp), tint = c.ink3)
        Text(
            tr(R.string.follow_up_count, count) + " · " + tr(R.string.follow_up_queue_hint),
            Modifier.weight(1f), style = Sub, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 输入框右边的停止键:黑底圆钮里一个方块,同对话页输入栏。 */
@Composable
internal fun StopKey(onStop: () -> Unit) {
    val c = MikaTheme.colors
    Box(
        Modifier.testTag("capsule-stop").size(KeySize).clip(MikaShapes.Pill).background(c.ink)
            .clickable(role = Role.Button, onClickLabel = CardCopy.STOP, onClick = onStop)
            .semantics { contentDescription = CardCopy.STOP },
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.Stop, null, Modifier.size(20.dp), tint = c.bg)
    }
}

internal val KeySize = 34.dp

internal const val TITLE_MAX = 12
