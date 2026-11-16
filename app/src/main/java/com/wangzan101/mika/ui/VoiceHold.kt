package com.wangzan101.mika.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.GlassBackdrop
import com.wangzan101.mika.ui.components.GlassBar
import com.wangzan101.mika.ui.components.GlassBlurSupported
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.floatShadow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.HoldToTalk
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.SpeechFailure
import com.wangzan101.mika.voice.VoiceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 对话输入栏的按住说话:输入框上的手势([holdToTalk])、按住时整条变蓝的输入栏([VoiceBarCover])、
 * 输入栏上方的「在听」卡片和「上滑 发送/取消」提示、没听清之类的轻提示([VoiceFloat])、
 * 「还不能语音输入」和录音权限的说明([VoiceSheetHost])。状态都在 [HoldToTalk] 里,这里只管画和接线。
 */

/** 按住约这么久(毫秒)才开始听;不到就是点一下打字。 */
private const val HOLD_MS = 300L

/** 从手指按下算,不到这么久(毫秒)松开就当误触、没听清。 */
private const val MIN_TOTAL_MS = 500L

/** 一次最多听这么久;最后 [COUNTDOWN_MS] 卡片右下角倒数。 */
private const val MAX_MS = 60_000L
private const val COUNTDOWN_MS = 10_000L

/** 「在听」卡片还没排好版时:手指往上滑过这么多就是「松开取消」。 */
private val CancelLift = 80.dp

/** 手指要往上滑过这么多,停在「在听」卡片里才是「松开 发送」;拇指稍微一动不会就直接发出去。 */
private val SendLift = 32.dp

/** 松开时手指落在哪:取消、直接发送,或都不是(字填进输入框)。 */
internal enum class VoiceAim { Fill, Send, Cancel }

/**
 * 按着说话时手指的落点。[finger] 和 [card](「在听」卡片)都是根布局坐标,[upPx] 是从按下处往上移了多少。
 * 卡片还没排好版(空的)时照旧只看上滑距离:过了 [cancelLiftPx] 是取消。
 * 排好版后:手指在卡片上沿以上是取消(「上滑 发送/取消」的小胶囊就在那里);在卡片里、且上滑过 [sendLiftPx] 是直接发送;
 * 其余(还在输入栏上、或卡片和输入栏之间)是填进输入框。只看纵向,卡片本来就差不多和屏幕一样宽。
 */
internal fun voiceAim(finger: Offset, card: Rect, upPx: Float, cancelLiftPx: Float, sendLiftPx: Float): VoiceAim = when {
    card.isEmpty -> if (upPx > cancelLiftPx) VoiceAim.Cancel else VoiceAim.Fill
    finger.y < card.top -> VoiceAim.Cancel
    finger.y <= card.bottom && upPx > sendLiftPx -> VoiceAim.Send
    else -> VoiceAim.Fill
}

/** 底部弹层:哪种说明。 */
internal sealed interface VoiceSheet {
    data object Permission : VoiceSheet
    /** 已经允许录音,识别服务仍说没有权限或录音出错:说明系统的麦克风总开关和权限管理,或被别的应用占用。 */
    data object MicBlocked : VoiceSheet
    data class Unavailable(val why: SpeechEngineSpec.Why) : VoiceSheet
    /** 缺录音权限的是识别服务(另一个应用),不是 Mika:说明这一点,「去设置」打开的是它的设置页。 */
    data class ServicePermission(val service: com.wangzan101.mika.voice.SpeechServiceTarget) : VoiceSheet
}

/**
 * 按着时听到的字:定下的深色、还在变的浅色。放不下 [maxLines] 行时按 [VoiceCopy.fitHeard] 去掉前面说完的整句,
 * 最新的字一直看得见。输入框和悬浮胶囊共用。
 */
@Composable
internal fun HeardText(stable: String, tail: String, style: androidx.compose.ui.text.TextStyle, maxLines: Int, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val (shownStable, shownTail) = remember(stable, tail, width, style, maxLines) {
            VoiceCopy.fitHeard(stable, tail) { st, tl ->
                measurer.measure(
                    androidx.compose.ui.text.AnnotatedString(st + tl), style,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = width.coerceAtLeast(1)),
                ).lineCount <= maxLines
            }
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = c.ink)) { append(shownStable) }
                withStyle(SpanStyle(color = c.ink2)) { append(shownTail) }
            },
            Modifier.fillMaxWidth(), style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 按住说话时界面上要的文字;不碰界面,单测直接测。 */
