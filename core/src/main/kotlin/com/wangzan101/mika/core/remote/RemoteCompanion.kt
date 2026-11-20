package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.agent.TaskState

/**
 * Web 控制台要用的只读入口。和 [RemoteTasks] 分开:任务入口只认电脑交来的任务,
 * 这里看到的是手机上的对话和正在跑的任务,由 App 一侧从现有的对话存储与任务中心读取,不另存一份。
 */
interface RemoteCompanion {
    /** 最近的对话在前;没有内容的对话不列。 */
    fun conversations(limit: Int): List<RemoteConversation>

    /** 一个对话的轮次;对话不存在返回 null。[from] 小于 0 时取最后一段。 */
    fun conversation(id: String, from: Int, limit: Int): RemoteConversationPage?

    /** 此刻在跑、排队或等用户的任务,包含手机上发起的。 */
    fun running(): List<RemoteRunning>

    companion object {
        const val DEFAULT_LIST = 20
        const val MAX_LIST = 50
        const val DEFAULT_PAGE = 8
        const val MAX_PAGE = 10
        /** 轮次分页的 from 用它表示「取最后一段」。 */
        const val TAIL = -1
        // 一页最多 10 轮、单轮各项合计上限约 35 KB(按 3 字节/UTF-16 单元的 CJK 估算),
        // 整页留在 relay 单帧 1 MiB 以内;截断过的字段由 truncated 标出。
        const val MAX_USER_TEXT = 2_000
        const val MAX_OUTCOME = 4_000
        const val MAX_NOTE = 500
        const val MAX_STEER = 500
        const val MAX_STEERS = 10
        const val MAX_TITLE = 200
        const val MAX_GOAL = 1_000
        const val MAX_PROGRESS = 500
        const val MAX_RUNNING = 20
    }
}

data class RemoteConversation(
    val id: String,
    val title: String,
    val updatedAtMs: Long,
    val pinnedAtMs: Long? = null,
    val turnCount: Int = 0,
)

data class RemoteConversationPage(
    val id: String,
    val title: String,
    val total: Int,
    val from: Int,
    val turns: List<RemoteTurn>,
)

/** 一轮对话;超长的字段截断并标 [truncated]。 */
data class RemoteTurn(
    val index: Int,
    val userText: String,
    val outcome: String,
    val status: String,
    val note: String? = null,
    val runId: String? = null,
    val steers: List<String> = emptyList(),
    val truncated: Boolean = false,
)

/** 一个正在跑的任务,状态沿用 [RemoteStatus] 的说法。 */
data class RemoteRunning(
    val conversationId: String,
    val goal: String,
    val status: String,
    val runId: String? = null,
    val step: Int? = null,
    val progress: String? = null,
    val question: String? = null,
    val options: List<String> = emptyList(),
    val needsYou: Boolean = false,
    val truncated: Boolean = false,
)

/** 截断文本时守住代理对(emoji 不会被截成半个),截了就在末尾加省略号。 */
object RemoteText {
    data class Clipped(val text: String, val truncated: Boolean)

    fun clip(text: String, max: Int): Clipped {
        if (text.length <= max) return Clipped(text, false)
        var end = max.coerceAtLeast(0)
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end -= 1
        return Clipped(text.substring(0, end) + "…", true)
    }
}

/**
 * 把手机上的任务状态翻成控制台的说法。纯函数,放在 core:App 只提供对话编号、目标和当前状态,
 * 状态怎么读、截断多少都只有这一处,Web 端和以后别的客户端不会各说各的。
 */
object RemoteRunningView {
    fun of(conversationId: String, goal: String, state: TaskState, runId: String? = null): RemoteRunning {
        val status = RemoteStatus.of(state)
        var step: Int? = null
        var progress: String? = null
        var question: String? = null
        var options: List<String> = emptyList()
        var needsYou = false
        when (state) {
            is TaskState.Running -> {
                step = state.step
                progress = state.narration ?: state.reason ?: state.note
            }
            is TaskState.Held -> {
                progress = state.reason.why.ifBlank { state.reason.code }
                needsYou = state.reason.needsYou
            }
            is TaskState.WaitingInput -> {
                question = state.prompt
                options = state.options.orEmpty()
                needsYou = true
            }
            is TaskState.WaitingConfirmation -> {
                progress = state.request.app
                needsYou = true
            }
            is TaskState.WaitingTakeover -> {
                progress = state.message.ifBlank { state.reason }
                needsYou = true
            }
            TaskState.Idle -> Unit
            is TaskState.Terminal -> Unit
        }
        val shownGoal = RemoteText.clip(goal, RemoteCompanion.MAX_GOAL)
        val shownProgress = progress?.let { RemoteText.clip(it, RemoteCompanion.MAX_PROGRESS) }
        val shownQuestion = question?.let { RemoteText.clip(it, RemoteCompanion.MAX_PROGRESS) }
        return RemoteRunning(
            conversationId = conversationId,
            goal = shownGoal.text,
            status = status,
            runId = runId,
            step = step,
            progress = shownProgress?.text,
            question = shownQuestion?.text,
            options = options.take(RemoteCompanion.MAX_STEERS),
            needsYou = needsYou,
            truncated = shownGoal.truncated || shownProgress?.truncated == true || shownQuestion?.truncated == true,
        )
    }
}
