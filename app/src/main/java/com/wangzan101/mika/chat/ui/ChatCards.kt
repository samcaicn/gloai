package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.ui.components.DirectionalIcon
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.task.ConfirmationCopy
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.viewer.FileViewer
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.VoicePlayback
import kotlinx.coroutines.launch
import com.wangzan101.mika.memory.ui.Link
import com.wangzan101.mika.settings.ui.StatusCopy
import com.wangzan101.mika.ui.MarkdownModel
import com.wangzan101.mika.ui.MarkdownText

@StringRes
fun takeoverReasonLabel(reason: String): Int? = when (reason) {
    "captcha" -> R.string.takeover_reason_captcha
    "login" -> R.string.takeover_reason_login
    "access_blocked" -> R.string.takeover_reason_access_blocked
    "payment" -> R.string.takeover_reason_payment
    "decision" -> R.string.takeover_reason_decision
    else -> null
}

/** 一问一答此刻的状态:要你做的事是一张白卡([NeedCard]),结果直接排在背景上。 */
@Composable
internal fun StatusCard(
    status: ExchangeStatus, stage: Takeover.Stage, vm: ChatViewModel, runId: String?,
    elapsedMs: Long?, tokens: Long?,
    loadSource: suspend (String) -> EvidenceSource?, openSource: (String?, String?) -> Unit,
    openRun: (String) -> Unit,
    /** 回答的长按菜单认这一条用的(见 [MessageArea])。 */
    key: String,
    /** 回答里的 /workspace 引用卡片点开要走这个对话的工作区。 */
    conversationId: String?,
    recover: (Boolean) -> Unit = {},
    goal: String, openReport: (String) -> Unit, openPlayback: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shareRequest by remember { mutableStateOf<ShareRequest?>(null) }
    ShareCardHost(shareRequest, onDone = { shareRequest = null })
    when (status) {
        ExchangeStatus.Working -> Unit
        ExchangeStatus.WaitingUnlock -> NeedCard(stringResource(R.string.chat_wait_unlock), stringResource(R.string.chat_unlock_body))
        is ExchangeStatus.NeedsTakeover -> {
            val label = takeoverReasonLabel(status.reason)
            val title = if (label != null) stringResource(R.string.takeover_title_reason, stringResource(label)) else stringResource(R.string.takeover_title)
            val handedOver = stage == Takeover.Stage.HandedOver
            val text = if (handedOver) stringResource(R.string.takeover_handed_over_hint) else status.message
            // 付款的商品、金额、地址单独成行:这张卡片就是付款前的确认
            val body = listOfNotNull(text, StatusCopy.paymentLines(status.item, status.amount, status.address)).joinToString("\n")
            NeedCard(title, body, footer = { TextLink(stringResource(R.string.action_cancel_task), vm::cancel) }) {
                if (handedOver) {
                    CardButton(stringResource(R.string.action_finished_myself), onClick = vm::finishedMyself)
                    CardButton(stringResource(R.string.action_hand_back), primary = true, onClick = vm::handBack)
                } else {
                    CardButton(stringResource(R.string.action_skip), onClick = vm::skip)
                    CardButton(stringResource(R.string.action_take_over), primary = true, onClick = vm::takeOver)
                }
            }
        }
        is ExchangeStatus.NeedsInput -> {
            val options = status.options.orEmpty()
            val note = stringResource(R.string.no_answer_note, TaskService.INPUT_TIMEOUT_MINUTES)
            NeedCard(
                stringResource(R.string.input_title), stringResource(R.string.chat_input_body, status.prompt, note),
                options = if (options.isEmpty()) null else ({
                    options.forEach { o -> MikaButton(o, { vm.answer(o) }, Modifier.fillMaxWidth(), kind = ButtonKind.Tonal, size = ButtonSize.Medium) }
                }),
            ) {
                if (options.isEmpty()) CardButton(stringResource(R.string.action_cancel_task), onClick = vm::cancel)
            }
        }
        // 只有花钱的操作要你同意,每次只同意这一次
        is ExchangeStatus.NeedsConfirmation -> NeedCard(status.title, status.body + "\n" + ConfirmationCopy.NOTE) {
            CardButton(stringResource(R.string.chat_refuse), onClick = { vm.decline(status.key) })
            CardButton(stringResource(R.string.chat_allow_once), primary = true, onClick = { vm.approve(status.key) })
        }
        is ExchangeStatus.Paused -> NeedCard(stringResource(if (status.resumable) R.string.chat_paused else R.string.chat_waiting_for_you), status.why) {
            CardButton(stringResource(R.string.action_cancel_task), onClick = vm::cancel)
            if (status.resumable) CardButton(stringResource(R.string.chat_continue), primary = true, onClick = vm::resume)
        }
        // 排队等开始:轮到时自动开始,点继续不能插队,只给取消
        is ExchangeStatus.Queued -> NeedCard(stringResource(R.string.chat_queued), status.why) {
            CardButton(stringResource(R.string.action_cancel_task), onClick = vm::cancel)
        }
        // 长按整块回答弹菜单;复制、引用的只是回答的字,不带上面的提示和下面的来源
        is ExchangeStatus.Done -> Box(Modifier.padding(bottom = 12.dp)) {
            AnswerArea(key, status.answer, Modifier.fillMaxWidth().answerSurface()) {
                Column {
                    TurnMeta(elapsedMs, tokens)
                    status.unverifiedLine?.let { line ->
                        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(18.dp).background(MikaTheme.colors.warnSoft, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(MikaIcons.Exclaim, null, Modifier.size(11.dp), tint = MikaTheme.colors.warn)
                            }
                            Text(line, style = MikaType.Caption, fontWeight = FontWeight.SemiBold, color = MikaTheme.colors.ink2)
                        }
                    }
                    MarkdownText(status.answer, AnswerText.copy(color = MikaTheme.colors.ink))
                    OutputRefs(status.answer, conversationId) { item ->
                        val id = conversationId ?: return@OutputRefs
                        scope.launch { if (!AttachFiles.openSent(context, id, item.path)) Toast.makeText(context, AttachCopy.GONE, Toast.LENGTH_SHORT).show() }
                    }
                    ReplyActions(runId, status.answer, goal, vm, { shareRequest = ShareRequest(goal, status.answer) }, openRun, loadSource, openSource, status.evidence, openReport, openPlayback)
                }
            }
        }
        is ExchangeStatus.Failed -> Column(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(MikaTheme.colors.bad, CircleShape))
                Text(stringResource(R.string.chat_not_done), style = MikaType.BodyStrong, color = MikaTheme.colors.ink)
            }
            MarkdownText(status.reason, AnswerText.copy(color = MikaTheme.colors.ink), Modifier.fillMaxWidth().padding(top = 6.dp).answerSurface())
            RecoveryActions(runId, recover)
            ReplyActions(runId, null, goal, vm, {}, openRun, loadSource, openSource, null, openReport, openPlayback)
        }
        // 停止后补上的交代当作这一轮的回答,排在「已停止」下面;还在写时一行小字
        is ExchangeStatus.Cancelled -> Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Text(stringResource(R.string.chat_stopped), Modifier.padding(start = 2.dp), style = MikaType.Body, color = MikaTheme.colors.ink2)
            if (status.summarizing) Text(
                StatusCopy.STOP_SUMMARIZING, Modifier.padding(start = 2.dp, top = 4.dp), style = MikaType.Caption, color = MikaTheme.colors.ink3,
            )
            status.summary?.let { summary ->
                Box(Modifier.padding(top = 8.dp)) {
                    AnswerArea(key, summary, Modifier.fillMaxWidth().answerSurface()) {
                        Column {
                            MarkdownText(summary, AnswerText.copy(color = MikaTheme.colors.ink))
                            OutputRefs(summary, conversationId) { item ->
                                val id = conversationId ?: return@OutputRefs
                                scope.launch { if (!AttachFiles.openSent(context, id, item.path)) Toast.makeText(context, AttachCopy.GONE, Toast.LENGTH_SHORT).show() }
                            }
                        }
                    }
                }
            }
            RecoveryActions(runId, recover)
            ReplyActions(runId, null, goal, vm, {}, openRun, loadSource, openSource, null, openReport, openPlayback)
        }
    }
}