internal object VoiceCopy {
    /** 一句说完的地方:这些标点之后断开。 */
    private val sentenceEnd = setOf('。', '！', '？', '；', '!', '?', ';', '.', '…', '\n')

    /**
     * 按着时听到的字太多、卡片里放不下时:从前面去掉已经说完的整句([stable] 里句号、问号等之后断开),前面加「…」,
     * 留下最近的几句和还在说的 [tail];只剩还在说的那句也放不下时,留它的末尾。[fits] 量一段字(定下的、还在变的)放不放得下。
     * 只改显示,交出去的文字不变。
     */
    fun fitHeard(stable: String, tail: String, fits: (String, String) -> Boolean): Pair<String, String> {
        if (fits(stable, tail)) return stable to tail
        val cuts = stable.indices.filter { stable[it] in sentenceEnd && it + 1 < stable.length }.map { it + 1 }
        for (cut in cuts) {
            val rest = ELLIPSIS + stable.substring(cut).trimStart()
            if (fits(rest, tail)) return rest to tail
        }
        if (fits(ELLIPSIS, tail)) return ELLIPSIS to tail
        // 还在说的那句自己就放不下:找能放下的最长末尾
        var lo = 0
        var hi = tail.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (fits(ELLIPSIS, tail.takeLast(mid))) lo = mid else hi = mid - 1
        }
        return ELLIPSIS to tail.takeLast(lo)
    }

    private const val ELLIPSIS = "…"

    /** 卡片顶上的小标签:用的是哪个转文字。 */
    fun engine(spec: SpeechEngineSpec): String = when (spec) {
        is SpeechEngineSpec.Doubao -> tr(R.string.speech_doubao_model)
        is SpeechEngineSpec.FunAsr -> tr(R.string.speech_funasr_model)
        is SpeechEngineSpec.Bailian -> spec.model
        is SpeechEngineSpec.System -> tr(if (spec.onDevice) R.string.voice_engine_phone_offline else R.string.voice_engine_phone)
        is SpeechEngineSpec.Provider -> spec.model
        is SpeechEngineSpec.Unavailable -> ""
    }

    /** 失败时输入栏上方那一句;权限、引擎用不了平时走说明弹层,这里只给一句标题兜底。 */
    fun failure(why: SpeechFailure): String = when (why) {
        SpeechFailure.Busy -> tr(R.string.voice_busy)
        SpeechFailure.Network -> tr(R.string.voice_network)
        SpeechFailure.MicUnavailable, SpeechFailure.AudioError -> tr(R.string.voice_mic_unavailable)
        // 服务商的错沿用模型出错时的说法(额度、Key、找不到模型……)
        is SpeechFailure.Provider -> tr(R.string.voice_provider_failed, why.error.message.orEmpty())
        SpeechFailure.NoPermission -> tr(R.string.voice_permission_title)
        is SpeechFailure.ServiceNoPermission -> tr(R.string.voice_service_permission_title)
        is SpeechFailure.Unavailable -> tr(R.string.voice_unavailable_title)
    }

    val NO_MATCH get() = tr(R.string.voice_no_match)

    fun unavailable(why: SpeechEngineSpec.Why): String = when (why) {
        SpeechEngineSpec.Why.DoubaoNotConfigured -> tr(R.string.speech_doubao_missing)
        SpeechEngineSpec.Why.BailianNotConfigured -> tr(R.string.speech_bailian_missing)
        SpeechEngineSpec.Why.NoSystemNoModel -> tr(R.string.voice_unavailable_no_engine)
        SpeechEngineSpec.Why.SystemGone -> tr(R.string.voice_unavailable_system_gone)
        SpeechEngineSpec.Why.SystemNoResponse -> tr(R.string.voice_unavailable_system_silent)
        SpeechEngineSpec.Why.SystemFailed -> tr(R.string.voice_unavailable_system_failed)
        SpeechEngineSpec.Why.ProviderGone -> tr(R.string.voice_unavailable_provider_gone)
        SpeechEngineSpec.Why.Off -> tr(R.string.voice_unavailable_off)
    }

    /** 最后十秒的倒数;还早时为 null。 */
    fun countdown(elapsedMs: Long): String? {
        val left = MAX_MS - elapsedMs
        if (left > COUNTDOWN_MS) return null
        return tr(R.string.voice_left, ((left + 999) / 1000).toInt().coerceAtLeast(0))
    }
}

