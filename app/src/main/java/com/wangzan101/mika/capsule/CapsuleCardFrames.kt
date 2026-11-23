package com.wangzan101.mika.capsule

import kotlinx.coroutines.delay

import androidx.compose.runtime.produceState

import com.wangzan101.mika.chat.ui.KeptPageCopy

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.task.ConfirmationCopy
import com.wangzan101.mika.chat.ui.ScreenCopy
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.MikaLogo
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme

/** 卡片的底:[fixed] 时就是 [height] 高(对话卡片),否则按内容高、最多 [height](任务卡片)。 */
@Composable
internal fun CardFrame(height: Dp, fixed: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = MikaTheme.colors
    Column(
        Modifier.testTag("capsule-card").width(cardWidth()).then(if (fixed) Modifier.height(height) else Modifier.heightIn(max = height))
            .glass(c, CardShape, need = false).padding(12.dp),
        content = content,
    )
}

/** 标题栏(按着它能拖卡片,见 [CapsuleCard] 的 onHandle):左边 Mika 图标,中间标题和状态,右边收起。 */
@Composable
internal fun Header(title: String, sub: String, actions: CardActions, handle: Modifier) {
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
internal fun IdleHeader(card: CardView.Chat, actions: CardActions, handle: Modifier) {
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
internal fun Detail(
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
internal fun KeptCard(card: CardView.Kept, preview: Bitmap?, actions: CardActions, height: Dp, handle: Modifier) {
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
