package com.wangzan101.mika.capsule

import kotlinx.coroutines.delay

import androidx.compose.runtime.produceState

import com.wangzan101.mika.ui.KeptPageCopy

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.core.userscreen.CapturedScreen
import com.wangzan101.mika.ui.MarkdownText
import com.wangzan101.mika.ui.FollowUpMode
import com.wangzan101.mika.ui.ScreenCopy
import com.wangzan101.mika.ui.ScreenThumb
import com.wangzan101.mika.ui.UserMessage
import androidx.compose.ui.graphics.ImageBitmap
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaLogo
import com.wangzan101.mika.ui.components.ScrollToBottomButton
import com.wangzan101.mika.ui.components.StepItem
import com.wangzan101.mika.ui.components.StepState
import com.wangzan101.mika.ui.components.glassSource
import com.wangzan101.mika.ui.components.rememberGlassBackdrop
import com.wangzan101.mika.ui.components.scrollListToBottom
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voice.HoldToTalk
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val CardShape = RoundedCornerShape(22.dp)
private val ScreenShape = RoundedCornerShape(14.dp)
private val BubbleShape = RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp)
private val ScreenWidth = 104.dp
private val ZoomWidth = 300.dp
private val Title = TextStyle(fontSize = 14.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
private val Sub = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
private val Body = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp)
private val Strong = Body.copy(fontWeight = FontWeight.SemiBold)
private val Step = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

/** 卡片上的固定说法(按钮、链接、读屏描述)。 */
object CardCopy {
    val STOP: String get() = tr(R.string.chat_stop)
    val TAKE_OVER: String get() = tr(R.string.action_take_over)
    val RESUME: String get() = tr(R.string.chat_continue)
    val APPROVE_ONCE_ONLY: String get() = tr(R.string.card_approve_once)
    val DECLINE: String get() = tr(R.string.action_decline)
    val SEE_PROCESS: String get() = tr(R.string.card_see_process)
    val BACK_TO_MIKA: String get() = tr(R.string.card_back_to_mika)
    val SEND: String get() = tr(R.string.chat_send)
    val NEW_CHAT: String get() = tr(R.string.chat_new)
    val CONTINUE_LAST: String get() = tr(R.string.card_continue_last)
    val COLLAPSE: String get() = tr(R.string.collapse)
    val SCREEN: String get() = tr(R.string.card_screen)
    val SCREEN_LABEL: String get() = tr(R.string.card_screen_label)
    val ZOOMED: String get() = tr(R.string.card_zoomed)
    val ZOOM_HINT: String get() = tr(R.string.card_zoom_hint)
    val HIDE_CAPSULE: String get() = tr(R.string.capsule_hide)

    /** 关掉胶囊后的提示:到哪里重新打开(「设置 › 悬浮胶囊」,名字和设置里的一致)。 */
    fun hiddenHint(): String = tr(R.string.capsule_hidden_hint, tr(R.string.settings_title), tr(R.string.capsule_settings_title))
}

/** 卡片上的按钮;都转成任务服务现有的动作(见 [CapsuleController])。[runKey] 是按下时那件事的编号。 */
interface CardActions {
    fun collapse()
    fun stop(runKey: String)
    fun takeOver(runKey: String)
    fun resume(runKey: String)
    fun answer(runKey: String, text: String)
    fun approve(runKey: String, requestKey: Int)
    fun decline(runKey: String, requestKey: Int)
    fun openMika(conversationId: String)
    fun zoom(on: Boolean)
    /** 点了底部的输入框:这时浮层才改成能接收键盘。 */
    fun openInput()
    fun draft(text: String)
    /** 发出输入框里的话(交给谁见 [CapsuleCards.input])。 */
    fun send()
    /** 空闲卡片:[fresh] 为 true 开新对话,false 接着最近的对话。 */
    fun freshChat(fresh: Boolean)
    /** 对话卡片滚到了顶:往前多加载几轮。 */
    fun loadEarlier()
    /** 输入框左边的截屏按钮:卡片先藏起来,截下用户此刻的屏幕,挂在输入框上面。 */
    fun captureScreen()
    /** 附件条上的 ×:不要这张截图。 */
    fun dropScreen()
    /** 留在后台运行的那一页的卡片上的「关闭网页」。 */
    fun closeKeptPage() {}
    /** 卡片最底下的「关闭悬浮胶囊」:设置里的开关改成关闭,胶囊和卡片当场收掉(任务照常跑、照常发通知)。 */
    fun hideCapsule() {}
    /** 空闲卡片上的「教 Mika」。 */
    fun teach() {}
    /** 胶囊卡片里切换「引导 / 排队」。 */
    fun setFollowUpMode(mode: FollowUpMode) {}
    /** 标题栏的电源按钮:在卡片里换成「完全退出」的确认,不马上退。 */
    fun askQuit() {}
    /** 退出确认里的「取消」:回到刚才那张卡片。 */
    fun cancelQuit() {}
    /** 退出确认里的「退出」:真正完全退出 Mika。 */
    fun quit() {}
}