/** 录音权限被拒过的记号:系统不再弹框时(拒过、也不该再解释)改弹说明,按钮直达 App 的权限页。 */
private object MicPermission {
    private const val PREFS = "voice_input"
    private const val DENIED = "mic_denied"

    fun denied(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(DENIED, false)

    fun record(context: Context, granted: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(DENIED, !granted).apply()
}

/**
 * 输入栏一次按住说话的界面状态:手指是不是还按着([holding])、卡片上的引擎标签、弹层和轻提示。
 * 开会话、转事件、收尾都交给 [hold]。
 */
@Stable
internal class VoiceHold(private val voice: VoiceSource, private val scope: CoroutineScope) {
    // 「太短」按手指按下算不到半秒:开始听时已经按了 HOLD_MS,状态机只再数剩下的
    val hold = HoldToTalk(clock = SystemClock::elapsedRealtime, maxMs = MAX_MS, minMs = MIN_TOTAL_MS - HOLD_MS)

    /** 长按开始以后手指还没离开。 */
    var holding by mutableStateOf(false)
        private set
    var engine by mutableStateOf("")
        private set
    var sheet by mutableStateOf<VoiceSheet?>(null)
    var toast by mutableStateOf<String?>(null)

    /** 有录音权限时返回 true;没有时去要(或弹说明)并返回 false,这一次不开始。由界面装上(要 Activity)。 */
    internal var ensureMic: () -> Boolean = { true }
    internal var haptic: () -> Unit = {}
    private var collector: Job? = null

    /**
     * 「在听」卡片在根布局里的位置,卡片排好版后报上来。松开后卡片还在(在等字)时照样留着:
     * 这时再按住,卡片没动、不会再报一次。
     */
    internal var cardAt = Rect.Zero
    /** 调用方能直接发出时才有「松开 发送」;不能时手指在卡片里也只是填进输入框。 */
    internal var canSend = false

    /**
     * 长按到时:先看能不能用、有没有权限,都行才开始听。
     * 上一段松开后还在转(服务商卡住、或自带识别在收尾)也可以重新按:上一段不要了,由 [HoldToTalk.press] 取消。
     */
    fun begin() {
        if (holding) return
        toast = null
        hold.reset()
        val spec = voice.spec()
        if (spec is SpeechEngineSpec.Unavailable) {
            sheet = VoiceSheet.Unavailable(spec.why)
            return
        }
        if (!ensureMic()) return
        haptic()
        val session = voice.start()
        engine = VoiceCopy.engine(spec)
        hold.press(session)
        holding = true
        collector?.cancel()
        collector = scope.launch { session.events.collect { hold.onEvent(it) } }
    }

    /** 手指移到了根布局里的 [finger],比按下处往上 [upPx]:算出落点告诉状态机。 */
    fun move(finger: Offset, upPx: Float, cancelLiftPx: Float, sendLiftPx: Float) {
        if (!holding) return
        val aim = voiceAim(finger, cardAt, upPx, cancelLiftPx, sendLiftPx)
        hold.aim(cancel = aim == VoiceAim.Cancel, send = aim == VoiceAim.Send && canSend)
    }

    /** 手指离开:交给状态机(取消、直接发送、太短、收尾)。 */
    fun end() {
        if (!holding) return
        holding = false
        hold.release()
    }

    /** 手势被打断、离开页面、转屏、App 到后台:这一段不要了。 */
    fun abort() {
        holding = false
        hold.dispose()
        collector?.cancel()
        collector = null
    }
}

private val IdleState = MutableStateFlow<HoldToTalk.State>(HoldToTalk.State.Idle)

/**
 * 输入栏要的那一份 [VoiceHold];没有 [voice] 或用户在设置里选了「不用」时为 null(输入框只能打字)。
 * 说完的字交给 [onText];松开在「松开 发送」上时交给 [onSend],直接发出。没有 [onSend] 时没有「松开 发送」。
 */
@Composable
internal fun rememberVoiceHold(voice: VoiceSource?, onText: (String) -> Unit, onSend: ((String) -> Unit)? = null): VoiceHold? {
    val off = remember(voice) {
        val spec = voice?.spec()
        voice == null || (spec is SpeechEngineSpec.Unavailable && spec.why == SpeechEngineSpec.Why.Off)
    }
    val scope = rememberCoroutineScope()
    val vh = remember(voice, off) { if (off || voice == null) null else VoiceHold(voice, scope) } ?: return null
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        MicPermission.record(context, granted)
    }
    vh.haptic = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    vh.canSend = onSend != null
    vh.ensureMic = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) true
        else {
            // 第一次按住才要权限;拒过且系统不再弹框时,改成说明 + 去系统设置
            val activity = context.findActivity()
            val blocked = MicPermission.denied(context) && activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            if (blocked) vh.sheet = VoiceSheet.Permission else launcher.launch(Manifest.permission.RECORD_AUDIO)
            false
        }
    }
    val state by vh.hold.state.collectAsState()
    LaunchedEffect(state) {
        when (val s = state) {
            is HoldToTalk.State.Done -> {
                if (s.send && onSend != null) onSend(s.text) else onText(s.text)
                vh.hold.reset()
            }
            is HoldToTalk.State.Problem -> {
                when (val why = s.why) {
                    null -> vh.toast = VoiceCopy.NO_MATCH
                    is SpeechFailure.Unavailable -> vh.sheet = VoiceSheet.Unavailable(why.why)
                    // 录音权限还没给:照旧弹系统框或「去设置」。已经给了仍报没有权限,或识别服务报录音出错:
                    // 另弹说明(系统的麦克风总开关、权限管理)。麦克风起不来的其他情况照旧提示可能被占用
                    // 查得出来是识别服务自己没有录音权限:说明是它,带用户去它的设置页(去 Mika 的权限页是指错了地方)
                    is SpeechFailure.ServiceNoPermission -> vh.sheet = VoiceSheet.ServicePermission(why.service)
                    SpeechFailure.NoPermission -> if (vh.ensureMic()) vh.sheet = VoiceSheet.MicBlocked
                    SpeechFailure.AudioError -> vh.sheet = VoiceSheet.MicBlocked
                    else -> vh.toast = VoiceCopy.failure(why)
                }
                vh.hold.reset()
            }
            else -> Unit
        }
    }
    // 按着时由界面推着走:计时、到 60 秒收尾(录音到点自己停,不收尾字就丢了)
    val listening = state is HoldToTalk.State.Listening
    LaunchedEffect(listening) {
        while (listening) {
            delay(100)
            vh.hold.tick()
        }
    }
    LaunchedEffect(vh.toast) {
        val t = vh.toast ?: return@LaunchedEffect
        delay(if (t == VoiceCopy.NO_MATCH) 2_000 else 3_500)
        vh.toast = null
    }
    // 离开页面、转屏、App 到后台:丢掉正在说的这一段
    LifecycleStartEffect(vh) { onStopOrDispose { vh.abort() } }
    return vh
}

