package com.wangzan101.mika.capsule

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.chat.ui.ChatCopy
import com.wangzan101.mika.chat.ui.Spinner
import com.wangzan101.mika.voice.ui.VoiceCopy
import com.wangzan101.mika.voice.ui.VoiceWave
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voice.HoldToTalk
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.VoiceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 悬浮胶囊长按说话:松开时手指落在哪([CapsuleVoice.drop])、发出后胶囊上闪的那句([CapsuleVoice.sent]),
 * 一次说话的会话和界面状态([CapsuleTalk],状态机用和输入栏同一个 [HoldToTalk]),
 * 和按住时浮在胶囊旁边的面板([CapsuleVoicePanel]:说给谁、实时的字、波形,「取消」「改一改」两个落点)。
 * 说完交给谁、怎么发由 [CapsuleController] 定(和双击发屏幕同一套找目标)。
 */
object CapsuleVoice {
    /** 松开时:直接发出、打开卡片接着改、丢掉。 */
    enum class Drop { Send, Edit, Cancel }

    /** 胶囊上闪的那句里最多带几个字。 */
    private const val SENT_CHARS = 12

    /**
     * 手指在屏幕上的位置 ([x],[y]) 落在哪:两个圆各自外扩 [slopPx] 算命中,其余是发送。
     * 圆还没排好版(空的)时不算命中,免得面板刚出来那一下误判。
     */
    fun drop(x: Float, y: Float, cancel: Rect, edit: Rect, slopPx: Int): Drop {
        fun Rect.hit() = !isEmpty && x >= left - slopPx && x <= right + slopPx && y >= top - slopPx && y <= bottom + slopPx
        return when {
            cancel.hit() -> Drop.Cancel
            edit.hit() -> Drop.Edit
            else -> Drop.Send
        }
    }

    /** 发出后胶囊上那句:插话、回答还是发出,后面带说的话的前几个字。 */
    fun sent(kind: CardInputKind, text: String): String {
        val head = text.trim().let { if (it.length > SENT_CHARS) it.take(SENT_CHARS) + "…" else it }
        return when (kind) {
            CardInputKind.Steer -> tr(R.string.capsule_voice_steered, head)
            CardInputKind.Answer -> tr(R.string.capsule_voice_answered, head)
            CardInputKind.Continue, CardInputKind.Start -> tr(R.string.capsule_voice_sent, head)
        }
    }

    /** 面板顶上那句:说给谁。和卡片输入框交给谁一致;没有输入框(要你接管、同意)或开新对话时是「在听」。 */
    fun title(card: CardView?): String {
        val input = card?.let(CapsuleCards::input)?.kind ?: return tr(R.string.voice_listening)
        val goal = when (card) {
            is CardView.Detail -> card.run.run.goal
            is CardView.Chat -> card.run?.run?.goal ?: card.title.takeIf { card.target != null }
            is CardView.Kept -> null
        }?.takeIf { it.isNotBlank() }
        return when (input) {
            CardInputKind.Steer -> goal?.let { tr(R.string.voice_steer_to, it) } ?: tr(R.string.voice_listening)
            CardInputKind.Answer -> tr(R.string.capsule_voice_answer_title)
            CardInputKind.Continue, CardInputKind.Start -> goal?.let { tr(R.string.capsule_voice_continue_title, it) } ?: tr(R.string.voice_listening)
        }
    }
}

/**
 * 松开后等结果:到 [ms] 还没有人取消就调 [onTimeout]。
 * [arm] 重新计时(上一次作废);会话交出结果、重新按住、面板收起时 [cancel]。
 * 单独拎出来是为了能测:引擎卡住时面板一定要自己收掉。
 */
internal class VoiceWait(private val scope: CoroutineScope, private val onTimeout: () -> Unit) {
    private var job: Job? = null

