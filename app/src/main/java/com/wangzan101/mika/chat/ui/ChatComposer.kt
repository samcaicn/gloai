package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.cap.sandbox.AttachmentFailureReason
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.components.pageBackground
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.components.GlassBackdrop
import com.wangzan101.mika.ui.components.GlassBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.HoldToTalk
import com.wangzan101.mika.voice.VoiceSource
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.voice.ui.VoiceBarCover
import com.wangzan101.mika.voice.ui.VoiceConverting
import com.wangzan101.mika.voice.ui.VoiceFloat
import com.wangzan101.mika.voice.ui.VoicePlaceholder
import com.wangzan101.mika.voice.ui.VoiceSheetHost
import com.wangzan101.mika.voice.ui.holdToTalk
import com.wangzan101.mika.voice.ui.rememberVoiceHold
import com.wangzan101.mika.voice.ui.voiceState

/**
 * 玻璃胶囊输入栏:左边「＋」,右边发送(没内容时灰);任务在跑时多一个停止,补充消息先排在输入栏上方。
 * 聊天走前台:前台在办的事在输入栏上方一条([DeskTray]),输入栏不因为后台任务变忙。
 * 「＋」打开功能面板([onPlus],对话页里是从底部升起的列表)。待发的附件在输入栏上方一排([PendingTray])。
 */
