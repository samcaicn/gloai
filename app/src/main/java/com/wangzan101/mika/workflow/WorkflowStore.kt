package com.wangzan101.mika.workflow

import com.wangzan101.mika.settings.KeyCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable data class WorkflowStep(val id: String = UUID.randomUUID().toString(), val title: String, val instruction: String)
@Serializable data class WorkflowTemplate(val id: String, val revision: Int, val title: String, val steps: List<WorkflowStep>)
@Serializable enum class WorkflowPhase { Active, Pausing, Paused, Review, Completed }
@Serializable enum class AttemptPhase { Dispatching, Running, Done, Review }
@Serializable data class StepAttempt(
    val id: String, val conversationId: String, val phase: AttemptPhase,
    val result: String = "", val problem: String = "", val manual: Boolean = false,
)
@Serializable data class WorkflowRun(
    val id: String, val template: WorkflowTemplate, val input: String, val createdAt: Long,
    val phase: WorkflowPhase = WorkflowPhase.Active,
    val attempts: List<List<StepAttempt>> = template.steps.map { emptyList() },
) {
    val completed: Int get() = attempts.takeWhile { it.lastOrNull()?.phase == AttemptPhase.Done }.size
    val current: StepAttempt? get() = attempts.getOrNull(completed)?.lastOrNull()
    val result: String get() = if (phase == WorkflowPhase.Completed) attempts.last().last().result else ""
}
@Serializable data class WorkflowData(val templates: List<WorkflowTemplate> = emptyList(), val runs: List<WorkflowRun> = emptyList())
enum class WorkflowProblem { Invalid, Full, Changed, Busy, Read, Write, ResultTooLong }
class WorkflowException(val problem: WorkflowProblem) : Exception(problem.name)
data class WorkflowDispatch(val runId: String, val attemptId: String, val conversationId: String, val step: WorkflowStep, val input: String, val previous: String)