    fun arm(ms: Long) {
        cancel()
        job = scope.launch {
            delay(ms)
            onTimeout()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

/**
 * 胶囊上一次说话:开会话([begin])、收事件、按着时推计时、手指移动时算落点([move])、松开([finish])。
 * 用手指按着时([byTouch])落点跟着手指;读屏的「说话」没有手指,只会发出。说完([HoldToTalk.State.Done]、
 * [HoldToTalk.State.Problem])交给 [onResult],连同松开时的落点。手还按着时会话先结束了,结果由 [HoldToTalk] 留着,
 * 等手指离开按那时的落点处理(取消就丢掉、改一改打开卡片、其余发出);读屏没有手指,会话一结束就当「发出」。
 * 松开后在转、手指又按下:旧的那段不要了,重新开始听。只在主线程用。
 */
internal class CapsuleTalk(
    private val context: Context,
    private val voice: () -> VoiceSource,
    private val scope: CoroutineScope,
    private val onResult: (CapsuleVoice.Drop, HoldToTalk.State) -> Unit,
) {
    /** [begin] 的结果:开始了,或者为什么没开始。 */
    sealed interface Begin {
        data object Started : Begin
        data object NoPermission : Begin
        data class Unavailable(val why: SpeechEngineSpec.Why) : Begin
    }

    // 「太短」从手指按下算:开始听时已经按了 HOLD_MS,状态机只再数剩下的
    val hold = HoldToTalk(clock = SystemClock::elapsedRealtime, maxMs = MAX_MS, minMs = MIN_TOTAL_MS - HOLD_MS)

    /** 面板在显示(按着、或松开后在转)。 */
    var active by mutableStateOf(false)
        private set
    /** 已松开(或读屏的「发出」),在等字。 */
    var released by mutableStateOf(false)
        private set
    var byTouch by mutableStateOf(true)
        private set
    var aim by mutableStateOf(CapsuleVoice.Drop.Send)
        private set
    var title by mutableStateOf("")
    var engine by mutableStateOf("")
        private set

    /** 两个落点在屏幕上的位置,面板排好版后报上来。 */
    var cancelAt = Rect.Zero
    var editAt = Rect.Zero

    private var drop: CapsuleVoice.Drop? = null
    private var collector: Job? = null
    private var ticker: Job? = null
    private var watcher: Job? = null
    /** 松开后等结果的兜底,见 [armWatchdog];[waitMs] 按这次用的引擎定。 */
    private val watchdog = VoiceWait(scope) {
        if (!active || !released) return@VoiceWait
        val at = drop ?: aim
        abort()
        onResult(at, HoldToTalk.State.Problem(null))
    }
    private var waitMs = WAIT_PROVIDER_MS
    private var startedAt = 0L

    /** 能不能用、有没有录音权限:都行才开始听。胶囊不是 Activity,弹不了系统的权限框。 */
    fun begin(title: String, byTouch: Boolean): Begin {
        if (active && !released) return Begin.Started
        val source = voice()
        val spec = source.spec()
        if (spec is SpeechEngineSpec.Unavailable) return Begin.Unavailable(spec.why)
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return Begin.NoPermission
        // 上一段松开后还在转(服务商卡住、或自带识别在收尾):用户重新按,上一段不要了;会话由 press 取消
        if (active) stop()
        hold.reset()
        val session = source.start()
        this.title = title
        this.byTouch = byTouch
        engine = VoiceCopy.engine(spec)
        waitMs = when (spec) {
            is SpeechEngineSpec.System -> WAIT_SYSTEM_MS
            is SpeechEngineSpec.Provider -> WAIT_PROVIDER_MS
            else -> WAIT_STREAM_MS
        }
        aim = CapsuleVoice.Drop.Send
        drop = null
        released = false
        cancelAt = Rect.Zero
        editAt = Rect.Zero
        startedAt = SystemClock.elapsedRealtime()
        hold.press(session)
        active = true
        collector = scope.launch { session.events.collect { hold.onEvent(it) } }
        watcher = scope.launch {
            hold.state.collect { s ->
                // 读屏没有手指可等:会话自己结束了就当按了「发出」
                if (s is HoldToTalk.State.Listening && s.ended && !this@CapsuleTalk.byTouch) finish(CapsuleVoice.Drop.Send)
                if (s is HoldToTalk.State.Done || s is HoldToTalk.State.Problem) {
                    val d = drop ?: aim
                    stop()
                    hold.reset()
                    onResult(d, s)
                }
            }
        }
        ticker = scope.launch {
            while (active && !released) {
                delay(TICK_MS)
                // 到点按此刻的落点收尾(状态机到点也会自己松开,但它不知道落点)
                val unlimited = (hold.state.value as? HoldToTalk.State.Listening)?.unlimited == true
                if (!unlimited && SystemClock.elapsedRealtime() - startedAt >= MAX_MS) finish(aim) else hold.tick()
            }
        }
        return Begin.Started
    }

    /** 手指移到了屏幕上的 ([x],[y])。 */
    fun move(x: Float, y: Float, slopPx: Int) {
        if (!active || released || !byTouch) return
        aim = CapsuleVoice.drop(x, y, cancelAt, editAt, slopPx)
        // 状态机也要知道手指在不在取消上:会话在按着时出错,停在取消上就不提示
        hold.armCancel(aim == CapsuleVoice.Drop.Cancel)
    }

    /** 松开(或读屏的「发出」):取消就丢掉,否则收尾等字。 */
    fun finish(at: CapsuleVoice.Drop) {
        if (!active || released) return
        if (at == CapsuleVoice.Drop.Cancel) {
            abort()
            return
        }
        aim = at
        drop = at
        released = true
        hold.armCancel(false)
        hold.release()
        armWatchdog()
    }

    /**
     * 松开后的兜底:引擎自己的收尾时限(自带识别 2 秒,流式连接加收尾约 20 秒,整段转写 30 秒)之外再留一点余量,
     * 到点还没交出结果就收起面板、当没听清。会话卡住时面板不会一直停在「转文字…」留在屏幕上。
     */
    private fun armWatchdog() {
        if (!active || !released) return
        watchdog.arm(waitMs)
    }

    /** 这一段不要了(拖到取消、胶囊被拆、触摸被系统收走)。可以重复调。 */
    fun abort() {
        stop()
        hold.dispose()
    }

    private fun stop() {
        active = false
        released = false
        drop = null
        ticker?.cancel()
        ticker = null
        watcher?.cancel()
        watcher = null
        collector?.cancel()
        collector = null
        watchdog.cancel()
    }

    companion object {
        /** 按住不动这么久才算长按(毫秒)。 */
        const val HOLD_MS = 400L
        /** 从手指按下算,不到这么久松开就当没听清。 */
        const val MIN_TOTAL_MS = 500L
        const val MAX_MS = 60_000L
        private const val TICK_MS = 100L
        /** 松开后等结果的上限,在引擎自己的收尾时限上各留一点余量(见 [armWatchdog])。 */
        private const val WAIT_SYSTEM_MS = 8_000L
        private const val WAIT_STREAM_MS = 25_000L
        private const val WAIT_PROVIDER_MS = 35_000L
    }
}

/** 面板和两个圆离胶囊多远、彼此多远;两个圆多大。 */
private val PanelGap = 16.dp
private val TargetsGap = 18.dp
private val TargetSize = 62.dp
private val PanelShape = RoundedCornerShape(24.dp)
private val HeadText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp)
private val TimeText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontFeatureSettings = "tnum")
private val ChipText = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp)
private val SaidText = TextStyle(fontSize = 17.5.sp, lineHeight = 26.sp)
private val FootText = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
private val TargetText = TextStyle(
    fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
    shadow = Shadow(Color.Black.copy(alpha = 0.4f), Offset(0f, 1f), 3f),
)
/** 背后的 App 稍微压暗,注意力在面板上。 */
private const val DIM = 0.28f

