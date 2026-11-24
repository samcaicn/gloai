package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.core.agent.GoalChecklist
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.ui.components.DirectionalIcon
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.task.unconsumed
import com.wangzan101.mika.ui.components.floatShadow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.wangzan101.mika.browser.ui.SentPage
import com.wangzan101.mika.settings.ui.StatusCopy
import com.wangzan101.mika.ui.MarkdownModel
import com.wangzan101.mika.ui.MarkdownText

/** 跳转目标行的短暂高亮:一条全宽淡色底,约 1.8 秒后渐隐。 */
@Composable
internal fun JumpHighlight(active: Boolean, reduceMotion: Boolean, content: @Composable () -> Unit) {
    val c = MikaTheme.colors
    val alpha by animateFloatAsState(if (active) 1f else 0f, if (reduceMotion) snap() else tween(260), label = "jump-highlight")
    Box(
        Modifier.fillMaxWidth()
            .background(c.accent.copy(alpha = 0.12f * alpha), MikaShapes.Card),
    ) { content() }
}

internal fun LazyListScope.exchange(
    index: Int, ex: Exchange, conversationId: String?, stage: Takeover.Stage, vm: ChatViewModel, reduceMotion: Boolean, backgroundNote: Boolean,
    openRun: (String) -> Unit,
    kept: KeptPage? = null,
    /** 从搜索或文件库跳过来时要高亮的行 key。 */
    highlightKey: String? = null,
    /** 终态后的两个新运行入口:true 从摘要断点继续,false 按原目标重新开始。 */
    recover: (Boolean) -> Unit = {},
) {
    // 前台的一轮:用户的话、前台回的话;不走任务那套步骤和状态卡
    if (ex.deskReply != null) {
        deskExchange(index, ex, conversationId, reduceMotion, highlightKey)
        return
    }
    if (ex.goal.isNotEmpty()) item("u$index") {
        JumpHighlight(highlightKey == "u$index", reduceMotion) { UserBubble(ex.goal, conversationId, key = "u$index") }
    }
    val paused = ex.status is ExchangeStatus.Paused || ex.status is ExchangeStatus.WaitingUnlock ||
        ex.status is ExchangeStatus.NeedsTakeover || ex.status is ExchangeStatus.NeedsConfirmation ||
        ex.status is ExchangeStatus.NeedsInput || ex.status is ExchangeStatus.Queued
    val active = ex.status.isActive()
    val answering = ex.status == ExchangeStatus.Working && !ex.draft.isNullOrBlank()
    // 插话:干活中作为右侧一步插进步骤里(见 [StepLine]);没在跑步骤(结束了、或在写总结)时收成紧跟
    // 用户消息下面的一条弱化小字,和前面的主对话区分主从。撤回的插话不显示。
    val steers = ex.steers.filterNot { it.state == SteerState.Withdrawn }
    if (active && !answering) {
        item("s$index") { WorkBlock(ex, steers.map { it.text }, reduceMotion, backgroundNote, openRun) }
    } else {
        steers.forEach { n ->
            item("m$index-${n.id}") {
                JumpHighlight(highlightKey == "m$index-${n.id}", reduceMotion) { SteerSupplement(n.text) }
            }
        }
    }
    ex.draft?.takeIf(String::isNotBlank)?.let { draft -> item("d$index") { DraftText(draft, ex.waitingForModel) } }
    item("r$index") {
        JumpHighlight(highlightKey == "r$index", reduceMotion) {
            StatusCard(ex.status, stage, vm, ex.recordedRun, "r$index", conversationId = conversationId, recover = recover)
        }
    }
    // 这一轮结束后留在后台运行的那一页:还开着是绿色一行(能关),关了是一句原因
    keptPageRow(index, ex, kept, vm::closeKeptPage)
    if (ex.audios.isNotEmpty()) item("au$index") { GeneratedAudioCards(ex.audios, vm) }
    if (ex.photos.isNotEmpty()) item("p$index") {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { GeneratedPhotos(ex.photos, conversationId, 292.dp) }
    }
    if (ex.files.isNotEmpty()) item("f$index") {
        JumpHighlight(highlightKey == "f$index", reduceMotion) { FileCards(ex.files) }
    }
    learningNotes(index, ex, vm)
}

