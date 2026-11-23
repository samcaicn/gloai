package com.wangzan101.mika.capsule

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.wangzan101.mika.chat.ConversationStore
import kotlinx.coroutines.withContext
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.task.TaskService.Companion.isActive
import com.wangzan101.mika.chat.ui.ScreenCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import com.wangzan101.mika.capsule.CapsuleController.Companion.IME_SHRINK_JUMP_DP

// 胶囊:空闲对话的加载、翻历史与定时淡出
/** 点开空闲图标:先读用户此刻在哪个对话([loadIdle]),再展开(来回由 [updateThread] 读)。 */
internal fun CapsuleController.openIdle() {
    scope.launch {
        idle = loadIdle()
        open()
        refresh()
    }
}

/**
 * 空闲卡片接着的那段对话:用户在 App 里所在的那个,没有记录时接着最近那个(见 [CapsuleIdle]);一个都没有时开新对话。
 * 不按对话文件的时间猜:新开的对话还没有文件,猜到的是上一个。
 */
internal suspend fun CapsuleController.loadIdle(): CardView.Chat = withContext(Dispatchers.IO) {
    runCatching {
        val store = ConversationStore.shared(context)
        val recent = store.list().firstOrNull()?.let { CardView.Chat(it.id, title = it.title?.let(ScreenCopy::plain)) }
        CapsuleIdle.chat(store.currentChat(), recent) { id ->
            // 和侧栏一样:用户起的名字或第一句话
            (store.title(id) ?: store.turns(id).firstOrNull()?.userText)?.let(ScreenCopy::plain)
        }
    }.getOrElse { CardView.Chat(null) }
}

/**
 * 对话卡片要的来回:换了对话从头读(先读最近 [CapsuleThreads.PAGE] 轮);这个对话里的任务出现、结束、拿掉时重读记下的轮次
 * (任务结束后那一轮才记进对话);其余时候只按此刻的任务重排(在回答的字跟着长)。不是对话卡片时清掉。
 */
internal fun CapsuleController.updateThread(chat: CardView.Chat?, runs: List<CapsuleRun>) {
    val id = chat?.target
    if (id == null) {
        threadJob?.cancel()
        threadOf = null
        past = null
        threadMark = null
        shownTurns = CapsuleThreads.PAGE
        // 空闲卡片的新对话:来回是空的
        thread = if (chat != null) ChatThread(emptyList()) else null
        return
    }
    val mark = runs.filter { it.conversationId == id }.map { it.key to it.state.isActive() }
    if (id != threadOf) {
        threadOf = id
        past = null
        shownTurns = CapsuleThreads.PAGE
        thread = null
    }
    if (mark != threadMark) {
        threadMark = mark
        loadPast(id)
    }
    rebuildThread()
}

/** 按记下的轮次和卡片里此刻的任务排出来回;记下的还没读到时先空着。 */
internal fun CapsuleController.rebuildThread() {
    val id = threadOf ?: return
    val p = past ?: return
    val live = CapsuleThreads.liveTail(model.cardRuns(), id, p.mapNotNull { it.turn.runId }.toSet())
    val built = CapsuleThreads.build(p, shownTurns, live, tasks.summarizing.value) { CapsuleThreads.places(it.run.exchange?.activities.orEmpty(), ::appLabel) }
    thread = pastTitle?.let { built.copy(title = it) } ?: built
}

/** 后台读对话 [id] 记下的轮次,连同要排出来的那几轮操作过的 App 和网站(从运行记录里读,读过的记着),读到后重排。 */
internal fun CapsuleController.loadPast(id: String) {
    threadJob?.cancel()
    val known = HashMap(placesOf)
    val n = shownTurns
    threadJob = scope.launch {
        val (turns, found) = withContext(Dispatchers.IO) {
            val turns = runCatching { ConversationStore.shared(context).turns(id) }.getOrDefault(emptyList())
            turns to turns.takeLast(n).mapNotNull { it.runId }.filter { it !in known }.associateWith(::placesIn)
        }
        val title = withContext(Dispatchers.IO) { ConversationStore.shared(context).title(id) }
        placesOf += found
        if (threadOf != id) return@launch
        pastTitle = title
        past = turns.map { PastTurn(it, it.runId?.let(placesOf::get).orEmpty()) }
        rebuildThread()
    }
}

