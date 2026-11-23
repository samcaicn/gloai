package com.wangzan101.mika.task

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.mcp.McpTaskHost
import com.wangzan101.mika.core.mcp.McpTaskLink
import com.wangzan101.mika.core.remote.RemoteSteer
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.remote.RemoteTaskError
import com.wangzan101.mika.core.remote.RemoteTaskFiles
import com.wangzan101.mika.core.remote.RemoteTaskPage
import com.wangzan101.mika.core.remote.RemoteTaskService
import com.wangzan101.mika.core.remote.RemoteTasks
import com.wangzan101.mika.core.remote.RunRecordExport
import com.wangzan101.mika.core.remote.StartOutcome
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunEventBus
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunSpec
import org.json.JSONArray
import java.io.IOException
import java.util.UUID

/** 电脑任务的唯一索引。内部视图可维护全部收件;每个外部请求必须使用 [scope] 后的令牌归属视图。 */
class RemoteTaskHub(
    private val context: Context,
    private val runs: () -> List<TaskRun> = { (context.applicationContext as com.wangzan101.mika.MikaApp).taskState.runs.value },
    private val clock: () -> Long = System::currentTimeMillis,
) : McpTaskHost, RemoteTasks, RemoteRunIndex {
    private val receipts by lazy { RemoteTaskReceipts(context) }
    internal fun migrateUsbOwner(previousOwner: String) = receipts.migrateUsbOwner(previousOwner)
    private val buses = HashMap<RunId, RunEventBus>()
    /** 同一 request_id 的附件任务必须把查重和单次消费放在同一把稳定锁里;scope() 每次返回新包装,不能拿它当锁。 */
    private val fileStartLock = Any()
    private data class PendingEnd(val state: TaskState.Terminal, val steers: List<RemoteSteer>, val runId: String?, val files: List<SharedFile>)
    private val unsaved = mutableMapOf<String, PendingEnd>()

    override val fileAccess: RemoteTaskFiles by lazy { PhoneTaskFiles(context) { receipts.files(it) } }
    override fun scope(owner: String): RemoteTasks = OwnedHub(owner)
    override fun scopeIndex(owner: String): RemoteRunIndex = OwnedHub(owner)

    private inner class OwnedHub(private val owner: String) : RemoteTasks, RemoteRunIndex {
        override val fileAccess get() = this@RemoteTaskHub.fileAccess.scope(owner)
        override fun start(goal: String, conversation: String?, requestId: String?, attachments: List<String>) =
            startOwned(owner, goal, conversation, requestId, attachments)
        override fun get(id: String) = getOwned(owner, id)
        override fun list(limit: Int) = listOwned(owner, limit)
        override fun page(limit: Int, before: String?) = pageOwned(owner, limit, before)
        override fun answer(id: String, text: String) = getOwned(owner, id) != null && this@RemoteTaskHub.answer(id, text)
        override fun cancel(id: String) = getOwned(owner, id) != null && this@RemoteTaskHub.cancel(id)
        override fun steer(id: String, text: String) = if (getOwned(owner, id) == null) null else this@RemoteTaskHub.steer(id, text)
        override fun task(id: String) = getOwned(owner, id)
        override fun rememberedRunId(taskId: String) = rememberedRunOwned(owner, taskId)
        override fun taskForRun(runId: String) = taskForRunOwned(owner, runId)
    }

    @Synchronized override fun submit(spec: RunSpec): McpTaskLink {
        require(spec.provenance.origin == RunOrigin.Mcp)
        val thread = spec.threadId.takeIf(RemoteTaskService::isConversation)
        val bus = buses.getOrPut(spec.id) { RunEventBus() }
        try { launch(null, spec.id.value, spec.goal, thread, spec.provenance.attendance) }
        catch (e: Exception) { buses.remove(spec.id); throw e }
        return McpTaskLink(spec.id, bus) { cancel(spec.id.value) }
    }

    override fun start(goal: String, conversation: String?, requestId: String?, attachments: List<String>) =
        startOwned(null, goal, conversation, requestId, attachments)

    private fun startOwned(owner: String?, goal: String, conversation: String?, requestId: String?, attachments: List<String>): StartOutcome = try {
        val id = requestId ?: UUID.randomUUID().toString()
        val task = if (attachments.isEmpty()) launch(owner, id, goal, conversation, Attendance.Reachable)
            else startWithFiles(owner, id, goal, conversation, attachments)
        StartOutcome.Started(task)
    } catch (e: IOException) {
        throw e
    } catch (e: RemoteTaskError) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "could not start remote task", e)
        StartOutcome.Refused("Mika could not start the task on the phone. Check its status before trying again.")
    }

    private fun startWithFiles(owner: String?, id: String, goal: String, conversation: String?, attachments: List<String>): RemoteTask {
        val access = requireNotNull(if (owner == null) fileAccess else fileAccess.scope(owner))
        val request = JSONArray(listOf(goal) + attachments).toString()
        return synchronized(fileStartLock) {
            receipts.findFileRequest(id, conversation, request, owner)?.let { return synchronized(this) { current(it) } }
            val target = conversation ?: RemoteTaskService.newConversation()
            access.uploads.consume(attachments) { uploads ->
                access.start(target, goal, uploads) { withPaths ->
                    launch(owner, id, withPaths, conversation, Attendance.Reachable, request, target)
                }
            }
        }
    }

    @Synchronized private fun launch(owner: String?, id: String, goal: String, conversation: String?, attendance: Attendance,
        fileRequest: String? = null, target: String? = conversation): RemoteTask {
        if (owner != null && conversation != null && !receipts.conversationAvailable(conversation, owner))
            throw RemoteTaskError.General(RemoteTaskError.Code.NOT_FOUND, "Conversation not found")
        receipts.find(id, goal, conversation, fileRequest, owner)?.let { return current(it) }
        val task = RemoteTask(id, target ?: RemoteTaskService.newConversation(), goal, clock(), null, owner = owner)
        receipts.insert(task, newChat = conversation == null, fileRequest = fileRequest)
        try { TaskService.startMcp(context, goal, task.conversation, id, attendance) }
        catch (e: Exception) {
            receipts.finish(id, TaskState.Failed("Mika could not start the task on the phone.", FailKind.Environment), emptyList())
            throw e
        }
        return task
    }

    fun claimStart(id: String): Boolean = try {
        receipts.claimStart(id)
    } catch (e: SQLiteException) {
        Log.e(TAG, "Could not claim remote task", e)
        ended(id, TaskState.Failed("Mika could not record this task's start. Check phone storage.", FailKind.Environment), emptyList())
        false
    }

    @Synchronized fun forget(conversation: String) {
        val discarded = unsaved.keys.filter { receipts.find(it)?.conversation == conversation }
        receipts.forget(conversation)
        discarded.forEach(unsaved::remove)
    }

    @Synchronized override fun get(id: String): RemoteTask? = receipts.find(id)?.let(::current)
    private fun getOwned(owner: String, id: String): RemoteTask? = synchronized(this) { receipts.find(id, owner = owner)?.let(::current) }
    override fun list(limit: Int): List<RemoteTask> = synchronized(this) { receipts.recent(limit).map(::current) }
    private fun listOwned(owner: String, limit: Int) = synchronized(this) { receipts.recent(limit, owner).map(::current) }
    override fun page(limit: Int, before: String?): RemoteTaskPage = RemoteTaskPage(list(limit))
    private fun pageOwned(owner: String, limit: Int, before: String?) = synchronized(this) {
        receipts.page(limit, before, owner).let { RemoteTaskPage(it.tasks.map(::current), it.nextBefore) }
    }

    override fun answer(id: String, text: String): Boolean {
        val run = live(id) ?: return false
        if (run.state.value !is TaskState.WaitingInput) return false
        run.answer(text) {}
        return true
    }

    override fun cancel(id: String): Boolean {
        val task = get(id) ?: return false
        if (task.state is TaskState.Terminal || live(id) == null) return false
        TaskService.cancelMcp(context, id)
        return true
    }

    override fun steer(id: String, text: String): String? {
        if (get(id)?.state !is TaskState.Running) return null
        return live(id)?.steerRemote(text)
    }

    private fun live(id: String): TaskRun? = runs().firstOrNull { it.mcpExternalId == id }

    override fun task(id: String): RemoteTask? = get(id)
    override fun rememberedRunId(taskId: String): String? = receipts.runId(taskId) ?: live(taskId)?.runId?.value
    private fun rememberedRunOwned(owner: String, taskId: String) = receipts.runId(taskId, owner) ?: live(taskId)?.runId?.value
    override fun taskForRun(runId: String): RemoteTask? = taskForRunOwned(null, runId)

    private fun taskForRunOwned(owner: String?, runId: String): RemoteTask? {
        val stored = receipts.taskIdForRun(runId, owner)
        val id = stored ?: runs().firstOrNull { it.runId.value == runId }?.mcpExternalId ?: return null
        return try { if (owner == null) get(id) else getOwned(owner, id) }
        catch (e: RemoteTaskError) { if (e.code == RemoteTaskError.Code.GONE) null else throw e }
    }

    fun rememberRun(taskId: String, runId: String) {
        if (!RunRecordExport.RUN_ID.matches(runId)) return
        try { receipts.rememberRun(taskId, runId) }
        catch (e: SQLiteException) { Log.w(TAG, "could not remember run id", e) }
    }

    private fun current(task: RemoteTask): RemoteTask {
        unsaved[task.id]?.let { pending ->
            try {
                receipts.finish(task.id, pending.state, pending.steers, runId = pending.runId)
                receipts.saveFiles(task.id, pending.files)
            } catch (_: SQLiteException) {
                throw RemoteTaskError.General(RemoteTaskError.Code.UNAVAILABLE, "The task result could not be saved. Check phone storage and query this ID again.")
            }
            unsaved.remove(task.id)
            return checkNotNull(receipts.find(task.id))
        }
        val run = live(task.id)
        val withLive = task.copy(
            files = run?.sharedFiles?.value?.map { it.remoteFile() }?.takeIf { it.isNotEmpty() } ?: task.files,
            runId = task.runId ?: run?.runId?.value,
        )
        if (task.state != null) return withLive
        if (run == null && clock() - task.createdAtMs > LOST_MS) {
            val end = TaskState.Failed("The task never started on the phone.", FailKind.Environment)
            if (receipts.finish(task.id, end, emptyList(), onlyUnclaimed = true)) return withLive.copy(state = end)
        }
        val state = run?.state?.value
        return withLive.copy(state = if (state is TaskState.Terminal) TaskState.Running(0, "Finishing the task.") else state,
            steers = run?.let { remoteSteers(it.steers.value) }.orEmpty())
    }

    @Synchronized fun attach(id: String, source: RunEventBus): AutoCloseable? =
        buses[RunId(id)]?.let { sink -> source.subscribe(sink) }

    @Synchronized fun ended(id: String, end: TaskState, steers: List<SteerNote>, runId: String? = null, files: List<SharedFile> = emptyList()) {
        require(end is TaskState.Terminal)
        val result = unsaved[id] ?: PendingEnd(end, remoteSteers(steers, finished = true), runId, files)
        try {
            receipts.finish(id, result.state, result.steers, runId = result.runId)
            receipts.saveFiles(id, result.files)
            unsaved.remove(id)
        } catch (e: SQLiteException) {
            unsaved[id] = result
            Log.e(TAG, "Could not commit remote task result", e)
        }
        buses.remove(RunId(id))?.event(RunEvent.State(clock(), "mcp_task_ended", end::class.simpleName.orEmpty()))
    }

    private companion object {
        const val TAG = "MikaMcp"
        const val LOST_MS = 2 * 60_000L
    }
}
