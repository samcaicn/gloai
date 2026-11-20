package com.wangzan101.mika.task

import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.JudgedOutcome
import com.wangzan101.mika.core.agent.LearnGate
import com.wangzan101.mika.core.agent.OutcomeJudge
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.StopSummary
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.knowledge.AppKnowledgeDrafter
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.knowledge.KnowledgeStore
import com.wangzan101.mika.core.memory.MemoryExtractor
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.memory.TaskMemoryRecorder
import com.wangzan101.mika.core.memory.UserUtterances
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.proactive.NoticeRuns
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.replay.RouteRecorder
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.skills.SkillDrafter
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.replay.FileRouteStore
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.settings.MemorySwitches
import com.wangzan101.mika.ui.ToolLabels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

/** 学习相关的一组运行时依赖;由 execute 开局时拼好,收尾/学习阶段读它。 */
internal class LearnKit(
    val switches: MemorySwitches, val memory: MemoryStore?, val utterances: UserUtterances?, val dispatcher: ToolDispatcher,
    val agent: AgentLoop, val catalog: GuideCatalog, val knowledge: KnowledgeStore,
    val model: ChatModel, val light: ChatModel?, val runStore: RunStore?,
    val routesOn: Boolean, val priorTurns: List<Turn>,
)

/**
 * 收尾后的交代与学习(断点交代、停止交代、结局判定、记忆提取、路线记录)。原 TaskRun 里的对应函数整体搬到这里。
 * 需要读的 TaskRun 瞬时字段经 lambda 注入。
 */