/** 操作区里的「朗读/停止」图标;没有配置声音模型时不显示。 */
@Composable
private fun ReadAloudIcon(answer: String, vm: ChatViewModel) {
    val ready by vm.canSpeak.collectAsState()
    if (!ready) return
    val playback by vm.voice.collectAsState()
    val playing = playback is VoicePlayback.Playing || playback is VoicePlayback.Loading
    val c = MikaTheme.colors
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button) { if (playing) vm.stopSpeaking() else vm.speak(answer) }
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(MikaIcons.Sound, null, Modifier.size(14.dp), tint = if (playing) c.accent else c.ink3)
            Text(
                stringResource(if (playing) R.string.chat_stop_speaking else R.string.chat_read_aloud),
                style = MikaType.Caption, color = if (playing) c.accent else c.ink3,
            )
        }
        (playback as? VoicePlayback.Error)?.let {
            Text(it.message, Modifier.padding(start = 8.dp), style = MikaType.Caption, color = c.bad)
        }
    }
}

/** 这一轮生成的音频:名字/稿子开头、时长、播放/停止;点整张卡播放,再点停止。 */
@Composable
internal fun GeneratedAudioCards(audios: List<com.wangzan101.mika.core.chat.GeneratedAudio>, vm: ChatViewModel) {
    val playback by vm.voice.collectAsState()
    val playing = playback is VoicePlayback.Playing || playback is VoicePlayback.Loading
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        audios.forEach { a ->
            Row(
                Modifier.fillMaxWidth().cardSurface(c, MikaShapes.Card).clip(MikaShapes.Card)
                    .clickable(role = Role.Button) { if (playing) vm.stopSpeaking() else vm.playAudio(a.path) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(36.dp).background(c.neutralSoft, FileTileShape), contentAlignment = Alignment.Center) {
                    Icon(MikaIcons.Sound, null, Modifier.size(20.dp), tint = c.ink)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        a.text?.lineSequence()?.firstOrNull()?.take(80)?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_audio),
                        style = FileName, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            a.durationMs?.let { secondsLabel(it) },
                            stringResource(if (playing) R.string.chat_audio_stop else R.string.chat_audio_play),
                        ).joinToString(" · "),
                        style = FileMeta, color = if (playing) c.accent else c.ink3, maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun secondsLabel(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** 终态后旧现场已经释放:两个按钮都开新运行,区别只是是否沿用摘要里的已确认进度。 */
@Composable
private fun RecoveryActions(runId: String?, recover: (Boolean) -> Unit) {
    if (runId == null) return
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CardButton(stringResource(R.string.chat_restart_task), onClick = { recover(false) })
        CardButton(stringResource(R.string.chat_resume_checkpoint), primary = true, onClick = { recover(true) })
    }
}

/** 回答正文下方的操作区:一条淡分隔线 + 一排纯图标按钮(朗读/复制/分享 | 查看过程/来源/任务报告/操作回放)。 */
@Composable
private fun ReplyActions(
    runId: String?,
    answer: String?,
    goal: String,
    vm: ChatViewModel,
    onShare: () -> Unit,
    openRun: (String) -> Unit,
    loadSource: suspend (String) -> EvidenceSource?,
    openSource: (String?, String?) -> Unit,
    evidence: String?,
    openReport: (String) -> Unit,
    openPlayback: (String) -> Unit,
) {
    if (runId == null && answer == null) return
    val source by produceState<EvidenceSource?>(null, runId) { value = if (runId == null) null else loadSource(runId) }
    val hasSource = evidence != null || !source?.items.isNullOrEmpty() || !source?.screenshots.isNullOrEmpty()
    val c = MikaTheme.colors
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.hair))
        Spacer(Modifier.height(11.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (answer != null) {
                ReadAloudIcon(answer, vm)
                Spacer(Modifier.width(4.dp))
                ReplyIcon(MikaIcons.Copy, stringResource(R.string.chat_copy)) { copyAnswer(context, answer) }
                Spacer(Modifier.width(4.dp))
                ReplyIcon(MikaIcons.Share, stringResource(R.string.share)) { onShare() }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.width(0.5.dp).height(18.dp).background(c.hair))
                Spacer(Modifier.width(8.dp))
            }
            if (runId != null) {
                ReplyIcon(MikaIcons.Clock, stringResource(R.string.chat_view_process)) { openRun(runId) }
                if (hasSource) {
                    Spacer(Modifier.width(4.dp))
                    ReplyIcon(MikaIcons.Link, sourceLabel(source)) { openSource(runId, evidence) }
                }
                Spacer(Modifier.width(4.dp))
                ReplyIcon(MikaIcons.Doc, stringResource(R.string.report_title)) { openReport(runId) }
                Spacer(Modifier.width(4.dp))
                ReplyIcon(MikaIcons.Play, stringResource(R.string.playback_title)) { openPlayback(runId) }
            }
        }
    }
}

