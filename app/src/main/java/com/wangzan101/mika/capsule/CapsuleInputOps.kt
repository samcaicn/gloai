package com.wangzan101.mika.capsule

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.chat.ConversationStore
import java.util.UUID
import kotlinx.coroutines.withContext
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.MainScreenCapture
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.core.screen.user.CapturedScreen
import com.wangzan101.mika.core.screen.user.UserScreenMessage
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.chat.ui.FollowUpMode
import com.wangzan101.mika.chat.ui.ScreenCopy
import com.wangzan101.mika.chat.ui.screenThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.wangzan101.mika.capsule.CapsuleController.Companion.TAG
import com.wangzan101.mika.capsule.CapsuleController.Companion.IME_GONE_MS
import com.wangzan101.mika.capsule.CapsuleController.Companion.IME_POLL_MS

// 胶囊:输入框、发送与截屏附图
/** 点了输入框:这时才让浮层能接收键盘,输入法对着它弹出。 */
internal fun CapsuleController.openInput() {
    if (inputUi.open) return
    cardWindow?.setFocusable(true)
    inputUi = inputUi.copy(open = true)
}

/** 收起输入框,立刻把焦点还给下面的 App。 */
internal fun CapsuleController.closeInput() {
    if (!inputUi.open) return
    inputUi = inputUi.copy(open = false)
    cardWindow?.setFocusable(false)
}

/** 返回键:先收起输入框,再关放大的画面,最后收起卡片。 */
internal fun CapsuleController.back() {
    when {
        inputUi.open -> closeInput()
        zoomed -> zoom(false)
        expanded -> collapse()
    }
}

/** 放大或关掉画面;放大时卡片不能拖(没有标题栏)。 */
internal fun CapsuleController.zoom(on: Boolean) {
    zoomed = on
    applyDrag()
}

/**
 * 发出输入框里的话,和在对话页里发的走同一条路(都记进那个对话):在跑、等条件时插话给它,
 * Mika 问你时作为回答,办完后在同一个对话里接着说,空闲时接着最近的对话或开新对话;后两种之后卡片跟着那个对话的任务看回答。
 */
internal fun CapsuleController.send() {
    val app = context.applicationContext as? com.wangzan101.mika.MikaApp
    if (app != null && app.teachReady && app.teach.blocksTasks()) {
        android.widget.Toast.makeText(context, com.wangzan101.mika.i18n.tr(com.wangzan101.mika.R.string.teach_blocked_task), android.widget.Toast.LENGTH_LONG).show()
        return
    }
    val text = inputUi.draft.trim()
    val shot = inputUi.screen?.shot
    val c = card ?: return
    val kind = CapsuleCards.input(c)?.kind ?: return
    if (text.isEmpty() && shot == null) return
    val mode = inputUi.followUpMode
    inputUi = CardInputUi()
    cardWindow?.setFocusable(false)
    if (shot != null) deliver(c, kind, shot, text, mode) else submit(c, kind, text, CapsuleCards.conversationOf(c), mode)
    refresh()
}

/** 按输入框此刻交给谁([kind])发出 [text];开始说时进 [conversationId](为 null 时新开一个)。 */
internal fun CapsuleController.submit(c: CardView, kind: CardInputKind, text: String, conversationId: String?, mode: FollowUpMode = FollowUpMode.Steer) {
    val run = when (c) {
        is CardView.Detail -> c.run.run
        is CardView.Chat -> c.run?.run
        is CardView.Kept -> null
    }
    when (kind) {
        CardInputKind.Steer -> run?.let {
            if (mode == FollowUpMode.Queue) {
                TaskService.enqueueFollowUp(context, it.conversationId, UUID.randomUUID().toString(), text)
            } else {
                TaskService.start(context, text, it.conversationId, messageId = UUID.randomUUID().toString())
            }
        }
        CardInputKind.Answer -> run?.let { TaskService.answer(context, text, it.key) }
        CardInputKind.Continue -> run?.let { startIn(it.conversationId, text) }
        CardInputKind.Start -> startIn(conversationId ?: ConversationStore.shared(context).newConversation(), text)
    }
}

/**
 * 截下的屏幕连同附言 [words] 发出:先存进这句话要去的那个对话(新对话这时就定下编号),
 * 再写成带屏幕的一句话([UserScreenMessage])照常发出;存不下就不发。
 */
