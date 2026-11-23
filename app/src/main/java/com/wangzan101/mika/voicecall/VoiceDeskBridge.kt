package com.wangzan101.mika.voicecall

import com.wangzan101.mika.calls.CallRealtimeSession
import com.wangzan101.mika.calls.VolcRealtimeProtocol
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.core.frontdesk.DeskSurface
import com.wangzan101.mika.core.frontdesk.DeskToolExecutor
import com.wangzan101.mika.core.frontdesk.DeskTools
import com.wangzan101.mika.core.frontdesk.FrontDesk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 应用内语音通话和前台之间的一层:
 * - 通话里模型调的前台工具交给 [executor],派单马上回结果,任务在后台跑,通话不等;
 * - 前台的汇报按 [copy] 变成一句话,交给此刻的通话会话 [session] 排在当前这句后面说;会话已经不在时返回 false,
 *   前台改交给 sink(写回会话、发通知)。说出去的话交给 [onSpoken](回声窗口用)。
 *
 * 用户插话只打断通话里正在说的话,不碰后台任务。
 */
class VoiceDeskBridge(
    private val desk: FrontDesk,
    private val executor: DeskToolExecutor,
    private val session: () -> CallRealtimeSession?,
    private val copy: (DeskReport) -> String,
    private val onSpoken: (String) -> Unit = {},
) : DeskSurface {
    /** 接到前台上;关掉返回的句柄后汇报都交给 sink。 */
    fun attach(): AutoCloseable = desk.attach(this)

    /** 执行一次工具调用并把结果回给通话会话;返回结果本身。 */
    suspend fun onFunctionCall(callId: String, name: String, args: JsonObject): JsonObject {
        val result = execute(name, args)
        session()?.sendToolResult(callId, result)
        return result
    }

    /**
     * 执行一次前台工具调用。用户原话 words 超过一句话的上限直接拒绝,绝不截断(截了就不是原话了);
     * 其余字段(note、title、task_id)按工具参数上限截断。
     */
    suspend fun execute(name: String, args: JsonObject): JsonObject {
        val words = (args["words"] as? JsonPrimitive)?.content
        if (words != null && words.length > VolcRealtimeProtocol.MAX_UTTERANCE_CHARS) return WORDS_TOO_LONG
        val bounded = JsonObject(
            args.mapValues { (key, value) ->
                if (key == "words" || value !is JsonPrimitive || !value.isString) value
                else JsonPrimitive(value.content.take(VolcRealtimeProtocol.MAX_FUNCTION_ARGUMENT_CHARS))
            },
        )
        return executor.execute(name, bounded)
    }

    /** 会话不能直接接打字时,把打的字原样派成一件任务(标题取前几个字)。 */
    suspend fun dispatchTyped(text: String): JsonObject = execute(
        DeskTools.DISPATCH,
        buildJsonObject { put("words", text) },
    )

    override suspend fun deliver(report: DeskReport): Boolean {
        val live = session() ?: return false
        val text = copy(report).ifBlank { return true }
        if (!live.sendNotice(text)) return false
        onSpoken(text)
        return true
    }

    private companion object {
        val WORDS_TOO_LONG = buildJsonObject { put("status", "rejected"); put("reason", "words_too_long") }
    }
}
