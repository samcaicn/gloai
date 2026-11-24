package com.wangzan101.mika.frontdesk

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.frontdesk.DeskTaskPort
import com.wangzan101.mika.core.frontdesk.DispatchOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 前台补给后台的说明最长这么多字,超出截断(说明只是补充,不该比用户原话还长出一大截)。 */
const val DESK_NOTE_MAX_CHARS = 1_000

/** 派出去的任务这么久还没在任务服务里登记(服务没起来、被挡下),当成没开始。 */
const val DESK_START_TIMEOUT_MS = 60_000L

/** 取前 [max] 个 char,不把一个字符(表情等占两个 char 的)切成两半。 */
internal fun String.takeWhole(max: Int): String {
    if (length <= max) return this
    val end = if (max > 0 && this[max - 1].isHighSurrogate() && this[max].isLowSurrogate()) max - 1 else max
    return substring(0, end)
}

/** 端口眼里一件在跑或排队的后台任务;产品里包着 [com.wangzan101.mika.task.TaskRun]。 */
interface DeskRun {
    val conversationId: String
    /** 这次运行在任务服务里的编号;同一个子会话里先后开的几次运行各不相同。 */
    val key: String
    val state: StateFlow<TaskState>

    /** 补一句用户的话;任务收下了返回 true。 */
    fun steer(words: String): Boolean
    fun answer(words: String)
    fun cancel()
}

/**
 * 前台的任务端口:每件活开一个新的子会话(挂在前台会话下),把用户原话和前台说明分开交给任务服务;
 * 任务编号就是子会话编号。新子会话里一定没有任务,服务收到后按新任务开始或排队,说明不会被当成插话丢掉。
 *
 * 任务服务异步登记任务,所以每件活的状态由这里另持一份:先是 Idle,任务登记后跟着它的状态走,收尾即停;
 * [startTimeoutMs] 内一直没登记就记成没开始。重开会话时接回的子会话,任务还在的跟着它走;任务已不在的按子会话最后一轮
 * 记下的结局给终态,连结局都没记下(进程中途没了)的直接记成中断([interrupted]),不等登记、也不占并行位置。
 *
 * [runs]/[current] 是任务服务里此刻在跑和排队的任务(排队的按先后),[start] 交给任务服务,[parallelLimit] 是此刻能同时跑几个。
 */
