package com.wangzan101.mika.chat.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.MarkdownText
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * 前台的一轮在对话里的几行(按原型):用户的话(汇报那一轮没有)、前台回的话(和别的回答一样的浅灰块,顶上可带一个工具小标签)。
 * 前台派出去的任务不在对话里画卡片,看输入框上方的进度条;要你处理时对话末尾一张问题卡。行 key 与 [chatItemKeys] 一致。
 */
internal fun LazyListScope.deskExchange(
    index: Int, ex: Exchange, conversationId: String?, reduceMotion: Boolean, highlightKey: String?,
) {
    if (ex.goal.isNotEmpty()) item("u$index") {
        JumpHighlight(highlightKey == "u$index", reduceMotion) { UserBubble(ex.goal, conversationId, key = "u$index") }
    }
    item("r$index") {
        JumpHighlight(highlightKey == "r$index", reduceMotion) { DeskReply(ex, "r$index", reduceMotion) }
    }
}

/**
 * 前台回的话:浅灰块,短的贴字宽、长的铺满;在想时块里三个点加「在想」,边说边长(末尾带光标);
 * 没说出话就收尾的那一轮(只派了单)整行不画。
 */
@Composable
private fun DeskReply(ex: Exchange, key: String, reduceMotion: Boolean) {
    if (!deskReplyShown(ex)) return
    val c = MikaTheme.colors
    val text = ex.deskReply.orEmpty()
    val working = ex.status == ExchangeStatus.Working
    Column(Modifier.fillMaxWidth().padding(bottom = ReplyGap).testTag("desk-reply")) {
        ex.deskTool?.let { tool ->
            Text(
                tool, Modifier.padding(bottom = 4.dp).background(c.fill, MikaShapes.Chip).padding(horizontal = 8.dp, vertical = 1.dp),
                style = MikaType.Micro, color = c.ink3, maxLines = 1,
            )
        }
        when {
            text.isNotBlank() && working -> MarkdownText(text, AnswerText.copy(color = c.ink), Modifier.answerSurface(), streaming = true)
            text.isNotBlank() -> AnswerArea(key, text, Modifier.answerSurface()) { MarkdownText(text, AnswerText.copy(color = c.ink)) }
            working -> Thinking(reduceMotion)
        }
        if (ex.status is ExchangeStatus.Failed) {
            Text(stringResource(R.string.desk_reply_failed), Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3)
        }
    }
}

/** 还没开口:灰块里三个起伏的点加一句「在想」。 */
@Composable
private fun Thinking(reduceMotion: Boolean) {
    val c = MikaTheme.colors
    Row(Modifier.answerSurface(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThinkingDots(reduceMotion)
        Text(stringResource(R.string.desk_thinking), style = MikaType.Caption, color = c.ink3, maxLines = 1)
    }
}

/** 前台在想:三个依次起伏的小点;关了动画时静止。 */
@Composable
private fun ThinkingDots(reduceMotion: Boolean) {
    val c = MikaTheme.colors
    Row(
        Modifier.padding(vertical = 6.dp, horizontal = 2.dp).clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(3) { i ->
            val phase = if (reduceMotion) 1f else rememberInfiniteTransition(label = "desk-think").animateFloat(
                0f, 1f,
                infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse, StartOffset(i * 150)),
                label = "desk-think-$i",
            ).value
            Box(
                Modifier.size(6.dp).graphicsLayer {
                    alpha = 0.25f + 0.75f * phase
                    translationY = -3.dp.toPx() * phase
                }.background(c.ink3, CircleShape),
            )
        }
    }
}