/**
 * 按住胶囊说话时整屏的那一层(不接触摸):背后稍微压暗,面板贴着胶囊([anchorTop]..[anchorBottom],屏幕纵坐标)那一边,
 * 放得下就在胶囊上面,否则在下面;两个落点在面板外侧(离胶囊更远),手指拖过去。
 * [insetTop]/[insetBottom] 是状态栏、底部手势条占的高度。两个圆排好版后把屏幕上的位置报给 [talk]。
 */
@Composable
internal fun CapsuleVoicePanel(talk: CapsuleTalk, anchorTop: Int, anchorBottom: Int, insetTop: Int, insetBottom: Int) {
    if (!talk.active) return
    val c = MikaTheme.colors
    val state by talk.hold.state.collectAsState()
    val view = LocalView.current
    val dim by animateFloatAsState(DIM, if (MikaTheme.reduceMotion) snap() else tween(200), label = "voice-dim")
    var below by remember { mutableStateOf(false) }
    // 圆在屏幕上的位置:窗口内坐标加上窗口在屏幕上的位置
    fun Rect.onScreen(): Rect {
        val at = IntArray(2).also(view::getLocationOnScreen)
        return translate(Offset(at[0].toFloat(), at[1].toFloat()))
    }
    Layout(
        content = {
            Targets(talk, show = talk.byTouch && !talk.released) { cancel, edit ->
                talk.cancelAt = cancel.onScreen()
                talk.editAt = edit.onScreen()
            }
            Panel(talk, state, below)
        },
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)).testTag("capsule-voice"),
    ) { measurables, cons ->
        val loose = cons.copy(minWidth = 0, minHeight = 0)
        val targets = measurables[0].measure(loose)
        val panel = measurables[1].measure(loose)
        val gap = PanelGap.roundToPx()
        val between = TargetsGap.roundToPx()
        val total = targets.height + between + panel.height
        val top = insetTop + gap
        val bottom = cons.maxHeight - insetBottom - gap
        // 胶囊上面放得下就放上面(两个圆在最上),否则放下面(两个圆在最下)
        val goBelow = anchorTop - gap - total < top
        if (goBelow != below) below = goBelow
        val groupTop = (if (goBelow) anchorBottom + gap else anchorTop - gap - total)
            .coerceAtMost(bottom - total).coerceAtLeast(top)
        layout(cons.maxWidth, cons.maxHeight) {
            if (goBelow) {
                panel.place((cons.maxWidth - panel.width) / 2, groupTop)
                targets.place((cons.maxWidth - targets.width) / 2, groupTop + panel.height + between)
            } else {
                targets.place((cons.maxWidth - targets.width) / 2, groupTop)
                panel.place((cons.maxWidth - panel.width) / 2, groupTop + targets.height + between)
            }
        }
    }
}

