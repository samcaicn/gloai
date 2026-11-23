package com.wangzan101.mika.capsule




import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.MarkdownText
import com.wangzan101.mika.chat.ui.ScreenCopy
import com.wangzan101.mika.chat.ui.UserMessage
import com.wangzan101.mika.ui.components.ScrollToBottomButton
import com.wangzan101.mika.ui.components.glassSource
import com.wangzan101.mika.ui.components.rememberGlassBackdrop
import com.wangzan101.mika.ui.components.scrollListToBottom
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 对话卡片:那个对话的来回在中间滚;要你回答、同意时按钮固定在来回下面,再下面是输入框和「回到 Mika」。
 * 不带任务时是空闲卡片(标题栏能切新对话)。空闲时「教 Mika」收在输入栏上方的箭头里,默认不展开。
 */
@Composable
internal fun ChatCard(
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
        if (r == null) TeachShelf(actions::teach, actions::voiceChat)
        input()
        FooterLinks(actions, card.target?.let { id -> CardCopy.BACK_TO_MIKA to { actions.openMika(id) } })
    }
}

/**
 * 空闲卡片输入栏正上方的「教 Mika」:默认只留一个箭头,点开才出现入口,再点收起。
 * 收起时箭头朝上,展开时朝下。学习中的浮层不走这里。
 */
@Composable
private fun TeachShelf(onTeach: () -> Unit, onVoiceChat: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = tr(R.string.teach_menu)
    val expandLabel = tr(R.string.teach_shelf_expand)
    val collapseLabel = tr(R.string.teach_shelf_collapse)
    val action = if (open) collapseLabel else expandLabel
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (open) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TextLink(name, onClick = onTeach)
            TextLink(tr(R.string.voicecall_menu), onClick = onVoiceChat)
        }
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
internal fun FooterLinks(actions: CardActions, primary: Pair<String, () -> Unit>?) {
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
