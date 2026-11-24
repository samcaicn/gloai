package com.wangzan101.mika.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.frontdesk.ActionKind
import com.wangzan101.mika.core.frontdesk.DeskTaskView
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.flow.MutableStateFlow

/** 前台派出去、此刻要你处理的每件任务([DeskTaskView.need] 不为空)在对话末尾一张问题卡;行 key 见 [needCardKey]。 */
internal fun LazyListScope.deskNeedRows(
    tasks: List<DeskTaskView>, highlightKey: String?, reduceMotion: Boolean,
    answer: (taskId: String, words: String) -> Unit, cancel: (taskId: String) -> Unit, vm: ChatViewModel,
) {
    tasks.filter { it.need != null }.forEach { view ->
        val key = needCardKey(view.taskId)
        item(key) {
            JumpHighlight(highlightKey == key, reduceMotion) {
                DeskNeedCard(view, key, { answer(view.taskId, it) }, { cancel(view.taskId) }, vm)
            }
        }
    }
}

/**
 * 一件任务在等你:有选项时「要你选」(点一个直接回给这件事,都不是就在下面打字);只问不给选项时「要你回答」;
 * 要你接管、同意一次或先办好一件事时,就在这张卡片上办:和聊天页同一种按钮([NeedStatusCard]),只作用于这一件,
 * 不再跳去别的页面。这件事的状态一时读不到时只给「停止这件事」。
 */
@Composable
private fun DeskNeedCard(view: DeskTaskView, key: String, onAnswer: (String) -> Unit, onCancel: () -> Unit, vm: ChatViewModel) {
    val need = view.need ?: return
    if (need.action != null) {
        val idle = remember { MutableStateFlow<TaskState>(TaskState.Idle) }
        val none = remember { MutableStateFlow(Takeover.Stage.None) }
        val state by remember(view.taskId) { vm.runState(view.taskId) ?: idle }.collectAsState()
        val stage by remember(view.taskId) { vm.runStage(view.taskId) ?: none }.collectAsState()
        val status = ChatReduce.needStatus(state, vm.appLabel)
        if (status != null) {
            val heading = when (status) {
                is ExchangeStatus.NeedsConfirmation -> null
                is ExchangeStatus.NeedsTakeover -> stringResource(R.string.desk_need_takeover, view.title)
                else -> stringResource(R.string.desk_need_handle, view.title)
            }
            Box(Modifier.testTag(key)) { NeedStatusCard(status, stage, vm.deskNeedOps(view.taskId), heading) }
            return
        }
    }
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
            }),
        )
    }
}