/** [vh] 此刻的状态;没有 [vh] 时一直是平时。 */
@Composable
internal fun voiceState(vh: VoiceHold?): HoldToTalk.State = (vh?.hold?.state ?: IdleState).collectAsState().value

/**
 * 盖在没聚焦的输入框上的手势:点一下照旧打字([onTap] 聚焦弹键盘),按住约 0.3 秒开始听,
 * 按着往上滑进「在听」卡片是直接发送、滑过卡片上沿是取消(见 [voiceAim]),松开交给状态机。
 */
@Composable
internal fun Modifier.holdToTalk(vh: VoiceHold, onTap: () -> Unit): Modifier {
    val density = LocalDensity.current
    val cancelLift = with(density) { CancelLift.toPx() }
    val sendLift = with(density) { SendLift.toPx() }
    // 手势这块在根布局里的位置:手指的坐标是相对它的,卡片的是根布局的
    val origin = remember { arrayOf(Offset.Zero) }
    return onGloballyPositioned { origin[0] = it.positionInRoot() }.pointerInput(vh, cancelLift, sendLift) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            // true:点一下;false:手指动了或没了,不算我们的;null:按满了,开始听
            val tapped = withTimeoutOrNull<Boolean>(HOLD_MS) {
                var result: Boolean? = null
                while (result == null) {
                    val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    result = when {
                        ch == null -> false
                        !ch.pressed -> { ch.consume(); true }
                        (ch.position - down.position).getDistance() > viewConfiguration.touchSlop -> false
                        else -> null
                    }
                }
                result
            }
            when (tapped) {
                true -> { onTap(); return@awaitEachGesture }
                false -> return@awaitEachGesture
                null -> Unit
            }
            vh.begin()
            if (!vh.holding) return@awaitEachGesture
            var ended = false
            try {
                while (true) {
                    val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    if (ch == null || !ch.pressed) {
                        ch?.consume()
                        ended = true
                        vh.end()
                        break
                    }
                    ch.consume()
                    vh.move(origin[0] + ch.position, down.position.y - ch.position.y, cancelLift, sendLift)
                }
            } finally {
                if (!ended) vh.abort()
            }
        }
    }
}