/** 操作区里的一枚纯图标按钮:30dp 触达区、17dp 图标,[label] 只作无障碍描述。 */
@Composable
private fun ReplyIcon(icon: ImageVector, label: String, tint: Color = MikaTheme.colors.ink2, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(MikaShapes.Tile).clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(17.dp), tint = tint)
    }
}

/** 复制回答正文的纯文字到剪贴板。 */
private fun copyAnswer(context: Context, answer: String) {
    val clip = ClipData.newPlainText("Mika", MarkdownModel.toPlainText(answer))
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    Toast.makeText(context, R.string.chat_copied, Toast.LENGTH_SHORT).show()
}

/**
 * 「需要你」卡片(要你处理、提问、确认、暂停、锁屏、被拦下):白卡,标题前一个橙色圆点,下面说明;
 * [options] 是一列可点的答案;按钮并排,次要的在左(浅色)、主要的在右(蓝色);[footer] 在最下面。
 */
@Composable
internal fun NeedCard(
    title: String,
    body: String,
    footer: (@Composable () -> Unit)? = null,
    options: (@Composable () -> Unit)? = null,
    buttons: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = MikaTheme.colors
    MikaCard(Modifier.padding(top = 8.dp, bottom = 12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(c.warnSolid, CircleShape))
                Text(title, style = NeedTitle, color = c.ink)
            }
            Text(body, Modifier.padding(top = 6.dp), style = CardBody, color = c.ink2)
            if (options != null) {
                Column(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { options() }
            }
            if (buttons != null) {
                Row(Modifier.padding(top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
            }
            footer?.let { Box(Modifier.padding(top = 10.dp).fillMaxWidth(), contentAlignment = Alignment.Center) { it() } }
        }
    }
}

@Composable
internal fun RowScope.CardButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    MikaButton(
        text, onClick, Modifier.weight(1f),
        kind = if (primary) ButtonKind.Primary else ButtonKind.Tonal, size = ButtonSize.Medium,
    )
}