/**
 * 插话分成两段:第一句还没交给模型的([SteerState.unconsumed])之前的,和它连同之后的。
 * 正在长出来的回答排在两段中间。
 */
internal fun steersAroundDraft(steers: List<SteerNote>): Pair<List<SteerNote>, List<SteerNote>> {
    val visible = steers.filterNot { it.state == SteerState.Withdrawn }
    val first = visible.indexOfFirst { it.state.unconsumed }.let { if (it < 0) visible.size else it }
    return visible.take(first) to visible.drop(first)
}

/**
 * 长按的 [key] 是进行中那一问一答里、模型还没取走的手机插话时给出它,菜单据此提供撤回和编辑(编辑另看 [editableSteer])。
 * 电脑追加的([SteerNote.remote])不给:编辑会把只归当前任务的话变成手机插话、可能转成下一件事;撤不撤由电脑定。
 */
internal fun withdrawableSteer(exchanges: List<Exchange>, key: String): SteerNote? = exchanges.mapIndexedNotNull { i, ex ->
    if (ex.status.isActive()) ex.steers.firstOrNull { key == "m$i-${it.id}" && it.state.unconsumed && !it.remote } else null
}.firstOrNull()

internal fun ExchangeStatus.isActive() =
    this !is ExchangeStatus.Done && this !is ExchangeStatus.Failed && this !is ExchangeStatus.Cancelled

/**
 * 用户的话:淡蓝底气泡(深字),圆角 20、右下尾角 6,最宽八成。[note] 是插话的去向。
 * 带着用户此刻的屏幕时上面是截图缩略图和「你此刻的屏幕 · App · 时刻」,附言在下面的气泡里(见 [UserMessage])。
 * 带着附件时上面是照片格子和文件卡片(见 [SentAttachments]),点开在 App 里的查看页看;写的话在下面另起一个气泡,路径那几行不显示。
 * 引用了前面的消息时最上面是引的那条(见 [SentQuote])。长按气泡弹出复制 / 引用 / 选择文字([key] 认是哪一条,见 [MessageArea])。
 * 在浏览器页上对着一页问的,最上面是「网页 · 网站」和选中的字(见 [SentPage]),页面说明那几行不显示。
 */
@Composable
internal fun UserBubble(text: String, conversationId: String?, note: String? = null, key: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val paged = remember(text) { AttachedPage.parse(text) }
    val quoted = remember(text) { QuoteMessage.parse(paged.words) }
    val attached = remember(text) { AttachmentMessage.parse(quoted.words) }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp), contentAlignment = Alignment.CenterEnd) {
        val max = maxWidth * 0.8f
        Column(horizontalAlignment = Alignment.End) {
            paged.page?.let { SentPage(it, max) }
            quoted.quote?.let { SentQuote(it, max) }
            if (attached.hasAttachments) {
                SentAttachments(attached, conversationId, max) { item ->
                    val id = conversationId ?: return@SentAttachments
                    scope.launch { if (!AttachFiles.openSent(context, id, item.path)) Toast.makeText(context, AttachCopy.GONE, Toast.LENGTH_SHORT).show() }
                }
            }
            if (attached.hasAttachments && attached.words.isNotEmpty()) Spacer(Modifier.size(6.dp))
            if ((!attached.hasAttachments || attached.words.isNotEmpty()) && (paged.page == null || quoted.words.isNotEmpty())) UserMessage(conversationId, attached.words.takeIf { attached.hasAttachments } ?: quoted.words, ChatThumbWidth) { words ->
                // 用户气泡是淡蓝底 + 深字:选中的底色用稍浓一点的蓝,把手仍是强调色(画在气泡外面)
                val colors = MikaTheme.colors
                val bubble = colors.accent.copy(alpha = 0.14f)
                CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.accent.copy(alpha = 0.3f))) {
                    MessageArea(
                        key, QuoteMessage.From.You, words, "selectable-user",
                        Modifier.widthIn(max = max).background(bubble, MikaShapes.UserBubble),
                        lifted = { LiftedMessage(MikaShapes.UserBubble, bubble) { UserBubbleText(words) } },
                    ) { UserBubbleText(words) }
                }
            }
            note?.let { Text(it, Modifier.padding(top = 3.dp, end = 4.dp), style = MikaType.Hint, color = MikaTheme.colors.ink3) }
        }
    }
}

