package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.browser.PageAskMode
import com.wangzan101.mika.browser.PageAskModel
import com.wangzan101.mika.browser.PageConversations
import com.wangzan101.mika.browser.UserBrowser
import com.wangzan101.mika.capsule.CapsuleModel
import com.wangzan101.mika.capsule.CapsuleRun
import com.wangzan101.mika.capsule.CapsuleThreads
import com.wangzan101.mika.capsule.CardActions
import com.wangzan101.mika.capsule.CardContent
import com.wangzan101.mika.capsule.CardRun
import com.wangzan101.mika.capsule.CapsuleCards
import com.wangzan101.mika.capsule.ChatThread
import com.wangzan101.mika.capsule.PastTurn
import com.wangzan101.mika.capsule.Pinned
import com.wangzan101.mika.capsule.Steps
import com.wangzan101.mika.capsule.StopKey
import com.wangzan101.mika.capsule.ThreadList
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.PACKAGE_NAME_RE
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.TaskRun
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.TaskService.Companion.isActive
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.floatShadow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** 浏览器页上「问 Mika」那一块的固定说法。 */
object PageAskCopy {
    val HINT: String get() = tr(R.string.browser_ask_hint)
    val FROM_PAGE: String get() = tr(R.string.browser_ask_from_page)
    val OPEN_CHAT: String get() = tr(R.string.browser_ask_open_chat)
    val MENU: String get() = tr(R.string.browser_ask_menu)
    val SELECTION: String get() = tr(R.string.browser_ask_selection)
    val DROP_SELECTION: String get() = tr(R.string.browser_ask_drop_selection)
    val PAUSED: String get() = tr(R.string.browser_ask_paused)
    val PAUSED_CARD: String get() = tr(R.string.browser_ask_paused_card)
    val PAUSED_HINT: String get() = tr(R.string.browser_ask_paused_hint)
    val EXPAND: String get() = tr(R.string.browser_ask_expand)
    val WEB: String get() = tr(R.string.browser_ask_web)
    val APP: String get() = tr(R.string.browser_ask_app)
    val HANDLING: String get() = tr(R.string.browser_ask_handling)
    fun continuing(title: String): String = tr(R.string.browser_ask_continuing, title)
}

/**
 * 浏览器页上对着这一页问 Mika 的状态:这一页接着哪个对话([conversationId],同一页或同一网站最近问过的,见 [PageConversations];
 * 选了「新对话」时 [fresh])、底部那一块开着没有([open])、输入框里的字和带上的选中文字;那个对话里此刻的任务([run]、[card])
 * 和对话的来回([thread],和悬浮胶囊的对话卡片同一套排法)。
 * 发出去的第一句带上这一页的说明([AttachedPage]),页面借给那个任务([UserBrowser.lend]);任务在跑时说的话是插话(回答提问时是回答),
 * 都走任务服务现有的入口。只在主线程用。
 */
@Stable
class PageChat(private val context: Context, private val browser: UserBrowser, private val scope: CoroutineScope) {
    private val app = context.applicationContext as MikaApp
    private val store = ConversationStore.shared(context)
    private val model = CapsuleModel()
    private val toolLabels by lazy { ToolLabels(knownTools()) }
    private val appLabels = HashMap<String, String>()

    var conversationId by mutableStateOf<String?>(null)
        private set
    /** 接着的那个对话的标题(给「接着「…」」那一行)。 */
    var title by mutableStateOf<String?>(null)
        private set
    /** 这一页上已经问过(这个对话是这次在这一页上开始或接上的),不再显示「接着 · 新对话」。 */
    var started by mutableStateOf(false)
        private set
    var fresh by mutableStateOf(false)
    var open by mutableStateOf(false)
    var draft by mutableStateOf("")
    var selection by mutableStateOf<String?>(null)
    /** 点了文字选择菜单里的「问 Mika」:输入框要拿焦点(每次加一)。 */
    var focusAsk by mutableIntStateOf(0)
        private set

