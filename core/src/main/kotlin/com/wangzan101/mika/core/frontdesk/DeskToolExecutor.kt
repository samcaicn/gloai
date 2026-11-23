package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 同一句原话在这么久内再派一次,当成重复,回第一次的任务。 */
const val DISPATCH_DEDUPE_MS = 30_000L

/**
 * 执行前台的工具调用。凡是带用户原话(words)的,先过 [verifyWords](代码核对这句确实是用户说过的),
 * 对不上就拒绝;任务编号只认 [ledger] 里这次对话派过的。派出的任务挂在 [conversationId] 下,
 * 开始或排上队后交给 [onDispatched]。[DeskTools.ASK_OWNER] 由电话适配层自己处理,这里不接。
 */
class DeskToolExecutor(
    private val tasks: DeskTaskPort,
    private val lookup: DeskLookup,
    private val ledger: DeskLedger,
    private val verifyWords: (String) -> Boolean,
    private val clock: () -> Long,
    private val conversationId: String,
    private val onDispatched: (DeskTaskEntry) -> Unit = {},
) {
    /** 查重和登记要连着做,否则同一句话并发派两次会都派出去。 */
    private val dispatching = Mutex()

    suspend fun execute(name: String, args: JsonObject): JsonObject = when (name) {
        DeskTools.DISPATCH -> dispatch(args)
        DeskTools.CHECK -> check()
        DeskTools.ADD -> withVerifiedTask(args) { id, words -> accepted(tasks.steer(id, words)) }
        DeskTools.ANSWER -> withVerifiedTask(args) { id, words -> accepted(tasks.answer(id, words)) }
        DeskTools.CANCEL -> knownTask(args)?.let { accepted(tasks.cancel(it)) } ?: status("unknown_task")
        DeskTools.SEARCH -> lookup(args) { lookup.webSearch(it) }
        DeskTools.RECALL -> lookup(args) { lookup.recallMemory(it) }
        else -> status("unsupported")
    }

    private suspend fun dispatch(args: JsonObject): JsonObject {
        val words = verifiedWords(args) ?: return wordsRejected()
        val note = args.string("note").orEmpty()
        val title = args.string("title")?.ifBlank { null } ?: words.take(DESK_LINE_MAX_CHARS)
        return dispatching.withLock {
            val now = clock()
            ledger.find(words, since = now - DISPATCH_DEDUPE_MS)?.let {
                return@withLock buildJsonObject { put("status", "already_dispatched"); put("task_id", it.taskId) }
            }
            when (val outcome = tasks.dispatch(words, note, conversationId)) {
                is DispatchOutcome.Started -> {
                    record(DeskTaskEntry(outcome.taskId, title, words, now))
                    buildJsonObject { put("status", "started"); put("task_id", outcome.taskId) }
                }
                is DispatchOutcome.Queued -> {
                    record(DeskTaskEntry(outcome.taskId, title, words, now))
                    buildJsonObject { put("status", "queued"); put("task_id", outcome.taskId); put("position", outcome.position) }
                }
                is DispatchOutcome.Failed -> buildJsonObject { put("status", "failed"); put("reason", outcome.reason) }
            }
        }
    }

    private fun record(entry: DeskTaskEntry) {
        ledger.add(entry)
        onDispatched(entry)
    }

    private fun check(): JsonObject = buildJsonObject {
        put("status", "ok")
        put("tasks", JsonArray(ledger.all().map { entry ->
            val (state, line) = describe(entry.taskId)
            buildJsonObject {
                put("task_id", entry.taskId)
                put("title", entry.title)
                put("state", state)
                line?.let { put("line", it) }
            }
        }))
    }

    /** 任务此刻的状态名和一句说明;状态名的归类复用 [DeskEvents.classify](要回答、要动手都算 needs_you)。 */
    private fun describe(taskId: String): Pair<String, String?> {
        val state = tasks.state(taskId)?.value ?: return "unknown" to null
        if (tasks.queuePosition(taskId) != null) return "queued" to null
        return when (val event = DeskEvents.classify(taskId, previous = null, next = state)) {
            is DeskEvent.Started, is DeskEvent.Progress -> "running" to (state as? TaskState.Running)?.let { deskLine(it.narration) }
            is DeskEvent.NeedsAnswer -> "needs_you" to event.prompt
            is DeskEvent.NeedsAction -> "needs_you" to null
            is DeskEvent.Succeeded -> "done" to event.answer
            is DeskEvent.Failed -> "failed" to event.reason.take(DESK_RESULT_MAX_CHARS)
            is DeskEvent.Cancelled -> "cancelled" to null
            // 归类不报的:还没开始、排在开始队列里算 queued,其余等条件的停顿仍算在做。
            is DeskEvent.Queued, null -> when {
                state == TaskState.Idle || (state is TaskState.Held && state.reason.queued) -> "queued" to null
                else -> "running" to null
            }
        }
    }

    private inline fun withVerifiedTask(args: JsonObject, action: (String, String) -> JsonObject): JsonObject {
        val id = knownTask(args) ?: return status("unknown_task")
        val words = verifiedWords(args) ?: return wordsRejected()
        return action(id, words)
    }

    private fun knownTask(args: JsonObject): String? = args.string("task_id")?.takeIf { ledger.get(it) != null }

    private fun verifiedWords(args: JsonObject): String? = args.string("words")?.takeIf { it.isNotBlank() && verifyWords(it) }

    private suspend fun lookup(args: JsonObject, query: suspend (String) -> String?): JsonObject {
        val q = args.string("query")?.ifBlank { null } ?: return buildJsonObject { put("status", "rejected"); put("reason", "missing_query") }
        val text = query(q) ?: return status("unavailable")
        return buildJsonObject { put("status", "ok"); put("text", text) }
    }

    private fun accepted(ok: Boolean) = status(if (ok) "ok" else "not_accepted")

    private fun wordsRejected() = buildJsonObject { put("status", "rejected"); put("reason", "words_not_verified") }

    private fun status(value: String) = buildJsonObject { put("status", value) }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}
