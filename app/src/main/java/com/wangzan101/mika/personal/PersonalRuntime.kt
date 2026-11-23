package com.wangzan101.mika.personal

import android.graphics.Bitmap
import android.util.AtomicFile
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.capsule.previewFrames
import com.wangzan101.mika.capsule.sameFrame
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.learn.memory.*
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.personal.*
import com.wangzan101.mika.core.run.RunPhase
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.TaskRun
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class FilePersonalStore(file: File) : PersonalStore {
    private val atomic = AtomicFile(file)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(
        if (!file.exists() && !File(file.path + ".bak").exists()) PersonalState()
        else runCatching { decodePersonalState(json, atomic.openRead().bufferedReader().use { it.readText() }) }
            .getOrElse {
                // Preserve the corrupt input for recovery, never silently overwrite the only copy.
                file.copyTo(File(file.path + ".unreadable-" + System.currentTimeMillis()), overwrite = false)
                PersonalState(messages = listOf(PersonalMessage(UUID.randomUUID().toString(), "assistant",
                    "历史记录暂时无法读取，原文件已保留备份。可以继续聊；请保留备份以便恢复。", System.currentTimeMillis(), error = true, systemType = "history_unreadable")))
            }
    )
    override val state = _state.asStateFlow()

    @Synchronized override fun update(change: (PersonalState) -> PersonalState) {
        val next = change(_state.value)
        if (next == _state.value) return
        val stream = atomic.startWrite()
        try {
            stream.write(json.encodeToString(next).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
        _state.value = next
    }
}

internal data class PersonalPhonePreview(val conversationId: String, val frame: Bitmap)

/** Process-owned chat coordinator; execution/approvals/recovery remain with TaskService. */
class PersonalRuntime(private val app: MikaApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store = FilePersonalStore(File(app.filesDir, "personal/main-v1.json"))
    private val replays by lazy { PersonalReplayStore(File(app.filesDir, "personal/replays")) }
    private val actions = object : PersonalActions {
        override fun start(conversationId: String, goal: String) {
            app.conversations.setTitle(conversationId, goal.take(50))
            TaskService.start(app, goal, conversationId)
        }
        override fun revise(conversationId: String, instruction: String): String {
            val run = app.taskState.run(conversationId) ?: error("任务还未就绪或已经结束，请先查看任务详情。")
            require(run.state.value !is TaskState.Terminal) { "任务已结束，请明确交代新的任务。" }
            run.steer(instruction)
            return "补充已交给原任务。"
        }
        override fun cancel(conversationId: String): String {
            val run = app.taskState.run(conversationId) ?: throw PersonalSystemException("stop_not_ready", "任务还未就绪或已经结束，请查看任务详情。")
            TaskService.cancel(app, run.key)
            return "已请求停止，等待执行服务交代当前进展。"
        }
        override fun remember(text: String, quote: String): String {
            require(app.memory.settings().switches.value.useMemory) { "记忆已关闭，可在记忆与技能里开启。" }
            require(Regex("记住|记一下|记下来|别忘|remember", RegexOption.IGNORE_CASE).containsMatchIn(quote)) { "只有明确要求记住的内容才会存入长期记忆。" }
            val source = MemorySource("user_said", quote.take(4000), "personal-main", System.currentTimeMillis())
            val result = app.memory.store().add(MemoryKind.preference, text, emptyList(), source).getOrThrow()
            return "已保存：" + result.item.content
        }
        override fun memory(): String {
            if (!app.memory.settings().switches.value.useMemory) return "用户关闭了长期记忆。"
            return personalMemoryText(app.memory.store().items.value)
        }
    }
    val agent = PersonalAgent(store, scope, { app.tasks.modelFactory(app, ModelSession.of("personal-main")) }, actions)
    private val _error = MutableStateFlow<Throwable?>(null)
    val error = _error.asStateFlow()
    fun clearError() { _error.value = null }
    val state get() = agent.state

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun phonePreview(conversationIds: List<String>) = app.taskState.runs.flatMapLatest { runs ->
        val sources = conversationIds.mapNotNull { id ->
            runs.firstOrNull { it.conversationId == id }?.phoneFrames?.map { frame ->
                frame?.let { PersonalPhonePreview(id, it) }
            }
        }
        if (sources.isEmpty()) flowOf(null) else combine(sources) { previews -> previews.firstOrNull { it != null } }
    }

    internal suspend fun replayFrames(conversationId: String) = withContext(Dispatchers.IO) {
        replays.frames(conversationId)
    }

    init {
        scope.launch {
            while (isActive) {
                try { refresh() } catch (e: CancellationException) { throw e } catch (e: Exception) { android.util.Log.w("PersonalAgent", "task refresh failed", e) }
                delay(if (state.value.tasks.any { !it.terminal }) 900L else 5000L)
            }
        }
        scope.launch {
            val recordingJobs = mutableMapOf<String, Job>()
            val activeIds = state.map { snapshot ->
                snapshot.tasks.filterNot { it.terminal }.mapTo(mutableSetOf()) { it.conversationId }
            }.distinctUntilChanged()
            combine(app.taskState.runs, activeIds) { runs, ids ->
                runs.filter { it.conversationId in ids }
            }.distinctUntilChanged().collect { runs ->
                val keys = runs.mapTo(mutableSetOf()) { it.key }
                recordingJobs.keys.filterNot { it in keys }.forEach { recordingJobs.remove(it)?.cancelAndJoin() }
                runs.forEach { run -> recordingJobs.getOrPut(run.key) { scope.launch { record(run) } } }
            }
        }
    }

    private suspend fun record(run: TaskRun) {
        val id = run.conversationId
        replays.begin(id)
        try {
            run.phoneFrames.filterNotNull().previewFrames(intervalMs = 500, same = ::sameFrame).collect { frame ->
                if (run.takeoverStage.value != Takeover.Stage.HandedOver && run.phoneFrames.value != null && !frame.isRecycled) {
                    val bytes = ByteArrayOutputStream()
                    if (frame.compress(Bitmap.CompressFormat.JPEG, 55, bytes)) replays.append(id, bytes.toByteArray())
                }
            }
        } finally {
            replays.end(id)
        }
    }
    private val submit = Mutex()
    suspend fun send(text: String) = submit.withLock { withContext(Dispatchers.IO) { agent.send(text) } }
    fun stop(id: String) {
        scope.launch {
            runCatching {
                val task = state.value.tasks.firstOrNull { it.id == id }
                    ?: throw PersonalSystemException("stop_not_ready", "任务还未就绪或已经结束，请查看任务详情。")
                actions.cancel(task.conversationId)
            }.onFailure { _error.value = it }
        }
    }

    private fun refresh() {
        state.value.tasks.filter { it.needsRefresh }.forEach { task ->
            val turns = app.conversations.turns(task.conversationId)
            val turn = turns.lastOrNull()
            if (turn != null) {
                val status = when (turn.status) {
                    "cancelled" -> "cancelled"
                    "failed" -> "failed"
                    else -> if (turn.result == "partial") "partial" else if (turn.result == "not_possible") "failed" else "completed"
                }
                agent.updateTask(task.id, status, result = turn.outcome, resultSystemType = turn.outcomeSystemType)
            } else {
                val run = app.taskState.run(task.conversationId)
                val current = run?.state?.value
                val pair = when (current) {
                    is TaskState.Running -> "running" to (current.narration ?: current.reason ?: current.note)
                    is TaskState.WaitingInput -> "needs_you" to current.prompt
                    is TaskState.WaitingConfirmation -> "needs_you" to "需要你确认，点开任务处理"
                    is TaskState.WaitingTakeover -> "needs_you" to current.message
                    is TaskState.Held -> (if (current.reason.queued) "queued" else "paused") to current.reason.why
                    is TaskState.Terminal -> "finishing" to "正在保存结果"
                    else -> "queued" to "等待开始"
                }
                val persisted = app.runStore.all().lastOrNull { it.threadId == task.conversationId }
                if (run == null && persisted?.phase == RunPhase.Interrupted) {
                    agent.updateTask(task.id, "failed", result = "上次运行中断了。已保留聊天与任务记录，你可以重新交代这件事。", resultSystemType = "task_interrupted")
                } else if (run == null && persisted?.phase == RunPhase.Ended && persisted.reply != null) {
                    agent.updateTask(task.id, if (persisted.runSucceeded == true) "completed" else "failed", result = persisted.reply)
                } else agent.updateTask(task.id, pair.first, pair.second, detailSystemType = when (current) {
                    is TaskState.WaitingConfirmation -> "wait_confirmation"
                    is TaskState.Terminal -> "saving"
                    null, TaskState.Idle -> "waiting"
                    else -> null
                })
            }
        }
    }
}

/**
 * 给个人助理看的长期记忆:置顶在前、新的在前,最多 [PERSONAL_MEMORY_ITEMS] 条、[max] 个字。
 * 按整条装:下一条装不下就到此为止,不从中间截断。Mika 自己归纳出来的那几条不是用户说的,标记放在这一条的最前面——
 * 放在后面的话,正好被截到的那一条会只剩内容、丢了这唯一的来源说明。
 */
internal fun personalMemoryText(items: List<MemoryItem>, max: Int = PERSONAL_MEMORY_CHARS): String {
    val out = StringBuilder()
    for (m in items.filter { it.status == MemoryStatus.active }
        .sortedWith(compareByDescending<MemoryItem> { it.pinned }.thenByDescending { it.updatedAt }).take(PERSONAL_MEMORY_ITEMS)) {
        val line = if (m.source.type == MemoryContext.INFERRED) PERSONAL_INFERRED_MARK + m.content else m.content
        if (out.length + line.length + 1 > max) break
        if (out.isNotEmpty()) out.append('\n')
        out.append(line)
    }
    return out.toString()
}

internal const val PERSONAL_INFERRED_MARK = "(Mika 自己归纳的,不是用户说的)"
private const val PERSONAL_MEMORY_ITEMS = 40
private const val PERSONAL_MEMORY_CHARS = 6500
