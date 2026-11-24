package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.chat.RetryAttachmentStatus
import com.wangzan101.mika.core.agent.DeskTurn
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.agent.UserWords
import com.wangzan101.mika.core.frontdesk.DESK_RESULT_MAX_CHARS
import com.wangzan101.mika.core.frontdesk.DeskBrain
import com.wangzan101.mika.core.frontdesk.DeskEvent
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.core.frontdesk.DeskSurface
import com.wangzan101.mika.core.frontdesk.DeskToolExecutor
import com.wangzan101.mika.core.frontdesk.DeskTools
import com.wangzan101.mika.core.frontdesk.DeskWords
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 聊天页接前台要的三样东西,由 app 装配,测试换成假的。 */
class DeskChatDeps(
    /** 这条会话常驻的前台;第一次要时会读会话记录,在 io 上调。 */
    val desk: (conversationId: String) -> FrontDesk,
    /** 聊天页用的前台工具执行器;[DeskWords] 由聊天页给(核对这段确实是用户在这条会话里打过的,交出所在的整句)。 */
    val executor: (conversationId: String, words: DeskWords) -> DeskToolExecutor,
    /**
     * 按此刻设置里的模型新造一个这条会话的文字前台;没配模型时为 null(发出去拦下,引导去模型设置)。
     * 每一轮(回话、汇报)都新造一个,所以设置里换了模型,下一句就用新的。
     */
    val brain: (conversationId: String) -> DeskBrain?,
    /** 给还没有标题、也还没存过一轮的会话按第一句话起标题(见 [com.wangzan101.mika.chat.ConversationTitler.ensure])。 */
    val title: suspend (conversationId: String, first: String) -> Unit = { _, _ -> },
)

/**
 * 聊天页和前台之间的一层,跟着屏上的会话:收这条会话的任务板(卡片和「在办」条看它);
 * 页面开着时接上这条会话的前台,后台的汇报由这里说;页面不在时不接,汇报交给兜底(写回会话、发通知),这里不另做节流。
 */
internal class ChatDesk(private val vm: ChatViewModel, private val session: ChatSession, private val deps: DeskChatDeps) {
    private val _ui = session._ui
    private val lines = HashMap<String, DeskChatOps>()
    private val shown = MutableStateFlow(false)
    private val children = ConcurrentHashMap<String, Boolean>()

    /** 聊天页在不在屏上(界面按生命周期报);默认不在,汇报都交给兜底。 */
    fun pageShown(value: Boolean) {
        shown.value = value
    }

    /** 页面开着,而且屏上就是这条会话。 */
    fun showing(conversationId: String) = shown.value && _ui.value.conversationId == conversationId

    /**
     * 这条会话由前台接。前台派出去的子任务的对话([com.wangzan101.mika.chat.ConversationStore.deskParent])不另起前台:
     * 在那里打的字照旧直接对它的任务说(在跑时插话、等回答时回答、空着时开新的一件)。还没有编号的新对话由前台接。
     * 是不是子对话在派出时就定了,记一次。
     */
    fun handles(conversationId: String?): Boolean =
        conversationId == null || !children.getOrPut(conversationId) { vm.store.deskParent(conversationId) != null }

    /** 这条会话的前台入口;同一条会话一直是同一个(它记着这条会话的前台说过的话)。 */
    fun line(conversationId: String): DeskChatOps = synchronized(lines) {
        lines.getOrPut(conversationId) { DeskChatOps(conversationId, vm, session, deps, this) }
    }

    /** 问题卡上点了一个选项:原话直接回给屏上这条会话派出的任务 [taskId],不经前台模型。 */
    fun answer(taskId: String, words: String) {
        val id = _ui.value.conversationId ?: return
        vm.scope.launch(vm.io) { deps.desk(id).answer(taskId, words) }
    }

    /** 进度条或问题卡上点了停止:停下屏上这条会话派出的任务 [taskId]。 */
    fun cancel(taskId: String) {
        val id = _ui.value.conversationId ?: return
        vm.scope.launch(vm.io) { deps.desk(id).cancel(taskId) }
    }

    fun start() {
        vm.scope.launch {
            combine(_ui.map { it.conversationId }.distinctUntilChanged(), shown) { id, s -> id to s }
                .distinctUntilChanged()
                .collectLatest { (id, s) -> if (id != null) follow(id, s) }
        }
        // 会话删掉了:它的前台入口一并丢掉(前台本身由前台登记处收掉,后台任务不碰)
        vm.scope.launch { vm.store.deletions.collect { id -> synchronized(lines) { lines.remove(id) }; children.remove(id) } }
    }