/** 按着时整条输入栏变蓝(上滑到取消时变红、滑进卡片要直接发送时变绿),中间波形和「松开 填入」;盖在玻璃输入栏上。 */
@Composable
internal fun VoiceBarCover(vh: VoiceHold, state: HoldToTalk.State, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val s = state as? HoldToTalk.State.Listening
    if (!vh.holding || s == null) return
    val c = MikaTheme.colors
    val label = when {
        s.cancelArmed -> stringResource(R.string.voice_release_cancel)
        s.sendArmed -> stringResource(R.string.voice_release_send)
        s.live -> stringResource(R.string.voice_release_fill)
        else -> stringResource(R.string.voice_release_convert)
    }
    Row(
        modifier.clip(CircleShape).background(aimTone(s, c.accent)).testTag("voice-bar"),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VoiceWave(s.level, flat = s.cancelArmed, reduceMotion = reduceMotion, color = c.onAccent)
        Text(label, style = BarLabel, color = c.onAccent, maxLines = 1)
    }
}

/** 波形:14 根细条,高低跟着音量走;关了动画时只跟音量,不自己摆。输入栏和悬浮胶囊的说话面板共用。 */
@Composable
internal fun VoiceWave(level: Float, flat: Boolean, reduceMotion: Boolean, color: Color) {
    val loud by animateFloatAsState(if (flat) 0f else level.coerceIn(0f, 1f), if (reduceMotion) snap() else tween(120), label = "wave-level")
    val phase = if (reduceMotion || flat) 0f else rememberInfiniteTransition(label = "wave").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "wave-phase",
    ).value
    Row(Modifier.height(22.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(WAVE_BARS) { k ->
            val peak = 8f + kotlin.math.abs(kotlin.math.sin(k * 1.7f)) * 14f
            val swing = if (reduceMotion) 1f else 0.55f + 0.45f * kotlin.math.sin(phase + k * 0.9f)
            val h = if (flat) 4f else 5f + (peak - 5f) * (0.25f + 0.75f * loud) * swing
            Box(Modifier.width(3.dp).height(h.dp).background(color, RoundedCornerShape(2.dp)))
        }
    }
}

private const val WAVE_BARS = 14

/**
 * 输入栏上方浮起的那一层,不占布局(放在输入栏前面、高度算 0、往上画):
 * 按着时的「上滑 发送/取消」提示、「在听」卡片,以及没听清之类的轻提示。
 * [title] 是卡片顶上那句(平时「在听」,任务在跑时「插话给『…』」)。
 */
@Composable
internal fun VoiceFloat(vh: VoiceHold, state: HoldToTalk.State, title: String, backdrop: GlassBackdrop?, reduceMotion: Boolean) {
    val gap = with(LocalDensity.current) { 12.dp.roundToPx() }
    val listening = state as? HoldToTalk.State.Listening
    // 卡片收起的动画里还要画最后那一刻的内容
    val last = remember { arrayOfNulls<HoldToTalk.State.Listening>(1) }
    if (listening != null) last[0] = listening
    Box(
        Modifier.fillMaxWidth().layout { m, cons ->
            val p = m.measure(cons.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            layout(cons.maxWidth, 0) { p.place(0, -p.height - gap) }
        },
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val armed = listening?.cancelArmed == true
            AnimatedVisibility(vh.holding && listening != null, enter = fadeIn(tween(if (reduceMotion) 0 else 200)), exit = fadeOut(tween(if (reduceMotion) 0 else 150))) {
                CancelHint(armed, Modifier.padding(bottom = 14.dp))
            }
            AnimatedVisibility(
                listening != null,
                enter = if (reduceMotion) fadeIn(snap()) else
                    fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 12 } + scaleIn(tween(200), initialScale = 0.98f),
                exit = fadeOut(tween(if (reduceMotion) 0 else 150)),
            ) {
                last[0]?.let { ListenCard(it, title, vh.engine, backdrop) { at -> vh.cardAt = at } }
            }
            val toast = vh.toast
            if (toast != null && listening == null) VoiceToast(toast)
        }
    }
}

