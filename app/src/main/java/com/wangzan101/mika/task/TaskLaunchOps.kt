package com.wangzan101.mika.task

import com.wangzan101.mika.i18n.tr
import android.app.NotificationManager
import android.os.RemoteException
import android.util.Log
import android.widget.Toast
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.ConversationTitler
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.trigger.ConditionOutcome
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.settings.FeatureFlags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import com.wangzan101.mika.pocket.LockTriggerPolicy
import com.wangzan101.mika.task.TaskService.Companion.ID_ATTENTION
import com.wangzan101.mika.task.TaskService.Companion.TAG
import com.wangzan101.mika.task.TaskService.Companion.SHELL_DIED
import com.wangzan101.mika.task.TaskService.Companion.start
import com.wangzan101.mika.task.TaskService.Companion.cancel
import com.wangzan101.mika.task.TaskService.Companion.answer
import com.wangzan101.mika.task.TaskService.Companion.describe

// 任务服务:新任务开始、命名对话与运行启动
/** 正在采集或暂停的示范里不开始任务，也不把排队项取走。 */
internal fun TaskService.teachingBlocks(): Boolean {
    val app = application as? com.wangzan101.mika.MikaApp ?: return false
    return app.teachReady && app.teach.blocksTasks()
}

/** A start rejected before a TaskRun exists still has to close its external owners. */
internal fun TaskService.finishRejectedStart(triggerKey: String?, mcpExternalId: String?) {
    val reason = tr(R.string.teach_blocked_task)
    triggerKey?.let { TriggerRuntime.actionFailed(this, it, reason) }
    mcpExternalId?.let {
        (application as com.wangzan101.mika.MikaApp).remoteTasks.ended(
            it, TaskState.Failed(reason, FailKind.Environment), emptyList(),
        )
    }
}

internal fun TaskService.begin(newGoal: String, requestedConversation: String?,
    provenance: RunProvenance = RunProvenance(), triggerKey: String? = null,
    triggerActionGoal: String? = null, triggerThreadId: String? = null, mcpExternalId: String? = null, messageId: String? = null,
    channelPeer: ChannelPeer? = null, channelMessageId: String? = null, triggerCondition: String? = null, workflowAttemptId: String? = null,
    brief: String? = null) {
    if (teachingBlocks()) {
        finishRejectedStart(triggerKey, mcpExternalId)
        if (provenance.origin == RunOrigin.Ui) {
            android.widget.Toast.makeText(this, tr(R.string.teach_blocked_task), android.widget.Toast.LENGTH_LONG).show()
        }
        return
    }
    parallel = FeatureFlags.get(this).speedFlags().parallelTasks
    // 同一对话里正在执行或排队的任务收到新原话时，交给它的 Inbox。
    requestedConversation?.let(center::byConversation)?.let { existing ->
        if (provenance.origin == RunOrigin.Ui) {
            if (messageId != null) existing.steer(newGoal, messageId) else existing.steer(newGoal)
            keepForeground()
            return
        }
    }
    // 插话到的时候那个任务已经收尾:作为这个对话的下一件事,不因服务还在收尾而丢掉。
    // 走同一个持久队列:即使对话刚好空着,也先写盘再认领和开始。
    if (messageId != null && requestedConversation != null) {
        val item = QueuedFollowUp(messageId, requestedConversation, newGoal, System.currentTimeMillis(), QueuedSource.Fallback,
            provenance = provenance, channelPeer = channelPeer, channelMessageId = channelMessageId)
        startFollowUp(item)
        keepForeground()
        return
    }
    if (triggerKey != null && center.runs().any { it.triggerWindowKey == triggerKey &&
            it.conditionCheck == (triggerActionGoal != null) }) {
        keepForeground()
        return
    }
    startNew(newGoal, requestedConversation, provenance, triggerKey, triggerActionGoal, triggerThreadId, mcpExternalId,
        triggerCondition = triggerCondition, channelPeer = channelPeer, channelMessageId = channelMessageId, workflowAttemptId = workflowAttemptId,
        brief = brief)
}

/**
 * 活任务检查之后的公共部分:建/排一个任务。[followUp] 是排队里取出来的项(或引导 fallback):
 * 运行真正 `markRunning` 时才结束它的持久排队生命周期;这个对话刚好又有任务时,tryStart 会在同一个锁里把它放回队列。
 */
