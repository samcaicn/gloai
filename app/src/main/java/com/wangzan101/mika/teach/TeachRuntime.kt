package com.wangzan101.mika.teach

import android.content.Context
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.assembly.knownToolNames
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.skills.PersonalSkill
import com.wangzan101.mika.core.teach.TeachDraft
import com.wangzan101.mika.core.teach.TeachEdit
import com.wangzan101.mika.core.teach.TeachObservation
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
            TeachPhase.Drafting -> TeachPhase.Reviewing
            else -> loaded.phase
        }
        synchronized(lock) {
            session = loaded.copy(phase = phase)
            publish(null)
            if (phase != loaded.phase) files.save(session!!)
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
        if (clean.isEmpty()) return TeachNotice.NeedGoal
        if (!teachGoalAccepted(clean)) return TeachNotice.SecretGoal
        if (busy()) return TeachNotice.Busy
        if (ScreenAccessibility.instance.value == null) return TeachNotice.NoAccessibility
        synchronized(lock) {
            val current = session
            if (current != null && current.phase != TeachPhase.Saved && current.phase != TeachPhase.Idle) return null
        }
        if (!chip.show(TeachPhase.Recording)) return TeachNotice.NoVisibleStop
        synchronized(lock) {
            session = TeachSession(newId(), clean, TeachPhase.Recording, updatedAt = System.currentTimeMillis())
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

    fun end() {
        chip.hide()
        synchronized(lock) {
            val cur = session ?: return
            if (cur.phase != TeachPhase.Recording && cur.phase != TeachPhase.Paused && cur.phase != TeachPhase.Interrupted) return
            session = cur.copy(phase = TeachPhase.Reviewing, updatedAt = System.currentTimeMillis())
            files.save(session!!)
            publish(null)
        }
        TeachNotifier.cancel(app)
    }

    fun discard() {
        chip.hide()
        val draft = synchronized(lock) {
            val id = session?.draftId
            session = null
            files.delete()
            publish(null)
            id
        }
        TeachNotifier.cancel(app)
        if (draft != null) (app.applicationContext as? MikaApp)?.memory?.skills()?.reject(draft)
    }

    fun remove(stepId: Int) = edit { TeachTrace.remove(it, stepId) }

    fun annotate(stepId: Int, correction: String?, explanation: String?): TeachNotice? {
        val cur = synchronized(lock) { session } ?: return null
        return when (val edited = TeachTrace.annotate(cur, stepId, correction, explanation)) {
            is TeachEdit.Ok -> {
                synchronized(lock) {
                    session = edited.session
                    files.save(edited.session)
                    publish(null)
                }
                null
            }
            TeachEdit.Secret -> fail(TeachNotice.SecretNote)
            TeachEdit.Missing -> null
        }
    }

    fun generate() {
        val snapshot = synchronized(lock) {
            val cur = session ?: return
            if (cur.phase != TeachPhase.Reviewing) return
            if (cur.steps.isEmpty()) {
                publish(TeachNotice.NoSteps)
                return
            }
            session = cur.copy(phase = TeachPhase.Drafting)
            publish(null)
            cur
        }
        scope.launch {
            val skill = withContext(Dispatchers.IO) { build(snapshot) }
            synchronized(lock) {
                val cur = session
                if (cur?.id != snapshot.id || cur.phase != TeachPhase.Drafting) return@launch
                if (skill == null) {
                    session = snapshot.copy(phase = TeachPhase.Reviewing)
                    publish(TeachNotice.DraftFailed)
                    return@launch
                }
                val draftId = newId()
                val stored = runCatching {
                    TeachPublish.toPending((app.applicationContext as MikaApp).memory.skills(), draftId, skill)
                }.isSuccess
                if (!stored) {
                    session = snapshot.copy(phase = TeachPhase.Reviewing)
                    publish(TeachNotice.DraftFailed)
                    return@launch
                }
                session = snapshot.copy(phase = TeachPhase.Saved, draftId = draftId, updatedAt = System.currentTimeMillis())
                files.save(session!!)
                publish(null)
            }
        }
    }

    /** 草稿被拒绝、删除或收下：轨迹不再需要。 */
    fun forget(skill: PersonalSkill) {
        val traceId = synchronized(lock) { session?.id ?: files.load()?.id } ?: return
        if (!TeachSkill.retiresTrace(skill, traceId)) return
        chip.hide()
        synchronized(lock) {
            if (session?.id == traceId) {
                session = null
                publish(null)
            }
            if (files.load()?.id == traceId) files.delete()
        }
    }

    private suspend fun build(snapshot: TeachSession): PersonalSkill? {
        val store = ProviderStore.get(app)
        val model = store.modelFor(ModelRole.Light) ?: store.modelFor(ModelRole.Main)
        val skills = (app.applicationContext as MikaApp).memory.skills()
        val bundled = GuideCatalog.loadBundled()
        val taken = { name: String ->
            skills.get(name) != null || skills.pending().any { it.name == name } || bundled.items.any { it.name == name }
        }
        return TeachDraft.draft(
            snapshot, TeachRoute.fold(snapshot.steps, emptySet(), emptySet()) { it },
            model, knownToolNames(), knownTools().nameOf(Verb.RunBundledScript), bundled.runnableScripts(), taken,
            System.currentTimeMillis(),
        )
    }

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

    private fun edit(block: (TeachSession) -> TeachSession) {
        synchronized(lock) {
            val cur = session ?: return
            if (cur.phase != TeachPhase.Reviewing) return
            session = block(cur)
            files.save(session!!)
            publish(null)
        }
    }

    private fun busy(): Boolean = tasks.busy.value || tasks.runs.value.isNotEmpty()

    private fun publish(notice: TeachNotice?) {
        _ui.value = _ui.value.copy(session = session, notice = notice)
    }

    private fun fail(notice: TeachNotice): TeachNotice {
        _ui.value = _ui.value.copy(notice = notice)
        return notice
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "")
}