    var card by mutableStateOf<CardRun?>(null)
        private set
    var thread by mutableStateOf<ChatThread?>(null)
        private set
    /** 此刻那件事的步骤在哪(「网页」「App」),和 [CardContent.Working] 的步骤一一对应。 */
    var stepTags by mutableStateOf<List<String?>>(emptyList())
        private set
    private var run: TaskRun? = null
    private var past: List<PastTurn>? = null
    private var shown = CapsuleThreads.PAGE
    private var watching: Job? = null
    private var loading: Job? = null
    private var lastState: TaskState? = null
    private var exchange: Exchange? = null

    val state: TaskState? get() = card?.run?.state
    val mode: PageAskMode get() = PageAskModel.mode(state?.takeIf { it.isActive() }, open)
    val target: String? get() = if (fresh) null else conversationId

    /** 打开了一页(或换了一页):还没在这一页上问过时,按这一页找接着的对话。 */
    fun onPage(url: String?) {
        if (url == null) {
            reset()
            return
        }
        if (started) return
        scope.launch {
            val pick = withContext(Dispatchers.IO) { runCatching { PageConversations.pick(store.list(), url, System.currentTimeMillis()) }.getOrNull() }
            if (started) return@launch
            follow(pick?.id, pick?.title)
        }
    }

    /** 页面关了:回到什么都没有。 */
    private fun reset() {
        started = false
        fresh = false
        open = false
        selection = null
        follow(null, null)
    }

    /** 选中的文字要问 Mika:带上它,输入框拿焦点。 */
    fun askAbout(text: String) {
        selection = AttachedPage.clip(text)
        focusAsk++
    }

    /** 发出输入框里的话:那件事在跑时是插话(在问你时是回答),否则在这一页上开始(带上页面说明,借出页面)。 */
    fun send() {
        val words = draft.trim()
        val sel = selection
        val live = run?.takeIf { it.state.value.isActive() && it.conversationId == target }
        if (live != null) {
            if (words.isEmpty() && sel == null) return
            if (live.state.value is TaskState.WaitingInput) TaskService.answer(context, words.ifEmpty { sel.orEmpty() }, live.key)
            else {
                // 插话里带着新选中的字:和开头一样写成页面说明(这一页此刻的样子)
                val text = sel?.let { browser.pageInfo()?.let { p -> AttachedPage.compose(p.copy(selection = it), words) } } ?: words
                TaskService.start(context, text, live.conversationId, messageId = UUID.randomUUID().toString())
            }
            draft = ""
            selection = null
            return
        }
        if (words.isEmpty() && sel == null) return
        val page = browser.pageInfo() ?: return
        val id = target ?: store.newConversation()
        val origin = PageConversations.origin(page.url, page.title)
        browser.lend(id)
        TaskService.start(context, AttachedPage.compose(page.copy(selection = sel), words), id)
        if (origin != null) scope.launch(Dispatchers.IO) { runCatching { store.setPageOrigin(id, origin) } }
        draft = ""
        selection = null
        fresh = false
        started = true
        open = true
        follow(id, title.takeIf { id == conversationId })
    }

