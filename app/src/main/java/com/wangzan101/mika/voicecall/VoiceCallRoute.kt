package com.wangzan101.mika.voicecall

import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update

/** 通话面板状态行缓冲的上限:进度行最多留这么多条,更旧的滚掉。 */
const val VOICE_LINE_MAX_COUNT = 12

/** 一条状态行最长这么多字,超出截断;面板一瞥可读,细节本来就不该上来。 */
const val VOICE_LINE_MAX_CHARS = 40

/** 回给实时模型的结果文本上限:协议帧对工具结果有总长度限制,给外层包装留余量。 */
const val VOICE_RESULT_MAX_CHARS = 1_800

/**
 * 语音请求的任务入口,装配时接与聊天页相同的任务发起方式(新建会话后交给任务服务),
 * 返回这次任务的会话编号。
 */
fun interface VoiceCallTaskLauncher {
    fun launch(goal: String): String
}

/**
 * 语音请求 → 既有任务入口的一次性目的地路由。同一时刻只接待一个请求(先到先得,
 * 后来的立即回拒,不重复发起任务);按会话编号经 [runState] 订阅该任务自己的状态
 * (并行任务时绝不收全局状态流);进度滚动成短状态行([lines]);任务停下等用户
 * (确认/回答/接管/需要动手的前置)时提示「去任务页看」并交还模型;用户开口打断
 * ([onBargeIn])取消已启动未完成的任务(不声称撤销已生效的操作);任务到终态把结果
 * 文本回给实时模型,由模型只复述一次。
 */