/** 「上滑 发送/取消」的小胶囊:往上滑进卡片是发送、再滑过卡片到这里是取消;滑到这里变红写「松开 取消」。 */
@Composable
private fun CancelHint(armed: Boolean, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.floatShadow(c, shape).background(if (armed) c.bad else c.surface, shape)
            .padding(horizontal = 14.dp, vertical = 7.dp).testTag("voice-cancel-hint"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fg = if (armed) c.onAccent else c.ink2
        Icon(MikaIcons.Up, null, Modifier.size(15.dp), tint = fg)
        Text(stringResource(if (armed) R.string.voice_release_cancel else R.string.voice_slide_send_cancel), style = HintText, color = fg)
    }
}

/**
 * 「在听」卡片:顶上一个红点(在录)、说给谁、用的哪个转文字、用时;中间实时的字(末尾还可能变的几个字浅一些);
 * 底下说松开以后会怎样,最后十秒倒数。上滑到取消时字划掉、边框变红;滑进卡片要直接发送时边框变绿。
 * 排好版后把自己在根布局里的位置交给 [onPlaced](手指落在卡片里就是直接发送)。
 */
@Composable
private fun ListenCard(
    s: HoldToTalk.State.Listening, title: String, engine: String, backdrop: GlassBackdrop?, onPlaced: (Rect) -> Unit,
) {
    val c = MikaTheme.colors
    val shape = RoundedCornerShape(22.dp)
    GlassBar(
        Modifier.fillMaxWidth().testTag("voice-listen").onGloballyPositioned { onPlaced(it.boundsInRoot()) }
            .then(if (s.cancelArmed || s.sendArmed) Modifier.border(1.5.dp, aimTone(s, c.accent), shape) else Modifier),
        backdrop, shape,
    ) {
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecDot(s)
                Text(title, Modifier.weight(1f, fill = false), style = HeadText, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (engine.isNotEmpty()) {
                    Text(
                        engine, Modifier.background(c.fill, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                        style = ChipText, color = c.ink2, maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(ChatCopy.clock(s.elapsedMs), style = TimeText, color = c.ink3)
            }
            val heard = s.stable + s.tail
            val style = if (s.cancelArmed) ListenText.copy(textDecoration = TextDecoration.LineThrough) else ListenText
            val modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 54.dp).alpha(if (s.cancelArmed) 0.4f else 1f)
            if (heard.isEmpty()) {
                Text(
                    stringResource(if (s.live) R.string.voice_say_now else R.string.voice_recording), modifier,
                    style = style, color = c.ink3, maxLines = 6, overflow = TextOverflow.Ellipsis,
                )
            } else {
                HeardText(s.stable, s.tail, style, maxLines = 6, modifier)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    stringResource(if (s.live) R.string.voice_foot_live else R.string.voice_foot_convert),
                    Modifier.weight(1f), style = FootText, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (!s.unlimited) VoiceCopy.countdown(s.elapsedMs)?.let { Text(it, style = FootText, color = c.warn) }
            }
        }
    }
}

/** 卡片顶上那个点:在录时蓝色一圈一圈往外;要取消时变红、要直接发送时变绿,都不动。 */
@Composable
private fun RecDot(s: HoldToTalk.State.Listening) {
    val c = MikaTheme.colors
    val reduce = MikaTheme.reduceMotion
    val armed = s.cancelArmed || s.sendArmed
    val halo = if (armed || reduce) 0f else rememberInfiniteTransition(label = "rec").animateFloat(
        0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "rec-halo",
    ).value
    val dot = aimTone(s, c.accent)
    val soft = c.accentSoft
    Box(
        Modifier.size(8.dp).drawBehind {
            if (halo > 0f) drawCircle(soft, radius = size.minDimension / 2 + 5.dp.toPx() * halo)
            drawCircle(dot)
        },
    )
}

/** 落点的颜色:取消红、直接发送绿,其余 [idle]。输入栏、卡片边框和卡片上的点共用,和取消一个样式。 */
@Composable
private fun aimTone(s: HoldToTalk.State.Listening, idle: Color): Color {
    val c = MikaTheme.colors
    return when {
        s.cancelArmed -> c.bad
        s.sendArmed -> c.ok
        else -> idle
    }
}

/** 输入栏上方的一句轻提示(没听清、识别服务忙……),一会儿自己消失。 */
@Composable
private fun VoiceToast(text: String) {
    val c = MikaTheme.colors
    Text(
        text,
        Modifier.background(c.ink.copy(alpha = 0.88f), RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 9.dp).testTag("voice-toast"),
        style = ToastText, color = c.bg, maxLines = 2,
    )
}

/** 服务商松开后在转:输入框里转圈加「转文字…」。 */
@Composable
internal fun VoiceConverting(modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    Row(modifier.testTag("voice-converting"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Spinner(Modifier.size(13.dp), spinning = !MikaTheme.reduceMotion, track = c.accentSoft, head = c.accent)
        Text(stringResource(R.string.voice_converting), style = MikaType.Body, color = c.ink2, maxLines = 1)
    }
}

/** 能按住说话时的灰字：在可用输入区域里居中。 */
@Composable
internal fun VoicePlaceholder(mode: ComposerMode, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(ChatCopy.voicePlaceholder(mode), style = MikaType.Body, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 按着说话时对话内容虚化变淡,注意力在卡片上;安卓 12 以下只变淡。 */
@Composable
internal fun Modifier.voiceDim(on: Boolean, reduceMotion: Boolean): Modifier {
    val p by animateFloatAsState(if (on) 1f else 0f, if (reduceMotion) snap() else tween(250), label = "voice-dim")
    if (p == 0f) return this
    return graphicsLayer {
        alpha = 1f - 0.55f * p
        if (GlassBlurSupported) {
            val r = 1.5.dp.toPx() * p
            renderEffect = if (r >= 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
        }
    }
}

/** 「还不能语音输入」(「去选」到设置里选转文字)和录音权限的说明(「去设置」到系统里 Mika 的权限页)。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceSheetHost(vh: VoiceHold, onPickSpeech: () -> Unit) {
    val sheet = vh.sheet ?: return
    val c = MikaTheme.colors
    val context = LocalContext.current
    val title: String
    val body: String
    val action: String
    val go: () -> Unit
    when (sheet) {
        is VoiceSheet.Unavailable -> {
            title = stringResource(R.string.voice_unavailable_title)
            body = VoiceCopy.unavailable(sheet.why)
            action = stringResource(R.string.voice_go_pick)
            go = onPickSpeech
        }
        is VoiceSheet.ServicePermission -> {
            val service = sheet.service
            title = stringResource(R.string.voice_service_permission_title)
            body = stringResource(R.string.voice_service_permission_body, service.label)
            action = stringResource(R.string.voice_permission_open)
            // Mika 不能替别的应用申请权限,只能带用户到它的设置页。回来之后不自己重新录音:下次按住时再试。
            // 这一页打不开时(有的系统不让看某些应用的详情)告诉用户到哪里找
            go = {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", service.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: RuntimeException) {
                    vh.toast = tr(R.string.voice_service_settings_failed, service.label)
                }
            }
        }
        VoiceSheet.Permission, VoiceSheet.MicBlocked -> {
            val blocked = sheet == VoiceSheet.MicBlocked
            title = stringResource(if (blocked) R.string.voice_mic_blocked_title else R.string.voice_permission_title)
            body = stringResource(if (blocked) R.string.voice_mic_blocked_body else R.string.voice_permission_body)
            action = stringResource(R.string.voice_permission_open)
            go = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = { vh.sheet = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 18.dp).testTag("voice-sheet")) {
            Text(title, style = MikaType.Title, color = c.ink)
            Text(body, Modifier.padding(top = 8.dp), style = SheetBody, color = c.ink2)
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MikaButton(stringResource(R.string.ok_got_it), { vh.sheet = null }, Modifier.weight(1f), kind = ButtonKind.Tonal, size = ButtonSize.Medium)
                MikaButton(action, { vh.sheet = null; go() }, Modifier.weight(1f), size = ButtonSize.Medium)
            }
        }
    }
}

private val BarLabel = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
private val HintText = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
private val HeadText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp)
private val ChipText = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp)
private val TimeText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontFeatureSettings = "tnum")
private val ListenText = TextStyle(fontSize = 18.sp, lineHeight = 27.sp)
private val FootText = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
private val ToastText = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp)
private val SheetBody = TextStyle(fontSize = 14.sp, lineHeight = 23.sp)
