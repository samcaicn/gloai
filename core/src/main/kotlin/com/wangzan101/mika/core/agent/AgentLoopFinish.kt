package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.RunInbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import com.wangzan101.mika.core.agent.AgentLoop.Companion.VERIFYING_NOTE
import com.wangzan101.mika.core.agent.AgentLoop.Companion.UNCONFIRMED_NOTE

// Agent 循环:结束、中断、改道与收尾轮
/**
 * 不带调用的回复已经记进历史、也显示出来了,这时有插话:接上插话接着做(返回 true)。
 * 屏幕上那段回复要留着:下一步一开始界面上的实时文字就清掉了,所以发 `reply_finished_before_steer` 把它挂到插话上方。
 * 没有插话返回 false,什么也不动。
 */
internal suspend fun AgentLoop.keepFinished(history: MutableList<Message>, inbox: RunInbox?): Boolean {
    val steers = inbox?.takeSteers().orEmpty()
    if (steers.isEmpty()) return false
    val current = _state.value as? TaskState.Running
    val shown = current?.preview?.trim()
    if (current?.preview != null) _state.value = current.copy(preview = null)
    // 不截短:这是一段完整的回答,界面要和没插话时一样显示全文(长度本来就受模型输出上限约束)
    if (!shown.isNullOrEmpty()) emit(RunEvent.State(now(), "reply_finished_before_steer", Redactor.text(shown)))
    appendSteers(history, steers)
    return true
}

/** 插话只取消正在等待的模型请求(本步不写进历史,下一步带着插话重问);工具调用进入执行入口后由它自己做完。 */
internal suspend fun AgentLoop.routedOrRedirect(
    inbox: RunInbox?, history: List<Message>, specs: List<ToolSpec>, track: StepTrack, step: Int, restarted: (Long) -> Unit,
    onText: ((String) -> Unit)?,
): Pair<ModelReply, ChatModel>? {
    if (inbox == null) return caller.routed(history, specs, track, step, restarted, onText)
    return coroutineScope {
        val pending = async { caller.routed(history, specs, track, step, restarted, onText) }
        select<Pair<ModelReply, ChatModel>?> {
            pending.onAwait { it }
            inbox.redirects.onReceive {
                pending.cancelAndJoin()
                null
            }
        }
    }
}

/**
 * 插话撤掉了第 [step] 步的请求:用户已经看到的那段字(界面上的 preview)作为一条只有文字的回复记进历史,
 * 接着才是插话,模型下一次看到的和用户看到的一样。没看到字、或没有插话时什么也不记。
 * 思考内容和半截工具调用本来就不在 preview 里。界面上的那段字在这里清掉,改由 `reply_interrupted` 事件挂到插话上方。
 */
internal suspend fun AgentLoop.keepInterrupted(history: MutableList<Message>, inbox: RunInbox?, step: Int) {
    val steers = inbox?.takeSteers().orEmpty()
    interruptedStep = step
    val current = _state.value as? TaskState.Running
    val partial = current?.takeIf { it.step == step }?.preview?.trim()
    if (current?.preview != null) _state.value = current.copy(preview = null)
    if (!partial.isNullOrEmpty() && steers.isNotEmpty()) {
        history += Message(Role.Assistant, listOf(Part.Text(partial)), Author.Mika)
        emit(RunEvent.State(now(), "reply_interrupted", RunEvent.clip(Redactor.text(partial))))
    }
    appendSteers(history, steers)
}

/**
 * 用户同意了、还没用掉的那次操作,或回答了的追问([answered] 是那个问题,之后又做了别的就是 null):回答交出前还欠着的下文;
 * 都没有为 null。
 */
internal fun AgentLoop.owedFollowUp(answered: String?): String? {
    confirmations?.granted?.let { r ->
        val what = listOfNotNull(r.action.name, r.target.takeIf(String::isNotBlank), r.content.takeIf(String::isNotBlank)?.let { "「$it」" }).joinToString(" ")
        return Feedback.format("followup_approved", "action" to what)
    }
    return answered?.let { Feedback.format("followup_answered", "prompt" to it.take(200)) }
}

/**
 * 第 [step] 步的回复没调工具,文字 [answer] 就是最终回答:按风险核对([AnswerReview]),来源由代码找。
 * 核对没对上:能退回([history] 不为 null,而且本任务还没退回过)就提醒模型一次、返回 null 接着做;
 * 否则照样交出,没对上的几处标成未确认。[facts] 是核对要看的任务情况(见 [ReviewFacts])。
 */
