package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSummary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 电脑拉取一次运行记录。格式版本 [FORMAT]:头里写清 task_id → conversation_id → run_id,
 * `run` 是再脱敏一遍的 run.json,`events` 是再脱敏一遍的事件。
 * 不带截图文件体;事件里的 media 只留编号和步号。进行中(还没有收尾)标 [partial]。
 */
object RunRecordExport {
    const val FORMAT = 1

    /** 和手机 `files/runs/<id>/` 的目录名同一套。 */
    val RUN_ID = Regex("""\d{8}-\d{6}-[a-z0-9]{6}""")

    /** 参数、工具结果、核查细节是嵌在字符串里的 JSON,按网页数据那条脱敏路再走一遍。 */
    private val JSON_TEXT = setOf("argsJson", "resultJson", "detailJson")

    data class Payload(val json: JsonObject, val jsonl: String)

    fun of(record: RunExportRecord): Payload {
        val events = record.events.map { event ->
            if (event is RunEvent.Media) buildJsonObject {
                put("type", "media"); put("atMs", event.atMs); put("mediaId", event.mediaId); put("step", event.step)
            } else redact(RunEvent.json.parseToJsonElement(RunEvent.encode(event)))
        }
        val summary = record.summary?.let { redact(RunEvent.json.parseToJsonElement(RunEvent.json.encodeToString(RunSummary.serializer(), it))) }
        val partial = record.summary?.endedAtMs == null && record.events.none { it is RunEvent.TaskEnded }
        val header = buildJsonObject {
            put("exportFormat", FORMAT)
            put("partial", partial)
            put("includesScreenshots", false)
            putNullable("task_id", record.taskId)
            putNullable("conversation_id", record.conversationId)
            putNullable("run_id", record.runId)
            put("run", summary ?: JsonNull)
        }
        val json = JsonObject(header + ("events" to JsonArray(events)))
        val jsonl = buildString {
            append(header.toString())
            append('\n')
            events.forEach {
                append(it.toString())
                append('\n')
            }
        }
        return Payload(json, jsonl)
    }

    /** 所有字符串再过 [Redactor.text];嵌套 JSON 文本再过 [Redactor.pageData](含 Key、JWT、凭据字段)。 */
    fun redact(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, value) ->
            if (key in JSON_TEXT && value is JsonPrimitive && value.isString) JsonPrimitive(Redactor.pageData(value.content))
            else redact(value)
        })
        is JsonArray -> JsonArray(element.map(::redact))
        is JsonPrimitive -> if (element.isString) JsonPrimitive(Redactor.text(element.content)) else element
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) {
        if (value == null) put(key, JsonNull) else put(key, value)
    }
}

/**
 * 按任务编号或运行记录编号取可导出的记录。手机上发起的运行不在此列。
 * 实现方只读已经落盘的记录,不附带截图字节。
 */
interface RunExportSource {
    fun scope(owner: String): RunExportSource = this
    fun load(id: String): RunExportLookup
}

sealed interface RunExportLookup {
    /** 没有这个电脑任务,也没有可导出的运行记录。 */
    data object Missing : RunExportLookup

    /** 任务还记着运行编号,记录文件已经不在了。 */
    data class Gone(val taskId: String, val runId: String) : RunExportLookup

    data class Found(val record: RunExportRecord) : RunExportLookup
}

/**
 * [runId] 与 [summary] 同有同无:任务已经交出去、运行记录还没开,两者都空,导出标 partial。
 */
data class RunExportRecord(
    val taskId: String?,
    val conversationId: String?,
    val runId: String?,
    val summary: RunSummary?,
    val events: List<RunEvent>,
) {
    init { require((runId == null) == (summary == null)) }
}