    /** 换成跟着对话 [id]:读它记下的轮次,盯着它此刻的任务。 */
    private fun follow(id: String?, title: String?) {
        this.title = title
        if (id == conversationId && watching != null) return
        conversationId = id
        past = null
        shown = CapsuleThreads.PAGE
        card = null
        thread = null
        run = null
        exchange = null
        lastState = null
        watching?.cancel()
        if (id == null) {
            watching = null
            return
        }
        loadPast(id)
        watching = scope.launch {
            launch { store.changes.collect { if (it == id) loadPast(id) } }
            watch(id)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun watch(id: String) {
        app.taskState.runs.map { runs -> runs.firstOrNull { it.conversationId == id } }.flatMapLatest { r ->
            if (r == null) flowOf(null)
            else combine(r.state, r.takeoverStage, r.activities, r.steers) { s, st, acts, steers ->
                r to CapsuleRun(r.key, r.conversationId, s, st, r.goal, exchangeOf(r, s, acts), runId = r.runId.value, onScreen = true, steers = steers)
            }
        }.collect { pair ->
            val r = pair?.first
            if (r != null) run = r
            val c = pair?.second ?: card?.run?.takeIf { !it.state.isActive() } ?: run?.let { gone ->
                // 任务已从列表里拿掉:按它最后的状态留着,等这一轮记进对话
                CapsuleRun(gone.key, gone.conversationId, gone.state.value, Takeover.Stage.None, gone.goal, exchange, runId = gone.runId.value)
            }
            val next = c?.let(model::cardRunOf)
            val before = lastState
            lastState = next?.run?.state
            card = next
            stepTags = tagsOf(next)
            PageAskModel.follow(before, lastState)?.let { open = it }
            if (before?.isActive() == true && lastState?.isActive() != true) loadPast(id)
            rebuild()
        }
    }

    private fun exchangeOf(r: TaskRun, s: TaskState, acts: List<RunEvent.Activity>): Exchange {
        val before = exchange?.takeIf { it.goal == r.goal } ?: Exchange(r.goal, emptyList(), ExchangeStatus.Working, live = true)
        return ChatViewModel.reduce(before.copy(activities = acts), s, ::appLabel, toolLabels::label).also { exchange = it }
    }

    // 每一步在哪:那一步的活动对象是「网站 …」的是网页,是包名的是 App
    private fun tagsOf(c: CardRun?): List<String?> {
        val ex = c?.run?.exchange ?: return emptyList()
        val content = CapsuleCards.content(c) as? CardContent.Working ?: return emptyList()
        val rows = ex.steps
        return content.steps.mapIndexed { i, _ ->
            val row = rows.getOrNull(i) ?: return@mapIndexed null
            val target = ex.activities.lastOrNull { it.step == row.step }?.target?.trim().orEmpty()
            when {
                target.startsWith(SITE_PREFIX) -> PageAskCopy.WEB
                PACKAGE_NAME_RE.matches(target) -> PageAskCopy.APP
                else -> null
            }
        }
    }

    private fun loadPast(id: String) {
        loading?.cancel()
        val n = shown
        loading = scope.launch {
            val (turns, name) = withContext(Dispatchers.IO) {
                runCatching { store.turns(id) to store.title(id) }.getOrDefault(emptyList<com.wangzan101.mika.core.agent.Turn>() to null)
            }
            if (conversationId != id) return@launch
            if (name != null && title == null) title = name
            past = turns.takeLast(n.coerceAtLeast(turns.size)).map { PastTurn(it) }
            rebuild()
        }
    }

    private fun rebuild() {
        val id = conversationId ?: return
        val p = past ?: return
        val live = listOfNotNull(card).let { CapsuleThreads.liveTail(it, id, p.mapNotNull { t -> t.turn.runId }.toSet()) }
        thread = CapsuleThreads.build(p, shown, live, app.taskState.summarizing.value) { r ->
            CapsuleThreads.places(r.run.exchange?.activities.orEmpty(), ::appLabel)
        }
    }

    private fun appLabel(pkg: String): String = appLabels.getOrPut(pkg) { ConfirmationNotifier.appLabel(context, pkg) }

    /** 面板、小卡片上的按钮:都走任务服务现有的动作,带着那件事的编号。 */
    val actions = object : CardActions {
        override fun collapse() { open = false }
        override fun stop(runKey: String) = TaskService.cancel(context, runKey)
        override fun takeOver(runKey: String) = TaskService.takeOver(context, waitKey = run?.takeover?.waitKey, runKey = runKey)
        override fun resume(runKey: String) = TaskService.resume(context, runKey)
        override fun answer(runKey: String, text: String) = TaskService.answer(context, text, runKey)
        override fun approve(runKey: String, requestKey: Int) = TaskService.approve(context, requestKey, runKey)
        override fun decline(runKey: String, requestKey: Int) = TaskService.decline(context, requestKey, runKey)
        override fun openMika(conversationId: String) {
            runCatching { context.startActivity(TaskService.openAppIntent(context, conversationId)) }
                .onFailure { Log.w(TAG, "could not open the conversation", it) }
        }
        override fun zoom(on: Boolean) = Unit
        override fun openInput() = Unit
        override fun draft(text: String) { draft = text }
        override fun send() = this@PageChat.send()
        override fun freshChat(fresh: Boolean) { this@PageChat.fresh = fresh }
        override fun loadEarlier() {
            val id = conversationId ?: return
            if ((past?.size ?: 0) < shown) return
            shown += CapsuleThreads.PAGE
            loadPast(id)
        }
        override fun captureScreen() = Unit
        override fun dropScreen() = Unit
    }

    /** 界面测试用:不跟任何任务,直接显示 [card] 和 [thread](对话 [id])。 */
    @androidx.annotation.VisibleForTesting
    internal fun show(id: String?, card: CardRun?, thread: ChatThread?) {
        watching?.cancel()
        watching = null
        conversationId = id
        this.card = card
        this.thread = thread
        PageAskModel.follow(lastState, card?.run?.state)?.let { open = it }
        lastState = card?.run?.state
    }

    /** 交还接手的页面(「处理好了」)。 */
    fun handBack() {
        val r = run ?: return
        TaskService.handBack(context, waitKey = r.takeover?.waitKey, runKey = r.key)
    }

    /** 用户亲手摸了页面。 */
    fun touched() = browser.userTouched()

    private companion object {
        const val TAG = "MikaPageChat"
        /** 运行记录里网页那一屏的对象写成「网站 <域名>」。 */
        const val SITE_PREFIX = "网站 "
    }
}

/**
 * 网页视图的容器:手指一按下就告诉 [onTouch]。只有经窗口送来的真实触摸会经过这里;Mika 自己的输入(DevTools 的鼠标键盘直接进页面,
 * 原生触摸直接发给网页视图)不经过它,所以不会把自己的动作当成用户在操作。
 * 网页的文字选择菜单里加一项「问 Mika」([onAsk]),点了先读出选中的字,读完调它给的 done 收起菜单。
 */
class PageFrame(context: Context, private val onTouch: () -> Unit, private val onAsk: (done: () -> Unit) -> Unit) : FrameLayout(context) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) onTouch()
        return super.dispatchTouchEvent(ev)
    }

    override fun startActionModeForChild(originalView: View, callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionModeForChild(originalView, AskCallback(callback), type)

    private inner class AskCallback(private val inner: ActionMode.Callback) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = inner.onCreateActionMode(mode, menu).also { if (it) addAsk(menu) }

        // 网页内核准备菜单时会清空重排:排完再加
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = inner.onPrepareActionMode(mode, menu).also { addAsk(menu) }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != ASK_ID) return inner.onActionItemClicked(mode, item)
            // 读出选中的字之后再收起菜单(收起时选中的字可能随之取消)
            onAsk { mode.finish() }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = inner.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            (inner as? ActionMode.Callback2)?.onGetContentRect(mode, view, outRect) ?: super.onGetContentRect(mode, view, outRect)
        }

        private fun addAsk(menu: Menu) {
            if (menu.findItem(ASK_ID) == null) menu.add(Menu.NONE, ASK_ID, 0, PageAskCopy.MENU).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
    }

    private companion object {
        val ASK_ID = View.generateViewId()
    }
}