@Composable
private fun UserBubbleText(words: String) {
    Text(words, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = BubbleText, color = MikaTheme.colors.ink)
}

/** 长按抬起的那条消息:[color] 底、带阴影;太长时(最高半屏)只露开头,下面裁掉。 */
@Composable
private fun LiftedMessage(shape: Shape, color: Color, content: @Composable () -> Unit) {
    Box(
        Modifier.floatShadow(MikaTheme.colors, shape).background(color, shape).clip(shape)
            .wrapContentHeight(Alignment.Top, unbounded = true),
    ) { content() }
}

/** 对话页里截图缩略图的宽。 */
private val ChatThumbWidth = 110.dp

/** 正在生成的回答:和完成后的回答一样放在淡灰底上。 */
@Composable
private fun DraftText(text: String, generating: Boolean) {
    MarkdownText(
        text, AnswerText.copy(color = MikaTheme.colors.ink),
        Modifier.padding(bottom = ReplyGap).answerSurface(), streaming = generating,
    )
}

/** 结束后插话收成的弱化小字:右对齐、比正文淡一点点,紧跟用户消息下面,和主回答区分主从。 */
@Composable
private fun SteerSupplement(text: String) {
    Box(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(text, Modifier.padding(horizontal = 4.dp), style = SupplementText, color = MikaTheme.colors.inkSoft)
    }
}

/** 干活中插话作为「右侧插入的一步」:淡蓝胶囊、右对齐,和左边的模型步骤区分开。 */
@Composable
private fun SteerStepPill(text: String) {
    val c = MikaTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.End) {
        Text(
            text,
            Modifier.background(c.accentSoft, SteerPillShape).clip(SteerPillShape).padding(horizontal = 12.dp, vertical = 6.dp),
            style = StepText, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Mika 的一段回答:长按弹出复制 / 引用 / 选择文字(见 [MessageArea]),复制、引用的是去掉 Markdown 符号的纯文字;
 * [modifier] 是回答的底,抬起的那张是同样的字放在白卡上。选择文字时选区的把手在单独的弹出窗口里,拖把手不会带出侧栏。
 */
@Composable
internal fun AnswerArea(key: String, answer: String, modifier: Modifier, content: @Composable () -> Unit) {
    val plain = remember(answer) { MarkdownModel.toPlainText(answer) }
    MessageArea(
        key, QuoteMessage.From.Mika, plain, "selectable-answer", modifier,
        lifted = {
            LiftedMessage(MikaShapes.AnswerBubble, MikaTheme.colors.surface) {
                MarkdownText(answer, AnswerText.copy(color = MikaTheme.colors.ink), Modifier.padding(horizontal = 14.dp, vertical = 12.dp), streaming = false)
            }
        },
    ) { content() }
}

/** 同一句话下面几条回复之间的距离。 */
internal val ReplyGap = 8.dp

/** Mika 回答的底:很淡的灰,左下尾角。短的贴着字宽,长的铺满;前台回话、汇报、旧式回答都用这一种。 */
@Composable
internal fun Modifier.answerSurface(): Modifier =
    background(MikaTheme.colors.answer, MikaShapes.AnswerBubble).padding(horizontal = 14.dp, vertical = 12.dp)

// ---------- 进度 ----------

/**
 * 进行中:一行状态(呼吸点 + 此刻的进展 + 用时),下面一条竖线步骤(做完的灰点、当前的蓝点;停下来等你时当前步是橙点)。
 * 在后台操作手机时最下面一行小字。
 */
@Composable
internal fun WorkBlock(ex: Exchange, steers: List<String>, reduceMotion: Boolean, backgroundNote: Boolean, openRun: (String) -> Unit) {
    val c = MikaTheme.colors
    val paused = ChatCopy.paused(ex.status)
    val elapsed = rememberElapsed(ex.startedAt)
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 24.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BreathingDot(paused, reduceMotion)
            Text(
                ChatCopy.progressLine(ex), Modifier.weight(1f), style = NowText, color = c.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (elapsed != null) Text(ChatCopy.clock(elapsed), style = ClockText, color = c.ink3)
        }
        ex.goals?.let { GoalChecklistRow(it, ex.runId) }
        if (ex.steps.isNotEmpty() || steers.isNotEmpty()) StepLine(ex.steps, ex.stepActions, steers, live = true, paused = paused)
        // 跑的时候就能进运行过程(页面按秒接着长);结束后收进「做了 N 步 · 查看过程」那一行
        ex.recordedRun?.let { id ->
            Row(
                Modifier.padding(top = 2.dp).clip(MikaShapes.Pill).clickable(role = Role.Button) { openRun(id) }
                    .padding(horizontal = 4.dp, vertical = 4.dp).testTag("work-view-process"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.chat_view_process), style = DoneText, color = c.ink3)
                DirectionalIcon(MikaIcons.Chevron, null, Modifier.size(12.dp), tint = c.ink3)
            }
        }
        if (backgroundNote) {
            Row(
                Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(MikaIcons.Eye, null, Modifier.size(15.dp), tint = c.ink3)
                Text(ChatCopy.BACKGROUND_NOTE, style = NoteText, color = c.ink3)
            }
        }
    }
}

