package com.wangzan101.mika.frontdesk

import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.frontdesk.DESK_LINE_MAX_CHARS
import com.wangzan101.mika.core.frontdesk.DeskBackground
import com.wangzan101.mika.core.frontdesk.DeskLedger
import com.wangzan101.mika.core.frontdesk.DeskSink
import com.wangzan101.mika.core.frontdesk.DeskTaskEntry
import com.wangzan101.mika.core.frontdesk.DeskTaskPort
import com.wangzan101.mika.core.frontdesk.DeskToolExecutor
import com.wangzan101.mika.core.frontdesk.DeskWords
import com.wangzan101.mika.core.frontdesk.FrontDesk
import com.wangzan101.mika.core.frontdesk.ReportPolicy
import com.wangzan101.mika.core.frontdesk.deskBackground
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.task.TaskRun
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * 每条会话一个常驻前台,按会话编号懒建:建的时候从会话里记下的 `deskTasks` 接回之前派过的任务。
 * 各入口(聊天页、语音通话……)从这里拿同一个前台、它的工具执行器和每次调模型前的背景;会话删掉后它的前台一并收掉。
 * 每个前台在自己的 [SupervisorJob] 作用域里跑,一个前台的协程出错不影响别的。
 */
class DeskRegistry(
    private val conversations: ConversationStore,
    private val tasks: DeskTaskPort,
    private val sink: DeskSink,
    private val memory: suspend (String) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    context: CoroutineContext = Dispatchers.Default,
) {
    constructor(app: MikaApp) : this(app, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private constructor(app: MikaApp, scope: CoroutineScope) : this(
        app.conversations,
        AppDeskTaskPort(
            app.conversations,
            app.taskState.runs.map { list -> list.map { TaskRunDesk(app, it) } },
            { app.taskState.runs.value.map { TaskRunDesk(app, it) } },
            { words, child, brief -> TaskService.start(app, words, child, brief = brief) },
            // 任务服务每次决定开始还是排队时算出并发布的上限,两边看的是同一个数。
            { app.taskState.parallelLimit.value.max },
            scope,
            { tr(R.string.desk_task_not_started) },
            { tr(R.string.task_interrupted) },
        ),
        DeskNotifier(app.conversations) { DeskNotifier.post(app, it) },
        AppDeskMemory(
            { app.memory.settings().switches.value.useMemory },
            { app.memory.store().let { it.reloadIfNeeded(); it.items.value } },
        )::recall,
    )

    private val root = SupervisorJob()
    private val scope = CoroutineScope(root + context)

    private class Desk(val desk: FrontDesk, val ledger: DeskLedger, val scope: CoroutineScope)

    private val desks = HashMap<String, Desk>()

    init {
        scope.launch { conversations.deletions.collect(::forget) }
    }

    /** 这条会话的前台;第一次要时建,并接回会话里记着的任务。 */
    fun desk(conversationId: String): FrontDesk = entry(conversationId).desk

    /**
     * 给一个入口用的前台工具执行器:和前台共用账本,派出的任务交给前台跟进。
     * [words] 由入口给(核对这段确实是用户在这个入口说过的,交出所在的整句)。
     */
    fun executor(conversationId: String, words: DeskWords): DeskToolExecutor {
        val entry = entry(conversationId)
        return DeskToolExecutor(tasks, entry.ledger, words, clock, conversationId) { entry.desk.track(it.taskId, it.title) }
    }

    /** 每次调模型前给前台备的背景:这条会话的任务板和这句话的相关记忆。 */
    fun background(conversationId: String): DeskBackground =
        deskBackground({ desk(conversationId).active.value }, memory)

    /**
     * 运行 [runKey] 以 [end] 收尾时,是不是有一个前台此刻正跟着这次运行、会把这个结局说出来(页面开着在聊天里说,
     * 不在时兜底发一条通知)。是的话前台就是唯一的声音,任务不再自己发「任务完成」。
     * 不算的:子会话里直接开的另一次运行(端口跟的不是它)、前台会话已删(前台收掉了)、进程重启后没接回、
     * 用户自己取消的(前台不说)、还没收尾的。
     */
    fun reportsEnding(runKey: String, end: TaskState): Boolean {
        if (end !is TaskState.Terminal || !ReportPolicy.saysEnding(end)) return false
        val taskId = tasks.followedTaskOf(runKey) ?: return false
        return synchronized(desks) { desks.values.any { it.desk.tracks(taskId) } }
    }

    /** 会话删掉了:收掉它的前台(不碰后台任务)。 */
    fun forget(conversationId: String) {
        val removed = synchronized(desks) { desks.remove(conversationId) } ?: return
        removed.scope.cancel()
    }

    private fun entry(conversationId: String): Desk {
        synchronized(desks) { desks[conversationId]?.let { return it } }
        val restored = restoredEntries(conversationId)
        val entry = synchronized(desks) {
            // 两个入口同时来要时只留先放进去的那个。
            desks[conversationId]?.let { return it }
            val deskScope = CoroutineScope(scope.coroutineContext + SupervisorJob(root))
            val ledger = DeskLedger()
            Desk(FrontDesk(conversationId, deskScope, tasks, ledger, ReportPolicy(clock), sink, clock), ledger, deskScope)
                .also { desks[conversationId] = it }
        }
        // 接回要读子会话,不占这把锁;接回本身可以重复做,先拿到前台的入口不受影响。
        entry.desk.restore(restored)
        return entry
    }

    /**
     * 会话里记着的、前台派过的任务(按第一次出现的先后)。标题取子会话的标题,没有就用子会话第一句话,
     * 再没有用派单那一轮用户说的话;原话取子会话第一句话。
     */
    private fun restoredEntries(conversationId: String): List<DeskTaskEntry> {
        val firstSeen = LinkedHashMap<String, com.wangzan101.mika.core.agent.Turn>()
        for (turn in conversations.turns(conversationId)) for (id in turn.deskTasks) firstSeen.putIfAbsent(id, turn)
        return firstSeen.map { (child, parentTurn) ->
            val first = conversations.turns(child).firstOrNull()
            val words = first?.userText.orEmpty()
            val title = (conversations.title(child) ?: words.ifBlank { parentTurn.userText }).takeWhole(DESK_LINE_MAX_CHARS)
            DeskTaskEntry(child, title, words, first?.atMs ?: parentTurn.atMs ?: 0L)
        }
    }

}

/**
 * 给在跑的任务补一句话,走本机插话:收件箱收下(下一步就带上)或转成任务收尾后的下一件事都算收下,
 * 任务已停、这句作废或根本没记下才算没收下。不走远程追加:那条要求任务带 MCP 外部编号,前台派出的子任务没有。
 */
internal fun steerLocally(words: String, steer: (text: String, messageId: String) -> Unit, notes: () -> List<SteerNote>): Boolean {
    val id = UUID.randomUUID().toString()
    steer(words, id)
    return when (notes().firstOrNull { it.id == id }?.state) {
        null, SteerState.Cancelled, SteerState.Withdrawn -> false
        else -> true
    }
}

/** 任务服务里的一件任务,给前台端口用。 */
private class TaskRunDesk(private val app: MikaApp, private val run: TaskRun) : DeskRun {
    override val conversationId: String get() = run.conversationId
    override val key: String get() = run.key
    override val state: StateFlow<TaskState> get() = run.state
    override fun steer(words: String): Boolean = steerLocally(words, run::steer) { run.steers.value }
    override fun answer(words: String) = TaskService.answer(app, words, run.key)
    override fun cancel() = TaskService.cancel(app, run.key)
}
