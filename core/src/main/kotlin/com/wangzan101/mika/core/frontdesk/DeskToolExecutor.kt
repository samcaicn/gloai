package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 同一句原话在这么久内再派一次,当成重复,回第一次的任务。 */
const val DISPATCH_DEDUPE_MS = 30_000L

/**
 * 执行前台的工具调用。四个工具都带用户原话(words),先过 [words](代码核对这段确实是用户说过的),对不上就拒绝;
 * 交给后台、记进账本的是这段所在的整句,不是前台摘的片段。取消只认用户这一轮的原话。
 * 任务编号只认 [ledger] 里这次对话派过的。派出的任务挂在 [conversationId] 下,开始或排上队后交给 [onDispatched]。[DeskTools.ASK_OWNER] 由电话适配层自己处理,这里不接;
 * 其他不认识的工具名(包括已去掉的查询类工具)一律回 unsupported。
 */
class DeskToolExecutor(
    private val tasks: DeskTaskPort,
    private val ledger: DeskLedger,
    private val words: DeskWords,
    private val clock: () -> Long,
    private val conversationId: String,
    private val onDispatched: (DeskTaskEntry) -> Unit = {},
) {
    /** 查重和登记要连着做,否则同一句话并发派两次会都派出去。 */
    private val dispatching = Mutex()

    suspend fun execute(name: String, args: JsonObject): JsonObject = when (name) {
        DeskTools.DISPATCH -> dispatch(args)
        DeskTools.ADD -> withVerifiedTask(args) { id, words -> accepted(tasks.steer(id, words)) }
        DeskTools.ANSWER -> withVerifiedTask(args) { id, words -> accepted(tasks.answer(id, words)) }
        DeskTools.CANCEL -> cancel(args)
        else -> status("unsupported")
    }

    private suspend fun dispatch(args: JsonObject): JsonObject {
        val fragment = args.string("words").orEmpty()
        val words = verifiedWords(args) ?: return wordsRejected()
        val note = args.string("note").orEmpty()
        val title = args.string("title")?.ifBlank { null } ?: fragment.take(DESK_LINE_MAX_CHARS)
        // 查重看前台要的是哪件(摘的那段、备注、标题):一句话可以派几件,各件带的整句相同
        val asked = listOf(fragment.trim(), note.trim(), title.trim()).joinToString("\n")
        return dispatching.withLock {
            val now = clock()
            // 上一件同样的任务已经结束(或端口已不认识它)时不算重复,当新任务派。
            ledger.find(asked, since = now - DISPATCH_DEDUPE_MS)?.takeIf { isUnfinished(it.taskId) }?.let {
                return@withLock buildJsonObject { put("status", "already_dispatched"); put("task_id", it.taskId) }
            }
            val outcome = try {
                tasks.dispatch(words, note, conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DispatchOutcome.Failed(e.message ?: e::class.simpleName ?: "dispatch_error")
            }
            when (outcome) {
                is DispatchOutcome.Started -> {
                    record(DeskTaskEntry(outcome.taskId, title, words, now), asked)
                    buildJsonObject { put("status", "started"); put("task_id", outcome.taskId) }
                }
                is DispatchOutcome.Queued -> {
                    record(DeskTaskEntry(outcome.taskId, title, words, now), asked)
                    buildJsonObject { put("status", "queued"); put("task_id", outcome.taskId); put("position", outcome.position) }
                }
                is DispatchOutcome.Failed -> buildJsonObject { put("status", "failed"); put("reason", outcome.reason) }
            }
        }
    }

    /**
     * 用户在输入框带着文件发来的一句:不经前台模型,直接派成一件新任务(前台的工具不收文件)。[typed] 是用户打的整句,
     * 不空时照样要过核对(只有文件、没写话时交给后台的只有代码写的文件行);[prepare] 见 [DeskTaskPort.dispatchWithFiles]。
     * 结果和 dispatch_task 同形。不查重:每次带的文件不同。放文件期间不占派单的锁,前台照样能派别的。
     */
    suspend fun dispatchWithFiles(typed: String, title: String, prepare: suspend (child: String) -> String?): JsonObject {
        val words = if (typed.isBlank()) typed else this.words.said(typed) ?: return wordsRejected()
        val outcome = try {
            tasks.dispatchWithFiles(conversationId, prepare)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DispatchOutcome.Failed(e.message ?: e::class.simpleName ?: "dispatch_error")
        }
        val name = title.trim().ifBlank { words }.take(DESK_LINE_MAX_CHARS)
        return when (outcome) {
            is DispatchOutcome.Started -> {
                record(DeskTaskEntry(outcome.taskId, name, words, clock()))
                buildJsonObject { put("status", "started"); put("task_id", outcome.taskId) }
            }
            is DispatchOutcome.Queued -> {
                record(DeskTaskEntry(outcome.taskId, name, words, clock()))
                buildJsonObject { put("status", "queued"); put("task_id", outcome.taskId); put("position", outcome.position) }
            }
            is DispatchOutcome.Failed -> buildJsonObject { put("status", "failed"); put("reason", outcome.reason) }
        }
    }

    private fun record(entry: DeskTaskEntry, asked: String = entry.words) {
        ledger.add(entry, asked)
        onDispatched(entry)
    }

    private fun isUnfinished(taskId: String): Boolean = tasks.state(taskId)?.value?.let { it !is TaskState.Terminal } ?: false

    private inline fun withVerifiedTask(args: JsonObject, action: (String, String) -> JsonObject): JsonObject {
        val id = knownTask(args) ?: return status("unknown_task")
        val words = verifiedWords(args) ?: return wordsRejected()
        return action(id, words)
    }

    /** 停一件任务:编号得是派过的,原话得是用户这一轮说的(汇报、任务板上的话都不能让它停)。 */
    private fun cancel(args: JsonObject): JsonObject {
        val id = knownTask(args) ?: return status("unknown_task")
        args.string("words")?.takeIf { it.isNotBlank() }?.let(words::saidNow) ?: return wordsRejected()
        return accepted(tasks.cancel(id))
    }

    private fun knownTask(args: JsonObject): String? = args.string("task_id")?.takeIf { ledger.get(it) != null }

    /** 核对过的整句:前台给的那段落在用户说过的哪一句里,就交那一句。 */
    private fun verifiedWords(args: JsonObject): String? = args.string("words")?.takeIf { it.isNotBlank() }?.let(words::said)

    private fun accepted(ok: Boolean) = status(if (ok) "ok" else "not_accepted")

    private fun wordsRejected() = buildJsonObject { put("status", "rejected"); put("reason", "words_not_verified") }

    private fun status(value: String) = buildJsonObject { put("status", value) }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}
