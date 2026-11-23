package com.wangzan101.mika.capsule




import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.core.screen.user.CapturedScreen
import com.wangzan101.mika.chat.ui.FollowUpMode
import androidx.compose.ui.graphics.ImageBitmap
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voice.HoldToTalk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull

internal val CardShape = RoundedCornerShape(22.dp)
internal val ScreenShape = RoundedCornerShape(14.dp)
internal val BubbleShape = RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp)
internal val ScreenWidth = 104.dp
internal val ZoomWidth = 300.dp
internal val Title = TextStyle(fontSize = 14.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
internal val Sub = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
internal val Body = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp)
internal val Strong = Body.copy(fontWeight = FontWeight.SemiBold)
internal val Step = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

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
    /** 空闲卡片上的「语音聊天」:打开 Mika 直进通话页。 */
    fun voiceChat() {}
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

/** 卡片此刻的缩放档位:由 [CapsuleController] 随设置流装进(缩放本身靠换 [LocalDensity],见 [Density.zoomed])。 */
val LocalCapsuleCardScale = staticCompositionLocalOf { CapsuleCardScale.DEFAULT }

/** 卡片缩放:dp 与 sp 等比放大(哪个分量起作用以 CapsuleCardScaleDensityTest 的实测为准)。 */
internal fun Density.zoomed(factor: Float): Density = Density(density * factor, fontScale * factor)

@Composable
internal fun cardWidth() = LocalCapsuleCardScale.current.width(LocalConfiguration.current.screenWidthDp)
