package com.wangzan101.mika.task

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.agent.DeskTurn
import com.wangzan101.mika.core.agent.NoticeTurn
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.remote.RemoteHistory
import com.wangzan101.mika.core.remote.RunRecordExport
import com.wangzan101.mika.core.remote.RemoteStatus
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.run.trace.TraceRow
import com.wangzan101.mika.core.run.trace.TraceStore
import com.wangzan101.mika.record.RunStore
import kotlinx.serialization.json.*

/** Reads the same stores as the phone UI. Viewing history never registers a remotely controllable task. */
class PhoneHistory(
    private val conversations: ConversationStore,
    private val runs: RunStore,
    private val traces: TraceStore,
    private val allowed: () -> Boolean,
    private val live: () -> List<RemoteTask> = { emptyList() },
) : RemoteHistory {
    override val enabled: Boolean get() = allowed()

    override fun conversations(offset: Int, limit: Int): JsonObject {
        val pending = live().distinctBy { it.conversation }.asReversed().map {
            ConversationSummary(it.conversation, it.goal, it.createdAtMs)
        }
        val page = conversations.listPage(offset, limit + 1, pending, includeArchived = true)
        return buildJsonObject {
            putJsonArray("conversations") {
                for (c in page.take(limit)) addJsonObject {
                    put("id", c.id); put("title", c.title.take(TEXT_LIMIT)); put("updated_at_ms", c.updatedAt)
                    put("archived", c.archivedAt != null)
                }
            }
            put("next_offset", if (page.size > limit) JsonPrimitive(offset + limit) else JsonNull)
        }
    }

    override fun turns(conversation: String, offset: Int, limit: Int): JsonObject? {
        val saved = conversations.turns(conversation)
        val active = live().filter { task ->
            task.conversation == conversation && saved.none { turn ->
                turn.remoteTaskId == task.id || (task.runId != null && turn.runId == task.runId)
            }
        }
        if (saved.isEmpty() && active.isEmpty()) return null
        val summaries = runs.list().associateBy { it.id }
        val rows = saved.mapIndexed { index, turn -> turn(conversation, index, turn, turn.runId?.let { summaries[it]?.startedAtMs }) } +
            active.mapIndexed { index, task -> JsonObject(RemoteStatus.describe(task) + buildJsonObject {
                put("task_id", task.runId?.let { "run-$it" } ?: "live-${task.id}")
                put("turn_index", saved.size + index); put("read_only", true)
                put("live", true)
                put("source_task_id", task.id)
            }) }
        // Offset counts back from the latest turn. A page is displayed in conversation order.
        val end = (rows.size - offset).coerceAtLeast(0)
        return buildJsonObject {
            put("conversation", conversation)
            put("total", rows.size)
            put("turns", JsonArray(rows.subList((end - limit).coerceAtLeast(0), end)))
            next(offset, limit, rows.size)
        }
    }

    private fun turn(conversation: String, index: Int, turn: Turn, started: Long?) = buildJsonObject {
        put("task_id", turn.runId?.let { "run-$it" } ?: "turn-$conversation-$index")
        put("conversation", conversation); put("turn_index", index); put("read_only", true)
        turn.runId?.let { put("run_id", it) }
        turn.remoteTaskId?.let { put("source_task_id", it) }
        started?.let { put("started_at_ms", it) }
        // A notice turn lists other apps' notifications (third-party text Mika showed the user). It is not an answer to a task,
        // and the remote client would read it as Mika's own words, so the text stays on the phone.
        val answer = if (NoticeTurn.isNotice(turn)) "" else turn.outcome
        put("goal", turn.userText.take(TEXT_LIMIT)); put("answer", answer.take(TEXT_LIMIT))
        put("text_truncated", turn.userText.length > TEXT_LIMIT || answer.length > TEXT_LIMIT)
        // 前台回的话、前台的汇报都算办完了的回话;前台那句没回出来时,原因记在 note 里
        put("status", when {
            Conversation.replied(turn) -> "done"
            turn.status == "cancelled" -> "cancelled"
            else -> "failed"
        })
        val failure = DeskTurn.failure(turn) ?: turn.outcome.takeIf { turn.status == "failed" }
        failure?.let { put("reason", it.take(TEXT_LIMIT)) }
        turn.verified?.let { put("verified", it) }
        turn.note?.let { put("verification_note", it.take(TEXT_LIMIT)) }
        turn.result?.let { put("result", it) }
    }

    private fun belongs(conversation: String, runId: String) =
        conversations.turns(conversation).any { it.runId == runId } ||
            live().any { it.conversation == conversation && it.runId == runId }

    override fun run(conversation: String, runId: String, offset: Int, limit: Int): JsonObject? {
        if (!belongs(conversation, runId)) return null
        val events = runs.events(runId)
        val summary = runs.list().firstOrNull { it.id == runId }
        val archive = traces.archive(runId)
        val rows = archive?.rows().orEmpty()
        return buildJsonObject {
            put("run_id", runId); put("record_available", summary != null || events.isNotEmpty())
            put("recording", traces.recording(runId)); put("trace_available", archive != null)
            rows.firstOrNull()?.let { put("trace_status", it.status) }
            put("events", JsonArray(events.drop(offset).take(limit).map { event ->
                val encoded = RunEvent.encode(event)
                val data = Json.parseToJsonElement(encoded).jsonObject
                val detail = RunRecordExport.redact(data).toString()
                buildJsonObject {
                    put("type", data.getValue("type")); put("at_ms", event.atMs)
                    data["step"]?.let { put("step", it) }
                    put("detail", detail.take(EVENT_LIMIT)); put("truncated", detail.length > EVENT_LIMIT)
                }
            }))
            put("rows", JsonArray(rows.drop(1).drop(offset).take(limit).map(::traceRow)))
            next(offset, limit, maxOf(events.size, (rows.size - 1).coerceAtLeast(0)))
        }
    }

    override fun body(conversation: String, runId: String, spanId: String, offset: Int, limit: Int): JsonObject? {
        if (!belongs(conversation, runId)) return null
        val archive = traces.archive(runId) ?: return null
        val page = archive.bodyPage(spanId, offset, limit) ?: return null
        return buildJsonObject {
            put("text", redactText(page.text)); put("next_offset", if (page.hasMore) JsonPrimitive(offset + page.text.length) else JsonNull)
        }
    }

    private fun traceRow(row: TraceRow) = buildJsonObject {
        put("id", row.id); put("name", row.name); put("step", row.step); put("attempt", row.attempt)
        put("status", row.status); put("duration_ms", row.durationMs)
        put("input", row.input); put("cached", row.cached); put("uncached", row.uncached); put("output", row.output)
        put("difference", redactText(row.difference)); put("context_change", redactText(row.contextChange))
        put("captured", row.captured); put("tool_call_id", row.toolCallId)
    }

    private fun JsonObjectBuilder.next(offset: Int, limit: Int, total: Int) {
        put("next_offset", if (offset + limit < total) JsonPrimitive(offset + limit) else JsonNull)
    }

    private fun redactText(text: String?): String {
        if (text == null) return ""
        val nested = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        return if (nested is JsonObject || nested is JsonArray) RunRecordExport.redact(nested).toString() else Redactor.text(text)
    }

    private companion object {
        const val TEXT_LIMIT = 8_000
        const val EVENT_LIMIT = 12_000
    }
}
