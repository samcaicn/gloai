package com.wangzan101.mika.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.wangzan101.mika.R
import com.wangzan101.mika.core.frontdesk.ActionKind
import com.wangzan101.mika.core.frontdesk.DeskTaskView
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 前台派出去、此刻要你处理的每件任务([DeskTaskView.need] 不为空)在对话末尾一张问题卡;行 key 见 [needCardKey]。 */
internal fun LazyListScope.deskNeedRows(
    tasks: List<DeskTaskView>, highlightKey: String?, reduceMotion: Boolean,
    answer: (taskId: String, words: String) -> Unit, open: (taskId: String) -> Unit, cancel: (taskId: String) -> Unit,
) {
    tasks.filter { it.need != null }.forEach { view ->
        val key = needCardKey(view.taskId)
        item(key) {
            JumpHighlight(highlightKey == key, reduceMotion) {
                DeskNeedCard(view, key, { answer(view.taskId, it) }, { open(view.taskId) }, { cancel(view.taskId) })
            }
        }
    }
}

/**
 * 一件任务在等你:有选项时「要你选」(点一个直接回给这件事,都不是就在下面打字);只问不给选项时「要你回答」;
 * 要你到画面里接手时「要你接管」,要你确认或先办好一件事时「要你处理」,这两种给「去处理」(进这件事的对话)和「停止这件事」。
 */
@Composable
private fun DeskNeedCard(view: DeskTaskView, key: String, onAnswer: (String) -> Unit, onOpen: () -> Unit, onCancel: () -> Unit) {
    val need = view.need ?: return
    val options = need.options.orEmpty()
    val asking = need.action == null
    val title = when {
        asking && options.isNotEmpty() -> R.string.desk_need_choose
        asking -> R.string.desk_need_answer
        need.action == ActionKind.Takeover -> R.string.desk_need_takeover
        else -> R.string.desk_need_handle
    }
    val foot = when {
        asking && options.isNotEmpty() -> R.string.desk_need_choose_foot
        asking -> R.string.desk_need_answer_foot
        need.action == ActionKind.Takeover -> R.string.desk_need_takeover_foot
        else -> null
    }
    val body = need.prompt ?: view.line.orEmpty()
    Box(Modifier.testTag(key)) {
        NeedCard(
            stringResource(title, view.title), body,
            footer = foot?.let { res ->
                {
                    Text(
                        stringResource(res), Modifier.fillMaxWidth(), style = MikaType.Hint, color = MikaTheme.colors.ink3,
                        textAlign = TextAlign.Center,
                    )
                }
            },
            options = if (asking && options.isNotEmpty()) ({
                options.forEach { o -> MikaButton(o, { onAnswer(o) }, Modifier.fillMaxWidth(), kind = ButtonKind.Tonal, size = ButtonSize.Medium) }
            }) else null,
            buttons = if (asking) null else ({
                CardButton(stringResource(R.string.desk_need_stop), onClick = onCancel)
                CardButton(stringResource(R.string.desk_need_go), primary = true, onClick = onOpen)
            }),
        )
    }
}