class VoiceCallRoute(
    private val launcher: VoiceCallTaskLauncher,
    private val runState: (String) -> StateFlow<TaskState>?,
    private val sendResult: (callId: String, text: String) -> Unit,
    private val cancelTask: (conversationId: String) -> Unit = {},
) {
    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** 面板滚动的状态行,最多 [VOICE_LINE_MAX_COUNT] 条,连续重复的只留一条。 */
    val lines: StateFlow<List<String>> = _lines

    private val _busy = MutableStateFlow(false)

    /** 是否有请求正在执行(认领到终态/交还之间);通话页据此把球换成办事形态。 */
    val busy: StateFlow<Boolean> = _busy

    private val lock = Any()
    private var claimed = false
    private var activeConversationId: String? = null
    private var bargeInGeneration = 0
    private var lastCancelledId: String? = null

    /**
     * 把一个过了安全闸的语音请求交给任务体系执行,挂起到任务终态(或交还)才返回;
     * 每个受理的 callId 恰好回一次结果。装配方在通话协程域里 launch 调用,别在事件循环里直接 await。
     */
    suspend fun execute(callId: String, text: String) {
        val claimGeneration = synchronized(lock) {
            if (claimed) null else {
                claimed = true
                bargeInGeneration
            }
        }
        if (claimGeneration == null) {
            sendResult(callId, RESULT_BUSY)
            return
        }
        _busy.value = true
        try {
            val conversationId = try {
                launcher.launch(text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                sendResult(callId, RESULT_START_FAILURE)
                return
            }
            synchronized(lock) { activeConversationId = conversationId }
            val flow = awaitRunState(conversationId)
            if (flow == null) {
                sendResult(callId, RESULT_START_FAILURE)
                return
            }
            // 认领之后、状态流登记之前发生的打断:任务一出现就取消(登记前 onBargeIn 已按会话编号取消过,这里幂等兜底)
            if (synchronized(lock) { bargeInGeneration } != claimGeneration) {
                cancelOnce(conversationId)
            }
            var handedOff = false
            flow.takeWhile { state ->
                if (state is TaskState.Terminal) return@takeWhile false
                state.toVoiceLine()?.let(::appendLine)
                if (state.needsUserOnPhone()) {
                    handedOff = true
                    return@takeWhile false
                }
                true
            }.collect {}
            if (handedOff) {
                sendResult(callId, RESULT_NEEDS_USER)
                return
            }
            val end = flow.value
            end.toVoiceLine()?.let(::appendLine)
            when (end) {
                is TaskState.Succeeded -> sendResult(callId, end.answer.ifBlank { RESULT_DONE_FALLBACK }.capResult())
                is TaskState.Failed -> sendResult(callId, end.reason.ifBlank { RESULT_FAILED_FALLBACK }.capResult())
                TaskState.Cancelled -> sendResult(callId, RESULT_CANCELLED)
                else -> sendResult(callId, RESULT_START_FAILURE) // takeWhile 只在终态或交还停,理论不可达
            }
        } finally {
            synchronized(lock) {
                claimed = false
                activeConversationId = null
            }
            _busy.value = false
        }
    }

    /** 用户开口打断:取消已启动还没跑完的任务;没有任务在跑时不做任何事。 */
    fun onBargeIn() {
        val id = synchronized(lock) {
            bargeInGeneration++
            activeConversationId
        } ?: return
        cancelOnce(id)
    }

    /** 同一个任务只取消一次:登记前的代次检查和 onBargeIn 可能都盯上它。 */
    private fun cancelOnce(conversationId: String) {
        val shouldCancel = synchronized(lock) {
            if (lastCancelledId == conversationId) false else {
                lastCancelledId = conversationId
                true
            }
        }
        if (shouldCancel) cancelTask(conversationId)
    }

    private fun appendLine(line: String) {
        _lines.update { current ->
            if (current.lastOrNull() == line) current else (current + line).takeLast(VOICE_LINE_MAX_COUNT)
        }
    }

    /** 任务服务登记运行是异步的:刚 launch 完 runState 可能还查不到,轮询等它出现。 */
    private suspend fun awaitRunState(conversationId: String): StateFlow<TaskState>? {
        var waited = 0L
        while (true) {
            runState(conversationId)?.let { return it }
            if (waited >= RUN_STATE_TIMEOUT_MS) return null
            delay(RUN_STATE_POLL_MS)
            waited += RUN_STATE_POLL_MS
        }
    }

    private fun String.capResult(): String = take(VOICE_RESULT_MAX_CHARS)

    companion object {
        private const val RUN_STATE_TIMEOUT_MS = 5_000L
        private const val RUN_STATE_POLL_MS = 100L

        // 回给实时模型的固定文本(模型侧指令风格,与协议层既有回复一致用英文;任务自身的答案/原因原样透传)
        const val RESULT_BUSY = "Another request is still running. Tell the user the previous request is still being handled; they can ask again when it finishes."
        const val RESULT_START_FAILURE = "The task could not be started. Apologize briefly and suggest the user start it from the task page instead."
        const val RESULT_NEEDS_USER = "The task started but needs the user to act on the phone (approve, answer, or take over). Tell the user to open the task page to continue."
        const val RESULT_CANCELLED = "The task was cancelled because the user interrupted."
        const val RESULT_DONE_FALLBACK = "The task finished."
        const val RESULT_FAILED_FALLBACK = "The task did not finish."
    }
}

/** 需要用户在手机上动一下手的状态:确认、回答、接管,或标明 needsYou 的前置等待;排队等开始不算。 */
private fun TaskState.needsUserOnPhone(): Boolean = when (this) {
    is TaskState.Held -> reason.needsYou
    is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover, is TaskState.WaitingInput -> true
    else -> false
}

/**
 * 任务状态 → 一行给通话面板的短状态([VOICE_LINE_MAX_CHARS] 字内)。
 * 运行中取模型的进度话,剥掉坐标(点击/滑动位置)与带引号的标题(日程名、联系人名、
 * 消息内容这类细节不上通话面板);剥完没剩东西时用一行笼统说法。Idle 没有可说的,返回 null。
 */
fun TaskState.toVoiceLine(): String? = when (this) {
    TaskState.Idle -> null
    is TaskState.Running -> voiceLineOf(narration ?: reason ?: note)
    is TaskState.Held -> if (reason.needsYou) tr(R.string.voicecall_see_task_page) else tr(R.string.voicecall_task_paused)
    is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover, is TaskState.WaitingInput ->
        tr(R.string.voicecall_see_task_page)
    is TaskState.Succeeded -> tr(R.string.voicecall_task_done)
    is TaskState.Failed -> tr(R.string.voicecall_task_failed)
    TaskState.Cancelled -> tr(R.string.voicecall_task_cancelled)
}

private fun voiceLineOf(raw: String): String {
    val cleaned = stripVoiceLineDetails(raw)
    return if (cleaned.isBlank()) tr(R.string.voicecall_task_running) else cleaned.take(VOICE_LINE_MAX_CHARS)
}

/** 坐标形态:界面树/点击位置常见的 [x,y]、(x,y) 及其全角变体;bounds 是相邻两对,逐对剥。 */
private val COORDINATE_PATTERN = Regex("""[\[\(（【]\s*\d+\s*[,，、]\s*\d+\s*[\]\)）】]""")

/** 带引号的一段:日程标题、联系人名、消息内容这类细节通常被引起来,整段剥掉。【】也按引号段剥(非数字形态)。 */
private val QUOTED_PATTERN = Regex("""「[^」]*」|『[^』]*』|《[^》]*》|【[^】]*】|"[^"]*"|“[^”]*”|‘[^’]*’|'[^']*'""")

private val WHITESPACE_RUN = Regex("""\s+""")

/** 剥掉坐标与引号包裹的标题等细节,并把剥出的空白收拢。 */
fun stripVoiceLineDetails(text: String): String =
    WHITESPACE_RUN.replace(COORDINATE_PATTERN.replace(QUOTED_PATTERN.replace(text, ""), ""), " ").trim()
