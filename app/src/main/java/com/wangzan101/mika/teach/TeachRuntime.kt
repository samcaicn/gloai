package com.wangzan101.mika.teach

import android.content.Context
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.assembly.knownToolNames
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.knowledge.FrontMatter
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.skills.PersonalSkill
import com.wangzan101.mika.core.skills.PersonalSkills
import com.wangzan101.mika.core.teach.TeachDraft
import com.wangzan101.mika.core.teach.TeachObservation
import com.wangzan101.mika.core.teach.TeachPending
import com.wangzan101.mika.core.teach.TeachPhase
import com.wangzan101.mika.core.teach.TeachPublish
import com.wangzan101.mika.core.teach.TeachRoute
import com.wangzan101.mika.core.teach.TeachSession
import com.wangzan101.mika.core.teach.TeachSkill
import com.wangzan101.mika.core.teach.TeachTrace
import com.wangzan101.mika.core.teach.TeachWatch
import com.wangzan101.mika.core.teach.teachGoalAccepted
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.task.TaskCenterState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class TeachNotice { Busy, NoAccessibility, NeedGoal, SecretGoal, DraftFailed, NoSteps, SecretNote, NoVisibleStop, WatchLost }

data class TeachUi(
    val session: TeachSession? = null,
    val notice: TeachNotice? = null,
    /** 输入框里还没发出的字，只作预填，不会自己开始。 */
    val hint: String? = null,
    /** 整理好、等着用户收下的技能。 */
    val draft: PersonalSkill? = null,
    /** 被折叠掉、没有写进技能的人工步骤，只用于总结页说明。 */
    val folded: List<String> = emptyList(),
    /** 这次没有配置模型，技能由本机规则整理。 */
    val basic: Boolean = false,
    val acceptedName: String? = null,
)

/**
 * 示范会话。只有 [TeachPhase.Recording] 会收下事件。
 * 进程起来时若上次停在采集或暂停，改成已停下，不继续采。
 */
