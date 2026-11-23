package com.wangzan101.mika.capsule

import android.animation.ValueAnimator
import android.view.Gravity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.graphics.Rect
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.ui.theme.animationsOff
import com.wangzan101.mika.capsule.CapsuleController.Companion.EDGE_MARGIN_DP
import com.wangzan101.mika.capsule.CapsuleController.Companion.TOP_GAP_DP
import com.wangzan101.mika.capsule.CapsuleController.Companion.PILL_DP
import com.wangzan101.mika.capsule.CapsuleController.Companion.BAR_DP
import com.wangzan101.mika.capsule.CapsuleController.Companion.SNAP_MS

// 胶囊:位置计算、拖动与贴边
/** 能摆的范围;[ime] 为 true 时让开输入法([at],默认是此刻的输入法上边)。 */
internal fun CapsuleController.area(ime: Boolean = true, at: Int? = imeTop): DockArea? = windowOn?.overlays()?.area()?.let {
    val room = room()
    DockArea.of(it, margin = dp(EDGE_MARGIN_DP), gap = dp(TOP_GAP_DP), room = room)
        .let { a -> if (ime) a.above(at, dp(TOP_GAP_DP), room) else a }
}

/** 卡片用的范围:让开这一轮输入法最高的样子;拼音候选行出来、收回去时卡片不跟着变高矮、上下挪。 */
internal fun CapsuleController.cardArea(): DockArea? = area(at = cardImeTop)

internal fun CapsuleController.room() = dp(CapsuleShadowRoom.value.toInt())

internal fun CapsuleController.isBar() = view?.kind == CapsuleKind.HandedOver

/**
 * 按记下的位置摆窗口:胶囊贴在记下的那一边,「你来处理」那一条在顶部正中,卡片左右居中、上边按记下的比例摆;
 * 放不下或输入法盖到时往上挪(卡片最高高度同时变矮)。卡片换了高度(换卡片、内容变了)窗口跟着变,再摆一次,上边不动。
 */
internal fun CapsuleController.place() {
    val w = window ?: return
    if (dragging || snap?.isRunning == true) return
    val a = area() ?: return
    placeCard()
    val h = w.height.coerceAtLeast(dp(PILL_DP))
    if (isBar()) {
        placedY = CapsuleDocking.fitY(CapsuleDocking.y(barFraction, a, dp(BAR_DP)), a, h)
        w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, placedY))
        return
    }
    // 卡片展开着时这个窗口是空的,照样停在边上:收起时一颗就在原处出来
    val d = dock
    // 输入法盖到原来的位置:胶囊和卡片都挪到它上面,收起后回原处
    val base = area(ime = false) ?: a
    val natural = CapsuleDocking.fitY(CapsuleDocking.y(d.fraction, base, dp(PILL_DP)), base, h)
    placedY = CapsuleDocking.fitY(natural, a, h)
    val side = if (d.side == DockSide.Left) Gravity.LEFT else Gravity.RIGHT
    val offset = if (edgeHidden) CapsuleEdge.offset(d.side, a, room()) else CapsuleDocking.edgeOffset(d.side, a)
    w.place(OverlayPlacement(Gravity.TOP or side, offset, placedY))
}

/**
 * 摆卡片窗口:左右居中、上边按记下的比例。卡片最高高度按屏幕和输入法定,变了内容跟着重排,窗口变了尺寸再摆一次。
 * 输入法按这一轮最高的样子算([cardArea]):打字时拼音候选行多出来一行、收回去,卡片不跟着变高矮也不上下挪。
 * 刚展开、窗口还空着时按卡片会有的高度先摆好(对话卡片固定高;任务卡片按内容高,先按最矮的算),内容出来时不用再挪。
 */
internal fun CapsuleController.placeCard() {
    val w = cardWindow ?: return
    val c = card ?: return
    val a = cardArea() ?: return
    windowOn?.overlays()?.area()?.let { cardHeight = CardDocking.maxHeight(it.height, a, room()) }
    val base = area(ime = false) ?: a
    val expected = if (c is CardView.Chat) cardHeight + 2 * room() else dp(PILL_DP)
    cardY = CardDocking.y(cardFraction, base, a, cardFull(base), maxOf(w.height, expected))
    cardTop = cardY
    w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, cardY))
}

/** 最高的卡片(对话卡片)在没让输入法的范围 [base] 里的窗口高(含阴影边):卡片上边的比例按它算。 */
internal fun CapsuleController.cardFull(base: DockArea): Int =
    CardDocking.maxHeight(windowOn?.overlays()?.area()?.height ?: 0, base, room()) + 2 * room()

/**
 * 能拖哪里:胶囊和那一条整个能拖;卡片只有按着标题栏能拖(中间上下滑是滚动),放大画面时不能拖。
 * 一颗和胶囊还能长按说话(那一条不能;语音输入设成不用时也不能,长按照旧是点按)。
 */
internal fun CapsuleController.applyDrag() {
    window?.draggable(drag)
    val talkable = canTalk()
    // 说着说着不能说了(例如这时 App 交到你手上、胶囊换成那一条):这一段丢掉,不发——用户没机会松开选落点
    if (!talkable && talk.active) talk.abort()
    window?.holdable(talkHold.takeIf { talkable }, CapsuleTalk.HOLD_MS)
    cardWindow?.draggable(cardDrag, cardHandle.takeIf { !zoomed } ?: Rect())
}

/** 卡片报来标题栏的位置。 */
internal fun CapsuleController.onHandle(r: Rect) {
    if (r == cardHandle) return
    cardHandle = r
    applyDrag()
}

/** 松手后滑到边上;关了动画时直接摆过去。 */
internal fun CapsuleController.snapTo(fromX: Int, y: Int, toX: Int) {
    val w = window ?: return
    placedY = y
    if (animationsOff(context) || fromX == toX) {
        place()
        return
    }
    snap = ValueAnimator.ofInt(fromX, toX).setDuration(SNAP_MS).apply {
        addUpdateListener { w.place(OverlayPlacement(Gravity.TOP or Gravity.LEFT, it.animatedValue as Int, y)) }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                snap = null
                place()
            }
        })
        start()
    }
}

/** 读屏时代替拖动:换到另一边。 */
internal fun CapsuleController.swapSide() {
    resetTaps()
    dock = dock.copy(side = if (dock.side == DockSide.Left) DockSide.Right else DockSide.Left)
    flags.setCapsuleDock(dock)
    place()
}