/** 截下来、挂在输入框上面等着发的一屏:[thumb] 是缩略图(不让截图的页面没有)。 */
data class PendingScreen(val shot: CapturedScreen, val thumb: ImageBitmap?)

/**
 * 卡片底部输入框此刻的样子:[open] 时是能打字的框(浮层能接收键盘),否则是一行提示,点了才打开;
 * [screen] 是截下来挂在上面的那一屏(随下一句发出,只发它也行)。
 */
data class CardInputUi(val open: Boolean = false, val draft: String = "", val screen: PendingScreen? = null,
    /** 任务进行中这句话交给谁;卡片收起或发送后回到引导。 */
    val followUpMode: FollowUpMode = FollowUpMode.Steer) {
    /** 写了字或挂着一屏:可以发。 */
    val canSend: Boolean get() = draft.isNotBlank() || screen != null

    /** 卡片收起、胶囊藏起时:输入框收起,截下的那一屏不留,没发出的字留着,下次展开接着改。 */
    fun collapsed(): CardInputUi = CardInputUi(draft = draft)

    /** 说的话接在没发出的字后面(和对话页输入框同一个接法)。 */
    fun withSpoken(text: String): CardInputUi = copy(draft = HoldToTalk.join(draft, text))
}

/**
 * 点开胶囊的卡片,不带状态,只说一件事(挑哪件见 [CapsuleCards.of])。
 * 任务卡片([CardView.Detail])左边是虚拟屏小画面 [preview](只有那件事有画面时才有),右边按状态放步骤、要你做的事和按钮;
 * 对话卡片([CardView.Chat])是那个对话的来回 [thread](还在读时为 null,中间先空着)。
 * 对话卡片固定 [height] 高(看得见的卡片,不含阴影边;不给时按屏幕高度的 [CardDocking.SHARE]),不随内容变;任务卡片
 * 按内容高、最多 [height],下面不留空——任务卡片中间那段取小画面(按画面的宽高比)和右边文字里高的那个。干活中的步骤最多和小画面
 * 一样高(还没有画面时按主屏的宽高比算,画面来了卡片不跳),步骤再多卡片也不变高:最新一步在底下,旧的往上滚着看;别的内容放不下就在里面滚,
 * 要你点的按钮和输入框固定在下面。高度变了(换卡片、内容变了)时一下到位:浮层窗口跟着内容大小,只变一次,不逐帧改窗口;上边不动(见 [CardDocking])。
 * 展开、收起的动效在外面套([OverlayMotion])。
 * [onHandle] 报标题栏在窗口里的位置(像素):按着它能拖卡片。
 * [zoomed] 时整张换成放大的画面,点一下回来。[elapsed] 把开始时刻换成用时(每秒更新)。
 */
@Composable
fun CapsuleCard(
    card: CardView,
    preview: Bitmap?,
    zoomed: Boolean,
    actions: CardActions,
    elapsed: @Composable (Long?) -> String?,
    modifier: Modifier = Modifier,
    input: CardInputUi = CardInputUi(),
    thread: ChatThread? = null,
    height: Dp = Dp.Unspecified,
    onHandle: (Rect) -> Unit = {},
    /** 这个对话明确排队、还没开始的件数;卡片上显示一行,点它到对话页管理。 */
    queuedCount: Int = 0,
    onQueued: (() -> Unit)? = null,
) {
    val field = CapsuleCards.input(card)
    // 任务还在进行(干活中、Mika 问你、等条件)时,停止和对话页一样放在输入框右边,不另占一个按钮
    val stopKey = CapsuleCards.stopKey(card)
    val h = if (height == Dp.Unspecified) (LocalConfiguration.current.screenHeightDp * CardDocking.SHARE).dp else height
    val report by rememberUpdatedState(onHandle)
    val handle = Modifier.testTag("capsule-header").onGloballyPositioned { c ->
        val b = c.boundsInWindow()
        report(Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()))
    }
    val inputRow: @Composable () -> Unit = {
        field?.let { InputRow(it, input, actions, stop = stopKey?.let { k -> { actions.stop(k) } }) }
    }
    Box(modifier.padding(CapsuleShadowRoom)) {
        when {
            zoomed && preview != null -> Zoomed(preview, actions)
            card is CardView.Detail -> Detail(card, preview, actions, elapsed, h, handle, inputRow, queuedCount, onQueued)
            card is CardView.Chat -> ChatCard(card, thread, actions, elapsed, h, handle, inputRow, queuedCount, onQueued)
            card is CardView.Kept -> KeptCard(card, preview, actions, h, handle)
        }
    }
}

/**
 * 浮层里的东西出现、收起的动效(卡片展开收起,收起后一颗回到边上):浮层窗口一下就是最后的大小和位置,里面的东西只靠图层的
 * 缩放和透明度从 [origin] 那边长出来(按此刻的大小给出方位,例如卡片从胶囊那边),真开始出来时调 [onOpened];
 * [open] 变成 false 时缩回那边、淡掉,做完调 [onClosed]。
 * 只动图层,不改布局,也就不逐帧改窗口。关了动画时直接到位,收起时马上调 [onClosed]。
 * 展开、收起都等不了没完没了的帧:浮层收不到下一帧时超时就位(见 [runOverlayOpen]、[runOverlayClose]),不把卡片留在半开。
 */