class TeachRuntime(
    private val app: Context,
    private val tasks: TaskCenterState,
) {
    private val files = TeachFiles(app.filesDir)
    private val chip = TeachChip(onPause = { pause() }, onResume = { resume() }, onEnd = { end(); openReview() })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var session: TeachSession? = null
    private var draft: PersonalSkill? = null
    private var folded: List<String> = emptyList()
    private var basic = false
    private val _ui = MutableStateFlow(TeachUi())
    val ui: StateFlow<TeachUi> = _ui.asStateFlow()

    init {
        scope.launch {
            ScreenAccessibility.instance.collect { connected ->
                if (connected == null) interrupt()
            }
        }
    }

    fun recover() {
        val loaded = files.load() ?: return
        val phase = when (loaded.phase) {
            TeachPhase.Recording, TeachPhase.Paused -> TeachPhase.Interrupted
            else -> loaded.phase
        }
        val snapshot = synchronized(lock) {
            session = loaded.copy(phase = phase)
            if (phase != loaded.phase) files.save(session!!)
            session!!
        }
        when (phase) {
            TeachPhase.Drafting -> {
                publish(null)
                scope.launch { summarize(snapshot) }
            }
            TeachPhase.Reviewing -> scope.launch {
                if (snapshot.draftId == null) {
                    publish(null)
                    summarize(snapshot)
                } else {
                    restoreDraft(snapshot)
                }
            }
            else -> publish(null)
        }
    }

    fun wantsEvents(): Boolean = synchronized(lock) { session?.phase?.collects() == true }

    fun blocksTasks(): Boolean = synchronized(lock) { session?.phase?.blocksTasks() == true }

    fun offer(event: TeachObservation) {
        val saved = synchronized(lock) {
            val cur = session ?: return
            val next = TeachTrace.reduce(cur, event, app.packageName)
            if (next === cur) return
            val grew = next.steps.size != cur.steps.size || next.phase != cur.phase || next.truncated != cur.truncated
            session = next
            publish(null)
            next.takeIf { grew }
        }
        saved?.let { files.save(it) }
    }

    fun prefill(text: String?) {
        val hint = text?.trim()?.takeIf { teachGoalAccepted(it) }
        _ui.value = _ui.value.copy(hint = hint)
    }

    fun start(goal: String): TeachNotice? {
        val clean = goal.trim()
        if (clean.isEmpty()) return fail(TeachNotice.NeedGoal)
        if (!teachGoalAccepted(clean)) return fail(TeachNotice.SecretGoal)
        if (busy()) return fail(TeachNotice.Busy)
        if (ScreenAccessibility.instance.value == null) return fail(TeachNotice.NoAccessibility)
        synchronized(lock) {
            val current = session
            if (current != null && current.phase != TeachPhase.Saved && current.phase != TeachPhase.Idle) return null
        }
        if (!chip.show(TeachPhase.Recording)) return fail(TeachNotice.NoVisibleStop)
        synchronized(lock) {
            session = TeachSession(newId(), clean, TeachPhase.Recording, updatedAt = System.currentTimeMillis())
            draft = null
            folded = emptyList()
            basic = false
            files.save(session!!)
            publish(null)
        }
        TeachNotifier.tryShow(app, TeachPhase.Recording)
        (app.applicationContext as? MikaApp)?.capsule?.yieldToTeaching()
        return null
    }

    fun pause() {
        setPhase(TeachPhase.Paused, TeachPhase.Recording)
        if (synchronized(lock) { session?.phase } == TeachPhase.Paused && !chip.show(TeachPhase.Paused)) loseWatch()
    }

    fun resume(): TeachNotice? {
        if (busy()) return fail(TeachNotice.Busy)
        if (ScreenAccessibility.instance.value == null) return fail(TeachNotice.NoAccessibility)
        synchronized(lock) {
            val cur = session ?: return TeachNotice.NeedGoal
            if (cur.phase != TeachPhase.Paused && cur.phase != TeachPhase.Interrupted) return null
        }
        if (!TeachWatch.allowsCollecting(chip.show(TeachPhase.Recording))) return fail(TeachNotice.NoVisibleStop)
        synchronized(lock) {
            val cur = session
            if (cur == null || (cur.phase != TeachPhase.Paused && cur.phase != TeachPhase.Interrupted)) {
                chip.hide()
                return if (cur == null) TeachNotice.NeedGoal else null
            }
            session = cur.copy(phase = TeachPhase.Recording, updatedAt = System.currentTimeMillis())
            files.save(session!!)
            publish(null)
        }
        TeachNotifier.tryShow(app, TeachPhase.Recording)
        return null
    }

    /** 做完了：立刻进入整理，不需要用户再点生成。 */
    fun end() {
        chip.hide()
        val snapshot = synchronized(lock) {
            val cur = session ?: return
            if (cur.phase != TeachPhase.Recording && cur.phase != TeachPhase.Paused && cur.phase != TeachPhase.Interrupted) return
            val next = cur.copy(phase = TeachPhase.Drafting, updatedAt = System.currentTimeMillis())
            session = next
            files.save(next)
            publish(null)
            next
        }
        TeachNotifier.cancel(app)
        scope.launch { summarize(snapshot) }
    }

    /** 整理失败或没看到操作后，再整理一次。 */
    fun retry() {
        val snapshot = synchronized(lock) {
            val cur = session ?: return
            if (cur.phase != TeachPhase.Reviewing || cur.draftId != null) return
            val next = cur.copy(phase = TeachPhase.Drafting, updatedAt = System.currentTimeMillis())
            session = next
            files.save(next)
            publish(null)
            next
        }
        scope.launch { summarize(snapshot) }
    }

    /** 用户看过总结后收下：这时技能才启用（试用）。 */
    fun accept(): TeachNotice? {
        val cur = synchronized(lock) { session } ?: return null
        val id = cur.draftId ?: return fail(TeachNotice.DraftFailed)
        val approved = runCatching { skills().approve(id) }.getOrNull() ?: return fail(TeachNotice.DraftFailed)
        val shown = (FrontMatter.parse(approved.markdown).first["display"] as? String)
            ?.takeIf { it.isNotBlank() } ?: approved.name
        synchronized(lock) {
            val live = session
            if (live?.id != cur.id) return null
            session = live.copy(phase = TeachPhase.Saved, acceptedName = shown, updatedAt = System.currentTimeMillis())
            files.save(session!!)
            publish(null)
        }
        return null
    }

    fun discard() {
        chip.hide()
        val rejected = synchronized(lock) {
            val id = session?.draftId
            session = null
            draft = null
            folded = emptyList()
            basic = false
            files.delete()
            publish(null)
            id
        }
        TeachNotifier.cancel(app)
        if (rejected != null) skills().reject(rejected)
    }

    /** 草稿被拒绝、删除或收下：轨迹不再需要。 */
    fun forget(skill: PersonalSkill) {
        val traceId = synchronized(lock) { session?.id ?: files.load()?.id } ?: return
        if (!TeachSkill.retiresTrace(skill, traceId)) return
        chip.hide()
        synchronized(lock) {
            if (session?.id == traceId) {
                session = null
                draft = null
                folded = emptyList()
                basic = false
                publish(null)
            }
            if (files.load()?.id == traceId) files.delete()
        }
    }

    private suspend fun summarize(snapshot: TeachSession) {
        val route = withContext(Dispatchers.IO) { foldRoute(snapshot) }
        if (route.steps.none { it.kind != null }) {
            failDraft(snapshot, TeachNotice.NoSteps)
            return
        }
        val existing = withContext(Dispatchers.IO) { TeachPending.find(skills().drafts(), snapshot.id) }
        if (existing != null) {
            val noModel = withContext(Dispatchers.IO) { model() == null }
            complete(snapshot, existing.first, existing.second, route.folded, noModel)
            return
        }
        val chosen = withContext(Dispatchers.IO) { model() }
        val skill = withContext(Dispatchers.IO) { build(snapshot, route, chosen) }
        if (skill == null) {
            failDraft(snapshot, TeachNotice.DraftFailed)
            return
        }
        val draftId = newId()
        val stored = withContext(Dispatchers.IO) {
            runCatching { TeachPublish.toPending(skills(), draftId, skill) }.isSuccess
        }
        if (!stored) {
            failDraft(snapshot, TeachNotice.DraftFailed)
            return
        }
        complete(snapshot, draftId, skill, route.folded, chosen == null)
    }

    /** 草稿已经存过、会话没来得及落盘：按轨迹编号找回，不重复生成。 */
    private suspend fun restoreDraft(snapshot: TeachSession) {
        val found = withContext(Dispatchers.IO) {
            val drafts = skills().drafts()
            snapshot.draftId?.let { id -> drafts[id]?.let { id to it } }
                ?: TeachPending.find(drafts, snapshot.id)
        }
        if (found == null) {
            publish(TeachNotice.DraftFailed)
            return
        }
        val routeFolded = withContext(Dispatchers.IO) { foldRoute(snapshot).folded }
        val noModel = withContext(Dispatchers.IO) { model() == null }
        synchronized(lock) {
            val cur = session ?: return
            if (cur.id != snapshot.id) return
            draft = found.second
            folded = routeFolded
            basic = noModel
            session = cur.copy(phase = TeachPhase.Reviewing, draftId = found.first)
            files.save(session!!)
            publish(null)
        }
    }

    private fun complete(
        snapshot: TeachSession,
        draftId: String,
        skill: PersonalSkill,
        foldedSteps: List<String>,
        noModel: Boolean,
    ) {
        synchronized(lock) {
            val cur = session
            if (cur?.id != snapshot.id || cur.phase != TeachPhase.Drafting) return
            draft = skill
            folded = foldedSteps
            basic = noModel
            session = cur.copy(phase = TeachPhase.Reviewing, draftId = draftId, updatedAt = System.currentTimeMillis())
            files.save(session!!)
            publish(null)
        }
    }

    private fun failDraft(snapshot: TeachSession, notice: TeachNotice) {
        synchronized(lock) {
            val cur = session
            if (cur?.id != snapshot.id || cur.phase != TeachPhase.Drafting) return
            session = cur.copy(phase = TeachPhase.Reviewing, updatedAt = System.currentTimeMillis())
            files.save(session!!)
            publish(notice)
        }
    }

    private suspend fun build(snapshot: TeachSession, route: TeachRoute, chosen: ChatModel?): PersonalSkill? {
        val skills = skills()
        val bundled = GuideCatalog.loadBundled()
        val taken = { name: String ->
            skills.get(name) != null || skills.pending().any { it.name == name } || bundled.items.any { it.name == name }
        }
        return TeachDraft.draft(
            snapshot, route, chosen, knownToolNames(), knownTools().nameOf(Verb.RunBundledScript),
            bundled.runnableScripts(), taken, System.currentTimeMillis(),
        )
    }

    private fun foldRoute(snapshot: TeachSession): TeachRoute =
        TeachRoute.fold(snapshot.steps, TeachApps.launchers(app), TeachApps.defaultBrowsers(app)) { pkg ->
            TeachApps.label(app, pkg)
        }

    private fun model(): ChatModel? {
        val store = ProviderStore.get(app)
        return store.modelFor(ModelRole.Light) ?: store.modelFor(ModelRole.Main)
    }

    private fun skills(): PersonalSkills = (app.applicationContext as MikaApp).memory.skills()

    private fun interrupt() = loseWatch()

    /** 浮层或无障碍没了：停止采集，并留下原因。不再记录。 */
    private fun loseWatch() {
        chip.hide()
        val stopped = synchronized(lock) {
            val cur = session ?: return@synchronized false
            val next = TeachWatch.afterControlLost(cur.phase)
            if (next == cur.phase) return@synchronized false
            session = cur.copy(phase = next, updatedAt = System.currentTimeMillis())
            files.save(session!!)
            true
        }
        TeachNotifier.cancel(app)
        if (stopped) publish(TeachNotice.WatchLost)
    }

    private fun openReview() {
        val intent = com.wangzan101.mika.task.TaskService.openAppIntent(app)
            .putExtra(com.wangzan101.mika.ui.MainActivity.EXTRA_OPEN_TEACH, true)
        runCatching { app.startActivity(intent) }
    }

    private fun setPhase(next: TeachPhase, from: TeachPhase) {
        synchronized(lock) {
            val cur = session ?: return
            if (cur.phase != from) return
            session = cur.copy(phase = next, updatedAt = System.currentTimeMillis())
            files.save(session!!)
            publish(null)
        }
        TeachNotifier.tryShow(app, next)
    }

    private fun busy(): Boolean = tasks.busy.value || tasks.runs.value.isNotEmpty()

    private fun publish(notice: TeachNotice?) {
        _ui.value = _ui.value.copy(
            session = session,
            notice = notice,
            draft = draft,
            folded = folded,
            basic = basic,
            acceptedName = session?.acceptedName,
        )
    }

    private fun fail(notice: TeachNotice): TeachNotice {
        _ui.value = _ui.value.copy(notice = notice)
        return notice
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "")
}