/** 「取消」「改一改」两个圆;手指移上去的那个放大、变色。松开后在转时藏起来(位置照样留着,面板不跳)。 */
@Composable
private fun Targets(talk: CapsuleTalk, show: Boolean, onPlaced: (Rect, Rect) -> Unit) {
    // 两个圆各自排好版的位置(窗口内坐标);两个都有了才报
    val at = remember { arrayOf(Rect.Zero, Rect.Zero) }
    Row(
        Modifier.alpha(if (show) 1f else 0f),
        horizontalArrangement = Arrangement.spacedBy(56.dp),
    ) {
        Target(MikaIcons.Close, stringResource(R.string.capsule_voice_cancel), hot = show && talk.aim == CapsuleVoice.Drop.Cancel, bad = true) {
            at[0] = it
            if (!at[1].isEmpty) onPlaced(at[0], at[1])
        }
        Target(MikaIcons.Edit, stringResource(R.string.capsule_voice_edit), hot = show && talk.aim == CapsuleVoice.Drop.Edit, bad = false) {
            at[1] = it
            if (!at[0].isEmpty) onPlaced(at[0], at[1])
        }
    }
}

@Composable
private fun Target(icon: ImageVector, label: String, hot: Boolean, bad: Boolean, onPlaced: (Rect) -> Unit) {
    val c = MikaTheme.colors
    val scale by animateFloatAsState(if (hot) 1.18f else 1f, if (MikaTheme.reduceMotion) snap() else tween(150), label = "target")
    val fill = when {
        !hot -> c.surface.copy(alpha = 0.95f)
        bad -> c.bad
        else -> c.accent
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(TargetSize)
                .onGloballyPositioned { onPlaced(it.boundsInWindow()) }
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .glass(c, CircleShape, need = false)
                .background(fill, CircleShape)
                .testTag(if (bad) "capsule-voice-cancel" else "capsule-voice-edit"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = if (hot) c.onAccent else c.ink)
        }
        Text(label, style = TargetText, color = Color.White, maxLines = 1)
    }
}

