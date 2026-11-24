package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.uiLocale
import com.wangzan101.mika.task.ConfirmationCopy
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.task.TaskGate
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.agent.DeskTurn
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import kotlinx.coroutines.flow.map
import com.wangzan101.mika.settings.ui.StatusCopy

/** 把任务状态和运行事件折成聊天卡片上的一问一答(纯函数)。 */
internal object ChatReduce {
    internal fun TaskState.active() = when (this) {
        TaskState.Idle, is TaskState.Succeeded, is TaskState.Failed, TaskState.Cancelled -> false
        else -> true
    }

    internal fun ExchangeStatus.active() = when (this) {
        is ExchangeStatus.Done, is ExchangeStatus.Failed, is ExchangeStatus.Cancelled -> false
        else -> true
    }

    internal fun ExchangeStatus.needsUser() =
        this is ExchangeStatus.NeedsTakeover || this is ExchangeStatus.NeedsInput || this is ExchangeStatus.Paused ||
            this is ExchangeStatus.NeedsConfirmation

    internal fun reduce(ex: Exchange, s: TaskState, appLabel: (String) -> String = { it }, toolLabel: (String) -> String = { it }): Exchange =
        state(ex, s, appLabel).let {
            it.copy(steps = rows(it, toolLabel, appLabel), stepActions = actionsOf(it.activities, appLabel, toolLabel))
        }

    /** 按步号归好的动作明细:点开一步时列出来。 */
    internal fun actionsOf(activities: List<RunEvent.Activity>, appLabel: (String) -> String, toolLabel: (String) -> String) =
        activities.groupBy { it.step }.mapValues { (_, list) ->
            // 用工具的界面说法(和运行过程页同一份),不把工具名露给用户;经 MCP 的调用说连接器名
            list.map { a ->
                val label = a.mcpConnector?.let(StatusCopy::calledMcp) ?: toolLabel(a.label)
                StepAction(label, a.target?.let { where(it, appLabel) }, a.effect, a.failed)
            }
        }

    /**
     * 正在办的一件事此刻要你动手的那张卡片(接管、同意一次、要你先办好一件事),和聊天页一问一答里的同一种;
     * 不是这几种(包括排队、锁屏自动继续)为 null。主聊天的问题卡用它,按钮直接作用于那一件。
     */
    internal fun needStatus(s: TaskState, appLabel: (String) -> String): ExchangeStatus? = when (s) {
        is TaskState.WaitingTakeover -> ExchangeStatus.NeedsTakeover(s.reason, s.message, s.item, s.amount, s.address)
        is TaskState.WaitingConfirmation -> ExchangeStatus.NeedsConfirmation(
            ConfirmationCopy.title(s.request), ConfirmationCopy.detail(s.request, appLabel(s.request.app)),
            ConfirmationNotifier.requestKey(s),
        )
        is TaskState.Held -> if (s.reason == TaskGate.LOCKED || s.reason.queued) null else ExchangeStatus.Paused(s.reason.why, s.reason.resumable)
        else -> null
    }

    internal fun state(ex: Exchange, s: TaskState, appLabel: (String) -> String): Exchange = when (s) {
        TaskState.Idle -> ex
        is TaskState.Running -> {
            val goals = (s.progress as? RunProgress.Task)?.goals
            // 正在调 MCP 先说哪个连接器,盖过模型写的话
            val progress = s.mcpConnector?.let(StatusCopy::callingMcp) ?: s.progress?.takeIf { goals == null }?.let(StatusCopy::progress)
            // 「在做什么」先用模型随调用写的话,没有再用调用里的用意
            val said = (s.narration ?: s.reason)?.takeIf(String::isNotBlank)
            val waiting = progress == null && s.tool == null && said == null
            ex.copy(
                status = ExchangeStatus.Working, waitingForModel = waiting, draft = s.preview, goals = goals ?: ex.goals,
                line = if (waiting) "" else progress ?: said.orEmpty(),
                notes = if (s.step > 0 && said != null) ex.notes + (s.step to said) else ex.notes,
            )
        }
        // 等能力的前置条件:锁屏时自动继续;排队等开始;其余能点继续的才给继续
        is TaskState.Held -> ex.copy(
            status = when {
                s.reason == TaskGate.LOCKED -> ExchangeStatus.WaitingUnlock
                s.reason.queued -> ExchangeStatus.Queued(s.reason.why)
                else -> ExchangeStatus.Paused(s.reason.why, s.reason.resumable)
            },
            waitingForModel = false, draft = null,
        )
        is TaskState.WaitingTakeover ->
            ex.copy(status = ExchangeStatus.NeedsTakeover(s.reason, s.message, s.item, s.amount, s.address), waitingForModel = false, draft = null)
        is TaskState.WaitingInput -> ex.copy(status = ExchangeStatus.NeedsInput(s.prompt, s.options), waitingForModel = false, draft = null)
        is TaskState.WaitingConfirmation -> ex.copy(
            waitingForModel = false,
            draft = null,
            status = ExchangeStatus.NeedsConfirmation(
                ConfirmationCopy.title(s.request), ConfirmationCopy.detail(s.request, appLabel(s.request.app)),
                ConfirmationNotifier.requestKey(s),
            ),
        )
        // 回答原样显示(不再有模型写的依据段);来源由运行记录里代码记下的那份给出
        is TaskState.Succeeded -> ex.copy(status = ExchangeStatus.Done(s.answer.trim(), null, s.verified, s.note, s.unconfirmed, s.reviewed),
            waitingForModel = false, draft = null, line = "")
        is TaskState.Failed -> ex.copy(status = ExchangeStatus.Failed(s.reason), waitingForModel = false, draft = null, line = "")
        TaskState.Cancelled -> ex.copy(status = ExchangeStatus.Cancelled(), waitingForModel = false, draft = null, line = "")
    }