@Composable
private fun GoalChecklistRow(goals: GoalChecklist, runId: String?) {
    val c = MikaTheme.colors
    var expanded by rememberSaveable(runId) { mutableStateOf(false) }
    Column {
        Row(
            Modifier.clickable(role = Role.Button) { expanded = !expanded }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(StatusCopy.goals(goals), style = NoteText, color = c.ink2)
            Icon(MikaIcons.Chevron, null, Modifier.size(16.dp).rotate(if (expanded) -90f else 90f), tint = c.ink3)
        }
        if (expanded) goals.items.forEachIndexed { i, goal ->
            Text("${if (i + 1 in goals.confirmed) "✓" else "○"}  $goal", style = NoteText, color = c.ink2)
        }
    }
}

/** 从 [startedAt] 起的用时,每秒更新;不知道开始时刻(为 0)时为 null。 */
@Composable
private fun rememberElapsed(startedAt: Long): Long? {
    if (startedAt <= 0L) return null
    val now by produceState(System.currentTimeMillis(), startedAt) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L - value % 1_000L)
        }
    }
    return (now - startedAt).coerceAtLeast(0L)
}

private enum class Dot { Done, Current, Paused, Dim }

/**
 * 竖线步骤。[live] 时最后一步按任务此刻的状态标蓝点或橙点;在跑时只列最近 [ChatCopy.VISIBLE_STEPS] 步,
 * 前面的收成一行,点开看全部。
 */
