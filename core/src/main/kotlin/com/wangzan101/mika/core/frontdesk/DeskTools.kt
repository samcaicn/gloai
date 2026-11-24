package com.wangzan101.mika.core.frontdesk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 前台用哪一套工具:和用户本人说话时派单、补充、转达回答、取消;在电话里替用户说话时只能请示主人。
 * 前台没有查询类工具:任务状态和相关记忆由代码每轮放进上下文(见 [DeskBackground]),要查资料、上网的派给后台。
 */
enum class DeskToolSet { User, Phone }

/** 前台的工具定义(名字、说明、参数的 JSON Schema),形状和实时语音协议的工具一致,[LlmBrain.toolSpec] 能直接转换。 */
object DeskTools {
    const val DISPATCH = "dispatch_task"
    const val ADD = "add_to_task"
    const val ANSWER = "answer_task"
    const val CANCEL = "cancel_task"
    const val ASK_OWNER = "ask_owner"

    /** 用户定的原文,逐字使用。 */
    private const val WORDS_DESCRIPTION = "用户说过的原话，逐字照抄，不要改写"

    fun specs(set: DeskToolSet): List<JsonObject> = when (set) {
        DeskToolSet.User -> listOf(dispatch(), add(), answer(), cancel())
        DeskToolSet.Phone -> listOf(askOwner())
    }

    private fun dispatch() = tool(
        DISPATCH,
        "Start doing something for the user. Use it for anything that needs a tool: apps, accounts, actions on the phone, " +
            "web search or looking something up. Never claim it is done until it reports finished.",
        required = listOf("words", "title"),
    ) {
        string("words", WORDS_DESCRIPTION)
        string("note", "Your own context for the task (what you understood, details agreed earlier). Kept apart from the user's words.")
        string("title", "A short label for the task, a few words, in the user's language.")
    }

    private fun add() = tool(
        ADD,
        "Pass something more the user said to a task that is still running (an extra requirement or a correction).",
        required = listOf("task_id", "words"),
    ) {
        string("task_id", "The task id returned when the task was started.")
        string("words", WORDS_DESCRIPTION)
    }

    private fun answer() = tool(
        ANSWER,
        "Answer the question a task is waiting on, with the user's reply.",
        required = listOf("task_id", "words"),
    ) {
        string("task_id", "The task id of the task that asked.")
        string("words", WORDS_DESCRIPTION)
    }

    private fun cancel() = tool(
        CANCEL,
        "Stop a task, only when the user asks to stop it in what they just said.",
        required = listOf("task_id", "words"),
    ) {
        string("task_id", "The task id of the task to stop.")
        string("words", WORDS_DESCRIPTION)
    }

    private fun askOwner() = tool(
        ASK_OWNER,
        "Ask the phone's owner a question when you are not sure what to say on their behalf; wait for the answer before committing to anything.",
        required = listOf("question"),
    ) {
        string("question", "The question for the owner, short and self-contained.")
    }

    private fun tool(name: String, description: String, required: List<String>, properties: JsonObjectBuilder.() -> Unit) =
        buildJsonObject {
            put("type", "function")
            put("name", name)
            put("description", description)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties", properties)
                put("required", JsonArray(required.map { JsonPrimitive(it) }))
                put("additionalProperties", false)
            }
        }

    private fun JsonObjectBuilder.string(name: String, description: String) {
        putJsonObject(name) {
            put("type", "string")
            put("description", description)
        }
    }
}
