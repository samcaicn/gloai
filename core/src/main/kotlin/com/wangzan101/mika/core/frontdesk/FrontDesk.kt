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

/** 在做、要你处理的任务最多记这么多条最近进展。 */
const val DESK_RECENT_LINES = 3

/** 一件任务的全程最多记这么多步(进度条展开时看的),更早的丢掉。 */
const val DESK_STEPS_MAX = 60

/** 一件任务走过的一步:[text] 已剥细节、截短,[atMs] 是第一次看到这一步的时刻。 */
data class DeskStep(val text: String, val atMs: Long)

/**
 * 一件任务此刻在等用户做的事:[action] 为 null 时是在问一个问题([prompt],[options] 非空时是可选的几项);
 * 否则是要用户动手(确认、接管、让前置条件满足),[prompt] 是任务给的说明(可能没有)。[prompt] 是原文,不剥不截。
 */
data class DeskNeed(val prompt: String?, val options: List<String>? = null, val action: ActionKind? = null)

/**
 * 前台列出的一件任务:[line] 是当前一句话(已剥细节),[position] 是排队时排第几(从 1 起);
 * [recent] 是在做、要你处理时最近几条不同的进展(已剥细节,最新的在最后,最多 [DESK_RECENT_LINES] 条)。
 * [goal] 是交给后台的原话;[startedAt] 是开始做的时刻(排队时为 null);[steps] 是到此刻走过的每一步
 * (最多 [DESK_STEPS_MAX] 步,最新的在最后);[need] 是要你处理时在等你做的事;[updatedAt] 是最近一次有变化的时刻。
 */
data class DeskTaskView(
    val taskId: String,
    val title: String,
    val state: DeskTaskPhase,
    val line: String?,
    val position: Int?,
    val recent: List<String> = emptyList(),
    val goal: String? = null,
    val startedAt: Long? = null,
    val steps: List<DeskStep> = emptyList(),
    val need: DeskNeed? = null,
    val updatedAt: Long = 0L,
)

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
    /** 记每一步时刻用的钟;测试换成假的。 */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val reports = Channel<DeskReport>(Channel.UNLIMITED)
    private val tracked = HashSet<String>()
    private val views = MutableStateFlow<List<DeskTaskView>>(emptyList())

    private val current = MutableStateFlow<DeskSurface?>(null)
    private val surface: DeskSurface? get() = current.value

    /** 此刻接着的入口;入口断开后为 null。别的入口可据此在空出来时接上,不去抢正在用的(比如通话)。 */
    val attached: StateFlow<DeskSurface?> = current.asStateFlow()

    /** 这次对话前台派过的任务及各自阶段,按派单先后;收尾了的留在列表里,标成对应终态。 */
    val active: StateFlow<List<DeskTaskView>> = views.asStateFlow()

    init {
        scope.launch { for (report in reports) deliver(report) }
    }

    /** 接上一个入口(替换之前接着的);关掉返回的句柄即断开,之后的汇报交给 sink。 */
    fun attach(surface: DeskSurface): AutoCloseable {
        current.value = surface
        return AutoCloseable { current.compareAndSet(surface, null) }
    }

    /** 开始跟一件刚派出的任务(派单时账本已由派单方记好);同一件只跟一次,不认识的编号忽略。 */
    fun track(taskId: String, title: String) {
        follow(taskId, title, report = true)
    }

    /** 这件任务在不在这个前台的跟进里(派出后交给它跟,或重开会话时接回过)。 */
    fun tracks(taskId: String): Boolean = synchronized(tracked) { taskId in tracked }

    /**
     * 用户在界面上直接回答这件任务在问的问题(点了问题卡上的一个选项);不归这个前台跟的任务不认。
     * 返回任务收下了没有。
     */
    fun answer(taskId: String, words: String): Boolean = tracks(taskId) && tasks.answer(taskId, words)

    /** 用户在界面上点了停止这件任务;不归这个前台跟的任务不认。返回停下了没有。 */
    fun cancel(taskId: String): Boolean = tracks(taskId) && tasks.cancel(taskId)

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
        val now = clock()
        views.update { list ->
            // 刚派出、任务服务还没接手的(Idle):不在开始队列里就是正在开始,不当成排队(否则卡片先闪「排队中」,前台也照着说还在排队)
            val phase = if (state == TaskState.Idle && tasks.queuePosition(taskId) == null) DeskTaskPhase.Running else deskPhaseOf(state)
            val line = lineOf(state)
            val old = list.firstOrNull { it.taskId == taskId }
            val before = old?.recent.orEmpty()
            val moving = phase == DeskTaskPhase.Running || phase == DeskTaskPhase.NeedsYou
            val recent = if (line != null && moving) {
                // 重复出现的一句挪到最后,不重复记。
                (before - line + line).takeLast(DESK_RECENT_LINES)
            } else {
                before
            }
            // 全程:和上一步说的不一样才算新的一步
            val steps = old?.steps.orEmpty().let { s ->
                if (line != null && moving && s.lastOrNull()?.text != line) (s + DeskStep(line, now)).takeLast(DESK_STEPS_MAX) else s
            }
            val startedAt = old?.startedAt ?: now.takeIf { phase != DeskTaskPhase.Queued }
            val changed = old == null || old.state != phase || old.line != line
            val view = DeskTaskView(
                taskId, title, phase, line, null, recent,
                goal = old?.goal ?: ledger.get(taskId)?.words?.ifBlank { null },
                startedAt = startedAt, steps = steps, need = needOf(state),
                updatedAt = if (changed) now else old!!.updatedAt,
            )
            val replaced = if (old != null) list.map { if (it.taskId == taskId) view else it } else list + view
            // 一件任务开始会让后面排队的往前挪,排队位置每次都整体重取。
            replaced.map { it.copy(position = if (it.state == DeskTaskPhase.Queued) tasks.queuePosition(it.taskId) else null) }
        }
    }

    /** 要你处理时在等你做的事;不在等你时为 null。说明是原文(问题卡上要看全,引号里的选项、名字都留着)。 */
    private fun needOf(state: TaskState): DeskNeed? = when (state) {
        is TaskState.WaitingInput -> DeskNeed(state.prompt.trim().ifBlank { null }, state.options?.takeIf { it.isNotEmpty() })
        is TaskState.WaitingConfirmation -> DeskNeed(null, action = ActionKind.Confirm)
        is TaskState.WaitingTakeover -> DeskNeed(state.message.trim().ifBlank { null }, action = ActionKind.Takeover)
        is TaskState.Held -> if (state.reason.needsYou) DeskNeed(state.reason.why.trim().ifBlank { null }, action = ActionKind.Prerequisite) else null
        else -> null
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

/** 任务此刻在前台眼里是哪一种(在做、排队、要你处理、做完、没做成、取消);任务板和电话前台共用这一套。 */
fun deskPhaseOf(state: TaskState): DeskTaskPhase = when (state) {
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