@Composable
internal fun Composer(
    modifier: Modifier,
    text: String,
    onText: (String) -> Unit,
    mode: ComposerMode,
    hasHistory: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** 这个对话明确排队、还没开始的下一件。 */
    queued: List<com.wangzan101.mika.task.QueuedFollowUp> = emptyList(),
    onQueueActions: (com.wangzan101.mika.task.QueuedFollowUp) -> Unit = {},
    onCancelQueued: (String) -> Unit = {},
    onEditQueued: (String) -> Unit = {},
    onStartQueued: () -> Unit = {},
    /** 沙箱就绪时就有「＋」;点开是功能面板。 */
    onPlus: (() -> Unit)? = null,
    /** 附件已启用但此刻不能加:「＋」还在,点了只给提示。 */
    attachBlocked: Boolean = false,
    attachments: List<PendingAttachment> = emptyList(),
    importing: Boolean = false,
    rejected: List<AttachmentFailure> = emptyList(),
    overLimit: Int = 0,
    onRemove: (String) -> Unit = {},
    onDismissRejected: () -> Unit = {},
    /** 要随下一句话发出去的引用;有时输入框上方一张小条,× 去掉。 */
    quote: QuoteMessage.Quote? = null,
    onRemoveQuote: () -> Unit = {},
    editRequest: Int = 0,
    /** 输入栏后面那层内容的画面;为 null 时输入栏不做模糊、用不透明底。 */
    backdrop: GlassBackdrop? = null,
    reduceMotion: Boolean = false,
    /** 按住说话从这里开会话;null 时输入框只能打字。 */
    voice: VoiceSource? = null,
    /** 说完的字;调用方把它接在已有的字后面,不自动发送。 */
    onVoiceText: (String) -> Unit = {},
    /** 松开在「在听」卡片里(「松开 发送」)说完的字:调用方接上已有的字直接发出。没给时没有「松开 发送」。 */
    onVoiceSend: ((String) -> Unit)? = null,
    /** 「还不能语音输入」里的「去选」:到设置里选转文字。 */
    onPickSpeech: () -> Unit = {},
    /** 听筒按钮:进语音通话;为 null 时不显示。 */
    onVoiceCall: (() -> Unit)? = null,
    /** 任务在跑时说的话交给谁:卡片顶上写「插话给『…』」。 */
    steerTarget: String? = null,
    /** 按着说话时为 true:对话内容跟着虚化。 */
    onVoiceHolding: (Boolean) -> Unit = {},
    sendFromIme: Boolean = false,
    draftKey: String? = null,
    /** 这条会话前台派过的任务:有在办的就在输入栏上方一条进度条。 */
    deskTasks: List<com.wangzan101.mika.core.frontdesk.DeskTaskView> = emptyList(),
    /** 进度条里一件的「停止」。 */
    onStopTask: (String) -> Unit = {},
    /** 进度条里一件的「查看详细过程」。 */
    onTaskDetail: (String) -> Unit = {},
) {
    val c = MikaTheme.colors
    var fieldFocused by remember { mutableStateOf(false) }
    // 底部从页面底色淡出到透明,输入栏是浮在上面的玻璃
    val fade = remember(c) { Brush.verticalGradient(0f to c.bg.copy(alpha = 0f), 0.25f to c.bg, 1f to c.bg) }
    val busy = mode == ComposerMode.Busy
    val context = LocalContext.current
    // 附件功能开着就保留「＋」;此刻不能加时只提示,不展开面板
    val showAttach = onPlus != null || attachBlocked
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val field = remember { FocusRequester() }
    val ime = WindowInsets.ime
    val nav = WindowInsets.navigationBars
    // 「松开 发送」和发送键一样直接发出
    val vh = androidx.compose.runtime.key(draftKey) { rememberVoiceHold(voice, onVoiceText, onVoiceSend?.let { send -> { text -> send(text) } }) }
    val vState = voiceState(vh)
    val voiceHolding = vh?.holding == true
    LaunchedEffect(voiceHolding) { onVoiceHolding(voiceHolding) }
    LaunchedEffect(editRequest) {
        if (editRequest > 0) {
            field.requestFocus()
            keyboard?.show()
        }
    }
    // 输入框聚焦时长按留给系统的选字、粘贴(悬浮、分体、实体键盘也一样);没聚焦时输入框上盖一层按住说话的手势
    val voiceGesture = vh != null && (!fieldFocused || voiceHolding)
    // 键盘从弹着到收起(返回键、键盘自己的收起键):输入框跟着失焦,又能按住说话。只看键盘的变化,实体键盘(从不弹出)不受影响
    LaunchedEffect(ime, density, vh != null) {
        if (vh == null) return@LaunchedEffect
        var wasUp = false
        snapshotFlow { ime.getBottom(density) > 0 }.collect { up ->
            if (wasUp && !up && fieldFocused) focus.clearFocus()
            wasUp = up
        }
    }
    // 输入框外面的字一变(说完填进来、点了示例),光标放到末尾,接着说或接着打都在后面
    var fieldValue by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val shownValue = if (fieldValue.text == text) fieldValue else TextFieldValue(text, TextRange(text.length))
    Box(
        modifier.fillMaxWidth()
            .pageBackground(c.bg, fade)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(start = ComposerInset, end = ComposerInset, top = 10.dp, bottom = 6.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            DeskTray(deskTasks, reduceMotion, backdrop, onStopTask, onTaskDetail)
            FollowUpTray(
                queued, active = mode != ComposerMode.Idle,
                onEdit = onEditQueued, onCancel = onCancelQueued, onStartNow = onStartQueued, onActions = onQueueActions,
            )
            quote?.let { QuoteChip(it, onRemoveQuote, Modifier.padding(bottom = 6.dp)) }
            if (attachments.isNotEmpty()) PendingTray(attachments, onRemove, Modifier.padding(bottom = 2.dp))
            if (importing || rejected.isNotEmpty() || overLimit > 0) {
                Column(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (importing) Text(AttachCopy.IMPORTING, style = MikaType.Caption, color = c.ink3)
                    if (rejected.isNotEmpty() || overLimit > 0) {
                        // 超限提示写当前档位,不再硬编码 200 MiB
                        val ctx = LocalContext.current
                        val attachmentLimitLabel = remember(ctx) {
                            trPlural(R.plurals.settings_attachment_file_limit_mb_n, com.wangzan101.mika.settings.FeatureFlags.get(ctx).attachmentMaxMb())
                        }
                        val why = listOfNotNull(
                            rejected.takeIf { it.isNotEmpty() }?.map { failure ->
                                when (failure.reason) {
                                    AttachmentFailureReason.TOO_LARGE -> stringResource(R.string.chat_attach_too_large, failure.name, attachmentLimitLabel)
                                    AttachmentFailureReason.LOW_DEVICE_SPACE -> stringResource(R.string.chat_attach_low_device_space, failure.name)
                                    AttachmentFailureReason.WORKSPACE_LIMIT -> stringResource(R.string.chat_attach_workspace_limit, failure.name)
                                    AttachmentFailureReason.IO_ERROR -> stringResource(R.string.chat_attach_io_error, failure.name)
                                }
                            }?.joinToString("\n"),
                            overLimit.takeIf { it > 0 }?.let(AttachCopy::overLimit),
                        ).joinToString("\n")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(why, Modifier.weight(1f), style = MikaType.Caption, color = c.warn)
                            TextLink(stringResource(R.string.ok_got_it), onDismissRejected)
                        }
                    }
                }
            }
            if (vh != null) {
                val title = if (busy && !steerTarget.isNullOrBlank()) stringResource(R.string.voice_steer_to, steerTarget)
                else stringResource(R.string.voice_listening)
                VoiceFloat(vh, vState, title, backdrop, reduceMotion)
            }
            Box(Modifier.fillMaxWidth()) {
            // 实底卡片:半透明玻璃在两行身后衬的底不一致会显出两个色,输入栏不走高斯模糊
            GlassBar(Modifier.fillMaxWidth().keepsKeyboard()) {
                val canSend = AttachTray.canSend(text, attachments, importing)
                // 两行:第一行纯输入,第二行按钮;字再多按钮排布也不动
                Column(Modifier.weight(1f)) {
                    // start 14dp:文字左缘对齐＋图标可视左缘(40dp 按钮内 20dp 图标居中,字形自身再内缩约 4dp)
                    Box(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 4.dp)) {
                        val converting = vState is HoldToTalk.State.Converting
                        if (text.isEmpty() && !converting) {
                            if (vh != null) VoicePlaceholder(mode)
                            else Text(ChatCopy.placeholder(mode, hasHistory), style = MikaType.Body, color = c.ink3, maxLines = 1)
                        }
                        BasicTextField(
                            shownValue,
                            { v -> fieldValue = v; if (v.text != text) onText(v.text) },
                            Modifier.fillMaxWidth().testTag("composer-field").focusRequester(field)
                                .alpha(if (converting) 0f else 1f)
                                .onFocusChanged { fieldFocused = it.isFocused },
                            textStyle = MikaType.Body.copy(color = c.ink, textDirection = TextDirection.Content),
                            cursorBrush = SolidColor(c.accent),
                            maxLines = 5,
                            keyboardOptions = if (sendFromIme) KeyboardOptions(imeAction = ImeAction.Send) else KeyboardOptions.Default,
                            keyboardActions = if (sendFromIme) KeyboardActions(onSend = {
                                if (AttachTray.canSend(text, attachments, importing)) onSend()
                            }) else KeyboardActions.Default,
                        )
                        // 服务商松开后在转:输入框里转圈,转好的字填进来
                        if (converting) VoiceConverting(Modifier.align(Alignment.CenterStart))
                        if (voiceGesture && vh != null) {
                            Box(Modifier.matchParentSize().holdToTalk(vh) { field.requestFocus(); keyboard?.show() })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (showAttach) {
                            val label = AttachCopy.ADD
                            val blockedHint = stringResource(R.string.attach_blocked_running)
                            Box(
                                Modifier.size(MikaSize.IconButton).clip(CircleShape)
                                    .clickable(enabled = !importing, onClickLabel = label, onClick = {
                                        if (attachBlocked) {
                                            Toast.makeText(context, blockedHint, Toast.LENGTH_SHORT).show()
                                        } else onPlus?.invoke()
                                    }),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(MikaIcons.Plus, label, Modifier.size(20.dp), tint = c.ink)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        // 听筒:进语音通话(与按住说话共存)
                        if (onVoiceCall != null) {
                            val callLabel = stringResource(R.string.voicecall_menu)
                            Box(
                                Modifier.size(MikaSize.IconButton).clip(CircleShape)
                                    .clickable(onClickLabel = callLabel, onClick = onVoiceCall)
                                    .testTag("composer-call"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(MikaIcons.Handset, callLabel, Modifier.size(MikaSize.Icon), tint = c.ink)
                            }
                        }
                        // 话筒常驻:按住才听,点一下提示按住(引擎不可用则直接弹说明页);键盘出来以后长按输入框留给系统选字
                        if (vh != null) {
                            val hint = stringResource(R.string.voice_hold_to_talk)
                            Box(
                                Modifier.size(MikaSize.IconButton).testTag("composer-mic").holdToTalk(vh) { if (vh.engineUsable) vh.toast = hint else vh.explainUnavailable() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(MikaIcons.Mic, hint, Modifier.size(MikaSize.Icon), tint = c.ink)
                            }
                        }
                        // 任务在跑时没字就停止;写了字停止让位给发送,发出去的话交给当前任务
                        if (busy && !canSend) {
                            Box(
                                Modifier.size(MikaSize.IconButton).clip(CircleShape)
                                    .clickable(onClickLabel = stringResource(R.string.chat_stop), onClick = onStop),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(MikaIcons.Stop, stringResource(R.string.chat_stop), Modifier.size(26.dp), tint = c.ink)
                            }
                        }
                        if (!busy || canSend) {
                            // 可发时黑底白箭头(主操作);空输入灰底灰箭头——发送是主行动,空态也有圆底和＋/话筒拉开层级,但灰箭头表明还不可用。全用 token,深色主题自动反转
                            Box(
                                Modifier.size(MikaSize.IconButton)
                                    .clip(CircleShape)
                                    .background(if (canSend) c.ink else c.neutralSoft)
                                    .clickable(enabled = canSend, onClickLabel = stringResource(R.string.chat_send)) { onSend() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(MikaIcons.Up, stringResource(R.string.chat_send), Modifier.size(20.dp), tint = if (canSend) c.bg else c.ink3)
                            }
                        }
                    }
                }
            }
            if (vh != null) VoiceBarCover(vh, vState, reduceMotion, Modifier.matchParentSize())
            }
            if (vh != null) VoiceSheetHost(vh, onPickSpeech)
        }
    }
}
