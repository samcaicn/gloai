package com.wangzan101.mika.export

import com.wangzan101.mika.core.export.ToolStep
import com.wangzan101.mika.core.run.record.RunEvent

/** 把运行事件里实际做过的事聚成导出用的工具步骤;最终回答(tool=null)不算一步。 */
object RunEventToolSteps {
    fun of(events: List<RunEvent>): List<ToolStep> {
        val replies = events.filterIsInstance<RunEvent.ModelReply>()
            .filter { it.acted && it.tool != null }
            .groupBy { it.step }
        val results = events.filterIsInstance<RunEvent.ToolResult>().groupBy { it.step }
        val activities = events.filterIsInstance<RunEvent.Activity>().groupBy { it.step }
        val steps = mutableListOf<ToolStep>()
        for (step in (replies.keys + results.keys).sorted()) {
            val stepReplies = replies[step].orEmpty()
            val stepResults = results[step].orEmpty().toMutableList()
            val stepActivities = activities[step].orEmpty()
            if (stepReplies.isEmpty()) {
                stepResults.forEach { r ->
                    val activity = stepActivities.firstOrNull { it.callId == r.callId } ?: stepActivities.firstOrNull()
                    steps += ToolStep(step, null, r.tool, null, r.resultJson, activity?.failed == true, r.durationMs, null)
                }
            } else {
                stepReplies.forEach { reply ->
                    val result = stepResults.firstOrNull { it.tool == reply.tool } ?: stepResults.removeFirstOrNull()
                    val activity = stepActivities.firstOrNull { it.callId == result?.callId } ?: stepActivities.firstOrNull()
                    steps += ToolStep(
                        step = step,
                        note = reply.text.takeIf { it.isNotBlank() },
                        tool = reply.tool,
                        argsJson = reply.argsJson,
                        result = result?.resultJson,
                        isError = activity?.failed == true,
                        durationMs = result?.durationMs ?: 0,
                        model = reply.model,
                    )
                }
            }
        }
        return steps
    }
}
