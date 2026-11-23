package com.wangzan101.mika.capsule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.TransformOrigin
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.chat.ui.ChatCopy
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.animationsOff
import kotlinx.coroutines.delay

// 胶囊:卡片、语音面板与胶囊本体的界面
/** 卡片窗口里:展开时是卡片,收起时窗口先显式隐藏、内容随后清空。标题栏的电源按钮点过时换成退出的确认。
 * 非标准档时整张卡换 [Density.zoomed] 的密度渲染:宽、字、间距等比缩放;高度上限仍按真实屏幕高换算(在 Provider 里 toDp)。 */
@Composable
internal fun CapsuleController.CardOverlay() {
    val c = card ?: return
    MikaTheme(reduceMotion = animationsOff(context)) {
        val scale = last?.cardScale ?: CapsuleCardScale.DEFAULT
        CompositionLocalProvider(
            LocalDensity provides LocalDensity.current.zoomed(scale.factor),
            LocalCapsuleCardScale provides scale,
        ) {
            val height = if (cardHeight > 0) with(LocalDensity.current) { cardHeight.toDp() } else Dp.Unspecified
            OverlayMotion(open = !closing, origin = ::cardOrigin, onClosed = ::closed, onOpened = {
                cardOut = true
                syncWindowVisibility()
            }) {
                if (quitConfirm) QuitCard(actions, height)
                else CapsuleCard(
                    c, preview, zoomed, actions, { elapsed(it) }, input = inputUi, thread = thread, height = height, onHandle = ::onHandle,
                    queuedCount = CapsuleCards.conversationOf(c)?.let { id ->
                        followUps.count { it.conversationId == id && !it.claimed && it.source == com.wangzan101.mika.task.QueuedSource.Explicit }
                    } ?: 0,
                    onQueued = { CapsuleCards.conversationOf(c)?.let(::open) },
                )
            }
        }
    }
}

/** 面板窗口里:按着说话时整屏的那一层,贴着一颗此刻的位置;平时什么都没有。 */
@Composable
internal fun CapsuleController.VoicePanelOverlay() {
    if (!talk.active) return
    val a = windowOn?.overlays()?.area()
    val top = placedY + room()
    val bottom = placedY + (window?.height ?: 0) - room()
    MikaTheme(reduceMotion = animationsOff(context)) {
        CapsuleVoicePanel(talk, top, bottom.coerceAtLeast(top), a?.insetTop ?: 0, a?.insetBottom ?: 0)
    }
}

/** 边上那个窗口里:一颗、胶囊或那一条;卡片出来后是空的,卡片往回缩时一颗同时从边上冒出来。胶囊上闪着一句时显示那句。 */
@Composable
internal fun CapsuleController.CapsuleOverlay() {
    val shown = view ?: return
    if (card != null && cardOut && !closing) return
    val n = note
    val v = if (n == null || shown.kind == CapsuleKind.HandedOver) shown
        else shown.copy(kind = n.kind, text = n.text, detail = null, count = 0, needCount = 0, pill = true, widthEpoch = -n.id)
    if (edgeHidden) {
        MikaTheme(reduceMotion = animationsOff(context)) {
            CapsuleEdgeContent(dock.side, alpha) {
                resetTaps()
                wake()
                place()
            }
        }
        return
    }
    val talking = talk.active && !talk.released
    MikaTheme(reduceMotion = animationsOff(context)) {
        OverlayMotion(open = true, origin = { TransformOrigin(if (dock.side == DockSide.Right) 1f else 0f, 0.5f) }, onClosed = {}) {
            CapsuleContent(
                v,
                onTap = ::tapCapsule,
                onSendScreen = ::previewScreen,
                flash = flash,
                onStop = { v.runKey?.let { TaskService.cancel(context, it) } },
                onHandBack = { v.runKey?.let { TaskService.handBack(context, runKey = it) } },
                side = dock.side,
                tapLabel = when {
                    n?.opensMika == true -> tr(R.string.capsule_voice_open)
                    v.kind == CapsuleKind.Idle -> CapsuleCopy.TALK
                    v.kind == CapsuleKind.Notice -> tr(R.string.notice_hint_open)
                    else -> CapsuleCopy.EXPAND
                },
                onSwapSide = if (v.kind == CapsuleKind.HandedOver) null else ::swapSide,
                alpha = alpha,
                holding = talking && talk.byTouch,
                onTalk = if (canTalk()) ::toggleTalk else null,
                talkLabel = tr(if (talking && !talk.byTouch) R.string.capsule_voice_send else R.string.capsule_voice_talk),
            )
        }
    }
}

/** 用时,每秒更新;不显示用时时为 null。 */
@Composable
internal fun CapsuleController.elapsed(startedAt: Long?): String? {
    if (startedAt == null) return null
    val now by produceState(System.currentTimeMillis(), startedAt) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L - value % 1_000L)
        }
    }
    return ChatCopy.clock(now - startedAt)
}

internal fun CapsuleController.appLabel(pkg: String): String = appLabels.getOrPut(pkg) { ConfirmationNotifier.appLabel(context, pkg) }

internal fun CapsuleController.reasonLabel(reason: String): String? = when (reason) {
    "captcha" -> R.string.takeover_reason_captcha
    "login" -> R.string.takeover_reason_login
    "access_blocked" -> R.string.takeover_reason_access_blocked
    "payment" -> R.string.takeover_reason_payment
    // 「需要你决定」接在「需要你：」后面重复了,用模型写的话
    else -> null
}?.let(context::getString)