/**
 * 盖在网页上的那几样(放在网页区域的 Box 里):干活时四周的光边;你在页面上动手、Mika 暂停时顶上一条「继续」;
 * 面板开着时下半截的对话面板([PagePanel]),收着、那件事还没结束时底部的小卡片([PageCard])。平时的输入条不在这里(在网页下面)。
 */
@Composable
fun BoxScope.PageAskOverlay(chat: PageChat, site: String, pageTitle: String) {
    val state = chat.state
    if (PageAskModel.glow(state)) PageGlow()
    if (PageAskModel.pausedForUser(state)) {
        PausedBanner({ chat.card?.run?.key?.let(chat.actions::resume) }, Modifier.align(Alignment.TopCenter).padding(10.dp))
    }
    when (chat.mode) {
        PageAskMode.Panel -> PagePanel(chat, site, pageTitle, Modifier.align(Alignment.BottomCenter).fillMaxHeight(PANEL_SHARE).imePadding())
        PageAskMode.Card -> PageCard(chat, Modifier.align(Alignment.BottomCenter).padding(10.dp))
        PageAskMode.Bar -> Unit
    }
}

/** 对话面板占网页区域的高度比例:上面露出来的网页就是 Mika 在操作的那一页。 */
private const val PANEL_SHARE = 0.58f