@Composable
fun OverlayMotion(
    open: Boolean, origin: (Size) -> TransformOrigin, onClosed: () -> Unit, onOpened: () -> Unit = {}, content: @Composable () -> Unit,
) {
    val still = MikaTheme.reduceMotion
    val shown = remember { Animatable(if (still) 1f else 0f) }
    val closed by rememberUpdatedState(onClosed)
    val opened by rememberUpdatedState(onOpened)
    val from by rememberUpdatedState(origin)
    LaunchedEffect(open, still) {
        if (open) {
            if (still) {
                shown.snapTo(1f)
                opened()
            } else {
                var openedCalled = false
                var finished = false
                runOverlayOpen(still) {
                    // 刚出来那一帧要排版、窗口换大小,最慢:等它画完再起步,动画的头几帧不被它吃掉
                    if (shown.value == 0f) repeat(SETTLE_FRAMES) { withFrameNanos { } }
                    openedCalled = true
                    opened()
                    shown.animateTo(1f, tween(CARD_IN_MS, easing = MikaMotion.Decelerate))
                    finished = true
                }
                // 帧停了(浮层收不到下一帧)时上面走不完:值归位、回调补上,卡片不留在半开的缩放上,窗口状态也不卡在 opening(#323)
                if (!finished) shown.snapTo(1f)
                if (!openedCalled) opened()
            }
        } else {
            runOverlayClose(still) {
                shown.animateTo(0f, tween(CARD_OUT_MS, easing = FastOutLinearInEasing))
                // 让完全看不见的那一帧先画出来,再换内容、挪窗口,不留一帧半透明的卡片
                withFrameNanos { }
            }
            // 动画帧停了也不能把中间帧永远留在屏上;收尾回调负责换内容、收回窗口状态。
            if (!still) shown.snapTo(0f)
            closed()
        }
    }
    Box(
        Modifier.graphicsLayer {
            val v = shown.value
            val scale = CARD_FROM_SCALE + (1f - CARD_FROM_SCALE) * v
            scaleX = scale
            scaleY = scale
            // 透明度走得比缩放快:一出来就看得清,收起时最后才淡完
            alpha = (v * 2f).coerceAtMost(1f)
            transformOrigin = from(size)
        },
    ) { content() }
}

/**
 * 等收起动画和最后一帧,但浮层拿不到后续帧时也必须收尾。等待时长跟随系统的动画速度;
 * 极大的慢动作倍率最多按十倍算,避免窗口状态无限期停在 closing。
 */
internal suspend fun runOverlayClose(reduceMotion: Boolean, animate: suspend () -> Unit) {
    if (reduceMotion) return
    val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor
        ?.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(10f) ?: 1f
    withTimeoutOrNull((CARD_OUT_MS * scale).toLong() + CLOSE_GRACE_MS) { animate() }
}

/**
 * 等展开动画走完,但浮层拿不到后续帧时也必须落位:与收起([runOverlayClose])同一形状,帧停超时后由调用处
 * 直接把值归位并补上展开回调。等待时长跟随系统的动画速度;极大的慢动作倍率最多按十倍算,避免卡在中间缩放的时间无限长。
 */
internal suspend fun runOverlayOpen(reduceMotion: Boolean, animate: suspend () -> Unit) {
    if (reduceMotion) return
    val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor
        ?.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(10f) ?: 1f
    withTimeoutOrNull((CARD_IN_MS * scale).toLong() + OPEN_GRACE_MS) { animate() }
}

private const val CARD_IN_MS = 240
private const val CARD_OUT_MS = 180
private const val CLOSE_GRACE_MS = 500L
private const val OPEN_GRACE_MS = 500L
private const val CARD_FROM_SCALE = 0.6f
private const val SETTLE_FRAMES = 2

/**
 * 底部输入框:平时是一行提示(浮层不抢键盘),点了打开成能打字的框并弹出输入法;回车或右边的按钮发出。
 * 有 [stop] 时右边一直有黑色的停止键(同对话页输入栏),写了字再多一个发送。
 */
