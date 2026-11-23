package com.wangzan101.mika.capsule

import android.util.Log
import android.view.Gravity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.capsule.CapsuleController.Snapshot
import com.wangzan101.mika.capsule.CapsuleController.Touch
import com.wangzan101.mika.capsule.CapsuleController.Companion.TAG

// 胶囊:按状态渲染窗口、显隐同步与复位
internal fun CapsuleController.render(s: Snapshot) {
    last = s
    val service = s.service
    if (windowOn != null && windowOn !== service) {
        talk.abort()
        window = null
        cardWindow = null
        panelWindow = null
        windowOn = null
    }
    val inputs = CapsuleInputs(
        s.runs, s.mode, s.mikaInForeground, screenInteractive = !s.locked,
        serviceAvailable = service != null && service !== failedOn, cardOpen = expanded, kept = s.kept, fullscreen = s.fullscreen,
        notices = s.notices,
    )
    val shown = model.update(inputs, System.currentTimeMillis()) as? CapsuleView.Shown
    schedule(model.nextChangeAt)
    if (shown == null || service == null) {
        hide()
        return
    }
    // App 已交到你手上:顶部那一条代替卡片
    if (shown.kind == CapsuleKind.HandedOver) resetCard()
    view = shown
    card = if (expanded) CapsuleCards.of(model.cardRuns(), follow, idle, s.kept) else null
    if (expanded && card == null) resetCard()
    updateThread(card as? CardView.Chat, s.runs)
    // 换成了没有输入框的内容(例如变成要你同意):输入框收起,焦点还给下面的 App
    if (inputUi.open && card?.let(CapsuleCards::input) == null) closeInput()
    val detail = card as? CardView.Detail
    val keptCard = card as? CardView.Kept
    if (keptCard != null) watchKept(keptCard.page.id)
    else watchPreview(detail?.takeIf { CapsuleCards.showsScreen(it.content) }?.run?.run?.key)
    val w = window ?: openWindows(service)
    if (w == null) {
        view = null
        card = null
        _showing.value = false
        return
    }
    applyDrag()
    place()
    // 换了状态、件数,展开或缩回:马上恢复,重新计时;只换了一句不算(一颗上看不到那句)
    val nowLook = listOf(shown.kind, shown.pill, shown.count, shown.needCount)
    if (nowLook != look) {
        look = nowLook
        resetTaps()
        wake()
    } else fade()
    _showing.value = true
}

/** 加上两个窗口(先一颗的,再卡片的,卡片在上面);有一个加不上就都不要,退回通知。 */
internal fun CapsuleController.openWindows(service: ScreenAccessibility): OverlayWindow? {
    val overlays = service.overlays()
    // 面板先加,在一颗下面:按着说话时压暗的那层不盖住胶囊
    val panel = overlays.open(OverlayPlacement(Gravity.TOP or Gravity.LEFT), touchable = false) { VoicePanelOverlay() }
    val ball = overlays.open(OverlayPlacement(Gravity.TOP or Gravity.RIGHT)) { CapsuleOverlay() }
    val cardW = ball?.let { overlays.open(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL), ::outside) { CardOverlay() } }
    if (ball == null || cardW == null) {
        ball?.close()
        panel?.close()
        failedOn = service
        Log.w(TAG, "capsule overlay unavailable, falling back to notifications")
        return null
    }
    window = ball
    cardWindow = cardW
    panelWindow = panel
    windowOn = service
    syncWindowVisibility()
    // 新加的面板窗口先藏起来:没在说话时它不该在屏幕上留任何东西
    syncPanelWindow()
    for (it in listOf(ball, cardW)) it.onResize(::place)
    ball.onTouch { e ->
        // Replacing the half-circle on ACTION_DOWN would turn the same touch into a full-ball click.
        if (!edgeHidden) wake()
        capsuleTouch = Touch(e.eventTime, e.rawX, e.rawY, e.source)
    }
    cardW.onTouch { wake() }
    cardW.onBack(::back)
    watchIme(service)
    return ball
}

internal fun CapsuleController.refresh() {
    last?.let(::render)
}

/**
 * 对话 [id] 删掉了:它刚结束的任务马上从胶囊和卡片上拿掉,卡片不再接着它(刚在卡片里发了话的;空闲卡片接着的,
 * 展开着时换成开新对话的那张),展开着的卡片按 [CapsuleCards.of] 重挑(什么都没有就收起)。删对话先停掉它的任务、
 * 等它收尾(最多 15 秒)再删记录,所以这时它的任务一般已经结束;等不到收尾的,或者收尾以后、删记录之前又在卡片里接着说的
 * (会在这个对话里开一件新任务),之后存进的那一轮会把对话重新建起来,胶囊照常显示它。
 */
internal fun CapsuleController.forget(id: String) {
    model.forget(id)
    if (follow == id) follow = null
    if (idle?.conversationId == id) idle = if (expanded) CardView.Chat(null) else null
    refresh()
}

internal fun CapsuleController.hide() {
    resetTaps()
    snap?.cancel()
    fadeJob?.cancel()
    look = null
    imeJob?.cancel()
    imeJob = null
    imeTop = null
    cardImeTop = null
    dragging = false
    edgeHidden = false
    talk.abort()
    talkIdle = null
    noteJob?.cancel()
    note = null
    window?.close()
    window = null
    cardWindow?.close()
    cardWindow = null
    panelWindow?.close()
    panelWindow = null
    windowOn = null
    view = null
    resetCard()
    card = null
    _showing.value = false
}

internal fun CapsuleController.resetCard() {
    expanded = false
    closing = false
    cardOut = false
    syncWindowVisibility()
    follow = null
    zoomed = false
    quitConfirm = false
    cardHandle = null
    closeInput()
    inputUi = inputUi.collapsed()
    watchPreview(null)
    updateThread(null, emptyList())
}

/** 两个窗口交替时显式显隐;空内容只负责排版,不承担把系统窗口旧帧撤下屏幕的职责。 */
internal fun CapsuleController.syncWindowVisibility() {
    val visible = capsuleWindowVisibility(expanded, cardOut, closing)
    window?.setVisible(visible.capsule)
    cardWindow?.setVisible(visible.card)
}

/**
 * 说话面板窗口:在说时铺满整屏(压暗背后)、才显出来;收起时先显式设为不可见再缩回没有大小。
 * 和一颗、卡片一样,不能拿空内容和零尺寸当作已经不可见:Compose 内容清空后可能不再出新帧,
 * 系统画布上会留着最后一帧「转文字…」,面板看上去就卡在屏幕上了。
 */
internal fun CapsuleController.syncPanelWindow() {
    val w = panelWindow ?: return
    if (talk.active) {
        w.setFullScreen(true)
        w.setVisible(true)
    } else {
        w.setVisible(false)
        w.setFullScreen(false)
    }
}