    /**
     * 步骤列表:每件实际做过的事一行。读取类只在知道读的是哪里时出现,连着读同一处合成一行(「看了微信的聊天列表」);
     * 动作写「点击 · 微信」,那一步模型写了话就用它的话;没成功只说没成功,不显示错误码。
     * 最后一行的状态跟着这一问一答:在跑、等你、结束。
     */
    /** 没办成的一步下面那行小字:按规矩没收的说原因,其余说「没成功」。 */
    internal fun failedLabel(a: RunEvent.Activity) = ChatCopy.declinedLabel(a.effect) ?: tr(R.string.step_failed)

    internal fun rows(ex: Exchange, toolLabel: (String) -> String, appLabel: (String) -> String): List<StepRow> {
        val out = mutableListOf<StepRow>()
        val said = mutableSetOf<Int>()
        for (a in ex.activities) {
            val where = a.target?.let { where(it, appLabel) }
            val mcp = a.mcpConnector?.let(StatusCopy::calledMcp)
            if (mcp != null) {
                // 经 MCP 的调用单列一行说连接器;失败也看得出是哪个连接器
                out += StepRow(a.step, mcp, if (a.failed) failedLabel(a) else "", StepState.Ok)
            } else if (a.kind == "observe") {
                // 读成功了却不知道读的是哪里:不列;没读成的照样列出来
                if (where == null && !a.failed) continue
                val text = if (where == null) toolLabel(a.label)
                    else a.title?.takeIf(String::isNotBlank)?.let { tr(R.string.step_viewed_part, where, it) } ?: tr(R.string.step_viewed, where)
                val subtitle = if (a.failed) failedLabel(a) else ""
                val last = out.lastOrNull()
                if (last?.title != text || last.subtitle != subtitle) out += StepRow(a.step, text, subtitle, StepState.Ok)
            } else {
                val reason = if (said.add(a.step)) ex.notes[a.step].orEmpty() else ""
                val title = listOfNotNull(toolLabel(a.label), where).joinToString(" · ")
                out += StepRow(a.step, title, if (a.failed) failedLabel(a) else "", StepState.Ok, reason)
            }
        }
        if (out.isEmpty()) return out
        val last = when (ex.status) {
            ExchangeStatus.Working -> StepState.Run
            is ExchangeStatus.Done -> StepState.Ok
            is ExchangeStatus.Failed, is ExchangeStatus.Cancelled -> StepState.Dim
            else -> StepState.Wait
        }
        out[out.lastIndex] = out.last().copy(state = last)
        return out
    }

    /** 活动对象给人看的说法:包名换成 App 名,「网站 域名」只留域名。 */
    internal fun where(target: String, appLabel: (String) -> String): String? = when {
        target.isBlank() -> null
        target.startsWith(SITE_PREFIX) -> target.removePrefix(SITE_PREFIX)
        com.wangzan101.mika.core.tool.PACKAGE_NAME_RE.matches(target) -> appLabel(target)
        else -> target
    }

    internal fun fromTurn(t: Turn): Exchange {
        // 前台写的一轮(聊天、通话里前台回的话,和它的汇报):画成前台的一行,派出的任务各画一张卡片
        if (DeskTurn.isDesk(t)) {
            val status = DeskTurn.failure(t)?.let { ExchangeStatus.Failed(it) } ?: ExchangeStatus.Done(t.outcome, null)
            return Exchange(t.userText, emptyList(), status, live = false, runId = t.runId, deskReply = t.outcome, deskTasks = t.deskTasks,
                callId = t.callId, atMs = t.atMs)
        }
        val status = when (t.status) {
            "succeeded" -> answerParts(t.outcome).let { (a, e) -> ExchangeStatus.Done(a, e) }
            "unverified" -> answerParts(t.outcome).let { (a, e) -> ExchangeStatus.Done(a, e, verified = false, note = t.note, unconfirmed = t.unconfirmed) }
            "cancelled" -> {
                val rawSummary = Conversation.stopSummary(t)
                val summary = if (rawSummary != null && uiLocale().language != "zh") {
                    rawSummary
                        .replace("已经停下。停止前:", "Stopped. Before stopping:")
                        .replace("已经停下,停止前做了这些:", "Stopped. Actions before stop:")
                        .replace("已经停下,还没开始操作。", "Stopped before starting any actions.")
                } else rawSummary
                ExchangeStatus.Cancelled(summary)
            }
            else -> ExchangeStatus.Failed(t.outcome)
        }
        return Exchange(t.userText, emptyList(), status, live = false, runId = t.runId,
            memoryNotes = t.memoryNotes, usedMemory = t.usedMemory, skillNotes = t.skillNotes,
            audios = t.audios,
            photos = t.photos,
            keptPage = t.keptPage, keptPageClosed = t.keptPageClosed, atMs = t.atMs,
            steers = t.steers.mapIndexed { i, text ->
                SteerNote("turn-$i", text, SteerState.Joined, t.interruptedReplies.getOrNull(i)?.takeIf(String::isNotEmpty),
                    t.finishedReplies.getOrNull(i)?.takeIf(String::isNotEmpty))
            })
    }

    /** 消息末尾每个附件一行:「附件:/workspace/inbox/<名字>(<大小>)」;[files] 是复制进工作区后的 (路径, 字节数)。 */
    internal fun withAttachments(text: String, files: List<Pair<String, Long>>): String =
        AttachmentLines.composeBytes(text, files)

    fun sizeLabel(bytes: Long): String = AttachmentLines.sizeLabel(bytes)
}
