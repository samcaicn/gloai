package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 前台眼里一件任务所处的阶段。 */
enum class DeskTaskPhase { Running, Queued, NeedsYou, Done, Failed, Cancelled }

/** 前台列出的一件任务:[line] 是当前一句话(已剥细节),[position] 是排队时排第几(从 1 起)。 */
data class DeskTaskView(val taskId: String, val title: String, val state: DeskTaskPhase, val line: String?, val position: Int?)

/**
 * 一条会话常驻的前台:按任务编号订阅它派出的各件任务,把值得说的变化(见 [DeskEvents]、[ReportPolicy])
 * 按发生先后排成一队,一条一条交给此刻接着的入口 [DeskSurface];没有入口在线时交给 [DeskSink]。
 * 前台自己不说话、不持有模型:怎么说由各入口自己定。
 *
 * 投递规则:同一时刻只送一条,上一条送完(入口说完)才送下一条;入口说到一半被打断时,这条改交给之后换上来的入口,
 * 没有新入口就交给 sink,每条只送达一次。
 */
class FrontDesk(
    private val conversationId: String,
    private val scope: CoroutineScope,
    private val tasks: DeskTaskPort,
    private val ledger: DeskLedger,
    private val policy: ReportPolicy,
    private val sink: DeskSink,
) {
    private val reports = Channel<DeskReport>(Channel.UNLIMITED)
    private val tracked = HashSet<String>()
    private val views = MutableStateFlow<List<DeskTaskView>>(emptyList())

    @Volatile
    private var surface: DeskSurface? = null

    /** 这次对话前台派过的任务及各自阶段,按派单先后;收尾了的留在列表里,标成对应终态。 */
    val active: StateFlow<List<DeskTaskView>> = views.asStateFlow()

    init {
        scope.launch { for (report in reports) deliver(report) }
    }

    /** 接上一个入口(替换之前接着的);关掉返回的句柄即断开,之后的汇报交给 sink。 */
    fun attach(surface: DeskSurface): AutoCloseable {
        this.surface = surface
        return AutoCloseable { if (this.surface === surface) this.surface = null }
    }

    /** 开始跟一件刚派出的任务(派单时账本已由派单方记好);同一件只跟一次,不认识的编号忽略。 */
    fun track(taskId: String, title: String) {
        follow(taskId, title, report = true)
    }

    /**
     * 会话重新打开时接回之前派过的任务:补回账本,还在跑的重新订阅、只汇报之后的变化;
     * 已经收尾的只标出终态,不再汇报。
     */
    fun restore(entries: List<DeskTaskEntry>) {
        for (entry in entries) {
            if (ledger.get(entry.taskId) == null) ledger.add(entry)
            follow(entry.taskId, entry.title, report = false)
        }
    }

    /** [report] 为 false 时当前状态只用来标阶段,不当成一次变化汇报(接回时这些早已说过)。 */
    private fun follow(taskId: String, title: String, report: Boolean) {
        val states = tasks.state(taskId) ?: return
        synchronized(tracked) { if (!tracked.add(taskId)) return }
        val current = states.value
        updateView(taskId, title, current)
        // 接回时已收尾的只标终态;刚派出就收尾的(很快失败)照常汇报这一条。
        if (!report && current is TaskState.Terminal) return
        scope.launch {
            var previous: TaskState? = if (report) null else current
            // 收到终态那一条就停止订阅,之后状态流再变也不再理会。
            states.transformWhile { emit(it); it !is TaskState.Terminal }.collect { next ->
                val event = DeskEvents.classify(taskId, previous, next)
                previous = next
                updateView(taskId, title, next)
                if (event != null && policy.shouldReport(event)) reports.trySend(DeskReport(event, title))
            }
        }
    }

    private fun updateView(taskId: String, title: String, state: TaskState) {
        views.update { list ->
            val view = DeskTaskView(taskId, title, phaseOf(state), lineOf(state), null)
            val replaced = if (list.any { it.taskId == taskId }) list.map { if (it.taskId == taskId) view else it } else list + view
            // 一件任务开始会让后面排队的往前挪,排队位置每次都整体重取。
            replaced.map { it.copy(position = if (it.state == DeskTaskPhase.Queued) tasks.queuePosition(it.taskId) else null) }
        }
    }

    private suspend fun deliver(report: DeskReport) {
        var interrupted: DeskSurface? = null
        while (true) {
            val target = surface?.takeIf { it !== interrupted }
            if (target == null) {
                record(report)
                return
            }
            try {
                if (!target.deliver(report)) record(report)
                return
            } catch (e: CancellationException) {
                // 前台自己被收掉时照常退出;否则是入口被用户打断,这条换个去处再送。
                currentCoroutineContext().ensureActive()
                interrupted = target
            } catch (e: Exception) {
                record(report)
                return
            }
        }
    }

    private suspend fun record(report: DeskReport) {
        try {
            sink.record(conversationId, report)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 兜底写失败只丢这一条,不能让投递协程停下、后面的汇报全丢。
        }
    }

    private fun phaseOf(state: TaskState): DeskTaskPhase = when (state) {
        TaskState.Idle -> DeskTaskPhase.Queued
        is TaskState.Running -> DeskTaskPhase.Running
        is TaskState.Held -> when {
            state.reason.queued -> DeskTaskPhase.Queued
            state.reason.needsYou -> DeskTaskPhase.NeedsYou
            else -> DeskTaskPhase.Running
        }
        is TaskState.WaitingInput, is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover -> DeskTaskPhase.NeedsYou
        is TaskState.Succeeded -> DeskTaskPhase.Done
        is TaskState.Failed -> DeskTaskPhase.Failed
        TaskState.Cancelled -> DeskTaskPhase.Cancelled
    }

    private fun lineOf(state: TaskState): String? = when (state) {
        is TaskState.Running -> deskLine(state.narration)
        is TaskState.Held -> if (state.reason.queued) null else deskLine(state.reason.why)
        is TaskState.WaitingInput -> deskLine(state.prompt)
        is TaskState.WaitingTakeover -> deskLine(state.message)
        is TaskState.Succeeded -> deskLine(state.answer)
        is TaskState.Failed -> deskLine(state.reason)
        else -> null
    }
}