/** 那次运行操作过的 App 和网站;记录已清掉或读不了时为空(当作纯聊天)。在后台线程调。 */
internal fun CapsuleController.placesIn(runId: String): List<String> = runCatching {
    CapsuleThreads.places(runStore.events(runId).filterIsInstance<RunEvent.Activity>()) { ConfirmationNotifier.appLabel(context, it) }
}.getOrDefault(emptyList())

/** 滚到了顶:往前多排 [CapsuleThreads.PAGE] 轮(先读出它们操作过的 App)。 */
internal fun CapsuleController.loadEarlier() {
    val id = threadOf ?: return
    val p = past ?: return
    if (p.size <= shownTurns) return
    shownTurns += CapsuleThreads.PAGE
    loadPast(id)
}

/** 跟着用户屏幕上的输入法:弹出、收起、变高时重新摆。卡片只按这一轮最高的样子摆,打字时不跟着候选行反复变(见 [cardImeTop])。 */
internal fun CapsuleController.watchIme(service: ScreenAccessibility) {
    imeJob?.cancel()
    imeJob = scope.launch {
        service.overlays().imeTop().collect {
            imeTop = it
            cardImeTop = CapsuleDocking.tallestImeTop(cardImeTop, it, dp(IME_SHRINK_JUMP_DP))
            place()
        }
    }
}

/** 到了模型说的时刻再算一次(换句、办完后缩小、消失)。 */
internal fun CapsuleController.schedule(at: Long?) {
    tick?.cancel()
    tick = at?.let { t ->
        scope.launch {
            delay((t - System.currentTimeMillis()).coerceAtLeast(0L))
            ticks.value++
        }
    }
}

/** 碰到了:马上恢复,从现在起重新计时。 */
internal fun CapsuleController.wake() {
    lastActive = System.currentTimeMillis()
    fade()
}

internal fun CapsuleController.fade() {
    fadeJob?.cancel()
    val v = view ?: return
    val idleFor = System.currentTimeMillis() - lastActive
    val before = edgeHidden
    edgeHidden = CapsuleEdge.hidden(last?.hideAtEdge == true, v.kind, v.pill, v.needCount,
        expanded || capturing, dragging, talk.active, note != null, idleFor)
    alpha = if (expanded || dragging) 1f else CapsuleFade.alpha(v.kind, v.needCount, v.pill, idleFor, last?.opacity?.alpha ?: CapsuleIdleOpacity.DEFAULT.alpha)
    if (before != edgeHidden) {
        applyDrag()
        place()
    }
    if (!expanded && idleFor < CapsuleFade.IDLE_MS) fadeJob = scope.launch {
        delay(CapsuleFade.IDLE_MS - idleFor)
        fade()
    }
}

/** 只给展开的那件事收画面;换了一件或收起就停。 */
internal fun CapsuleController.watchPreview(key: String?) {
    if (key == previewKey) return
    previewKey = key
    previewJob?.cancel()
    previewJob = null
    preview = null
    zoomed = false
    val run = key?.let(::runFor) ?: return
    previewJob = scope.launch {
        launch { run.frames.collect { if (it == null) preview = null } }
        run.frames.filterNotNull().previewFrames(same = ::sameFrame).flowOn(Dispatchers.Default).collect {
            preview = it.takeIf { run.frames.value != null }
        }
    }
}

/** 留在后台运行的那一页的卡片:收它那块显示的画面(和任务卡片的小画面同一个节流)。 */
internal fun CapsuleController.watchKept(id: Long) {
    val key = "kept:$id"
    if (key == previewKey) return
    previewKey = key
    previewJob?.cancel()
    preview = null
    zoomed = false
    previewJob = scope.launch {
        keeper.frames().filterNotNull().previewFrames(same = ::sameFrame).flowOn(Dispatchers.Default).collect { preview = it }
    }
}