@Composable
internal fun TextLink(text: String, onClick: () -> Unit) {
    Text(text, Modifier.clip(MikaShapes.Pill).clickable(onClick = onClick).padding(4.dp), style = MikaType.Caption, color = MikaTheme.colors.ink3)
}

/** 导出到「下载/Mika」的文件:小卡片,名字、大小和存放处;点了能看的在 App 里打开([FileViewer]),其余交给系统里能打开它的 App。 */
@Composable
internal fun FileCards(files: List<SharedFile>) {
    val context = LocalContext.current
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in files) {
            Row(
                Modifier.fillMaxWidth().cardSurface(c, MikaShapes.Card).clip(MikaShapes.Card)
                    .clickable(onClickLabel = stringResource(R.string.chat_open), role = Role.Button) { openShared(context, f) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).background(c.neutralSoft, FileTileShape), contentAlignment = Alignment.Center) {
                    Icon(MikaIcons.Doc, null, Modifier.size(20.dp), tint = c.ink)
                }
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = FileName, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${ChatViewModel.sizeLabel(f.size)} · ${f.savedAs.substringBeforeLast('/')}",
                        style = FileMeta, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                DirectionalIcon(MikaIcons.Chevron, null, Modifier.size(MikaSize.SmallIcon), tint = c.ink3)
            }
        }
    }
}

private fun openShared(context: Context, f: SharedFile) = FileViewer.open(context, Uri.parse(f.uri), f.name)

// ---------- 输入栏 ----------
