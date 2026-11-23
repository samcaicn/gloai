package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.safety.gate.ConfirmationRequest
import com.wangzan101.mika.core.safety.guard.GuardVerdict
import com.wangzan101.mika.core.tool.ToolExecution
import kotlinx.serialization.json.JsonObject

/** 调用方在执行入口里插的几处(只有模型单步用):等用户的结局、交给模型前的最后加工。 */
interface FollowUp {
    /** 执行之后、守卫核对之前:要等用户的结局(确认、追问、接管)换成最终结果;返回 null 表示任务就此取消。 */
    suspend fun resolve(exec: ToolExecution): ToolExecution? = exec

    /** 守卫核对之后、记录之前:返回最终结果和交给模型(也记进运行记录)的内容。 */
    suspend fun present(exec: ToolExecution, verdicts: List<GuardVerdict>): Presented = Presented(exec, exec.content)

    /** 被确认关卡拦下:说明交给模型之后、记进运行记录之前。 */
    fun refused(content: JsonObject) {}

    /**
     * 不在屏上、工具声明要问的一步([request] 是给用户看的申报):当场问用户,返回用户的决定(同意时 ok 为 true,
     * 同意已记进确认关卡);问不了人为 null(这一步就不做)。
     */
    suspend fun approve(request: ConfirmationRequest): ToolExecution? = null

    companion object {
        val NONE = object : FollowUp {}
    }
}

data class Presented(val exec: ToolExecution, val content: JsonObject)