/** 页面四周的一圈淡紫光边:Mika 正在这一页上干活。不挡触摸。 */
@Composable
fun PageGlow(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().testTag("page-glow").drawBehind {
            val w = 2.dp.toPx()
            for (i in 0 until GLOW_LAYERS) {
                val inset = w / 2 + i * w * 2
                drawRoundRect(
                    GlowColor.copy(alpha = 0.7f / (i + 1)), topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                    cornerRadius = CornerRadius(6.dp.toPx()), style = Stroke(w),
                )
            }
        },
    )
}

private const val GLOW_LAYERS = 4
/** Mika 在页面上干活时光边的颜色(淡紫,和页面本身的颜色分得开)。 */
private val GlowColor = Color(0xFF7B5CFF)

/**
 * 平时浏览器页底部那一条:「✦ 问问 Mika…」输入框;这一页有接着的对话时上面一行「接着「…」 · 新对话」;
 * 选中文字问的时候上面一条「选中的文字」和 ×。
 */
@Composable
fun PageAskBar(chat: PageChat, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    Column(modifier.fillMaxWidth().background(c.bg).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp).testTag("page-ask-bar")) {
        val continuing = chat.conversationId
        if (continuing != null && !chat.started) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (chat.fresh) CardCopyText.NEW_CHAT else PageAskCopy.continuing(ChatCopy.plainTitle(AttachedPage.words(chat.title.orEmpty())).take(TITLE_MAX)),
                    Modifier.weight(1f, fill = false), style = MikaType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(" · ", style = MikaType.Hint, color = c.ink3)
                Text(
                    if (chat.fresh) CardCopyText.CONTINUE_LAST else CardCopyText.NEW_CHAT,
                    Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button) { chat.fresh = !chat.fresh }.padding(horizontal = 4.dp, vertical = 2.dp)
                        .testTag("page-ask-fresh"),
                    style = MikaType.Hint, color = c.accent,
                )
            }
        }
        chat.selection?.let { SelectionChip(it) { chat.selection = null } }
        AskField(chat, PageAskCopy.HINT, stop = null)
    }
}

private const val TITLE_MAX = 16

/** 卡片里「新对话」「接着上次聊」的说法(和悬浮胶囊同一套字)。 */
private object CardCopyText {
    val NEW_CHAT: String get() = tr(R.string.chat_new)
    val CONTINUE_LAST: String get() = tr(R.string.card_continue_last)
}

/** 输入框上面带着的选中文字:淡色一条,右边 × 不带它。 */
@Composable
private fun SelectionChip(text: String, onRemove: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.padding(bottom = 6.dp).fillMaxWidth().clip(MikaShapes.Chip).background(c.ink.copy(alpha = 0.05f))
            .padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 4.dp).testTag("page-ask-selection"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(PageAskCopy.SELECTION, style = MikaType.Hint, color = c.ink3)
            Text(text, style = MikaType.Caption, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.size(28.dp).clip(MikaShapes.Pill).clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = PageAskCopy.DROP_SELECTION },
            contentAlignment = Alignment.Center,
        ) { Icon(MikaIcons.Close, null, Modifier.size(15.dp), tint = c.ink3) }
    }
}

/** 输入框:左边 ✦,写了字(或带着选中的字)右边是发送;有 [stop] 时右边一直有停止键。 */
@Composable
private fun AskField(chat: PageChat, hint: String, stop: (() -> Unit)?) {
    val c = MikaTheme.colors
    val shape = MikaShapes.Card
    val focus = remember { FocusRequester() }
    LaunchedEffect(chat.focusAsk) { if (chat.focusAsk > 0) runCatching { focus.requestFocus() } }
    val canSend = chat.draft.isNotBlank() || chat.selection != null
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(shape).background(c.fill).padding(start = 12.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(MikaIcons.Spark, null, Modifier.size(17.dp), tint = GlowColor)
        Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
            if (chat.draft.isEmpty()) Text(hint, style = MikaType.Body, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                chat.draft, { chat.draft = it },
                Modifier.fillMaxWidth().focusRequester(focus).testTag("page-ask-field").semantics { contentDescription = hint },
                textStyle = MikaType.Body.copy(color = c.ink, textDirection = androidx.compose.ui.text.style.TextDirection.Content), maxLines = 4, cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) chat.send() }),
            )
        }
        stop?.let { StopKey(it) }
        if (stop == null || canSend) {
            Box(
                Modifier.testTag("page-ask-send").size(32.dp).clip(MikaShapes.Pill).background(if (canSend) c.ink else c.ink3.copy(alpha = 0.3f))
                    .clickable(enabled = canSend, role = Role.Button, onClick = chat::send)
                    .semantics { contentDescription = tr(R.string.chat_send) },
                contentAlignment = Alignment.Center,
            ) { Icon(MikaIcons.Up, null, Modifier.size(16.dp), tint = c.bg) }
        }
    }
}

