package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.DeskTurn
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.frontdesk.DeskBrain
import com.wangzan101.mika.core.frontdesk.DeskEvent
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.core.frontdesk.DeskSurface
import com.wangzan101.mika.core.frontdesk.DeskToolExecutor
import com.wangzan101.mika.core.frontdesk.DeskTools
import com.wangzan101.mika.core.frontdesk.FrontDesk
import com.wangzan101.mika.core.frontdesk.ReplyContext
import com.wangzan101.mika.frontdesk.DeskNotifier
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.Problem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** 聊天页接前台要的三样东西,由 app 装配,测试换成假的。 */
class DeskChatDeps(
    /** 这条会话常驻的前台;第一次要时会读会话记录,在 io 上调。 */
    val desk: (conversationId: String) -> FrontDesk,
    /** 聊天页用的前台工具执行器;[verifyWords] 由聊天页给(核对这句话确实是用户在这条会话里打过的)。 */
    val executor: (conversationId: String, verifyWords: (String) -> Boolean) -> DeskToolExecutor,
    /** 这条会话的文字前台;没配模型时为 null(发出去拦下,引导去模型设置)。 */
    val brain: (conversationId: String) -> DeskBrain?,
)

/**
 * 聊天页和前台之间的一层,跟着屏上的会话:收这条会话的任务板(卡片和「在办」条看它);
 * 页面开着时接上这条会话的前台,后台的汇报由这里说;页面不在时不接,汇报交给兜底(写回会话、发通知),这里不另做节流。
 */
internal class ChatDesk(private val vm: ChatViewModel, private val session: ChatSession, private val deps: DeskChatDeps) {
    private val _ui = session._ui
    private val lines = HashMap<String, DeskChatOps>()
    private val shown = MutableStateFlow(false)

    /** 聊天页在不在屏上(界面按生命周期报);默认不在,汇报都交给兜底。 */
    fun pageShown(value: Boolean) {
        shown.value = value
    }

    /** 页面开着,而且屏上就是这条会话。 */
    fun showing(conversationId: String) = shown.value && _ui.value.conversationId == conversationId

    /** 这条会话的前台入口;同一条会话一直是同一个(它记着这条会话的前台说过的话)。 */
    fun line(conversationId: String): DeskChatOps = synchronized(lines) {
        lines.getOrPut(conversationId) { DeskChatOps(conversationId, vm, session, deps, this) }
    }

    fun start() {
        vm.scope.launch {
            combine(_ui.map { it.conversationId }.distinctUntilChanged(), shown) { id, s -> id to s }
                .distinctUntilChanged()
                .collectLatest { (id, s) -> if (id != null) follow(id, s) }
        }
        // 会话删掉了:它的前台入口一并丢掉(前台本身由前台登记处收掉,后台任务不碰)
        vm.scope.launch { vm.store.deletions.collect { id -> synchronized(lines) { lines.remove(id) } } }
    }

    private suspend fun follow(id: String, attach: Boolean) = coroutineScope {
        val desk = withContext(vm.io) { deps.desk(id) }
        val surface = line(id)
        var handle: AutoCloseable? = null
        try {
            // 页面开着才接上前台;别的入口(比如通话)正接着时不抢,它断开、空出来了再接回
            if (attach) launch { desk.attached.collect { if (it == null) handle = desk.attach(surface) } }
            // 和界面状态一起看:换对话重建界面时任务板清空了,编号没变也要补回去
            combine(desk.active, _ui) { views, _ -> views }.collect { views ->
                _ui.update { if (it.conversationId == id && it.deskTasks != views) it.copy(deskTasks = views) else it }
            }
        } finally {
            handle?.close()
        }
    }
}

/**
 * 聊天页里一条会话的前台:用户打的字交给这条会话的文字前台,它的回话一段一段流进那一问一答([Exchange.deskReply]),
 * 派出的任务挂成同一轮下面的卡片([Exchange.deskTasks]);每一轮存成 `status = desk` 的一轮,带这一轮派出的任务。
 * 后台有该说的变化、页面正开着这条会话时,由前台说一句,存成一行 `desk_report`;页面不在时返回 false,交给兜底。
 *
 * 回话和汇报一条一条来(前一句回完才回下一句):前台还在回的时候输入框照样能发,发出去的排在后面,派单不会因为抢话丢掉。
 * 前台交给后台的原话要核对:是用户在这条会话里打过的字(这一句或之前的,整句或其中一段)才认。
 */