internal fun CapsuleController.deliver(c: CardView, kind: CardInputKind, shot: CapturedScreen, words: String, mode: FollowUpMode = FollowUpMode.Steer) {
    val access = (context.applicationContext as MikaApp).appAccess.current()
    if (shot.sourcePackages.isEmpty() || shot.sourcePackages.any { !access.canShare(it) }) {
        inputUi = inputUi.copy(screen = null)
        android.widget.Toast.makeText(context, tr(R.string.appaccess_share_expired), android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val conversationId = CapsuleCards.conversationOf(c) ?: ConversationStore.shared(context).newConversation()
    scope.launch {
        val saved = runCatching { (context.applicationContext as MikaApp).userScreens.save(conversationId, shot) }
            .onFailure { Log.w(TAG, "could not save the screen", it) }.getOrNull() ?: return@launch
        val latest = (context.applicationContext as MikaApp).appAccess.current()
        if (shot.sourcePackages.any { !latest.canShare(it) }) {
            inputUi = inputUi.copy(screen = null)
            android.widget.Toast.makeText(context, tr(R.string.appaccess_share_expired), android.widget.Toast.LENGTH_SHORT).show()
            return@launch
        }
        submit(c, kind, UserScreenMessage.compose(saved.id, words), conversationId, mode)
        refresh()
    }
}

/**
 * 双击或读屏的截屏操作:展开单击会打开的那张卡片,把此刻的屏幕挂到现有输入框的预览上。
 * 这里只截屏和填 [PendingScreen],不保存进对话、不提交;用户检查预览后按现有发送键,才由 [send] 调 [deliver]。
 * 那张卡片没有输入框(要你接管、同意)时没有地方预览,就和单击一样只展开。
 */
internal fun CapsuleController.previewScreen() {
    val v = view ?: return
    if (capturing) return
    scope.launch {
        if (v.kind == CapsuleKind.Idle || v.kind == CapsuleKind.Notice) idle = loadIdle()
        val target = CapsuleCards.of(model.cardRuns(), follow, idle)
        val kind = target?.let(CapsuleCards::input)?.kind
        if (target == null || kind == null) {
            expand()
            return@launch
        }
        CapsuleCards.conversationOf(target)?.let { follow = it }
        open()
        refresh()
        val shot = captureScreen() ?: return@launch
        val thumb = withContext(Dispatchers.Default) { shot.jpeg?.let(::screenThumbnail) }
        if (expanded) inputUi = inputUi.copy(screen = PendingScreen(shot, thumb))
    }
}

/** 卡片里的截屏按钮:截下来挂在输入框上面,补不补一句都行。 */
internal fun CapsuleController.captureForCard() {
    if (capturing) return
    scope.launch {
        val shot = captureScreen() ?: return@launch
        val thumb = withContext(Dispatchers.Default) { shot.jpeg?.let(::screenThumbnail) }
        if (expanded) inputUi = inputUi.copy(screen = PendingScreen(shot, thumb))
    }
}

/**
 * 截下用户此刻的屏幕:输入框开着先收起(等输入法下去),浮层藏起来、真的从屏幕上下去了再截,截完马上出来、一颗上闪一下;
 * 再在后台整理屏上的字。前台是 Mika 自己、截不到时为 null。
 */
internal suspend fun CapsuleController.captureScreen(): CapturedScreen? {
    val service = windowOn ?: return null
    val w = window ?: return null
    val c = cardWindow ?: return null
    capturing = true
    try {
        if (inputUi.open) {
            closeInput()
            withTimeoutOrNull(IME_GONE_MS) { while (imeTop != null) delay(IME_POLL_MS) }
        }
        // 空着的那个窗口(展开时一颗那个、收起时卡片那个)不画东西,不用等它
        suspend fun <T> OverlayWindow.hidden(block: suspend () -> T): T = if (height > 0) hiddenWhile(block) else block()
        val store = (context.applicationContext as MikaApp).appAccess
        val sources = MainScreenCapture.sourcePackages(service) ?: return null
        if (context.packageName in sources || !store.current().available) return null
        for (pkg in sources) {
            if (!store.current().canShare(pkg) && !com.wangzan101.mika.cap.phone.a11y.requestScreenShare(service, store, pkg, appLabel(pkg))) return null
        }
        val capture = w.hidden { c.hidden {
            if (MainScreenCapture.sourcePackages(service) != sources || sources.any { !store.current().canShare(it) }) null
            else MainScreenCapture.shoot(service)
        } } ?: return null
        val (shot, read) = capture
        if (MainScreenCapture.sourcePackages(service) != sources || sources.any { !store.current().canShare(it) }) {
            (shot as? ScreenAccessibility.MainShot.Ok)?.bitmap?.recycle()
            return null
        }
        flash++
        wake()
        val area = service.overlays().area()
        val r = MainScreenCapture.build(service, shot, read, area.insetTop, area.insetBottom) { ConfirmationNotifier.appLabel(context, it) }
        if (sources.any { !store.current().canShare(it) } || MainScreenCapture.sourcePackages(service) != sources) return null
        val captured = (r as? MainScreenCapture.Result.Captured)?.shot ?: return null
        return CapturedScreen(captured.jpeg, captured.screen, sources)
    } finally {
        capturing = false
    }
}

/**
 * 在对话 [conversationId] 里开始说 [text];别的任务在跑时排上,不丢。卡片接着跟这个对话;
 * 空闲卡片也改成接着这个对话(新开的对话标题就是这句话)。
 */
internal fun CapsuleController.startIn(conversationId: String, text: String) {
    TaskService.start(context, text, conversationId)
    follow = conversationId
    idle = idle?.let { CardView.Chat(conversationId, title = if (it.target == conversationId) it.title else ScreenCopy.plain(text)) }
}