private val PanelShape = MikaShapes.SheetTop
private val CardShape = MikaShapes.Card

/**
 * 半屏对话面板:盖在网页下半截,上面露出来的网页就是 Mika 正在操作的那一页。顶上是网站名、「从这一页开始 · 页面标题」、
 * 「在聊天中打开」和收起;中间是这个对话的来回(和悬浮胶囊的对话卡片同一套排法);干活中下面是步骤,标出那一步在网页还是 App 上;
 * 要你点的按钮(回答的选项、同意、接手、继续)固定在下面;最下面是输入框:在跑时说的话是插话(右边停止键),Mika 问你时是回答,
 * 结束后是在这一页上接着问。页面在你手上(接手中)时是「处理好了」。
 */
@Composable
fun PagePanel(chat: PageChat, site: String, pageTitle: String, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val card = chat.card
    val content = card?.let(CapsuleCards::content)
    val live = card?.run?.state?.isActive() == true
    Column(
        modifier.fillMaxWidth().clip(PanelShape).background(c.bg).border(0.5.dp, c.hair, PanelShape)
            .padding(horizontal = 14.dp).testTag("page-panel"),
    ) {
        Box(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = tr(R.string.collapse)) { chat.open = false }.padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.width(36.dp).height(4.dp).clip(MikaShapes.Pill).background(c.hair)) }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(site, style = MikaType.Label.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(PageAskCopy.FROM_PAGE, pageTitle).filter(String::isNotBlank).joinToString(" · "),
                    style = MikaType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            chat.conversationId?.let { id ->
                Row(
                    Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button) { chat.actions.openMika(id) }.padding(horizontal = 6.dp, vertical = 6.dp)
                        .testTag("page-panel-open-chat"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(PageAskCopy.OPEN_CHAT, style = MikaType.Hint, color = c.accent)
                    Icon(MikaIcons.External, null, Modifier.size(13.dp), tint = c.accent)
                }
            }
            Box(
                Modifier.size(30.dp).clip(MikaShapes.Pill).clickable(role = Role.Button, onClick = chat.actions::collapse)
                    .semantics { contentDescription = tr(R.string.collapse) }.testTag("page-panel-collapse"),
                contentAlignment = Alignment.Center,
            ) { Icon(MikaIcons.Close, null, Modifier.size(17.dp), tint = c.ink3) }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.hair))
        // 步骤紧接在对话来回的最后一条(「网站 · 在干活」那一行)下面,和对话页一样跟着往下长,不压到面板底部
        val steps: (@Composable () -> Unit)? = if (content is CardContent.Working && content.steps.isNotEmpty()) {
            { Box(Modifier.padding(bottom = 6.dp)) { Steps(content.steps, StepsMax, chat.stepTags) } }
        } else null
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
            chat.thread?.let { t -> androidx.compose.runtime.key(chat.conversationId) { ThreadList(t, chat.conversationId, chat.actions, footer = steps) } }
        }
        if (card != null && card.run.stage == Takeover.Stage.HandedOver) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(PageAskCopy.HANDLING, style = MikaType.Caption, color = c.ink2)
                MikaButton(tr(R.string.action_hand_back), chat::handBack, Modifier.fillMaxWidth().testTag("page-panel-hand-back"), kind = ButtonKind.Primary, size = ButtonSize.Medium)
            }
        } else if (card != null && content != null && live) {
            Box(Modifier.padding(top = 4.dp)) { Pinned(content, card.run.key, chat.actions) }
        }
        val hint = when (content) {
            is CardContent.Takeover, is CardContent.Confirm -> null
            is CardContent.Question -> CapsuleCards.HINT_ANSWER
            is CardContent.Working, is CardContent.Held -> CapsuleCards.HINT_STEER
            else -> CapsuleCards.HINT_CONTINUE
        }
        val stoppable = live && (content is CardContent.Working || content is CardContent.Question || content is CardContent.Held)
        if (hint != null) Column(Modifier.padding(top = 8.dp, bottom = 10.dp)) {
            chat.selection?.let { SelectionChip(it) { chat.selection = null } }
            AskField(chat, hint, stop = if (stoppable) ({ card?.run?.key?.let(chat.actions::stop) }) else null)
        } else Box(Modifier.height(10.dp))
    }
}