internal fun TaskService.startNew(newGoal: String, requestedConversation: String?,
    provenance: RunProvenance = RunProvenance(), triggerKey: String? = null,
    triggerActionGoal: String? = null, triggerThreadId: String? = null, mcpExternalId: String? = null,
    followUp: QueuedFollowUp? = null, triggerCondition: String? = null,
    channelPeer: ChannelPeer? = null, channelMessageId: String? = null, workflowAttemptId: String? = null,
    brief: String? = null) {
    if (teachingBlocks()) {
        finishRejectedStart(triggerKey, mcpExternalId)
        if (followUp != null) center.followUps.add(followUp)
        else if (provenance.origin == RunOrigin.Ui) {
            android.widget.Toast.makeText(this, tr(R.string.teach_blocked_task), android.widget.Toast.LENGTH_LONG).show()
        }
        return
    }
    parallel = FeatureFlags.get(this).speedFlags().parallelTasks
    // 别的对话的任务正在跑、并行关着(或已到上限)时不丢:登记处让它排在后面,那个任务收尾时放出来。
    // 对话页先等服务空下来再交过来,但查完到交到之间别处(定时、电脑)可能先开始了一个,这里同样排上
    val store = ConversationStore.shared(this)
    val conversationId = requestedConversation ?: store.newConversation()
    // 定时和电脑交来的任务不丢:这个对话还有任务时排在它后面
    val queueHere = provenance.origin == RunOrigin.Trigger || provenance.origin == RunOrigin.Mcp || provenance.origin == RunOrigin.Channel
    when (val r = center.tryStart(conversationId, newGoal, queueInConversation = queueHere,
        channelPeer = channelPeer, channelMessageId = channelMessageId, followUp = followUp)) {
        StartResult.DuplicateChannelMessage -> acknowledgeChannel(channelPeer, channelMessageId)
        StartResult.DuplicateFollowUp -> keepForeground()
        StartResult.AlreadyRunningInConversation -> keepForeground()
        is StartResult.Queued -> {
            nameConversation(store, conversationId, newGoal, provenance)
            r.run.provenance = provenance
            r.run.triggerWindowKey = triggerKey
            r.run.triggerActionGoal = triggerActionGoal
            r.run.triggerCondition = triggerCondition
            r.run.triggerThreadId = triggerThreadId
            if (triggerKey != null) center.store.get(com.wangzan101.mika.core.run.RunId(r.run.key))?.let {
                center.store.save(it.copy(triggerWindowKey = triggerKey, userText = triggerCondition))
            }
            r.run.mcpExternalId = mcpExternalId
            r.run.workflowAttemptId = workflowAttemptId
            r.run.brief = brief
            r.run.channelPeer = channelPeer
            r.run.channelMessageId = channelMessageId
            acceptChannel(r.run)
            acknowledgeChannel(channelPeer, channelMessageId)
            taskState._busy.value = true
            taskState._conversationId.value = conversationId
            // 先登记再显示:界面收到它的状态时,任务列表里已经有它(对话、原话)
            publishRuns()
            // 清空成功标准、运行编号,显示排队
            if (center.running().none { it.conversationId == conversationId }) r.run.show()
            r.run.queue(r.position)
            keepForeground()
        }
        is StartResult.Started -> {
            nameConversation(store, conversationId, newGoal, provenance)
            taskState._busy.value = true
            taskState._conversationId.value = conversationId
            // 清空成功标准、运行编号,状态置为「正在准备」
            val run = r.run
            run.provenance = provenance
            run.triggerWindowKey = triggerKey
            run.triggerActionGoal = triggerActionGoal
            run.triggerCondition = triggerCondition
            run.triggerThreadId = triggerThreadId
            if (triggerKey != null) center.store.get(com.wangzan101.mika.core.run.RunId(run.key))?.let {
                center.store.save(it.copy(triggerWindowKey = triggerKey, userText = triggerCondition))
            }
            run.mcpExternalId = mcpExternalId
            run.workflowAttemptId = workflowAttemptId
            run.brief = brief
            run.channelPeer = channelPeer
            run.channelMessageId = channelMessageId
            acceptChannel(run)
            acknowledgeChannel(channelPeer, channelMessageId)
            // 先登记再显示:界面收到它的状态时,任务列表里已经有它(对话、原话)
            publishRuns()
            run.show()
            val fg = foreground
            if (fg != null && fg !== run && fg in center.running()) keepForeground()
            else {
                foreground = run
                startInForeground(run, progressNotification(run, run.state.value))
            }
            launch(run, store)
        }
    }
}

/** 用户(或电脑)在新对话里交来的第一句话:后台请模型起一个标题记下(只起一次,起不出来就不记)。 */
internal fun TaskService.nameConversation(store: ConversationStore, id: String, first: String, provenance: RunProvenance) {
    if (provenance.origin != RunOrigin.Ui && provenance.origin != RunOrigin.Mcp && provenance.origin != RunOrigin.Channel) return
    scope.launch(Dispatchers.IO + store.meter(id)) {
        runCatching {
            val env = TaskEnvironment.of(this@nameConversation)
            // 起标题也属于这段对话,和对话里的任务用同一个会话号
            val session = ModelSession.of(id)
            ConversationTitler.ensure(store, id, first) {
                listOfNotNull(env.lightModelFactory(this@nameConversation, session), env.modelFactory(this@nameConversation, session))
            }
        }.onFailure { if (it is CancellationException) throw it }
    }
}

