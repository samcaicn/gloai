package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.safety.guard.OperationGuards
import com.wangzan101.mika.core.safety.guard.GuardVerdict
import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.screen.observation.tag
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Agent 循环:一批工具调用的执行与结果事件
/**
 * 执行一次 do_steps:整批对步数上限算一步,每个子步对守卫和运行记录各算一个动作(由 [BatchRunner] 记)。
 * 批停下时让下一步回到主模型。返回非 null 表示任务就此结束。
 */
internal suspend fun AgentLoop.batch(
    first: Part.ToolCall, calls: ReplyCalls, history: MutableList<Message>, specs: List<ToolSpec>, guards: OperationGuards,
    executor: ActionExecutor, track: StepTrack, eachLoop: EachLoopDetector, step: Int, note: String, tool: String?, reason: String?, narration: String?,
    recordReply: (String?) -> Unit, app: (Place) -> Unit, steerWaiting: () -> Boolean,
    noteVerdicts: (BatchStep, ToolExecution?, List<GuardVerdict>) -> String?,
): TaskState? {
    // 一批打断了逐条干活的连击(这正是提醒想要的方向):连着次数清零重算
    eachLoop.reset()
    // 收着的组里的工具也能放进批里(和单步一样照常执行、展开它那一组)
    val (parsed, problem) = BatchRunner.parse(first.argsJson, tools.definitions().map { it.name }.toSet(), tools.batchable())
    // 一批只在一块屏上做:混了两块屏的整批不做
    val mixed = parsed?.let { BatchRunner.mixedSurface(it, tools::surfaceOf) }
    val steps = parsed.takeIf { mixed == null }
    val running = TaskState.Running(step, note, tool, steps?.let { RunProgress.Batch(it.size) }, reason, narration)
    if (steps != null) _state.value = running
    val started = now()
    var runner: BatchRunner? = null
    val exec = if (steps == null) ToolExecution(buildJsonObject {
        put("ok", false); put("error", if (mixed != null) "mixed_surface" else "invalid_arguments"); put("message", mixed ?: problem); put("recoverable", true)
    }) else {
        gateState = running
        runner = BatchRunner(tools, guards, executor, noteVerdicts = noteVerdicts, steerWaiting = steerWaiting)
        runner.run(steps, first.id, step)
    }
    val durationMs = now() - started
    val isError = exec.content["ok"] != JsonPrimitive(true)
    history += calls.results(Part.ToolResult(first.id, ScreenElementTable.text(exec.content, flags.tablesAtBirth && !isError), isError, exec.observations.map { it.tag }))
    // 批做完(或停下)后再给模型一份当前元素表；只有元素信息不足才附画面。
    if (steps != null && ranAnyStep(exec.content)) {
        tools.lookNow()?.let { v ->
            history += Message(Role.User, listOfNotNull(Part.Text(batchViewNote(ScreenElementTable.text(v.content, flags.tablesAtBirth))), v.image?.let { tools.modelImage(it, v.imageId) }), Author.Mika)
        }
    }
    recordReply(BatchRunner.redactArgs(first.argsJson))
    val picture = exec.frame
    val shotId = picture?.let { executor.recorder.nextShot() }
    // 子步之后按条件等界面的时间另记,小结里算等待
    emit(RunEvent.ToolResult(now(), step, first.name, RunEvent.clipResult(Redactor.text(exec.content.toString())), exec.changed, shotId, durationMs,
        waitMs = runner?.waitedMs ?: 0, callId = first.id))
    val currentPlace = exec.place
    currentPlace?.let(app)
    // 要确认不算失败(和单步被拦一样不进失败熔断),但同样交给主模型
    // 为新插话停下的批同样不算失败
    val confirm = exec.content["error"] == JsonPrimitive("confirmation_required") || exec.content["reason"] == JsonPrimitive(BatchRunner.NEW_MESSAGE)
    track.after(tools.verb(first.name), !isError || confirm, runner?.verdicts?.isNotEmpty() == true || exec.content["advice"] != null, exec.changed, false, currentPlace?.name)
    // 批停下(核对没过、要确认):下一步交给主模型
    if (isError) track.markRecovery()
    if (picture != null && shotId != null) {
        record { sink.media(shotId, picture) }
        media.getOrPut(step) { mutableListOf() } += shotId
        emit(RunEvent.Media(now(), shotId, step, RunEvent.placeLabel(currentPlace)))
    }
    return guards.stop?.let(StopRules::guard)
}

internal fun AgentLoop.previewFor(step: Int): ((String) -> Unit)? = if (!streamReplies) null else { raw ->
    val preview = CompletionPlanBlock.visible(raw)
    val current = _state.value as? TaskState.Running
    // 撤掉之后网络线程可能还送来一段:不再显示,免得屏幕上比历史里多
    if (current?.step == step && interruptedStep != step) _state.value = current.copy(preview = preview.takeIf(String::isNotEmpty))
}

/** do_steps 的结果里至少有一个子步真的执行了(没执行的停止项标着 executed=false)。 */
internal fun AgentLoop.ranAnyStep(content: JsonObject): Boolean =
    (content["steps"] as? JsonArray)?.any { (it as? JsonObject)?.get("executed") != JsonPrimitive(false) } == true

/** 运行记录里按结果另记的事:报告的障碍(供评测按 barrier 类归因;参数不对被拒的不算)、看过的个人技能、存下的记忆。 */
internal fun AgentLoop.noteResultEvents(verb: Verb?, content: JsonObject) {
    if (verb == Verb.HandleObstacle && content["ok"] == JsonPrimitive(true)) {
        (content["kind"] as? JsonPrimitive)?.contentOrNull?.let { emit(RunEvent.Barrier(now(), it)) }
    }
    GuideRecommender.viewedPersonal(verb, content)?.let { emit(RunEvent.SkillUsed(now(), it, "viewed")) }
    if (verb == Verb.MemorySave && content["ok"] == JsonPrimitive(true)) emit(RunEvent.MemorySaved(now(),
        content["id"]?.jsonPrimitive?.content ?: "", content["kind"]?.jsonPrimitive?.content ?: "", content["status"]?.jsonPrimitive?.content ?: ""))
}

/** MCP 调用进行中把 Running 标上连接器名,结束(含失败、取消)时收回;返回的闭包由调用方在 finally 里调。 */
internal fun AgentLoop.showMcpCall(connector: String): () -> Unit {
    val before = _state.value
    if (before !is TaskState.Running) return {}
    val calling = before.copy(mcpConnector = connector)
    _state.compareAndSet(before, calling)
    return { _state.compareAndSet(calling, before) }
}
