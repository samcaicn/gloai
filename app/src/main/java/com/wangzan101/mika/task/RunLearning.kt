package com.wangzan101.mika.task

import com.wangzan101.mika.core.general.NOTICES_READ
import com.wangzan101.mika.core.proactive.ObservedContext
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.agent.LearnGate
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.learn.knowledge.FrontMatter
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.learn.knowledge.KnowledgeStore
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeDrafter
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeDraftStore
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeStage
import com.wangzan101.mika.core.learn.knowledge.AppRunInput
import com.wangzan101.mika.core.learn.memory.Change
import com.wangzan101.mika.core.learn.memory.MemoryExtractor
import com.wangzan101.mika.core.learn.memory.MemorySource
import com.wangzan101.mika.core.learn.memory.MemoryStore
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.screen.observation.PlaceKind
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.learn.skills.DraftInput
import com.wangzan101.mika.core.learn.skills.DraftResult
import com.wangzan101.mika.core.learn.skills.Eligibility
import com.wangzan101.mika.core.learn.skills.PersonalSkills
import com.wangzan101.mika.core.learn.skills.SkillDrafter
import com.wangzan101.mika.core.learn.skills.SkillFollow
import com.wangzan101.mika.core.learn.skills.SkillNote
import com.wangzan101.mika.core.learn.skills.SkillOutcomes
import com.wangzan101.mika.core.learn.skills.SkillResult
import com.wangzan101.mika.core.learn.skills.SkillStatus
import com.wangzan101.mika.core.screen.map.ScreenMapBuilder
import com.wangzan101.mika.core.screen.map.ScreenMapGrower
import com.wangzan101.mika.core.screen.map.ScreenMapStore
import com.wangzan101.mika.memory.RunNotes
import com.wangzan101.mika.settings.MemorySwitches
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wangzan101.mika.core.tool.ToolIndex

/** 一次运行结束后,学东西要用的全部材料;由 [TaskRun] 在任务收尾时交出。 */
data class RunAftermath(
    val conversationId: String,
    val runId: String?,
    val goal: String,
    val end: TaskState,
    /** 用户本次说的话;记忆关着时为空。 */
    val utterances: List<String>,
    /** 任务中 memory_save / memory_edit 写下的变更。 */
    val saved: List<Change>,
    val usedMemory: List<String>,
    val toolNames: Set<String>,
    /** 按名字查工具做的动作。 */
    val tools: ToolIndex,
    /** 这次运行的事件(从运行记录读);读不到为空。 */
    val events: suspend () -> List<RunEvent>,
    val origin: RunOrigin = RunOrigin.Ui,
    val knowledge: KnowledgeStore? = null,
    /** 交出回答后判的结局;只从办成的学([LearnGate])。没判成、没判过为 [RunOutcome.Unknown]。 */
    val outcome: RunOutcome = RunOutcome.Unknown,
    /**
     * 这段对话里有通知提醒([com.wangzan101.mika.core.agent.NoticeTurn]),或这次任务开工时带了从通知里得来的那一段
     * ([com.wangzan101.mika.core.proactive.ObservedContext]):用户在这里说的话多半是在回应别人写的通知
     * (「好,就按他说的」),和读过网页的任务一样,不自动从里面提取记忆。用户明说要记的(memory_save)不受影响。
     */
    val noticeTainted: Boolean = false,
)

/**
 * 任务结束后:记下用到的个人技能和试用中 App 知识的结果(可能自动停用、转正式或归档)、从用户的话里提取记忆、
 * 办成的任务起草技能;动过手的 App 从办成的、没办成的和磕磕绊绊的运行里起草知识补充的修订(见 [AppKnowledgeDrafter])。
 * 办成按交出回答后判的结局([RunAftermath.outcome])算,不按「交出了回答」,也不要求跑过最终核查([LearnGate])。
 * 全在结果通知发出之后的后台协程里做([launchAfter]);哪一步出错都不影响任务结局。
 *
 * 不起草的情形另加一条:这次走的是回放的路线([RunEvent.Replay] 有对上的步)——路线已经记住了,
 * 再起草只会把同一条路线抄一遍。只走了几步批量(do_steps)的照常起草,那是模型自己规划的。
 */