/** One encrypted, atomic ledger for templates and executions. Call on IO; never dispatch before committing. */
class WorkflowStore(private val file: File, private val cipher: KeyCipher, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _data = MutableStateFlow(WorkflowData())
    private val _loaded = MutableStateFlow(false)
    val data = _data.asStateFlow()
    val loaded = _loaded.asStateFlow()

    @Synchronized fun load() {
        if (_loaded.value) return
        val saved = try {
            if (!file.exists()) WorkflowData() else json.decodeFromString<WorkflowData>(cipher.decrypt(file.readBytes()).toString(Charsets.UTF_8))
                .also(::validate)
        } catch (_: Exception) { throw WorkflowException(WorkflowProblem.Read) }
        // A dead process cannot prove what a screen action did. Never replay its in-flight attempt.
        val recovered = saved.copy(runs = saved.runs.map { run ->
            if (run.phase !in listOf(WorkflowPhase.Active, WorkflowPhase.Pausing)) run
            else if (run.current != null) replaceAttempt(run, run.current!!.copy(phase = AttemptPhase.Review, problem = "interrupted"))
                .copy(phase = WorkflowPhase.Review)
            else run.copy(phase = WorkflowPhase.Paused)
        })
        if (recovered != saved) persist(recovered)
        _data.value = recovered
        _loaded.value = true
    }

    @Synchronized fun saveTemplate(id: String, expected: Int, title: String, steps: List<WorkflowStep>): WorkflowTemplate {
        load()
        val clean = steps.map { it.copy(title = it.title.trim(), instruction = it.instruction.trim()) }
        checkTemplate(title.trim(), clean)
        val old = _data.value.templates.find { it.id == id }
        requireProblem((old?.revision ?: 0) == expected, WorkflowProblem.Changed)
        requireProblem(old != null || _data.value.templates.size < MAX_TEMPLATES, WorkflowProblem.Full)
        val item = WorkflowTemplate(id, expected + 1, title.trim(), clean)
        commit(_data.value.copy(templates = listOf(item) + _data.value.templates.filterNot { it.id == id }))
        return item
    }

    @Synchronized fun deleteTemplate(id: String, expected: Int) {
        load()
        requireProblem(_data.value.templates.find { it.id == id }?.revision == expected, WorkflowProblem.Changed)
        commit(_data.value.copy(templates = _data.value.templates.filterNot { it.id == id }))
    }

    @Synchronized fun start(id: String, expected: Int, input: String): WorkflowRun {
        load()
        requireProblem(input.length <= MAX_INPUT, WorkflowProblem.Invalid)
        requireIdle()
        val template = _data.value.templates.find { it.id == id && it.revision == expected }
            ?: throw WorkflowException(WorkflowProblem.Changed)
        val run = WorkflowRun(UUID.randomUUID().toString(), template, input.trim(), clock())
        val old = _data.value.runs
        // Prune only completed history; unresolved runs must remain reviewable.
        val retained = if (old.size < MAX_RUNS) old else old.indexOfLast { it.phase == WorkflowPhase.Completed }
            .takeIf { it >= 0 }?.let { index -> old.filterIndexed { i, _ -> i != index } }
            ?: throw WorkflowException(WorkflowProblem.Full)
        commit(_data.value.copy(runs = listOf(run) + retained))
        return run
    }

    @Synchronized fun prepare(id: String): WorkflowDispatch? {
        load()
        val run = get(id)
        if (run.phase != WorkflowPhase.Active || run.current != null) return null
        val index = run.completed
        if (index >= run.template.steps.size) return null
        val attempt = StepAttempt(UUID.randomUUID().toString(), UUID.randomUUID().toString(), AttemptPhase.Dispatching)
        val updated = run.copy(attempts = run.attempts.mapIndexed { i, list -> if (i == index) list + attempt else list })
        update(updated)
        return dispatch(updated, attempt)
    }

    /** At-most-once claim, including duplicate Android start intents. */
    @Synchronized fun claim(attemptId: String): WorkflowDispatch? {
        load()
        val run = _data.value.runs.find { it.current?.id == attemptId } ?: return null
        val attempt = run.current ?: return null
        if (attempt.phase != AttemptPhase.Dispatching || run.phase !in listOf(WorkflowPhase.Active, WorkflowPhase.Pausing)) return null
        val updated = replaceAttempt(run, attempt.copy(phase = AttemptPhase.Running))
        update(updated)
        return dispatch(updated, updated.current!!)
    }

    @Synchronized fun finish(attemptId: String, successful: Boolean, result: String, problem: String = ""): String? {
        load()
        val run = _data.value.runs.find { it.current?.id == attemptId } ?: return null
        val attempt = run.current!!
        if (attempt.phase !in listOf(AttemptPhase.Dispatching, AttemptPhase.Running)) return null
        val complete = successful && result.isNotBlank() && result.length <= MAX_RESULT
        val next = replaceAttempt(run, attempt.copy(phase = if (complete) AttemptPhase.Done else AttemptPhase.Review,
            result = result.take(MAX_RESULT), problem = if (result.length > MAX_RESULT) "result_too_long" else problem.take(1000)))
        val phase = when {
            !complete -> WorkflowPhase.Review
            next.completed == next.template.steps.size -> WorkflowPhase.Completed
            run.phase == WorkflowPhase.Pausing -> WorkflowPhase.Paused
            else -> WorkflowPhase.Active
        }
        update(next.copy(phase = phase))
        return run.id
    }

    @Synchronized fun pause(id: String) {
        load()
        val run = get(id)
        if (run.phase != WorkflowPhase.Active) return
        update(run.copy(phase = if (run.current == null) WorkflowPhase.Paused else WorkflowPhase.Pausing))
    }

    @Synchronized fun resume(id: String) {
        load(); requireIdle()
        val run = get(id)
        requireProblem(run.phase == WorkflowPhase.Paused, WorkflowProblem.Changed)
        update(run.copy(phase = WorkflowPhase.Active))
    }

    /** Explicit user resolution only. Retrying starts a new conversation and keeps the previous attempt. */
    @Synchronized fun resolve(id: String, attemptId: String, completedResult: String?) {
        load(); requireIdle()
        val run = get(id)
        requireProblem(run.phase == WorkflowPhase.Review && run.current?.id == attemptId, WorkflowProblem.Changed)
        if (completedResult != null) {
            requireProblem(completedResult.isNotBlank() && completedResult.length <= MAX_RESULT, WorkflowProblem.Invalid)
            val next = replaceAttempt(run, run.current!!.copy(phase = AttemptPhase.Done, result = completedResult.trim(), manual = true))
            update(next.copy(phase = if (next.completed == next.template.steps.size) WorkflowPhase.Completed else WorkflowPhase.Active))
        } else {
            requireProblem(run.attempts[run.completed].size < MAX_ATTEMPTS, WorkflowProblem.Full)
            val next = StepAttempt(UUID.randomUUID().toString(), UUID.randomUUID().toString(), AttemptPhase.Dispatching)
            update(run.copy(phase = WorkflowPhase.Active, attempts = run.attempts.mapIndexed { index, list ->
                if (index == run.completed) list + next else list
            }))
        }
    }

    @Synchronized fun pendingDispatch(id: String): WorkflowDispatch? {
        load()
        val run = get(id)
        return run.current?.takeIf { it.phase == AttemptPhase.Dispatching }?.let { dispatch(run, it) }
    }

    @Synchronized fun deleteRun(id: String) {
        load()
        requireProblem(get(id).phase !in listOf(WorkflowPhase.Active, WorkflowPhase.Pausing), WorkflowProblem.Busy)
        commit(_data.value.copy(runs = _data.value.runs.filterNot { it.id == id }))
    }

    private fun requireIdle() = requireProblem(_data.value.runs.none { it.phase in listOf(WorkflowPhase.Active, WorkflowPhase.Pausing) }, WorkflowProblem.Busy)
    private fun get(id: String) = _data.value.runs.find { it.id == id } ?: throw WorkflowException(WorkflowProblem.Changed)
    private fun update(run: WorkflowRun) = commit(_data.value.copy(runs = _data.value.runs.map { if (it.id == run.id) run else it }))
    private fun replaceAttempt(run: WorkflowRun, attempt: StepAttempt) = run.copy(attempts = run.attempts.mapIndexed { index, list ->
        if (index == run.completed) list.dropLast(1) + attempt else list
    })
    private fun dispatch(run: WorkflowRun, attempt: StepAttempt) = WorkflowDispatch(run.id, attempt.id, attempt.conversationId,
        run.template.steps[run.completed], run.input, run.attempts.getOrNull(run.completed - 1)?.lastOrNull()?.result.orEmpty())
    private fun commit(data: WorkflowData) { persist(data); _data.value = data }
    private fun persist(data: WorkflowData) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
            val bytes = cipher.encrypt(json.encodeToString(data).toByteArray(Charsets.UTF_8))
            FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) { throw WorkflowException(WorkflowProblem.Write) }
        finally { tmp.delete() }
    }
    private fun validate(data: WorkflowData) {
        require(data.templates.size <= MAX_TEMPLATES && data.runs.size <= MAX_RUNS)
        require(data.templates.map { it.id }.distinct().size == data.templates.size)
        require(data.runs.map { it.id }.distinct().size == data.runs.size)
        data.templates.forEach { require(it.id.isNotBlank() && it.revision > 0); checkTemplate(it.title, it.steps) }
        data.runs.forEach { run ->
            checkTemplate(run.template.title, run.template.steps)
            require(run.input.length <= MAX_INPUT && run.attempts.size == run.template.steps.size)
            run.attempts.forEach { list ->
                require(list.size <= MAX_ATTEMPTS)
                list.forEach { require(it.id.isNotBlank() && it.conversationId.isNotBlank() && it.result.length <= MAX_RESULT) }
            }
            require(run.attempts.drop(run.completed + 1).all { it.isEmpty() })
            require((run.phase == WorkflowPhase.Completed) == (run.completed == run.template.steps.size))
        }
    }
    companion object {
        const val FILE = "workflows-v1.bin"
        const val MAX_TEMPLATES = 30
        const val MAX_RUNS = 50
        const val MAX_STEPS = 8
        const val MAX_ATTEMPTS = 10
        const val MAX_INPUT = 4000
        const val MAX_RESULT = 12000
        private fun requireProblem(ok: Boolean, problem: WorkflowProblem) { if (!ok) throw WorkflowException(problem) }
        private fun checkTemplate(title: String, steps: List<WorkflowStep>) {
            requireProblem(title.isNotBlank() && title.length <= 100 && steps.size in 2..MAX_STEPS &&
                steps.map { it.id }.distinct().size == steps.size && steps.all {
                    it.id.isNotBlank() && it.title.isNotBlank() && it.title.length <= 100 && it.instruction.isNotBlank() && it.instruction.length <= 2000
                }, WorkflowProblem.Invalid)
        }
    }
}