@Composable
private fun InputRow(field: CardInput, ui: CardInputUi, actions: CardActions, stop: (() -> Unit)? = null) {
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
private fun QueuedLine(count: Int, onClick: (() -> Unit)?) {
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

private val KeySize = 34.dp

private const val TITLE_MAX = 12

/** 卡片此刻的缩放档位:由 [CapsuleController] 随设置流装进(缩放本身靠换 [LocalDensity],见 [Density.zoomed])。 */
val LocalCapsuleCardScale = staticCompositionLocalOf { CapsuleCardScale.DEFAULT }

/** 卡片缩放:dp 与 sp 等比放大(哪个分量起作用以 CapsuleCardScaleDensityTest 的实测为准)。 */
internal fun Density.zoomed(factor: Float): Density = Density(density * factor, fontScale * factor)

@Composable
private fun cardWidth() = LocalCapsuleCardScale.current.width(LocalConfiguration.current.screenWidthDp)

/** 卡片的底:[fixed] 时就是 [height] 高(对话卡片),否则按内容高、最多 [height](任务卡片)。 */
@Composable
private fun CardFrame(height: Dp, fixed: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = MikaTheme.colors
    Column(
        Modifier.testTag("capsule-card").width(cardWidth()).then(if (fixed) Modifier.height(height) else Modifier.heightIn(max = height))
            .glass(c, CardShape, need = false).padding(12.dp),
        content = content,
    )
}

/** 标题栏(按着它能拖卡片,见 [CapsuleCard] 的 onHandle):左边 Mika 图标,中间标题和状态,右边收起。 */
@Composable
private fun Header(title: String, sub: String, actions: CardActions, handle: Modifier) {
    val c = MikaTheme.colors
    Row(handle.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MikaLogo(28.dp, corner = 14.dp)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(title, style = Title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = Sub, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(MikaIcons.Power, tr(R.string.quit_title), actions::askQuit)
        IconButton(MikaIcons.Close, CardCopy.COLLAPSE, actions::collapse)
    }
}

/** 空闲卡片的标题栏:「接着「…」聊 · 新对话」或「新对话 · 接着上次聊」;没有任何对话时只有「新对话」。 */
@Composable
private fun IdleHeader(card: CardView.Chat, actions: CardActions, handle: Modifier) {
    val c = MikaTheme.colors
    val continuing = !card.fresh && card.conversationId != null
    Row(handle.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MikaLogo(28.dp, corner = 14.dp)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (continuing) tr(R.string.card_continuing, card.title.orEmpty().let(ScreenCopy::plain).let { if (it.length > TITLE_MAX) it.take(TITLE_MAX) + "…" else it }) else CardCopy.NEW_CHAT,
                Modifier.weight(1f, fill = false), style = Title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (card.conversationId != null) {
                Text(" · ", style = Sub, color = c.ink3)
                TextLink(if (continuing) CardCopy.NEW_CHAT else CardCopy.CONTINUE_LAST) { actions.freshChat(continuing) }
            }
        }
        IconButton(MikaIcons.Power, tr(R.string.quit_title), actions::askQuit)
        IconButton(MikaIcons.Close, CardCopy.COLLAPSE, actions::collapse)
    }
}

@Composable
private fun IconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(MikaShapes.Pill).clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MikaTheme.colors.ink3)
    }
}

/**
 * 「完全退出」的确认:标题栏的电源按钮点过后替掉卡片内容。说清楚会发生什么:后台任务停下、胶囊和通知收起,
 * 下次打开 Mika 就回来。「取消」回到刚才那张卡片,「退出」交给 [CardActions.quit]。
 * 卡片按内容高,最矮和任务卡片一样,最高不超过 [height]。
 */
@Composable
internal fun QuitCard(actions: CardActions, height: Dp = Dp.Unspecified) {
    val c = MikaTheme.colors
    val quitLabel = tr(R.string.quit_title)
    CardFrame(height, fixed = false) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MikaLogo(28.dp, corner = 14.dp)
            Text(tr(R.string.quit_title), Modifier.weight(1f), style = Title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(MikaIcons.Close, CardCopy.COLLAPSE, actions::collapse)
        }
        Text(tr(R.string.quit_confirm_body), Modifier.padding(top = 10.dp), style = Body, color = c.ink2)
        Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f).height(40.dp).testTag("capsule-quit-cancel").clip(MikaShapes.Pill).background(c.fill)
                    .clickable(role = Role.Button, onClickLabel = tr(R.string.cancel)) { actions.cancelQuit() },
                contentAlignment = Alignment.Center,
            ) { Text(tr(R.string.cancel), style = Strong, color = c.ink) }
            // 和「关闭网页」一样:淡红底、红字,和别的操作分开
            Box(
                Modifier.weight(1f).height(40.dp).testTag("capsule-quit").clip(MikaShapes.Pill).background(c.badSoft)
                    .clickable(role = Role.Button, onClickLabel = quitLabel) { actions.quit() },
                contentAlignment = Alignment.Center,
            ) { Text(tr(R.string.quit_confirm_action), style = Strong, color = c.bad) }
        }
    }
}