class RunLearning(
    private val switches: MemorySwitches,
    /** 记忆总开关关着时为 null。 */
    private val store: MemoryStore?,
    private val skills: PersonalSkills,
    private val catalog: GuideCatalog,
    private val extractor: MemoryExtractor?,
    private val drafter: SkillDrafter?,
    private val ids: () -> String,
    /** 评测钩子:草稿起好直接收下(训练轮)。 */
    private val autoApprove: Boolean = false,
    private val yieldToForeground: suspend () -> Unit = {},
    private val appDrafter: AppKnowledgeDrafter? = null,
    private val appDrafts: AppKnowledgeDraftStore? = null,
    /** 各 App 的屏图仓库;为 null 时收尾不长图。 */
    private val screenMaps: ScreenMapStore? = null,
) {
    suspend fun learn(a: RunAftermath): RunNotes {
        yieldToForeground()
        val events = guarded(emptyList()) { a.events() }
        growGraphs(a, events)
        val used = events.filterIsInstance<RunEvent.SkillUsed>()
        // 照着做了的技能(只读过的不算):记结果和要不要起草都看它
        val followed = SkillFollow.followed(events, used, a.tools, catalog::skillPlaces)
        val skillNotes = recordOutcomes(followed, used, a.end, a.outcome).toMutableList()
        // 试用中的 App 知识和个人技能按同一种结果算:取消的、没判成的不记
        val trial = SkillOutcomes.resultOf(a.end, a.outcome)?.takeIf { it != SkillResult.unreviewed }
        val trialNames = events.filterIsInstance<RunEvent.State>().filter { it.name == "knowledge_trial_used" }.map { it.detail }.distinct()
        if (trial != null) trialNames.forEach { name -> guarded(null) { appDrafts?.recordTrial(name, trial == SkillResult.verified) } }

        val changes = a.saved.toMutableList()
        val store = store
        val extractor = extractor
        // 没有运行编号就没处挂提示,用户看不到也撤销不了:不提取
        val learnable = a.origin == RunOrigin.Ui && a.end is TaskState.Succeeded
        val readWeb = events.filterIsInstance<RunEvent.Observed>().any { it.place?.kind in setOf(PlaceKind.Site, PlaceKind.Page) }
        // 读过通知的任务和读过网页的一样:用户这次说的话多半是在回应别人写的东西,不自动提取
        val readNotices = events.filterIsInstance<RunEvent.Activity>().any { it.label == NOTICES_READ }
            // 开工时就带着从通知里得来的那一段的也一样(运行记录里有那一笔;记录读不到时还有 a.noticeTainted)
            || events.filterIsInstance<RunEvent.State>().any { it.name == ObservedContext.RUN_STATE }
        if (learnable && !readWeb && !readNotices && !a.noticeTainted && a.runId != null && store != null && extractor != null && switches.autoRemember && a.utterances.isNotEmpty()) {
            val now = store.clock()
            changes += guarded(emptyList()) {
                yieldToForeground()
                extractor.extract(a.utterances, MemoryExtractor.related(a.utterances, store.items.value), store) { q ->
                    MemorySource("user_said", q, a.conversationId, now, a.origin.name)
                }
            }
        }

        // 做法只从办成的学:回答如实说没办成、只办了一部分,或没判成的,不起草
        val done = learnable && LearnGate.done(a.end, a.outcome)
        if (done) draft(a, events, followed.toList())?.let(skillNotes::add)
        // App 知识也从没办成、磕磕绊绊的运行里学(值不值得由 AppKnowledgeDrafter 按经过判)
        if (a.origin == RunOrigin.Ui) skillNotes += draftApps(a, events)
        return RunNotes(a.conversationId, a.runId.orEmpty(), MemoryExtractor.notes(changes), a.usedMemory, skillNotes)
    }

    /**
     * 每份技能单独读改写(和别的任务同时记结果也不丢);一份出错不影响别的。
     * 结果只记到这次照着做了的技能上([followed],见 [SkillFollow]):只是读过的不记;
     * 照着做了的不止一份时分不清结果归谁,一份也不记,免得没用上的技能跟着被记成没办成、被停用(#569)。
     */
    private suspend fun recordOutcomes(followed: Set<String>, used: List<RunEvent.SkillUsed>, end: TaskState, outcome: RunOutcome): List<SkillNote> {
        val result = SkillOutcomes.resultOf(end, outcome) ?: return emptyList()
        if (followed.size != 1) return emptyList()
        return used.filter { it.name in followed }.groupBy { it.name }.mapNotNull { (name, uses) ->
            val next = guarded(null) {
                skills.update(name) { s -> if (s.status == SkillStatus.active || s.status == SkillStatus.trial) SkillOutcomes.record(s, result, followed = true) else null }
            } ?: return@mapNotNull null
            when (next.status) {
                SkillStatus.suspended -> SkillNote(tr(R.string.skill_suspended, name), suspended = name)
                SkillStatus.active -> if (uses.isNotEmpty() && result == SkillResult.verified) SkillNote(tr(R.string.skill_verified, name)) else null
                else -> null
            }
        }
    }

    private suspend fun draft(a: RunAftermath, events: List<RunEvent>, used: List<String>): SkillNote? {
        val d = drafter ?: return null
        val runId = a.runId ?: return null
        if (!switches.learnSkills) return null
        if (events.any { it is RunEvent.Replay && it.matched }) return null
        // 照着个人技能做成的只记一次结果,不再起草;只是读过、没照着做的不挡起草(这次是自己办成的)
        val followed = used.firstOrNull().takeIf { SkillOutcomes.resultOf(a.end, a.outcome) == SkillResult.verified }
        val done = a.end as? TaskState.Succeeded
        if (Eligibility.check(events, followed, a.tools, done = LearnGate.done(a.end, a.outcome)) != null) return null
        val r = guarded(null) {
            yieldToForeground()
            d.draft(DraftInput(runId, a.goal, done?.answer.orEmpty(), done?.sources.orEmpty(), events, a.toolNames, a.tools), skills, catalog)
        }
        val draft = r as? DraftResult.Draft ?: return null
        // 是对一份待确认草稿的更新:写回那份草稿,不另起一份
        val id = draft.replacesPending ?: ids()
        guarded(Unit) { skills.putPending(id, draft.skill) }
        if (autoApprove) {
            guarded(null) { skills.approve(id) }
            return null
        }
        val description = FrontMatter.parse(draft.skill.markdown).first["description"] as? String ?: draft.skill.name
        return SkillNote(tr(R.string.skill_learned, description), draftId = id)
    }

    /** 这次动过手、有知识条目的每个 App 各判一次;同一个 App 已有待确认的修订就不再起草。 */
    private suspend fun draftApps(a: RunAftermath, events: List<RunEvent>): List<SkillNote> {
        val drafter = appDrafter ?: return emptyList()
        val drafts = appDrafts ?: return emptyList()
        val knowledge = a.knowledge ?: return emptyList()
        val runId = a.runId ?: return emptyList()
        if (!switches.learnSkills) return emptyList()
        val run = AppRunInput(runId, a.goal, a.end, a.outcome, events, a.tools)
        return AppKnowledgeDrafter.touched(events, a.tools, knowledge).mapNotNull { app ->
            if (guarded(true) { drafts.all().any { it.appName == app.name && it.stage == AppKnowledgeStage.Draft } }) return@mapNotNull null
            val current = guarded(null) { drafts.inUse(app.name) }
            val draft = guarded(null) {
                yieldToForeground()
                drafter.draft(run, app, knowledge.builtIn(app.name), current, ids(), events.lastOrNull()?.atMs ?: 0L)
            } ?: return@mapNotNull null
            if (!guarded(false) { drafts.putPending(draft) }) return@mapNotNull null
            SkillNote(tr(if (draft.revises != null) R.string.skill_app_revised else R.string.skill_app_draft, app.display))
        }
    }

    /** 学的每一步出错都不影响别的步骤;取消照常往外抛。 */
    private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }

    /** 收尾长图:重放本次 events 得到屏/边片段,按屏 id 并进已存图。失败只计数、不影响任务结局。 */
    private suspend fun growGraphs(a: RunAftermath, events: List<RunEvent>) {
        val store = screenMaps ?: return
        if (a.origin != RunOrigin.Ui) return
        val fragments = ScreenMapBuilder().apply(a.runId ?: "", events)
        if (fragments.isEmpty()) return
        guarded(Unit) {
            withContext(Dispatchers.IO) { ScreenMapGrower.grow(store, fragments) }
        }
    }

    companion object {
        /** 先发结果通知,再在后台学:用户先看到结果,学东西慢一点没关系。 */
        suspend fun launchAfter(scope: CoroutineScope, notify: suspend () -> Unit, learn: suspend () -> Unit): Job {
            notify()
            return scope.launch { learn() }
        }
    }
}