    private suspend fun follow(id: String, attach: Boolean) = coroutineScope {
        // 子任务的对话不造前台,也不接上
        if (!withContext(vm.io) { handles(id) }) return@coroutineScope
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
 * 前台交给后台的原话要核对:是用户在这条会话里说过的话(这一句或之前的,整句或其中一段)才认,交给后台的是那一整句;
 * 取消只认用户正在回的这一句。汇报的一轮不是用户开的口,前台的工具一概不认原话(只能把事说给用户听)。
 *
 * 前台跟着会话走:每一轮都按此刻的模型新造前台,用这条会话存下的轮次垫上(和通话打开时同一条路),
 * 所以通话里写进来的轮次、页面不在时兜底写下的汇报、设置里换的模型,下一句都已经算上;通话里说过的话也认作用户的原话。
 * 带附件的一句不经前台模型,直接派成一件新任务([sendFiles])。
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
    private var executor: DeskToolExecutor? = null

    /** 汇报一轮用的执行器:不认任何原话(见 [DeskWords.None])。 */
    private var reportExecutor: DeskToolExecutor? = null

    /** 这条会话里用户说过的话:存下的(每一轮前重读,包括通话里说的)和这次打开后发的。 */
    private val said = CopyOnWriteArrayList<String>()

    /** 前台正在回的这一句(用户自己说的部分);不在回话时为 null。取消只认它。 */
    @Volatile private var current: String? = null

    /** 起标题这件事这条入口做过没有。 */
    @Volatile private var named = false

    /** 聊天页给执行器的原话核对:落在用户说过的哪一句里(先看正在回的这一句,再从近往远),交出那一整句。 */
    private val words = object : DeskWords {
        override fun said(fragment: String): String? {
            val w = fragment.trim().ifEmpty { return null }
            current?.takeIf { w in it }?.let { return it }
            return said.lastOrNull { w in it }
        }

        override fun saidNow(fragment: String): String? {
            val w = fragment.trim().ifEmpty { return null }
            return current?.takeIf { w in it }
        }
    }

    /**
     * 把 [text](已带上引用)交给前台:马上加一行用户气泡和「在想」,回话在后台流进来。
     * 没配模型时不发,界面上拦下并引导去模型设置,返回 false。
     */
    fun send(text: String): Boolean {
        val brain = deps.brain(conversationId)
        if (brain == null) {
            _ui.update { it.copy(blocked = listOf(Problem.NoModel)) }
            return false
        }
        remember(text)
        name(text)
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
     * 带着附件发来的一句:[goal] 是 [text] 加上文件行,文件已在这条会话的工作区里(气泡和「重新问」看的是这里)。
     * 不经前台模型(前台的工具不收文件),直接派成一件新的后台任务:文件复制进它的子会话,卡片挂在这一轮下面。
     * 有没有任务在跑都一样,输入框不因此变忙。[title] 是卡片上的标题。
     */
    fun sendFiles(text: String, goal: String, files: List<Pair<String, Long>>, title: String) {
        val typed = remember(text)
        name(text)
        val runId = newRunId()
        val startedAt = vm.clock()
        val row = Exchange(goal, emptyList(), ExchangeStatus.Working, live = true, runId = runId, startedAt = startedAt, deskReply = "")
        _ui.update {
            if (it.conversationId != null && it.conversationId != conversationId) it
            else it.copy(conversationId = conversationId, exchanges = it.exchanges + row, blocked = null)
        }
        vm.scope.launch {
            val result = try {
                executor(words).dispatchWithFiles(typed, title) { child -> copyFiles(child, files)?.let { ChatReduce.withAttachments(text, it) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val status = result?.string("status")
            val taskId = result?.string("task_id")?.takeIf { status == "started" || status == "queued" }
            val failure = if (taskId == null) result?.string("reason") ?: status ?: "dispatch_error" else null
            val tasks = listOfNotNull(taskId)
            update(runId) { it.copy(deskTasks = tasks, status = failure?.let(ExchangeStatus::Failed) ?: ExchangeStatus.Done("", null)) }
            // 和前台的回话排在同一条线上落库,重读会话时先后不乱
            turns.withLock { persist(Turn(goal, "", DeskTurn.STATUS, note = failure, runId = runId, atMs = startedAt, deskTasks = tasks)) }
        }
    }

    /** 把这条会话工作区里的文件复制进子会话 [child] 的工作区;有一个没复制成就整件不派(返回 null)。 */
    private suspend fun copyFiles(child: String, files: List<Pair<String, Long>>): List<Pair<String, Long>>? {
        val workspace = vm.branchWorkspace ?: return null
        val copied = workspace.copyInboxForRetry(conversationId, child, files.map { it.first })
        if (copied.size != files.size || copied.any { it.status != RetryAttachmentStatus.COPIED || it.sizeBytes == null }) return null
        return copied.map { it.path to it.sizeBytes!! }
    }

    /**
     * 页面开着这条会话时说一句汇报。开始、排队、进度在进度条里实时看得到,要你处理在对话里有问题卡,都不另说一句;
     * 办好了把后台的完整结果原文写成一行汇报,不经前台模型转述;没办成由前台说(原因和下一步),写成一行汇报。
     * 页面不在、没配模型、前台什么也没说时返回 false(交给兜底);前台说失败时去掉这一行、抛出,也交给兜底。
     */
    override suspend fun deliver(report: DeskReport): Boolean {
        if (!page.showing(conversationId)) return false
        when (val event = report.event) {
            is DeskEvent.Started, is DeskEvent.Queued, is DeskEvent.Progress,
            is DeskEvent.NeedsAnswer, is DeskEvent.NeedsAction -> return true
            is DeskEvent.Succeeded -> return turns.withLock { result(event.taskId, DeskNotifier.rowText(report).orEmpty()) }
            else -> Unit
        }
        val brain = deps.brain(conversationId) ?: return false
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
                prepare(brain)
                brain.notice(noticeText(report), context(runId, reply, tasks, executor(DeskWords.None)))
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

    /** 办好了的结果原文写成一行汇报(页面换走了就不写,返回 false 交给兜底)。 */
    private suspend fun result(taskId: String, text: String): Boolean {
        if (!page.showing(conversationId) || text.isBlank()) return false
        val runId = newRunId()
        val at = vm.clock()
        _ui.update {
            if (it.conversationId != conversationId) it
            else it.copy(exchanges = it.exchanges + Exchange("", emptyList(), ExchangeStatus.Done(text, null), live = true, runId = runId,
                startedAt = at, deskReply = text, deskTasks = listOf(taskId)))
        }
        persist(Turn("", text, DeskTurn.REPORT_STATUS, runId = runId, atMs = at, deskTasks = listOf(taskId)))
        return true
    }

    private suspend fun answer(brain: DeskBrain, text: String, runId: String, startedAt: Long) {
        val reply = StringBuilder()
        val tasks = mutableListOf<String>()
        var failure: String? = null
        try {
            prepare(brain)
            current = UserWords.saidToDesk(text).ifBlank { null }
            brain.reply(text, context(runId, reply, tasks, executor(words)))
        } catch (e: CancellationException) {
            current = null
            // 派出去的任务得记进这一轮,下次打开会话才接得回来
            withContext(NonCancellable) { persist(Turn(text, reply.toString(), DeskTurn.STATUS, runId = runId, atMs = startedAt, deskTasks = tasks)) }
            throw e
        } catch (e: Exception) {
            failure = e.message ?: e::class.simpleName ?: "error"
        }
        current = null
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

    /**
     * 每一轮回话、汇报前:重读这条会话存下的轮次(别的入口写进来的也在),垫给这一轮新造的前台,记下其中用户说过的话。
     */
    private suspend fun prepare(brain: DeskBrain) {
        val prior = withContext(vm.io) { runCatching { vm.store.turns(conversationId) }.getOrDefault(emptyList()) }
        said.addAllAbsent(prior.map { UserWords.saidToDesk(it.userText) }.filter { it.isNotBlank() })
        brain.seed(prior)
    }

    /** 和前台共用账本的工具执行器:[check] 是用户开口的一轮用的 [words],或汇报一轮用的 [DeskWords.None];各建一次。 */
    private suspend fun executor(check: DeskWords): DeskToolExecutor {
        val report = check === DeskWords.None
        synchronized(this) { if (report) reportExecutor else executor }?.let { return it }
        val made = withContext(vm.io) { deps.executor(conversationId, check) }
        return synchronized(this) {
            if (report) reportExecutor ?: made.also { reportExecutor = it } else executor ?: made.also { executor = it }
        }
    }

    /**
     * 记下用户这句里他自己说的话([UserWords.saidToDesk]):引的 Mika 的回答、屏幕和页面的说明、附件行都不记,
     * 前台拿它们派单核对不过。返回记下的那份(可能为空)。
     */
    private fun remember(text: String): String =
        UserWords.saidToDesk(text).also { if (it.isNotBlank()) said += it }

    /** 给前台的一条汇报:带上任务编号(它要接着回话、补话时用),后面是这件任务这次的变化。 */
    private fun noticeText(report: DeskReport) =
        "task_id ${report.event.taskId} · " + tr(R.string.desk_report_line, report.title, DeskNotifier.line(report.event).take(DESK_RESULT_MAX_CHARS))

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

    /**
     * 这条入口发出的第一句:请模型给会话起标题(会话已有标题、已存过轮次的由 ensure 自己跳过)。
     * 和回话并行,这一轮落库前就已经查过「还没存过轮次」;只试一次,后面的话不再起。
     */
    private fun name(first: String) {
        if (named) return
        named = true
        vm.scope.launch(vm.io) {
            try {
                deps.title(conversationId, first)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 起不出标题就不记,界面照旧用第一句话
            }
        }
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