class AppDeskTaskPort(
    private val conversations: ConversationStore,
    private val runs: Flow<List<DeskRun>>,
    private val current: () -> List<DeskRun>,
    private val start: (words: String, conversationId: String, brief: String?) -> Unit,
    private val parallelLimit: () -> Int,
    private val scope: CoroutineScope,
    private val notStarted: () -> String,
    private val interrupted: () -> String,
    private val startTimeoutMs: Long = DESK_START_TIMEOUT_MS,
) : DeskTaskPort {
    /** [adopted]:不是这里派的,是重开会话时接回的;它在不在服务里只看服务那份,不当成刚派出的占位。 */
    private class Followed(val state: MutableStateFlow<TaskState>, val queuedAt: Int?, val adopted: Boolean = false) {
        /** 任务服务里已经看到过它。 */
        @Volatile var seen = false
        /** 跟着的是哪次运行(任务服务里登记后才有);子会话里之后再开的运行不是它。 */
        @Volatile var runKey: String? = null
    }

    private val followed = HashMap<String, Followed>()

    override fun dispatch(words: String, note: String, parentConversation: String): DispatchOutcome =
        startIn(openChild(parentConversation), words, note)

    /** 文件放进子会话的工作区期间不占这把锁(复制可能要一会儿),放好了才按此刻的并行情况开始或排队。 */
    override suspend fun dispatchWithFiles(parentConversation: String, prepare: suspend (child: String) -> String?): DispatchOutcome {
        val child = openChild(parentConversation)
        val words = try {
            prepare(child)
        } catch (e: CancellationException) {
            runCatching { conversations.delete(child) }
            throw e
        } catch (e: Exception) {
            runCatching { conversations.delete(child) }
            return DispatchOutcome.Failed(e.message ?: e::class.simpleName ?: "prepare_failed")
        }
        if (words.isNullOrBlank()) {
            runCatching { conversations.delete(child) }
            return DispatchOutcome.Failed("files_not_ready")
        }
        return startIn(child, words, "")
    }

    /** 开一个新的子会话,挂在前台会话 [parentConversation] 下。 */
    private fun openChild(parentConversation: String): String =
        conversations.newConversation().also { conversations.setDeskParent(it, parentConversation) }

    /** 在子会话 [child] 里开始(或排上队)这件活;没交给任务服务时删掉子会话。 */
    @Synchronized
    private fun startIn(child: String, words: String, note: String): DispatchOutcome {
        val outcome = predict(child)
        val entry = Followed(MutableStateFlow(TaskState.Idle), (outcome as? DispatchOutcome.Queued)?.position)
        followed[child] = entry
        try {
            start(words, child, note.trim().takeWhole(DESK_NOTE_MAX_CHARS).ifBlank { null })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            followed.remove(child)
            runCatching { conversations.delete(child) }
            return DispatchOutcome.Failed(e.message ?: e::class.simpleName ?: "start_failed")
        }
        mirror(child, entry)
        return outcome
    }

    /** 按任务服务的规则预判:在跑的(含这里刚派出、还没登记的)不到上限就开始,否则排在已排队的后面。 */
    private fun predict(child: String): DispatchOutcome {
        val live = current()
        val unseen = followed.values.filter { !it.adopted && !it.seen && it.state.value !is TaskState.Terminal }
        val running = live.count { !it.queued() && it.state.value !is TaskState.Terminal } + unseen.count { it.queuedAt == null }
        val queued = live.count { it.queued() } + unseen.count { it.queuedAt != null }
        return if (running < parallelLimit().coerceAtLeast(1)) DispatchOutcome.Started(child)
        else DispatchOutcome.Queued(child, queued + 1)
    }

    @Synchronized
    override fun state(taskId: String): StateFlow<TaskState>? {
        followed[taskId]?.let { return it.state.asStateFlow() }
        // 不是这里派的:只认挂在某个前台会话下的子会话(重开会话时接回)。
        if (conversations.deskParent(taskId) == null) return null
        val live = current().firstOrNull { it.conversationId == taskId }
        val initial = live?.state?.value ?: ended(taskId) ?: TaskState.Failed(interrupted(), FailKind.Interrupted)
        val entry = Followed(MutableStateFlow(initial), null, adopted = true)
        followed[taskId] = entry
        if (initial !is TaskState.Terminal) mirror(taskId, entry)
        return entry.state.asStateFlow()
    }

    @Synchronized
    override fun followedTaskOf(runKey: String): String? = followed.entries.firstOrNull { it.value.runKey == runKey }?.key

    override fun queuePosition(taskId: String): Int? {
        val live = current()
        val run = live.firstOrNull { it.conversationId == taskId }
        if (run != null) {
            if (!run.queued()) return null
            return live.filter { it.queued() }.indexOfFirst { it.conversationId == taskId } + 1
        }
        val entry = synchronized(this) { followed[taskId] } ?: return null
        return entry.queuedAt?.takeIf { !entry.seen && entry.state.value !is TaskState.Terminal }
    }

    override fun steer(taskId: String, words: String): Boolean {
        val run = live(taskId) ?: return false
        if (run.state.value !is TaskState.Running) return false
        return run.steer(words)
    }

    override fun answer(taskId: String, words: String): Boolean {
        val run = live(taskId) ?: return false
        if (run.state.value !is TaskState.WaitingInput) return false
        run.answer(words)
        return true
    }

    override fun cancel(taskId: String): Boolean {
        val run = live(taskId) ?: return false
        if (run.state.value is TaskState.Terminal) return false
        run.cancel()
        return true
    }

    private fun live(taskId: String): DeskRun? = current().firstOrNull { it.conversationId == taskId }

    /** 等任务在服务里登记,然后跟着它的状态走,收尾即停;一直没登记时按子会话记下的结局,没有就记成没开始。 */
    private fun mirror(child: String, entry: Followed) {
        scope.launch {
            val run = withTimeoutOrNull(startTimeoutMs) {
                runs.mapNotNull { list -> list.firstOrNull { it.conversationId == child } }.first()
            }
            if (run == null) {
                entry.state.value = ended(child) ?: TaskState.Failed(notStarted(), FailKind.Environment)
                return@launch
            }
            entry.runKey = run.key
            entry.seen = true
            run.state.transformWhile { emit(it); it !is TaskState.Terminal }.collect { entry.state.value = it }
        }
    }

    /** 子会话最后一轮记下的结局;还没有记下任何一轮时为 null。轮次里不存失败来源,失败一律记成运行环境。 */
    private fun ended(child: String): TaskState.Terminal? {
        val last: Turn = conversations.turns(child).lastOrNull { it.userText.isNotBlank() } ?: return null
        return when (last.status) {
            "succeeded" -> TaskState.Succeeded(last.outcome)
            "unverified" -> TaskState.Succeeded(last.outcome, verified = false, note = last.note)
            "cancelled" -> TaskState.Cancelled
            else -> TaskState.Failed(last.outcome, FailKind.Environment)
        }
    }

    private fun DeskRun.queued(): Boolean = (state.value as? TaskState.Held)?.reason?.queued == true
}