internal class DeskChatOps(
    private val conversationId: String,
    private val vm: ChatViewModel,
    session: ChatSession,
    private val deps: DeskChatDeps,
    private val page: ChatDesk,
) : DeskSurface {
    private val _ui = session._ui
    private val turns = Mutex()
    private var brain: DeskBrain? = null
    private var executor: DeskToolExecutor? = null

    /** 这条会话里用户说过的话:之前存下的(第一次回话前读进来)和这次打开后发的。 */
    private val said = CopyOnWriteArrayList<String>()

    /**
     * 把 [text](已带上引用)交给前台:马上加一行用户气泡和「在想」,回话在后台流进来。
     * 没配模型时不发,界面上拦下并引导去模型设置,返回 false。
     */
    fun send(text: String): Boolean {
        val brain = brain()
        if (brain == null) {
            _ui.update { it.copy(blocked = listOf(Problem.NoModel)) }
            return false
        }
        said += text
        val runId = newRunId()
        val startedAt = vm.clock()
        val row = Exchange(text, emptyList(), ExchangeStatus.Working, live = true, runId = runId, startedAt = startedAt, deskReply = "")
        _ui.update {
            if (it.conversationId != null && it.conversationId != conversationId) it
            else it.copy(conversationId = conversationId, exchanges = it.exchanges + row, blocked = null)
        }
        vm.scope.launch { turns.withLock { answer(brain, text, runId, startedAt) } }
        return true
    }

    /**
     * 页面开着这条会话时说一句汇报。开始、排队、进度卡片上和「在办」条里实时看得到,不另说一句;
     * 要你处理、办好、没办成由前台说,写成一行汇报。页面不在、没配模型、前台什么也没说时返回 false(交给兜底);
     * 前台说失败时去掉这一行、抛出,也交给兜底。
     */
    override suspend fun deliver(report: DeskReport): Boolean {
        if (!page.showing(conversationId)) return false
        when (report.event) {
            is DeskEvent.Started, is DeskEvent.Queued, is DeskEvent.Progress -> return true
            else -> Unit
        }
        val brain = brain() ?: return false
        return turns.withLock {
            if (!page.showing(conversationId)) return@withLock false
            val taskId = report.event.taskId
            val runId = newRunId()
            val startedAt = vm.clock()
            _ui.update {
                if (it.conversationId != conversationId) it
                else it.copy(exchanges = it.exchanges + Exchange("", emptyList(), ExchangeStatus.Working, live = true, runId = runId,
                    startedAt = startedAt, deskReply = "", deskTasks = listOf(taskId)))
            }
            val reply = StringBuilder()
            val tasks = mutableListOf(taskId)
            try {
                brain.notice(noticeText(report), context(runId, reply, tasks, prepare(brain)))
            } catch (e: Throwable) {
                remove(runId)
                throw e
            }
            val text = reply.toString()
            if (text.isBlank() && tasks.size == 1) {
                remove(runId)
                return@withLock false
            }
            update(runId) { it.copy(deskReply = text, status = ExchangeStatus.Done(text, null)) }
            persist(Turn("", text, DeskTurn.REPORT_STATUS, runId = runId, atMs = startedAt, deskTasks = tasks))
            true
        }
    }

    private suspend fun answer(brain: DeskBrain, text: String, runId: String, startedAt: Long) {
        val reply = StringBuilder()
        val tasks = mutableListOf<String>()
        var failure: String? = null
        try {
            brain.reply(text, context(runId, reply, tasks, prepare(brain)))
        } catch (e: CancellationException) {
            // 派出去的任务得记进这一轮,下次打开会话才接得回来
            withContext(NonCancellable) { persist(Turn(text, reply.toString(), DeskTurn.STATUS, runId = runId, atMs = startedAt, deskTasks = tasks)) }
            throw e
        } catch (e: Exception) {
            failure = e.message ?: e::class.simpleName ?: "error"
        }
        val spoken = reply.toString()
        update(runId) {
            it.copy(deskReply = spoken, status = failure?.let(ExchangeStatus::Failed) ?: ExchangeStatus.Done(spoken, null))
        }
        persist(Turn(text, spoken, DeskTurn.STATUS, note = failure, runId = runId, atMs = startedAt, deskTasks = tasks))
    }

    private fun context(runId: String, reply: StringBuilder, tasks: MutableList<String>, exec: DeskToolExecutor) = object : ReplyContext {
        override suspend fun say(delta: String) {
            reply.append(delta)
            val now = reply.toString()
            update(runId) { it.copy(deskReply = now) }
        }

        override suspend fun callTool(name: String, arguments: JsonObject): JsonObject {
            val result = exec.execute(name, arguments)
            val status = result.string("status")
            val taskId = result.string("task_id")
            if (name == DeskTools.DISPATCH && taskId != null && (status == "started" || status == "queued")) {
                tasks += taskId
                update(runId) { it.copy(deskTasks = it.deskTasks + taskId) }
            } else if (name != DeskTools.DISPATCH && status == "ok") {
                toolLabel(name, arguments.string("task_id"))?.let { label -> update(runId) { it.copy(deskTool = label) } }
            }
            return result
        }
    }

    /** 第一次回话前:读这条会话之前的轮次(喂给前台、记下用户说过的话),拿到和前台共用账本的工具执行器。 */
    private suspend fun prepare(brain: DeskBrain): DeskToolExecutor {
        executor?.let { return it }
        val prior = withContext(vm.io) { runCatching { vm.store.turns(conversationId) }.getOrDefault(emptyList()) }
        said.addAll(0, prior.map { it.userText }.filter { it.isNotBlank() })
        brain.seed(prior)
        return withContext(vm.io) { deps.executor(conversationId, ::verified) }.also { executor = it }
    }

    /** 前台交给后台的原话得是用户在这条会话里打过的字(整句或其中一段),前台自己编的不认。 */
    private fun verified(words: String): Boolean {
        val w = words.trim()
        return w.isNotEmpty() && said.any { w in it }
    }

    @Synchronized
    private fun brain(): DeskBrain? = brain ?: deps.brain(conversationId)?.also { brain = it }

    /** 给前台的一条汇报:带上任务编号(它要接着回话、补话时用),后面是这件任务这次的变化。 */
    private fun noticeText(report: DeskReport) =
        "task_id ${report.event.taskId} · " + tr(R.string.desk_report_line, report.title, DeskNotifier.line(report.event))

    /** 前台给已有任务补话、回话、取消后,那一行上的小标签。 */
    private fun toolLabel(name: String, taskId: String?): String? {
        val title = _ui.value.deskTasks.firstOrNull { it.taskId == taskId }?.title.orEmpty()
        return when (name) {
            DeskTools.ADD -> tr(R.string.desk_tool_added, title)
            DeskTools.ANSWER -> tr(R.string.desk_tool_answered, title)
            DeskTools.CANCEL -> tr(R.string.desk_tool_cancelled, title)
            else -> null
        }
    }

    private fun update(runId: String, change: (Exchange) -> Exchange) = _ui.update { ui ->
        if (ui.conversationId != conversationId) ui
        else ui.copy(exchanges = ui.exchanges.map { if (it.runId == runId) change(it) else it })
    }

    private fun remove(runId: String) = _ui.update { ui ->
        if (ui.conversationId != conversationId) ui else ui.copy(exchanges = ui.exchanges.filterNot { it.runId == runId })
    }

    private suspend fun persist(turn: Turn) {
        withContext(vm.io) { runCatching { vm.store.append(conversationId, turn) } }
    }

    private fun newRunId() = DeskTurn.RUN_PREFIX + UUID.randomUUID()

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

    companion object {
        /** 前台那一轮的编号前缀(见 [DeskTurn.RUN_PREFIX]):只用来认这一轮,没有运行记录可看。 */
        const val RUN_PREFIX = DeskTurn.RUN_PREFIX
    }
}