internal class RunTeardown(
    private val service: TaskService,
    private val conversationId: String,
    private val goal: String,
    private val state: TaskRunState,
    private val kit: () -> LearnKit?,
    private val env: TaskEnvironment,
    private val session: ModelSession,
    private val memoryRuntime: MemoryRuntime,
    private val routeStore: FileRouteStore,
    private val workflowAttemptId: () -> String?,
    private val noticeTainted: () -> Boolean,
    private val provenance: () -> RunProvenance,
) {
    /** 真正失败后的断点交代:只按记录拼,不再请模型,也不把短失败原因改写成成功。 */
    suspend fun checkpointSummary(): String? {
        val k = kit()
        val runId = state._runId.value
        val recorded = withContext(Dispatchers.IO) {
            try {
                runId?.let { id -> k?.runStore?.events(id) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        val events = recorded?.takeIf { StopSummary.worthSaying(it, null) } ?: synchronized(state.activityLog) { state.activityLog.toList() }
        if (!StopSummary.worthSaying(events, null)) return null
        val lang = com.wangzan101.mika.i18n.AppLanguage.resolved(service).tag
        val labels = ToolLabels(knownTools())
        return StopSummary.plain(k?.agent?.taskText ?: goal, events, null, labels::label, {
            ConfirmationNotifier.appLabel(service, it)
        }, language = lang)
    }

    /**
     * 用户停止之后:按这次已经做了的事写几行交代(见 [StopSummary]),只读运行记录,不再操作任何东西。
     * 轻量模型优先,没配用主模型;没模型、出错、超时就按记录直接拼。没什么可交代的为 null。
     */
    suspend fun stopSummary(draft: String?): String? {
        val k = kit()
        val runId = state._runId.value
        val recorded = withContext(Dispatchers.IO) { runId?.let { id -> runCatching { k?.runStore?.events(id) }.getOrNull() } }
        val events = recorded?.takeIf { e -> e.any { it is RunEvent.Activity } } ?: synchronized(state.activityLog) { state.activityLog.toList() }
        if (!StopSummary.worthSaying(events, draft)) return null
        // 还没开出模型就停下的(准备阶段):按设置现取
        val (main, light) = if (k != null) k.model to k.light else withContext(Dispatchers.IO) {
            runCatching { env.modelFactory(service, session) }.getOrNull() to runCatching { env.lightModelFactory(service, session) }.getOrNull()
        }
        val lang = com.wangzan101.mika.i18n.AppLanguage.resolved(service).tag
        val labels = ToolLabels(knownTools())
        return StopSummary(light ?: main, main, language = lang).summarize(k?.agent?.taskText ?: goal, events, draft, labels::label) {
            ConfirmationNotifier.appLabel(service, it)
        }
    }

    /**
     * 结果交给用户之后调用(不拖慢、不改交出的回答):回答按用户的要求判结局([OutcomeJudge],轻量模型优先),
     * 记进运行记录和这一轮对话;只有办成的([LearnGate])才记路线、给回放的路线记成功。
     * 放弃、失败、取消的不判(结局已经清楚),返回 null;判不成是 [RunOutcome.Unknown]。
     */
    suspend fun settleOutcome(end: TaskState, conversations: ConversationStore): JudgedOutcome? {
        val k = kit() ?: return null
        if (end !is TaskState.Succeeded) return null
        val judged = OutcomeJudge(k.light ?: k.model, k.model).judge(k.agent.taskText ?: goal, end.answer, k.priorTurns)
        val runId = state._runId.value
        if (runId != null) withContext(Dispatchers.IO) {
            k.runStore?.append(runId, judged.event(System.currentTimeMillis()))
            if (BuildConfig.DEBUG) k.runStore?.mirrorToExternal(runId)
            runCatching { conversations.updateTurn(conversationId, runId) { it.copy(result = judged.outcome.wire) } }
                .onFailure { Log.w(TAG, "could not save run outcome", it) }
        }
        k.agent.noteOutcome(judged.outcome)
        // 只有办成的才记成路线;从落盘的运行事件里取
        if (workflowAttemptId() == null && runId != null && LearnGate.shouldSaveRoute(k.routesOn, end, judged.outcome, runId, goal)) k.runStore?.let { s ->
            saveRoute(goal, withContext(Dispatchers.IO) { s.events(runId) }, k.dispatcher.index)
        }
        return judged
    }

    /**
     * 结果通知发出之后调用:记下用到的个人技能的结果、提取记忆、起草技能,提示挂到这一轮的结果卡片上并存进对话。
     * 在进程级的作用域里跑,服务退出也不中断。
     */
    suspend fun learnAfter(end: TaskState, outcome: RunOutcome?, conversations: ConversationStore) {
        rememberTask()
        val k = kit() ?: return
        val runId = state._runId.value
        val learning = RunLearning(
            k.switches, k.memory, memoryRuntime.skills(), k.catalog,
            extractor = k.memory?.let { MemoryExtractor(k.light ?: k.model) },
            drafter = SkillDrafter(k.model),
            appDrafter = AppKnowledgeDrafter(k.light ?: k.model),
            appDrafts = memoryRuntime.appKnowledge(),
            screenMaps = memoryRuntime.screenMaps(),
            ids = { UUID.randomUUID().toString() },
            autoApprove = env.evalHooks.autoApproveSkillDrafts,
            yieldToForeground = { while (service.hasActiveRuns()) delay(500) },
        )
        val notes = learning.learn(RunAftermath(
            conversationId, runId, k.agent.taskText ?: goal, end, k.utterances.takeIf { k.memory != null }?.all.orEmpty(), k.dispatcher.savedChanges, k.agent.usedMemoryIds,
            k.dispatcher.definitions().map { it.name }.toSet(), k.dispatcher.index,
            events = { withContext(Dispatchers.IO) { runId?.let { k.runStore?.events(it) }.orEmpty() } },
            origin = provenance().origin,
            knowledge = k.knowledge,
            outcome = outcome ?: RunOutcome.Unknown,
            noticeTainted = noticeTainted() || k.agent.carriedObserved,
        ))
        if (runId == null || notes.memoryNotes.isEmpty() && notes.usedMemory.isEmpty() && notes.skillNotes.isEmpty()) return
        memoryRuntime.publish(notes)
        withContext(Dispatchers.IO) {
            runCatching {
                conversations.updateTurn(conversationId, runId) { it.copy(memoryNotes = notes.memoryNotes, usedMemory = notes.usedMemory, skillNotes = notes.skillNotes) }
            }.onFailure { Log.w(TAG, "could not save memory notes", it) }
        }
    }

    /**
     * 任务结束之后:按这次运行的事件记一条跨会话摘要(不拖慢交付,失败不影响任务结局)。
     */
    private suspend fun rememberTask() {
        val runId = state._runId.value ?: return
        val settings = memoryRuntime.settings()
        if (!settings.switches.value.taskMemoryEnabled) return
        val events = withContext(Dispatchers.IO) { runCatching { kit()?.runStore?.events(runId) }.getOrNull() } ?: return
        val labels = ToolLabels(knownTools())
        val recorded = TaskMemoryRecorder.record(goal, events, System.currentTimeMillis(), UUID.randomUUID().toString(), labels::label) {
            ConfirmationNotifier.appLabel(service, it)
        } ?: return
        val task = NoticeRuns.summary(recorded, events, noticeTurnInContext = noticeTainted())
        withContext(Dispatchers.IO) {
            if (!settings.switches.value.taskMemoryEnabled) return@withContext
            runCatching { memoryRuntime.taskMemory().add(task) }
                .onFailure { Log.w(TAG, "task memory not saved", it) }
        }
    }

    /** 办成的运行记成路线;记不成(不该记、写盘失败)不影响任务结局。 */
    private suspend fun saveRoute(goal: String, events: List<RunEvent>, tools: ToolIndex) = withContext(Dispatchers.IO) {
        runCatching {
            val route = RouteRecorder.record(goal, events, System.currentTimeMillis(), tools) ?: return@runCatching
            val versions = route.packages.mapNotNull { p ->
                runCatching { service.packageManager.getPackageInfo(p, 0).versionName }.getOrNull()?.let { p to it }
            }.toMap()
            routeStore.putReplacing(route.copy(appVersions = versions))
        }.onFailure { Log.w(TAG, "route not saved", it) }
    }

    private companion object {
        const val TAG = "MikaTask"
    }
}
