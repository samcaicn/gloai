package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.TaskState

/** 后台任务一次值得前台知道的变化;[taskId] 是这件任务的编号。 */
sealed interface DeskEvent {
    val taskId: String

    /** 开始做了(包括排队轮到)。 */
    data class Started(override val taskId: String) : DeskEvent
    /** 排在后面,[position] 从 1 起,表示前面还有几件。 */
    data class Queued(override val taskId: String, val position: Int) : DeskEvent
    /** 在做什么的一句话,已剥掉细节并截短。 */
    data class Progress(override val taskId: String, val line: String) : DeskEvent
    /** 任务在问用户一个问题,[options] 非空时是可选的几项。 */
    data class NeedsAnswer(override val taskId: String, val prompt: String, val options: List<String>?) : DeskEvent
    /** 要用户在手机上动手(确认、接管、满足前置条件)。 */
    data class NeedsAction(override val taskId: String, val kind: ActionKind) : DeskEvent
    /** 后台已经收尾并给出回答,[answer] 是完整原文(交给模型、读出来时各自再截到 [DESK_RESULT_MAX_CHARS] 字内)。 */
    data class Succeeded(override val taskId: String, val answer: String) : DeskEvent
    data class Failed(override val taskId: String, val reason: String) : DeskEvent
    data class Cancelled(override val taskId: String) : DeskEvent
}

/** 要用户动手的几类:同意一次不可逆操作、接管手机、让前置条件满足。 */
enum class ActionKind { Confirm, Takeover, Prerequisite }

object DeskEvents {
    /**
     * 把任务状态从 [previous] 变到 [next] 归成一个前台事件;没变、或者这次变化不用前台知道时为 null。
     * [previous] 为 null 表示第一次看到这件任务。排队不从状态里判:排第几只有发起时知道,由发起方给出 [DeskEvent.Queued]。
     */
    fun classify(taskId: String, previous: TaskState?, next: TaskState): DeskEvent? {
        if (previous == next) return null
        return when (next) {
            TaskState.Idle -> null
            is TaskState.Running -> when {
                previous == null || previous == TaskState.Idle || (previous is TaskState.Held && previous.reason.queued) ->
                    DeskEvent.Started(taskId)
                previous is TaskState.Running -> progressLine(previous.narration, next.narration)?.let { DeskEvent.Progress(taskId, it) }
                else -> null
            }
            is TaskState.WaitingInput -> DeskEvent.NeedsAnswer(taskId, next.prompt, next.options)
            is TaskState.WaitingConfirmation -> DeskEvent.NeedsAction(taskId, ActionKind.Confirm)
            is TaskState.WaitingTakeover -> DeskEvent.NeedsAction(taskId, ActionKind.Takeover)
            is TaskState.Held -> if (next.reason.needsYou) DeskEvent.NeedsAction(taskId, ActionKind.Prerequisite) else null
            is TaskState.Succeeded -> DeskEvent.Succeeded(taskId, next.answer)
            is TaskState.Failed -> DeskEvent.Failed(taskId, next.reason)
            TaskState.Cancelled -> DeskEvent.Cancelled(taskId)
        }
    }

    /** 进度说明清洗后有字、且和上一句清洗后不同时才算新进度(只差坐标、引号里的细节不算变了)。 */
    private fun progressLine(before: String?, after: String?): String? {
        val line = deskLine(after) ?: return null
        return line.takeIf { it != deskLine(before) }
    }
}