/** 任务卡片:此刻在操作 App 或网页的那件事;按内容高,中间那段取左边小画面和右边文字里高的那个(步骤最多和小画面一样高)。 */
@Composable
private fun Detail(
    card: CardView.Detail, preview: Bitmap?, actions: CardActions, elapsed: @Composable (Long?) -> String?,
    height: Dp, handle: Modifier, input: @Composable () -> Unit, queuedCount: Int = 0, onQueued: (() -> Unit)? = null,
) {
    val c = MikaTheme.colors
    val r = card.run
    val sub = listOfNotNull(CapsuleCards.status(r.kind), elapsed(r.startedAt)).joinToString(" · ")
    CardFrame(height, fixed = false) {
        Header(ScreenCopy.plain(r.run.goal).ifBlank { "Mika" }, sub, actions, handle)
        val screen = preview?.takeIf { CapsuleCards.showsScreen(card.content) }
        // 步骤那段的高度上限 = 小画面的高;还没有画面时按主屏的宽高比算同样的高,画面来了不跳
        val config = LocalConfiguration.current
        val stepsMax = ScreenWidth / (screen?.let(::ratio) ?: ratio(config.screenWidthDp, config.screenHeightDp))
        Row(Modifier.padding(top = 10.dp).weight(1f, fill = false).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (screen != null) MiniScreen(screen) { actions.zoom(true) }
            Column(Modifier.weight(1f)) {
                val content = card.content
                // 干活中的步骤单独排(自己滚、最多和小画面一样高),没有要你点的按钮
                if (content is CardContent.Working) Steps(content.steps, stepsMax) else {
                    // 文字放不下就在这一段里滚;要你点的按钮放在下面,不跟着滚走
                    Column(
                        Modifier.testTag("capsule-scroll").weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when (content) {
                            // 干活中在上面单独排
                            is CardContent.Working -> Unit
                            is CardContent.Takeover -> {
                                Text(content.need, style = Strong, color = c.warn)
                                if (content.body.isNotEmpty()) Text(content.body, style = Body, color = c.ink2)
                                content.queueLine?.let { Text(it, style = Sub, color = c.ink3) }
                            }
                            is CardContent.Confirm -> {
                                Text(content.title, style = Strong, color = c.warn)
                                Text(content.body, style = Body, color = c.ink2)
                                Text(ConfirmationCopy.NOTE, style = Sub, color = c.ink3)
                            }
                            is CardContent.Question -> Text(content.prompt, style = Body, color = c.ink)
                            is CardContent.Held -> Text(content.why, style = Body, color = c.ink2)
                            // 结束了的是对话卡片,不在这里
                            is CardContent.Result -> Unit
                        }
                    }
                    Pinned(content, r.run.key, actions)
                }
            }
        }
        QueuedLine(queuedCount, onQueued)
        input()
        FooterLinks(actions, CardCopy.SEE_PROCESS to { actions.openMika(r.run.conversationId) })
    }
}

/**
 * 留在后台运行的那一页的卡片:标题「后台网页 · 还剩 1 小时 52 分」,实时画面(它那块显示的最新一帧,点一下放大),
 * 站点和来自哪个对话,下面「打开对话」「关闭网页」。按内容高。剩下的时间每分钟走一次。
 */