/** 面板:顶上在录的点、说给谁、用的哪个转文字、用时;中间实时的字;波形;底下松开会怎样。 */
@Composable
private fun Panel(talk: CapsuleTalk, state: HoldToTalk.State, below: Boolean) {
    val c = MikaTheme.colors
    val listening = state as? HoldToTalk.State.Listening
    val converting = talk.released || state is HoldToTalk.State.Converting
    val aim = if (converting) CapsuleVoice.Drop.Send else talk.aim
    val cancel = aim == CapsuleVoice.Drop.Cancel
    val edit = aim == CapsuleVoice.Drop.Edit
    Column(
        Modifier.padding(horizontal = 14.dp).widthIn(max = 520.dp).fillMaxWidth()
            .glass(c, PanelShape, need = false)
            .then(if (cancel) Modifier.border(1.5.dp, c.bad, PanelShape) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
            .testTag("capsule-voice-panel"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val dot = when {
                cancel -> c.bad
                edit || converting -> c.ink2
                else -> c.accent
            }
            Box(Modifier.size(8.dp).drawBehind { drawCircle(dot) })
            Text(talk.title, Modifier.weight(1f, fill = false), style = HeadText, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (talk.engine.isNotEmpty()) {
                Text(
                    talk.engine, Modifier.background(c.fill, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                    style = ChipText, color = c.ink2, maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            listening?.let { Text(ChatCopy.clock(it.elapsedMs), style = TimeText, color = c.ink3) }
        }
        val heard = listening?.let { it.stable + it.tail }.orEmpty()
        val ghost = when {
            converting -> ""
            listening?.live == true -> stringResource(R.string.voice_say_now)
            else -> stringResource(R.string.voice_recording)
        }
        if (converting && heard.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 52.dp).testTag("capsule-voice-converting"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                Spinner(Modifier.size(15.dp), spinning = !MikaTheme.reduceMotion, track = c.accentSoft, head = c.accent)
                Text(stringResource(R.string.voice_converting), style = SaidText, color = c.ink2, maxLines = 1)
            }
        } else {
            val style = if (cancel) SaidText.copy(textDecoration = TextDecoration.LineThrough) else SaidText
            val modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 52.dp).alpha(if (cancel) 0.4f else 1f)
            if (heard.isEmpty()) Text(ghost, modifier, style = style, color = c.ink3, maxLines = 5, overflow = TextOverflow.Ellipsis)
            // 字太多时去掉前面说完的整句,最新的字一直看得见(和输入框同一套)
            else com.wangzan101.mika.voice.ui.HeardText(listening?.stable.orEmpty(), listening?.tail.orEmpty(), style, maxLines = 5, modifier)
        }
        if (!converting) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                VoiceWave(listening?.level ?: 0f, flat = cancel, reduceMotion = MikaTheme.reduceMotion, color = if (cancel || edit) c.ink3 else c.accent)
            }
        }
        // 在转时中间已经写着「转文字…」,底下不再重复
        if (converting) return@Column
        val foot = when {
            !talk.byTouch -> stringResource(R.string.capsule_voice_foot_a11y)
            cancel -> stringResource(R.string.capsule_voice_foot_cancel)
            edit -> stringResource(R.string.capsule_voice_foot_edit)
            listening?.live == true -> stringResource(if (below) R.string.capsule_voice_foot_send_down else R.string.capsule_voice_foot_send_up)
            else -> stringResource(if (below) R.string.capsule_voice_foot_convert_down else R.string.capsule_voice_foot_convert_up)
        }
        Text(foot, Modifier.fillMaxWidth().padding(top = 8.dp), style = FootText, color = c.ink3, maxLines = 2)
    }
}
