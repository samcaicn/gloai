package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.remote.RemoteSteer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 远程收件库里终态和插话去向的存储格式,只在收件这一层用:领域类型([TaskState]、[FailKind])改名、加值不动盘上的字。
 * 读不出来的(格式坏了、更新的版本写的类别)不抛异常,按读不出来的失败返回,电脑照样拿到一个终态。
 */
internal object RemoteTaskRecord {
    private val json = Json { ignoreUnknownKeys = true }

    /** 盘上读不懂的终态一律报成这个。 */
    val UNREADABLE: TaskState.Terminal = TaskState.Failed("The saved result of this task could not be read.", FailKind.Environment)

    data class Result(val state: TaskState.Terminal, val runId: String? = null)

    fun encode(state: TaskState.Terminal, runId: String? = null): String = buildJsonObject {
        runId?.let { put("run_id", it) }
        when (state) {
            is TaskState.Succeeded -> {
                put("type", "done"); put("answer", state.answer); put("verified", state.verified)
                state.note?.let { put("note", it) }
                if (state.unconfirmed.isNotEmpty()) putJsonArray("unconfirmed") { state.unconfirmed.forEach { add(it) } }
                put("reviewed", state.reviewed)
            }
            is TaskState.Failed -> { put("type", "failed"); put("reason", state.reason); put("kind", wire(state.kind)) }
            TaskState.Cancelled -> put("type", "cancelled")
        }
    }.toString()

    fun decode(text: String): Result = runCatching {
        val o = json.parseToJsonElement(text) as JsonObject
        val state = when (o.str("type")) {
            "done" -> TaskState.Succeeded(
                answer = o.str("answer") ?: return Result(UNREADABLE),
                verified = o.bool("verified") ?: true, note = o.str("note"),
                unconfirmed = (o["unconfirmed"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                reviewed = o.bool("reviewed") ?: true,
            )
            "failed" -> TaskState.Failed(o.str("reason").orEmpty(), kind(o.str("kind")))
            "cancelled" -> TaskState.Cancelled
            else -> UNREADABLE
        }
        Result(state, o.str("run_id"))
    }.getOrDefault(Result(UNREADABLE))

    fun encodeSteers(steers: List<RemoteSteer>): String = buildJsonArray {
        steers.forEach { s -> addJsonObject { put("messageId", s.messageId); put("state", s.state) } }
    }.toString()

    /** 坏掉的条目跳过,不连累整份结果。 */
    fun decodeSteers(text: String): List<RemoteSteer> = runCatching {
        (json.parseToJsonElement(text) as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            RemoteSteer(o.str("messageId") ?: return@mapNotNull null, o.str("state") ?: return@mapNotNull null)
        }
    }.getOrDefault(emptyList())

    /** 盘上的类别名单独定下,不跟着枚举改名走;新加的类别在这里编译不过,逼着定一个名字。 */
    private fun wire(kind: FailKind): String = when (kind) {
        FailKind.GaveUp -> "gave_up"
        FailKind.Model -> "model"
        FailKind.Guard -> "guard"
        FailKind.StepLimit -> "step_limit"
        FailKind.TokenLimit -> "token_limit"
        FailKind.TimeLimit -> "time_limit"
        FailKind.Environment -> "environment"
        FailKind.Interrupted -> "interrupted"
    }

    /** 也认早先直接写枚举名的记录(如 `Interrupted`);认不出的(更新的版本写的)算运行环境出错。 */
    private fun kind(raw: String?): FailKind = FailKind.entries.firstOrNull { wire(it) == raw }
        ?: FailKind.entries.firstOrNull { it.name == raw } ?: FailKind.Environment

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
}