@Composable
private fun KeptCard(card: CardView.Kept, preview: Bitmap?, actions: CardActions, height: Dp, handle: Modifier) {
    val c = MikaTheme.colors
    val p = card.page
    val now by produceState(System.currentTimeMillis(), p.id) {
        while (true) {
            delay(KEPT_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    CardFrame(height, fixed = false) {
        Header(tr(R.string.kept_card_title), tr(R.string.kept_card_left, KeptPageCopy.duration(p.closesAt - now)), actions, handle)
        Column(Modifier.padding(top = 10.dp).weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (preview != null) {
                val image = remember(preview) { preview.asImageBitmap() }
                Box(
                    Modifier.testTag("kept-live").fillMaxWidth().aspectRatio(ratio(preview)).clip(ScreenShape)
                        .border(1.dp, c.glassEdge, ScreenShape).background(Color.Black)
                        .clickable(role = Role.Image, onClickLabel = CardCopy.SCREEN) { actions.zoom(true) }
                        .semantics { contentDescription = tr(R.string.kept_card_live) },
                ) {
                    Image(image, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                }
            }
            Text(p.host, style = Strong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(tr(R.string.kept_card_from, ScreenCopy.plain(p.goal).let { if (it.length > TITLE_MAX) it.take(TITLE_MAX) + "…" else it }), tr(R.string.kept_card_live))
                    .joinToString(" · "),
                style = Sub, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).testTag("kept-open")) { Action(tr(R.string.kept_open_chat), ButtonKind.Tonal) { actions.openMika(p.conversationId) } }
            // 关闭是红字的淡红按钮,和别的按钮分开
            Box(
                Modifier.weight(1f).height(40.dp).testTag("kept-close").clip(MikaShapes.Pill).background(c.badSoft)
                    .clickable(role = Role.Button, onClick = actions::closeKeptPage),
                contentAlignment = Alignment.Center,
            ) { Text(tr(R.string.kept_close), style = Strong, color = c.bad) }
        }
    }
}

private const val KEPT_TICK_MS = 30_000L

/** 要你点的按钮(接管、同意、选项、继续),固定在卡片中间那段的下面,不跟着滚。 */
@Composable
internal fun Pinned(content: CardContent?, key: String, actions: CardActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (content) {
            is CardContent.Takeover -> {
                Spacer(Modifier.height(0.dp))
                if (content.canTakeOver) Action(CardCopy.TAKE_OVER, ButtonKind.Primary) { actions.takeOver(key) }
                Action(CardCopy.STOP, ButtonKind.Tonal) { actions.stop(key) }
            }
            is CardContent.Confirm -> {
                Spacer(Modifier.height(0.dp))
                Action(CardCopy.APPROVE_ONCE_ONLY, ButtonKind.Primary) { actions.approve(key, content.key) }
                Action(CardCopy.DECLINE, ButtonKind.Tonal) { actions.decline(key, content.key) }
            }
            is CardContent.Question -> if (content.options.isNotEmpty()) {
                Spacer(Modifier.height(0.dp))
                content.options.forEach { o -> Action(o, ButtonKind.Tonal) { actions.answer(key, o) } }
            }
            is CardContent.Held -> if (content.resumable) {
                Spacer(Modifier.height(0.dp))
                Action(CardCopy.RESUME, ButtonKind.Primary) { actions.resume(key) }
            }
            else -> Unit
        }
    }
}

/**
 * 对话卡片:那个对话的来回在中间滚;要你回答、同意时按钮固定在来回下面,再下面是输入框和「回到 Mika」。
 * 不带任务时是空闲卡片(标题栏能切新对话)。空闲时「教 Mika」收在输入栏上方的箭头里,默认不展开。
 */
@Composable
private fun ChatCard(
    card: CardView.Chat, thread: ChatThread?, actions: CardActions, elapsed: @Composable (Long?) -> String?,
    height: Dp, handle: Modifier, input: @Composable () -> Unit, queuedCount: Int = 0, onQueued: (() -> Unit)? = null,
) {
    val r = card.run
    CardFrame(height) {
        if (r == null) IdleHeader(card, actions, handle)
        else Header(
            ScreenCopy.plain(thread?.title ?: r.run.goal).ifBlank { "Mika" },
            listOfNotNull(CapsuleCards.status(r.kind), elapsed(r.startedAt)).joinToString(" · "),
            actions, handle,
        )
        Box(Modifier.padding(top = 8.dp).weight(1f).fillMaxWidth()) {
            // 换了对话(空闲卡片切到新对话)时从那个对话的最新一问重新读起
            if (thread != null) key(card.target) { ThreadList(thread, card.conversationId, actions) }
        }
        if (r != null) Pinned(card.content, r.run.key, actions)
        QueuedLine(queuedCount, onQueued)
        if (r == null) TeachShelf(actions::teach)
        input()
        FooterLinks(actions, card.target?.let { id -> CardCopy.BACK_TO_MIKA to { actions.openMika(id) } })
    }
}

/**
 * 空闲卡片输入栏正上方的「教 Mika」:默认只留一个箭头,点开才出现入口,再点收起。
 * 收起时箭头朝上,展开时朝下。学习中的浮层不走这里。
 */
@Composable
private fun TeachShelf(onTeach: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = tr(R.string.teach_menu)
    val expandLabel = tr(R.string.teach_shelf_expand)
    val collapseLabel = tr(R.string.teach_shelf_collapse)
    val action = if (open) collapseLabel else expandLabel
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (open) TextLink(name, onClick = onTeach)
        Box(
            Modifier.testTag("capsule-teach-shelf").widthIn(min = 48.dp).height(28.dp).clip(MikaShapes.Pill)
                .clickable(role = Role.Button, onClickLabel = action) { open = !open }
                .semantics {
                    contentDescription = name
                    stateDescription = action
                    if (open) collapse(collapseLabel) { open = false; true } else expand(expandLabel) { open = true; true }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                MikaIcons.Chevron, null,
                Modifier.size(16.dp).graphicsLayer { rotationZ = if (open) 90f else -90f },
                tint = MikaTheme.colors.ink3,
            )
        }
    }
}

/**
 * 卡片最底下一行:[primary](「查看过程」「回到 Mika」;空闲卡片还没有对话时没有)和灰色的「关闭悬浮胶囊」。
 * 右上角的 × 只是收起;想让胶囊不再出现的人在这里关,不用去找设置。
 */
@Composable
private fun FooterLinks(actions: CardActions, primary: Pair<String, () -> Unit>?) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        primary?.let { (text, onClick) ->
            TextLink(text, onClick = onClick)
            Text("·", style = Sub, color = MikaTheme.colors.ink3)
        }
        TextLink(CardCopy.HIDE_CAPSULE, MikaTheme.colors.ink3, actions::hideCapsule)
    }
}

/**
 * 对话的来回(旧的在上):打开时最新那句你说的话停在顶上;滚到顶往前多加载几轮,列表按每条的 key 停在原处;
 * 往下长的字只在你本来就在底部时跟着往下(同对话页);来了新的一问时滚到它。[footer] 排在最后一条后面。
 */
