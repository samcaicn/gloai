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
import com.wangzan101.mika.core.frontdesk.FrontDesk
import com.wangzan101.mika.core.frontdesk.ReportPolicy
import com.wangzan101.mika.core.frontdesk.deskBackground
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.ParallelCapability
import com.wangzan101.mika.task.TaskRun
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
            { parallelLimit(app) },
            scope,
            { tr(R.string.desk_task_not_started) },
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
     * [verifyWords] 由入口给(核对这句话确实是用户在这个入口说过的)。
     */
    fun executor(conversationId: String, verifyWords: (String) -> Boolean): DeskToolExecutor {
        val entry = entry(conversationId)
        return DeskToolExecutor(tasks, entry.ledger, verifyWords, clock, conversationId) { entry.desk.track(it.taskId, it.title) }
    }

    /** 每次调模型前给前台备的背景:这条会话的任务板和这句话的相关记忆。 */
    fun background(conversationId: String): DeskBackground =
        deskBackground({ desk(conversationId).active.value }, memory)

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
            Desk(FrontDesk(conversationId, deskScope, tasks, ledger, ReportPolicy(clock), sink), ledger, deskScope)
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
            val title = (conversations.title(child) ?: words.ifBlank { parentTurn.userText }).take(DESK_LINE_MAX_CHARS)
            DeskTaskEntry(child, title, words, first?.atMs ?: parentTurn.atMs ?: 0L)
        }
    }

    private companion object {
        /** 和任务服务的并行上限同一套规则:开关关着一次一个,开着按设备能力。 */
        fun parallelLimit(app: MikaApp): Int {
            if (!FeatureFlags.get(app).speedFlags().parallelTasks) return 1
            val lowRam = app.getSystemService(android.app.ActivityManager::class.java).isLowRamDevice
            return ParallelCapability.detect(android.os.Build.VERSION.SDK_INT, lowRam, FeatureFlags.get(app).parallelMax(),
                app.taskState.secondDisplayFailed).max
        }
    }
}

/** 任务服务里的一件任务,给前台端口用。 */
private class TaskRunDesk(private val app: MikaApp, private val run: TaskRun) : DeskRun {
    override val conversationId: String get() = run.conversationId
    override val state: StateFlow<TaskState> get() = run.state
    override fun steer(words: String): Boolean = run.steerRemote(words) != null
    override fun answer(words: String) = TaskService.answer(app, words, run.key)
    override fun cancel() = TaskService.cancel(app, run.key)
}