@Composable
private fun StepLine(steps: List<StepRow>, actions: Map<Int, List<StepAction>>, steers: List<String>, live: Boolean, paused: Boolean) {
    val c = MikaTheme.colors
    var all by rememberSaveable { mutableStateOf(!live) }
    // 哪几步点开了:按这一屏里的序号记,列表变长也不乱
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    // 步数多到值得收起来(超过这一屏列出的最近几步)时,才给「展开/收起」的开关
    val collapsible = steps.size > ChatCopy.VISIBLE_STEPS
    val hidden = if (all) 0 else (steps.size - ChatCopy.VISIBLE_STEPS).coerceAtLeast(0)
    Column(
        Modifier.padding(top = 6.dp).fillMaxWidth().drawBehind {
            val x = StepRail.toPx()
            drawLine(c.hair, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        },
    ) {
        // 顶部开关:折叠时「前面还有 N 步」,展开时「收起」,点一下来回切
        if (collapsible) {
            val label = if (all) stringResource(R.string.collapse) else ChatCopy.earlierSteps(hidden)
            StepItem(label, "", Dot.Dim, current = false, link = true, onClick = { all = !all })
        }
        val visible = steps.drop(hidden)
        visible.forEachIndexed { i, row ->
            val key = hidden + i
            val isLast = key == steps.lastIndex
            // 用户插话:右侧一步,插在完成的步骤和当前步之间
            if (isLast) steers.forEach { SteerStepPill(it) }
            val dot = when {
                live && isLast && paused -> Dot.Paused
                live && isLast && row.state == StepState.Run -> Dot.Current
                row.state == StepState.Dim -> Dot.Dim
                else -> Dot.Done
            }
            val open = expanded[key] == true
            val detail = actions[row.step].orEmpty()
            StepItem(
                row.text, row.subtitle, dot, current = dot == Dot.Current || dot == Dot.Paused,
                actions = detail, open = open,
                // 有具体动作才点得开:点开看这一步实际做了什么、对什么、结果如何
                onClick = if (detail.isEmpty()) null else ({ expanded[key] = !open }),
            )
        }
        // 还没有任何步骤时(刚把插话交给任务)只显示插话这一步
        if (visible.isEmpty()) steers.forEach { SteerStepPill(it) }
        // 底部「收起」:展开后步数一多,滑到最下面不用再爬回去也能收
        if (all && collapsible) {
            StepItem(stringResource(R.string.collapse), "", Dot.Dim, current = false, link = true, onClick = { all = false })
        }
    }
}

@Composable
private fun StepItem(
    text: String, subtitle: String, dot: Dot, current: Boolean, link: Boolean = false, onClick: (() -> Unit)? = null,
    actions: List<StepAction> = emptyList(), open: Boolean = false,
) {
    val c = MikaTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        Modifier.fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = stringResource(if (open) R.string.collapse else R.string.chat_expand_steps), onClick = onClick)
                } else Modifier,
            )
            .heightIn(min = 28.dp).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(StepRail * 2), contentAlignment = Alignment.Center) { StepDot(dot) }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text, style = if (current) StepCurrentText else StepText,
                color = when {
                    link -> c.accent
                    current -> c.ink
                    else -> c.ink2
                },
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) Text(subtitle, style = MikaType.Hint, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // 点开:这一步实际做的几件事,一件一行「做了什么 · 对什么 · 结果」,说法和运行过程页一样
            if (open) actions.forEach { a ->
                Text(
                    ChatCopy.actionLine(a), Modifier.padding(top = 2.dp),
                    style = MikaType.Hint, color = if (a.failed) c.bad else c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 能点开的只有有动作的那些:右边一个小箭头,点开时朝下
        if (actions.isNotEmpty()) DirectionalIcon(
            MikaIcons.Chevron, null,
            Modifier.padding(start = 6.dp).size(12.dp).graphicsLayer { rotationZ = if (open) if (rtl) -90f else 90f else 0f },
            tint = c.ink3,
        )
    }
}

/** 步骤点:6dp 实心;当前步外面一圈 3dp 的淡色光环。 */
@Composable
private fun StepDot(dot: Dot) {
    val c = MikaTheme.colors
    val (fill, ring) = when (dot) {
        Dot.Done -> c.ink3 to null
        Dot.Dim -> c.hair to null
        Dot.Current -> c.accent to c.accentSoft
        Dot.Paused -> c.warnSolid to c.warnSoft
    }
    // 底色先盖住竖线,点才是干净的
    val bg = c.bg
    Box(
        Modifier.size(12.dp).drawBehind {
            val center = Offset(size.width / 2, size.height / 2)
            if (ring != null) {
                drawCircle(bg, 6.dp.toPx(), center)
                drawCircle(ring, 6.dp.toPx(), center)
            }
            drawCircle(fill, 3.dp.toPx(), center)
        },
    )
}

/** 转圈:淡色圆环,顶上一段实色。[spinning] 为假(关了动画、停下来等你)时停在原处。 */
@Composable
internal fun Spinner(modifier: Modifier, spinning: Boolean, track: Color, head: Color) {
    val turn = if (!spinning) 0f else rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "spin",
    ).value
    Box(
        modifier.graphicsLayer { rotationZ = turn }
            .border(2.dp, track, CircleShape)
            .border(
                2.dp,
                Brush.sweepGradient(0f to Color.Transparent, 0.62f to Color.Transparent, 0.63f to head, 0.88f to head, 0.89f to Color.Transparent),
                CircleShape,
            ),
    )
}

/** 干活那行「活着」的信号:一颗呼吸的小点(淡→实、微微放大);停下来等你时变橙、静止。 */
@Composable
private fun BreathingDot(paused: Boolean, reduceMotion: Boolean) {
    val c = MikaTheme.colors
    val active = !paused && !reduceMotion
    val breathe = if (active) rememberInfiniteTransition(label = "breathe").animateFloat(
        0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe",
    ).value else 1f
    val scale = if (active) 0.8f + 0.38f * breathe else 1f
    val alpha = if (active) 0.28f + 0.72f * breathe else 1f
    Box(
        Modifier.padding(top = 5.dp).size(9.dp).graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .background(if (paused) c.warnSolid else c.accent, CircleShape),
    )
}