@Composable
internal fun ThreadList(thread: ChatThread, conversationId: String?, actions: CardActions, footer: (@Composable () -> Unit)? = null) {
    val c = MikaTheme.colors
    val backdrop = rememberGlassBackdrop()
    val density = LocalDensity.current
    val reduceMotion = MikaTheme.reduceMotion
    val scope = rememberCoroutineScope()
    val state = rememberLazyListState(initialFirstVisibleItemIndex = thread.anchor)
    var following by remember { mutableStateOf(false) }
    var settled by remember { mutableStateOf(false) }
    var scrollingToBottom by remember { mutableStateOf(false) }
    var scrollToBottomJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
        following = !state.canScrollForward
        settled = true
        var wasScrolling = false
        snapshotFlow { state.isScrollInProgress to !state.canScrollForward }.collect { (scrolling, atEnd) ->
            if (!scrollingToBottom && (scrolling || wasScrolling)) following = atEnd
            wasScrolling = scrolling
        }
    }
    val canScrollForward by remember { derivedStateOf { state.canScrollForward } }
    val showScrollToBottom = settled && thread.items.isNotEmpty() && !following && canScrollForward
    val anchorKey = thread.items.getOrNull(thread.anchor)?.key
    var lastAnchor by remember { mutableStateOf(anchorKey) }
    LaunchedEffect(anchorKey) {
        if (anchorKey == lastAnchor) return@LaunchedEffect
        lastAnchor = anchorKey
        scrollToBottomJob?.cancel()
        state.scrollToItem(thread.anchor)
        following = !state.canScrollForward
    }
    // 回答按 Markdown 排版(流式时在后台解析),排好了高度才变:按条数和最后一条排出来的高度跟,不按字数
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.layoutInfo
            info.totalItemsCount to info.visibleItemsInfo.lastOrNull()?.takeIf { it.index == info.totalItemsCount - 1 }?.size
        }.collect { (n, _) ->
            if (following && !state.isScrollInProgress && n > 0) state.scrollToItem(n - 1, END_OFFSET)
        }
    }
    if (thread.hasEarlier) LaunchedEffect(thread.items.size) {
        snapshotFlow { state.firstVisibleItemIndex == 0 }.first { it }
        actions.loadEarlier()
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.testTag("capsule-thread").fillMaxSize().glassSource(backdrop), state = state) {
            items(thread.items, key = { it.key }) { item ->
                when (item) {
                    // 带着屏幕的那句:缩略图和「你此刻的屏幕 · App · 时刻」,附言另起一个气泡
                    is ThreadItem.User -> BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), contentAlignment = Alignment.CenterEnd) {
                        val maxWidth = maxWidth
                        Column(horizontalAlignment = Alignment.End) {
                            UserMessage(conversationId, item.text, ThreadThumbWidth) { text ->
                                Text(
                                    text,
                                    Modifier.widthIn(max = maxWidth * 0.8f).background(c.accent, BubbleShape).padding(horizontal = 11.dp, vertical = 7.dp),
                                    style = Body, color = c.onAccent,
                                )
                            }
                            // 插的话的去向(已收到、已加入、下一件事)
                            item.note?.let { Text(it, Modifier.padding(top = 2.dp, end = 4.dp), style = Sub, color = c.ink3) }
                        }
                    }
                    is ThreadItem.Note -> Text(
                        item.text, Modifier.padding(bottom = 4.dp), style = Sub,
                        color = if (item.kind.needsYou) c.warn else c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    // 表格、代码块在自己那块里左右滑,不撑宽卡片;链接另开任务打开,浮层里也能点
                    is ThreadItem.Answer -> if (!item.cut) {
                        MarkdownText(item.text, Body.copy(color = c.ink), Modifier.fillMaxWidth().padding(bottom = 8.dp), streaming = item.streaming)
                    } else Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        // 被插话打断的那段:淡色,下面一行小字(和对话页同一句话)
                        MarkdownText(item.text, Body.copy(color = c.ink2), Modifier.fillMaxWidth(), streaming = false)
                        Text(tr(R.string.steer_interrupted_reply), Modifier.padding(top = 2.dp), style = Sub, color = c.ink3)
                    }
                }
            }
            // 接在来回后面的一段(浏览器页面板里此刻的步骤):紧跟着最后一条,跟到底部时一起往下
            footer?.let { f -> item(FOOTER_KEY) { f() } }
        }
        ScrollToBottomButton(
            visible = showScrollToBottom,
            onClick = {
                scrollToBottomJob?.cancel()
                following = true
                scrollToBottomJob = scope.launch {
                    scrollingToBottom = true
                    try {
                        scrollListToBottom(state, density, reduceMotion)
                        following = !state.canScrollForward
                    } finally {
                        scrollingToBottom = false
                    }
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 6.dp),
            backdrop = backdrop,
            reduceMotion = reduceMotion,
            buttonSize = KeySize,
            iconSize = 18.dp,
            tag = "capsule-scroll-to-bottom",
        )
    }
}

private const val FOOTER_KEY = "thread-footer"

/** 卡片来回里截图缩略图的宽。 */
private val ThreadThumbWidth = 78.dp

/** 跟到底部时滚到最后一条再往下这么多像素,由列表夹到真正的底。 */
private const val END_OFFSET = 1_000_000