internal suspend fun AgentLoop.conclude(task: String, answer: String, step: Int, facts: ReviewFacts, history: MutableList<Message>?): TaskState? {
    if (!reviewFinalAnswer) {
        val sources = review.sourcesOnly(task, answer, media)
        if (sources.isNotEmpty()) emit(RunEvent.Sources(now(), sources))
        return TaskState.Succeeded(answer, verified = false, sources = sources, reviewed = false)
    }
    val r = review.review(task, answer, step, facts, earlierGoals, media) {
        _state.value = TaskState.Running(step, VERIFYING_NOTE, reason = VERIFYING_NOTE,
            preview = (_state.value as? TaskState.Running)?.preview)
    }
    val missing = r.unconfirmed
    if (missing.isNotEmpty() && history != null && !gapsNudged) {
        gapsNudged = true
        TurnPolicy.unconfirmed(r.gaps, r.objections).let { history += ctx.notice(it.source, it.text) }
        return null
    }
    if (missing.isNotEmpty()) emit(RunEvent.Unconfirmed(now(), step, missing.map(::detail)))
    if (r.sources.isNotEmpty()) emit(RunEvent.Sources(now(), r.sources))
    return if (missing.isEmpty()) TaskState.Succeeded(answer, r.verified, r.note, r.sources, reviewed = r.reviewed)
    else TaskState.Succeeded(answer, verified = false, note = UNCONFIRMED_NOTE + missing.joinToString("、"), sources = r.sources, unconfirmed = missing)
}

/**
 * 步数用完后最后给一轮,工具只剩 end_not_done:做完了就用文字回答(照样核对,但不再退回,没对上的标成未确认),
 * 做不成就如实说查过什么。这一轮什么也没给(或被截断)仍算步数用完。
 */
internal suspend fun AgentLoop.finalTurn(
    task: String, history: MutableList<Message>, specs: List<ToolSpec>, step: Int, labels: Map<String, String>, place: Place?, recorder: ActionRecorder,
    facts: ReviewFacts, inbox: RunInbox?, progress: TaskProgressTracker, closeout: Closeout = Closeout.StepLimit,
): TaskState {
    fun acceptProgress(parsed: CompletionPlanBlock.Extracted) = progress.acceptParsed(parsed, step)
    TurnPolicy.closeout(closeout, progress.state(step)).let { history += ctx.notice(it.source, it.text) }
    repeat(3) { attempt ->
        appendSteers(history, inbox)
        appendRuntimeState(history, place, labels, step, progress.pending(step))
        _state.value = TaskState.Running(step, "", progress = progress.state(step))
        val asked = now()
        val (reply, used) = try {
            caller.askRouted(history, TurnPolicy.finalTools(specs, tools::verb), step, onText = previewFor(step))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError.TaskBudgetExceeded) {
            return StopRules.modelFailed(e)
        } catch (e: ModelError.DailyBudgetExceeded) {
            return StopRules.modelFailed(e)
        } catch (e: Exception) {
            return StopRules.closeoutFailed(closeout, maxSteps)
        }
        val rawText = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.trim()
        val parsed = CompletionPlanBlock.extract(rawText)
        parsed.stage?.let { stages?.reported(it) }
        acceptProgress(parsed)
        val text = parsed.text
        history += Message(Role.Assistant, cleanReplyParts(reply.parts, text), Author.Mika)
            .also { caller.noteReply(it, used, reply.usage) }
        val calls = ReplyCalls(reply.parts, ::acceptProgress)
        val call = calls.actions.firstOrNull { tools.verb(it.name) in TurnPolicy.FINAL_VERBS }
        val retryWaitMs = caller.lastRetryWaitMs
        val repliedAtMs = now()
        val latencyMs = repliedAtMs - asked - retryWaitMs
        emit(RunEvent.ModelReply(
            now(), step, RunEvent.clip(Redactor.text(text)), call?.name, call?.let { Redactor.args(tools.verb(it.name), it.argsJson, secretField = false) },
            reply.usage?.inputTokens, reply.usage?.outputTokens, reply.usage?.cachedInputTokens, latencyMs, retryWaitMs = retryWaitMs,
            model = used.name, role = if (caller.recordRoles) caller.roleOf(used) else null,
            group = caller.groupOf(used), repliedAtMs = repliedAtMs,
        ))
        // 收尾模型也可能正在生成时收到插话。最多重答两次，其余交给下一任务。
        if (attempt < 2 && inbox?.hasSteers() == true) {
            if (!calls.isEmpty) history += calls.results()
            if (keepFinished(history, inbox)) return@repeat
        }
        if (call == null) {
            if (calls.action == null && !calls.isEmpty && text.isEmpty()) {
                history += calls.results()
                return@repeat
            }
            if (calls.action != null || text.isEmpty() || reply.stopReason == StopReason.MaxTokens) return StopRules.closeoutFailed(closeout, maxSteps)
            return conclude(task, text, step, facts, history = null)!!
        }
        val started = now()
        // 只剩放弃,不改变手机
        val exec = tools.executeRead(call)
        recorder.result(step, call.name, tools.verb(call.name), exec.content, exec.record, null, null, now() - started, "model", call.id)
        return StopRules.afterFinalTurn(exec.outcome, maxSteps)
    }
    return StopRules.closeoutFailed(closeout, maxSteps)
}
