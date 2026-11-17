package com.wangzan101.mika.workflow

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Android adapter only starts an intent; TaskService owns execution and calls complete. */
class WorkflowRuntime(val store: WorkflowStore, private val launch: (WorkflowDispatch) -> Unit) {
    private data class Completion(val id: String, val successful: Boolean, val result: String, val problem: String)
    private val pending = linkedMapOf<String, Completion>()
    private val _saveError = MutableStateFlow(false)
    val saveError = _saveError.asStateFlow()

    @Synchronized fun start(template: WorkflowTemplate, input: String): String {
        val run = store.start(template.id, template.revision, input)
        advanceOrReport(run.id)
        return run.id
    }
    @Synchronized fun resume(id: String) { store.resume(id); advanceOrReport(id) }
    @Synchronized fun resolve(id: String, attemptId: String, result: String?) {
        store.resolve(id, attemptId, result)
        advanceOrReport(id)
    }
    @Synchronized fun complete(id: String, successful: Boolean, result: String, problem: String = "") {
        pending[id] = Completion(id, successful, result, problem)
        retrySave()
    }
    @Synchronized fun retrySave() {
        try {
            store.load()
            pending.values.toList().forEach { value ->
                val runId = store.finish(value.id, value.successful, value.result, value.problem)
                pending.remove(value.id)
                if (runId != null) advance(runId)
            }
            store.data.value.runs.filter { it.phase == WorkflowPhase.Active && it.current == null }.forEach { advance(it.id) }
            _saveError.value = false
        } catch (_: Exception) { _saveError.value = true }
    }
    private fun advanceOrReport(id: String) {
        try { advance(id) } catch (_: WorkflowException) { _saveError.value = true }
    }
    private fun advance(id: String) {
        val next = store.pendingDispatch(id) ?: store.prepare(id) ?: return
        try { launch(next) }
        catch (_: Exception) {
            pending[next.attemptId] = Completion(next.attemptId, false, "", "start_failed")
            store.finish(next.attemptId, false, "", "start_failed")
            pending.remove(next.attemptId)
        }
    }
}