/**
 * 干活中的步骤(旧的在上):按内容高、最多 [max] 高。放不下时最新一步留在底下,旧的在上面截掉、顶上淡出,往上滚看更早的;
 * 来了新的一步时,本来就在底部才跟着往下,往上翻着看时不拽回去(同 [ThreadList])。
 * [tags] 和 [steps] 一一对应:那一步在哪(「网页」「App」),写成步骤前面的小标签;没有为 null。
 */
@Composable
internal fun Steps(steps: List<String>, max: Dp, tags: List<String?> = emptyList()) {
    val c = MikaTheme.colors
    // 倒着排:第 0 项是最新一步,贴在底下
    val state = rememberLazyListState()
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        var wasScrolling = false
        snapshotFlow { state.isScrollInProgress to !state.canScrollBackward }.collect { (scrolling, atEnd) ->
            if (scrolling || wasScrolling) following = atEnd
            wasScrolling = scrolling
        }
    }
    LaunchedEffect(steps.size) {
        if (following && !state.isScrollInProgress) state.scrollToItem(0)
    }
    val fade = with(LocalDensity.current) { FadeEdge.toPx() }
    LazyColumn(
        Modifier.testTag("capsule-steps").fillMaxWidth().heightIn(max = max)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                // 上面还有更早的步骤:顶上淡出
                if (state.canScrollForward) drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, endY = fade), blendMode = BlendMode.DstIn)
            },
        state = state,
        reverseLayout = true,
    ) {
        // 按从旧到新的序号作 key:往上翻着看时来了新的一步,看着的那几步留在原处
        items(steps.size, key = { steps.lastIndex - it }) { j ->
            val i = steps.lastIndex - j
            val current = j == 0
            StepItem(if (current) StepState.Current else StepState.Done, first = i == 0, last = current, dotY = 11.dp) {
                Row(verticalAlignment = Alignment.Top) {
                    tags.getOrNull(i)?.let { t ->
                        Text(
                            t, Modifier.padding(top = 3.dp, end = 5.dp).background(c.accentSoft, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp),
                            style = TextStyle(fontSize = 10.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium), color = c.accent, maxLines = 1,
                        )
                    }
                    Text(
                        steps[i], Modifier.padding(vertical = 2.dp), style = if (current) Step.copy(fontWeight = FontWeight.Medium) else Step,
                        color = if (current) c.ink else c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val FadeEdge = 28.dp

@Composable
private fun Action(text: String, kind: ButtonKind, onClick: () -> Unit) {
    MikaButton(text, onClick, Modifier.fillMaxWidth(), kind = kind, size = ButtonSize.Medium)
}

@Composable
private fun TextLink(text: String, color: Color = MikaTheme.colors.accent, onClick: () -> Unit) {
    Text(
        text, Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
        style = Sub.copy(fontSize = 13.sp), color = color,
    )
}

/** 虚拟屏小画面,点一下放大。 */
@Composable
private fun MiniScreen(frame: Bitmap, onZoom: () -> Unit) {
    val c = MikaTheme.colors
    val image = remember(frame) { frame.asImageBitmap() }
    Box(
        Modifier.testTag("capsule-screen").width(ScreenWidth).aspectRatio(ratio(frame)).clip(ScreenShape)
            .border(1.dp, c.glassEdge, ScreenShape).background(c.surface)
            .clickable(role = Role.Image, onClickLabel = CardCopy.SCREEN, onClick = onZoom)
            .semantics { contentDescription = CardCopy.SCREEN },
    ) {
        Image(image, null, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
        Text(
            CardCopy.SCREEN_LABEL, Modifier.align(Alignment.BottomStart).padding(6.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            style = TextStyle(fontSize = 10.sp, lineHeight = 14.sp), color = Color.White,
        )
    }
}

/** 放大的画面:点一下回到卡片。 */
@Composable
private fun Zoomed(frame: Bitmap, actions: CardActions) {
    val image = remember(frame) { frame.asImageBitmap() }
    val width = minOf(ZoomWidth, (LocalConfiguration.current.screenWidthDp * 0.72f).dp)
    Box(
        Modifier.testTag("capsule-zoom").width(width).aspectRatio(ratio(frame)).glass(MikaTheme.colors, CardShape, need = false)
            .clickable(role = Role.Image, onClickLabel = CardCopy.ZOOMED) { actions.zoom(false) }
            .semantics { contentDescription = CardCopy.ZOOMED },
    ) {
        Image(image, null, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
        Text(
            CardCopy.ZOOM_HINT, Modifier.align(Alignment.BottomCenter).padding(10.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            style = Sub, color = Color.White,
        )
    }
}

/** 画面的宽高比,夹在合理范围里(防止异常尺寸撑坏卡片)。 */
private fun ratio(b: Bitmap): Float = ratio(b.width, b.height)

private fun ratio(width: Int, height: Int): Float = if (height <= 0) 0.45f else (width.toFloat() / height).coerceIn(0.3f, 1.8f)