private val StepsMax = 132.dp

/**
 * 面板收起时底部的小卡片:那件事此刻在做什么,点一下展开。你在页面上动手而暂停时写「已暂停」和「继续」。
 */
@Composable
fun PageCard(chat: PageChat, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val card = chat.card ?: return
    val paused = PageAskModel.pausedForUser(card.run.state)
    val warn = paused || card.kind.needsYou
    Row(
        modifier.fillMaxWidth().floatShadow(c, CardShape).clip(CardShape).background(c.bg).border(0.5.dp, c.hair, CardShape)
            .clickable(role = Role.Button, onClickLabel = PageAskCopy.EXPAND) { chat.open = true }
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp).testTag("page-card"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(MikaShapes.Pill).background(if (warn) c.warnSolid else GlowColor))
        Column(Modifier.weight(1f)) {
            Text(
                if (paused) PageAskCopy.PAUSED_CARD else card.line.ifBlank { CapsuleCards.status(card.kind) },
                style = MikaType.Label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (paused) Text(PageAskCopy.PAUSED_HINT, style = MikaType.Hint, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (paused) MikaButton(tr(R.string.chat_continue), { chat.actions.resume(card.run.key) }, Modifier.testTag("page-card-resume"), kind = ButtonKind.Primary, size = ButtonSize.Small)
        Icon(MikaIcons.Up, PageAskCopy.EXPAND, Modifier.size(18.dp), tint = c.ink3)
    }
}

/** 你在页面上动手、Mika 停下时顶上那一条:「你在操作，Mika 已暂停 · 继续」。 */
@Composable
fun PausedBanner(onResume: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(MikaShapes.Chip).background(Color(0xFF1D1F24))
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).testTag("page-paused-banner"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(MikaIcons.Hand, null, Modifier.size(16.dp), tint = Color.White)
        Text(PageAskCopy.PAUSED, Modifier.weight(1f), style = MikaType.Caption, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            tr(R.string.chat_continue),
            Modifier.clip(MikaShapes.Tile).background(Color.White).clickable(role = Role.Button, onClick = onResume)
                .padding(horizontal = 12.dp, vertical = 5.dp).testTag("page-paused-resume"),
            style = MikaType.Label.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFF111111),
        )
    }
}

/** 对话页上那句话是在浏览器页上对着一页问的:气泡上面一小块「网页 · 网站」,带着选中的字时下面是那段字。 */
@Composable
internal fun SentPage(page: AttachedPage.Page, maxWidth: androidx.compose.ui.unit.Dp) {
    val c = MikaTheme.colors
    val host = com.wangzan101.mika.core.cap.web.WebUrlPolicy.host(page.url) ?: page.url
    Column(
        Modifier.padding(bottom = 4.dp).widthIn(max = maxWidth).background(c.ink.copy(alpha = 0.05f), MikaShapes.Chip)
            .padding(horizontal = 10.dp, vertical = 6.dp).testTag("sent-page"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(MikaIcons.Globe, null, Modifier.size(13.dp), tint = c.ink3)
            Text("${PageAskCopy.WEB} · $host", style = MikaType.Hint, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        page.selection?.let { Text(it, style = MikaType.Caption, color = c.ink2, maxLines = 3, overflow = TextOverflow.Ellipsis) }
    }
}
