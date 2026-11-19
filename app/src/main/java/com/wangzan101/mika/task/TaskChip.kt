package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.TaskStage
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.ToolLabels

private val tools by lazy { knownTools() }
private val labels by lazy { ToolLabels(tools) }

/** 收起时优先显示需要用户处理的事项，运行中显示当前大阶段。 */
internal fun taskChip(origin: RunOrigin, state: TaskState, stage: TaskStage? = null): String? {
    if (origin != RunOrigin.Ui) return null
    val label = when (state) {
        is TaskState.WaitingConfirmation -> R.string.chat_wait_approval
        is TaskState.WaitingInput -> R.string.chat_wait_answer
        is TaskState.WaitingTakeover -> R.string.chip_take_over
        is TaskState.Held -> when {
            state.reason.queued -> return null
            state.reason == TaskGate.LOCKED -> R.string.task_wait_unlock
            state.reason.needsYou -> R.string.screens_needs_you
            else -> R.string.chat_paused
        }
        TaskState.Idle, is TaskState.Terminal -> return null
        is TaskState.Running -> return stage?.compact ?: tr(
            if (state.step <= 1) R.string.runtime_preparing else R.string.task_running)
    }
    return tr(label)
}

/** 没有旁白时展开卡片也能看懂当前动作；等模型时不沿用上一步的工具名。 */
internal fun chipDetail(state: TaskState.Running, stage: TaskStage? = null): String {
    if (stage != null) return "${stage.ordinal} · ${stage.detail}"
    val tool = state.tool
    return when {
        state.progress != null || state.step == 0 -> TaskService.describe(state).second
        tool == null -> tr(R.string.chip_thinking)
        else -> tr(R.string.task_step_note, state.step, state.narration?.takeIf { it.isNotBlank() }
            ?: state.reason?.takeIf { it.isNotBlank() } ?: tools[tool]?.let { labels.label(tool) } ?: tr(R.string.task_running))
    }
}