/** 已经在前台(或即将收尾)时再次进入前台:用前台那个任务的进度通知。 */
internal fun TaskService.keepForeground() {
    val fg = foreground
    if (fg != null) startInForeground(fg, progressNotification(fg, fg.state.value))
    else startInForeground(null, preparingNotification())
}

internal fun TaskService.launch(run: TaskRun, store: ConversationStore) {
    val service = this
    val waitingLockWatcher = scope.launch {
        run.state.collectLatest { state ->
            if (LockTriggerPolicy.isWaiting(state)) {
                pocketGuard.lockWhileWaitingIfCovered {
                    LockTriggerPolicy.isWaiting(run.state.value) && run in center.running() &&
                        center.running().none { it !== run } && center.queued().isEmpty()
                }
            } else if (state is TaskState.Running) {
                pocketGuard.onTaskResumed()
            }
        }
    }
    val meter = store.meter(run.conversationId)
    run.job = scope.launch(meter) {
        center.markRunning(run)
        // 定时任务没人在场,卡住了没人停:跑过设置里的最长运行时间就停下(默认不限制)
        val limitMinutes = if (run.triggerWindowKey != null) FeatureFlags.get(service).triggerRunMinutes() else null
        val result = try {
            val prior = runCatching { store.turns(run.conversationId) }.getOrDefault(emptyList()).map(::withKeptState)
            if (limitMinutes == null) run.execute(prior) else withTimeout(limitMinutes * 60_000L) { run.execute(prior) }
        } catch (e: TimeoutCancellationException) {
            if (limitMinutes != null) TaskState.Failed(tr(R.string.task_trigger_time_limit, limitMinutes), FailKind.TimeLimit)
            else TaskState.Cancelled
        } catch (e: CancellationException) {
            TaskState.Cancelled
        } catch (e: RemoteException) {
            run.shellDied = true
            TaskState.Failed(SHELL_DIED, FailKind.Environment)
        } catch (e: UnattendedLockException) {
            TaskState.Failed(e.message.orEmpty(), FailKind.Environment)
        } catch (e: com.wangzan101.mika.core.device.access.AppAccessDenied) {
            val reason = when (e.code) {
                "app_blocked" -> tr(R.string.task_app_blocked, e.packageName ?: tr(R.string.task_app_this_app))
                "app_access_unavailable" -> tr(R.string.task_app_access_unavailable)
                "app_access_protected" -> tr(R.string.task_app_protected)
                else -> tr(R.string.task_app_unverified)
            }
            TaskState.Failed(reason, FailKind.Environment)
        } catch (e: Exception) {
            TaskState.Failed(tr(R.string.task_error, e.message ?: e::class.simpleName), FailKind.Environment)
        }
        // shell 进程没了,虚拟屏和上面的 App 一起没了;任务没法接着做
        val end = if (run.shellDied && result !is TaskState.Succeeded) TaskState.Failed(SHELL_DIED, FailKind.Environment) else result
        // 停下了:界面当场显示已停止,交代随后补上(先登记「正在整理」,界面收到终态时就知道还有话要来)
        val draft = if (end == TaskState.Cancelled) run.draftText() else null
        val stopRunId = run.runId.value?.takeIf { end == TaskState.Cancelled && !run.conditionCheck && run.hasStopMaterial(draft) }
        stopRunId?.let { id -> taskState._summarizing.update { it + id } }
        run.mcpExternalId?.let { (application as com.wangzan101.mika.MikaApp).remoteTasks.ended(it, end, run.steers.value, run.runId.value, run.sharedFiles.value) }
        run.end(end)
        if (!run.conditionCheck) recordTurn(store, run, end, run.runId.value, run.sharedFiles.value, run.audios.value, run.photos.value, run.joinedSteers(), run.usedGroups, run.joinedInterrupted(), run.joinedFinished())
        // Sequential workflows must know whether the step actually completed before dispatching the next one.
        // Reuse the existing outcome judge once; unknown/partial results remain for the user to resolve.
        val workflowJudged = if (run.workflowAttemptId != null) runCatching { run.settleOutcome(end, store) }.getOrNull() else null
        run.workflowAttemptId?.let { attempt ->
            withContext(Dispatchers.IO) {
                (application as com.wangzan101.mika.MikaApp).workflows.complete(attempt,
                    end is TaskState.Succeeded && workflowJudged?.outcome == RunOutcome.Done,
                    (end as? TaskState.Succeeded)?.answer.orEmpty(),
                    (end as? TaskState.Failed)?.reason ?: workflowJudged?.reason.orEmpty())
            }
        }
        stopRunId?.let { summarizeStop(run, it, draft, store) }
        if (end is TaskState.Failed && !run.conditionCheck) run.runId.value?.let { summarizeFailure(run, it, end.reason, store) }
        if (run.conditionCheck) {
            val outcome = if (end is TaskState.Succeeded) ConditionOutcome.parse(end.answer)
                else ConditionOutcome.Error((end as? TaskState.Failed)?.reason ?: tr(R.string.task_condition_interrupted))
            TriggerRuntime.checked(service, run.triggerWindowKey.orEmpty(), run.triggerActionGoal.orEmpty(),
                run.triggerThreadId.orEmpty(), outcome)
            if (outcome is ConditionOutcome.Error) {
                val failure = TaskState.Failed(tr(R.string.task_check_unfinished, outcome.reason), FailKind.Environment)
                recordTurn(store, run, failure, run.runId.value, audios = run.audios.value)
                runCatching { notifyResult(run, prepareResult(run, failure, "condition:${run.key}")) }
                    .onFailure { Log.w(TAG, "condition failure notification failed", it) }
            }
            center.markDelivered(run)
            if (outcome is ConditionOutcome.Error) withContext(NonCancellable) {
                pocketGuard.lockAfterStopIfCovered {
                    center.running().none { it !== run } && center.queued().isEmpty()
                }
            }
            onRunEnded(run, store)
            return@launch
        }
        val deliveryId = "result:${run.key}"
        run.clearTakeoverStage()
        synchronized(notifyLock) { getSystemService(NotificationManager::class.java).cancel(id(ID_ATTENTION, run)) }
        // 先发结果通知,再在后台学(提取记忆、记技能结果、起草技能);用进程级作用域,服务退出也做完
        var delivered = false
        val app = applicationContext
        runCatching {
            // Persist the whole delivery before marking the result ready; recovery must know about its images too.
            // A storage failure skips sending but must still release this task's slot below.
            val delivery = prepareResult(run, end, deliveryId)
            center.markResult(run, describe(end).second, deliveryId, successful = end is TaskState.Succeeded)
            RunLearning.launchAfter(MemoryRuntime.of(service).scope + meter, {
                delivered = notifyResult(run, delivery) == DeliveryState.Delivered
                // 失败结局不需要额外判定,送达后立即结束定时窗口。
                if (delivered && end !is TaskState.Succeeded) run.triggerWindowKey?.let { key ->
                    runCatching {
                        TriggerRuntime.actionFailed(app, key, describe(end).second)
                    }.onFailure { Log.w(TAG, "could not record trigger result", it) }
                }
            }) {
                // 交出之后:回答按用户的要求判结局(记进运行记录和这一轮对话),只从办成的学
                val judged = if (run.workflowAttemptId != null) workflowJudged else runCatching { run.settleOutcome(end, store) }.onFailure { Log.w(TAG, "outcome judging failed", it) }.getOrNull()
                // 回答如实说没办成、只办了一部分的,和放弃一样记成没办成;没判成的照交出算
                val notDone = judged?.outcome == RunOutcome.Partial || judged?.outcome == RunOutcome.NotPossible
                if (delivered && notDone && end is TaskState.Succeeded) run.triggerWindowKey?.let { key ->
                    runCatching { TriggerRuntime.actionFailed(app, key, judged?.reason?.ifBlank { null } ?: describe(end).second) }
                        .onFailure { Log.w(TAG, "could not downgrade trigger result", it) }
                } else if (delivered && end is TaskState.Succeeded) run.triggerWindowKey?.let { key ->
                    runCatching { TriggerRuntime.delivered(app, key) }
                        .onFailure { Log.w(TAG, "could not record trigger result", it) }
                }
                if (run.provenance.origin == RunOrigin.Ui && run.workflowAttemptId == null) runCatching { run.learnAfter(end, judged?.outcome, store) }
                    .onFailure { Log.w(TAG, "learning after run failed", it) }
            }
        }.onFailure { Log.w(TAG, "result notification failed", it) }
        if (delivered) center.markDelivered(run)
        if (LockTriggerPolicy.shouldCheck(end)) {
            withContext(NonCancellable) {
                pocketGuard.lockAfterStopIfCovered {
                    center.running().none { it !== run } && center.queued().isEmpty()
                }
            }
        }
        onRunEnded(run, store)
    }
    // 收尾前被取消(服务退出)、放出来还没跑就被停掉时,替它拿着的屏幕锁没交出去也没放开:这里放开
    run.job?.invokeOnCompletion { waitingLockWatcher.cancel(); run.wakeHandoff?.release(); run.wakeHandoff = null }
}
