package com.wangzan101.mika.chat.ui
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.task.unconsumed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.wangzan101.mika.chat.ui.ChatReduce.actionsOf
import com.wangzan101.mika.chat.ui.ChatReduce.active
import com.wangzan101.mika.chat.ui.ChatReduce.needsUser
import com.wangzan101.mika.chat.ui.ChatReduce.reduce
import com.wangzan101.mika.chat.ui.ChatReduce.rows
import com.wangzan101.mika.chat.ui.ChatReduce.state
import com.wangzan101.mika.settings.ui.models.refresh

/** 跟着屏上这个对话的任务:状态、步骤、文件、插话的去向,切走时停到一边,切回来接上。 */
internal class ChatLiveFollow(private val vm: ChatViewModel, private val session: ChatSession) {
    private var autoReadKey by session::autoReadKey
    private val _ui = session._ui
    private val _deleted = session._deleted
    private val parked = session.parked
    private val parkedJobs = session.parkedJobs
    private var liveIndex by session::liveIndex
    private var lingerJob by session::lingerJob
    private var pendingStart by session::pendingStart
    private var screenSeen by session::screenSeen
    private var pipClosed by session::pipClosed
    private val scope = vm.scope
    private val takeoverStage = vm.takeoverStage
    private val actions = vm.actions
    private val appLabel = vm.appLabel
    private val screenOpen = vm.screenOpen
    private val endedRunId = vm.endedRunId
    private val liveRunId = vm.liveRunId
    private val endedRunIds = vm.endedRunIds
    private val shownTask = vm.shownTask
    private val activeConversations = vm.activeConversations
    private val runGoal = vm.runGoal
    private val runState = vm.runState
    private val runNotes = vm.runNotes
    private val sharedFiles = vm.sharedFiles
    private val generatedAudios = vm.generatedAudios
    private val generatedPhotos = vm.generatedPhotos
    private val toolLabels = vm.toolLabels
    private val activities = vm.activities
    private val steers = vm.steers
    private val clock = vm.clock
    private val autoRead = vm.autoRead

    private fun maybeAutoRead(ex: Exchange) {
        if (!autoRead.value) return
        val status = ex.status as? ExchangeStatus.Done ?: return
        val key = ex.runId ?: "${ex.startedAt}-${status.answer.hashCode()}"
        if (key == autoReadKey) return
        autoReadKey = key
        vm.speak(status.answer)
    }

    /** 把屏上这个对话正在跟的那一问一答停到一边(换到别的对话之前调用)。 */
    fun parkLive() {
        val ui = _ui.value
        val id = ui.conversationId ?: return
        val live = ui.exchanges.getOrNull(liveIndex) ?: return
        if (!live.status.active() || (id !in activeConversations.value && pendingStart?.isActive != true)) return
        parked[id] = Parked(live, screenSeen, pipClosed)
        parkedJobs.remove(id)?.cancel()
        val flow = runState(id) ?: return
        parkedJobs[id] = scope.launch {
            flow.collect { s ->
                if (!s.active()) {
                    if (s != TaskState.Idle) { parked.remove(id); parkedJobs.remove(id)?.cancel() }
                    return@collect
                }
                parked[id]?.let { p -> parked[id] = Parked(reduce(p.exchange, s, appLabel, toolLabels::label), p.screenSeen, p.pipClosed) }
            }
        }
    }

    fun unpark(id: String): Parked? {
        parkedJobs.remove(id)?.cancel()
        return parked.remove(id)
    }

    fun onActivities(list: List<RunEvent.Activity>) {
        if (liveIndex < 0) return
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) {
                val ex = exchanges[liveIndex].copy(activities = list)
                exchanges[liveIndex] = ex.copy(
                    steps = rows(ex, toolLabels::label, appLabel),
                    stepActions = actionsOf(list, appLabel, toolLabels::label),
                )
            }
            ui.copy(exchanges = exchanges)
        }
    }

    /**
     * 插话的去向按编号改到它所在的那一问一答上(任务收尾后它可能已经不是在跟的那条)。
     * 别处(悬浮胶囊)插给正在跟的任务的话,这里还没有的,挂到正在跟的那一问一答下面。
     */
    fun onSteers(list: List<SteerNote>) {
        val byId = list.associateBy { it.id }
        _ui.update { ui ->
            val shown = ui.exchanges.flatMap { ex -> ex.steers.map { it.id } }.toSet()
            val added = list.filter { it.id !in shown }
            ui.copy(exchanges = ui.exchanges.mapIndexed { i, ex ->
                val updated = if (ex.steers.none { it.id in byId }) ex
                    else ex.copy(steers = ex.steers.map { n -> byId[n.id]?.let { n.copy(state = it.state, interrupted = it.interrupted ?: n.interrupted, finished = it.finished ?: n.finished) } ?: n })
                if (i == liveIndex && added.isNotEmpty() && ex.status.active()) updated.copy(steers = updated.steers + added) else updated
            })
        }
    }

    fun onAudios(audios: List<com.wangzan101.mika.core.chat.GeneratedAudio>) {
        if (liveIndex < 0) return
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].copy(audios = audios)
            ui.copy(exchanges = exchanges)
        }
    }

    fun onSharedFiles(files: List<SharedFile>) {
        if (liveIndex < 0) return
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].copy(files = files)
            ui.copy(exchanges = exchanges)
        }
    }

    fun onPhotos(photos: List<com.wangzan101.mika.core.chat.GeneratedPhoto>) {
        if (liveIndex < 0) return
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].copy(photos = photos)
            ui.copy(exchanges = exchanges)
        }
    }

    fun onScreenOpened() {
        if (liveIndex < 0 || screenSeen) return
        screenSeen = true
        _ui.update { ui ->
            val live = ui.exchanges.getOrNull(liveIndex)
            if (live != null && live.status.active()) ui.copy(pip = session.gated(ui.pip.copy(visible = true))) else ui
        }
    }

    /**
     * 只收屏上这个对话的任务的状态;界面刚起来还没有对话时接上正在跑的那个(连同那个对话存下的轮次)。
     * 服务换去显示别的对话的任务(别处刚开始了一个)时,这里正在跟的任务还在跑就让服务显示回来,不让它停在半路。
     */
    fun onShown(conversationId: String, s: TaskState) {
        // 删掉的对话的任务正在停下:不接到屏上
        if (conversationId in _deleted.value) return
        val current = _ui.value.conversationId
        when {
            current == null -> {
                if (!s.active()) return
                _ui.update {
                    it.copy(
                        conversationId = conversationId,
                        exchanges = session.saved(conversationId),
                        projectId = session.projectFor(conversationId),
                    )
                }
            }
            current != conversationId -> {
                val live = _ui.value.exchanges.getOrNull(liveIndex)
                if (live != null && live.status.active() && current in activeConversations.value && pendingStart?.isActive != true) actions.show(current)
                return
            }
        }
        onState(s)
    }

    /**
     * 接上屏上这个对话正在跑的任务(不论从哪里开始的:这里、悬浮胶囊、定时任务)。没在跟时先摆好那一问一答
     * (切走时停着的 [p],或按任务的原话新起一条),再让服务显示它,并按服务此刻对外的各项补齐。
     */
    fun follow(conversationId: String, p: Parked? = null) {
        val fresh = liveIndex < 0
        if (fresh) {
            // 先把这个对话的那一问一答摆好,再让服务显示它:服务一换显示,状态可能当场就送进来(onShown),
            // 那时这一问一答必须已经在,不然会另起一条没有原话的
            val live = p?.exchange ?: Exchange(runGoal(conversationId).orEmpty(), emptyList(), ExchangeStatus.Working, live = true, startedAt = clock())
            screenSeen = p?.screenSeen ?: false
            pipClosed = p?.pipClosed ?: false
            _ui.update {
                liveIndex = it.exchanges.size
                it.copy(exchanges = it.exchanges + live, composer = ComposerMode.Busy, pip = session.gated(PipUi(visible = true)),
                    queued = it.queued.filterNot { q -> q.text == live.goal })
            }
        }
        actions.show(conversationId)
        val shown = shownTask.value?.takeIf { it.first == conversationId } ?: return
        // 正在跟的那一问一答不拿服务里已收尾的状态去结束它(那可能是上一个任务的)
        if (!fresh && !shown.second.active()) return
        // 服务对外的这几项此刻都是这个任务的了:按它们补齐(状态流只在变化时送,切过来不一定再送一次)
        if (!screenSeen && screenOpen.value) screenSeen = true
        _ui.update { it.copy(takeoverStage = takeoverStage.value) }
        onSharedFiles(sharedFiles.value)
        onAudios(generatedAudios.value)
        onPhotos(generatedPhotos.value)
        onActivities(activities.value)
        onSteers(steers.value)
        onState(shown.second)
    }

    /**
     * 对话页重新看得见时(回到前台、从别的页面回来):别处存进屏上这个对话的轮次补上;
     * 这个对话有任务在跑(从悬浮胶囊、定时任务开始的)而这里没在跟,或服务显示的换成了别的任务,就接上它。
     */
    fun refresh() {
        session.refreshParallel()
        val id = _ui.value.conversationId ?: return
        syncSaved(id)
        if (id !in activeConversations.value) { actions.drainFollowUps(id); return }
        if (pendingStart?.isActive == true) return
        // 开关关着、这里没在跟任务时换对话要等任务结束:那时屏上这个对话的任务就是服务里唯一在跑的
        follow(id, if (session.parallel()) unpark(id) else null)
    }

    /**
     * 存储里这个对话变了(别处跑完存进一轮、改了某一轮):屏上按存下的那份重排。这里跟过的那几轮按运行编号认
     * (没有编号的按原话),用这里的那份(带步骤和用时),不重复;还没存进去的接在后面;正在跟的那一问一答留在最后。
     */
    fun syncSaved(conversationId: String) {
        val stored = session.saved(conversationId)
        _ui.update { ui ->
            if (ui.conversationId != conversationId) return@update ui
            val live = ui.exchanges.getOrNull(liveIndex)
            val tracked = ui.exchanges.filterIndexed { i, ex -> ex.live && i != liveIndex }.toMutableList()
            // 从最近的往前配:同一句原话说过好几次时,配上的是最近那次
            val merged = stored.asReversed().map { s ->
                val mine = tracked.lastOrNull { if (s.runId != null) it.runId == s.runId else it.runId == null && it.goal == s.goal }
                    ?: return@map s
                tracked.remove(mine)
                // 停止后补上的交代记在存下的那份里
                val m = if (mine.status is ExchangeStatus.Cancelled && s.status is ExchangeStatus.Cancelled && s.status.summary != null) mine.copy(status = s.status) else mine
                // 留页的记录(留下的站点、后来关掉的原因)也只在存下的那份里
                val k = if (s.keptPage != null) m.copy(keptPage = s.keptPage, keptPageClosed = s.keptPageClosed) else m
                if (k.goal.isEmpty()) k.copy(goal = s.goal) else k
            }.asReversed()
            val exchanges = merged + tracked + listOfNotNull(live)
            if (exchanges == ui.exchanges) return@update ui
            liveIndex = if (live != null) exchanges.lastIndex else -1
            ui.copy(exchanges = exchanges)
        }
    }

    fun onState(s: TaskState) {
        if (liveIndex < 0) {
            // 任务不是从这里开始的(比如界面重建前就在跑):接着显示,只是没有用户那句话
            if (!s.active()) return
            screenSeen = screenOpen.value
            pipClosed = false
            _ui.update {
                liveIndex = it.exchanges.size
                // 原话、文件按对话取:这条状态属于屏上这个对话的任务
                val goal = it.conversationId?.let(runGoal).orEmpty()
                val files = if (session.ownTask()) sharedFiles.value else emptyList()
                val audios = if (session.ownTask()) generatedAudios.value else emptyList()
                val photos = if (session.ownTask()) generatedPhotos.value else emptyList()
                val ex = Exchange(goal, emptyList(), ExchangeStatus.Working, live = true, files = files, audios = audios, photos = photos, startedAt = clock())
                it.copy(exchanges = it.exchanges + ex,
                    queued = it.queued.filterNot { q -> q.text == goal })
            }
        }
        _ui.update { ui ->
            val old = ui.exchanges[liveIndex]
            val ex = reduce(withGoal(old, ui.conversationId), s, appLabel, toolLabels::label).let {
                if (it.status.active() || !old.status.active()) {
                    // 跑的时候就挂上这个对话的任务的编号,「运行过程」当场可用;结束那一下仍走下面那一支
                    val live = if (it.status.active() && it.runId == null) ui.conversationId?.let(liveRunId) else null
                    if (live != null) it.copy(runId = live) else it
                }
                // 收尾时还没交到任务手里的插话:服务把它当成这个对话的下一件事
                else session.withSummarizing(session.withNotes(it.copy(runId = endedRunFor(ui.conversationId), elapsedMs = elapsedOf(old),
                    steers = it.steers.map { n -> if (n.state.unconsumed) n.copy(state = SteerState.Next) else n }), runNotes.value))
            }
            val exchanges = ui.exchanges.toMutableList().also { it[liveIndex] = ex }
            ui.copy(
                exchanges = exchanges,
                pip = session.gated(pipFor(ui.pip, old.status, ex)),
                composer = when {
                    ex.status is ExchangeStatus.NeedsInput -> ComposerMode.Answer
                    ex.status.active() -> ComposerMode.Busy
                    else -> ComposerMode.Idle
                },
            )
        }
        _ui.value.exchanges.getOrNull(liveIndex)?.let(::maybeAutoRead)
        if (!s.active() && s != TaskState.Idle) {
            liveIndex = -1
            // 这一轮可能已先一步存进对话、补到了屏上:按运行编号并成一条
            _ui.value.conversationId?.let(::syncSaved)
            if (s is TaskState.Succeeded) {
                lingerJob = scope.launch {
                    delay(ChatViewModel.DONE_LINGER_MS)
                    _ui.update { it.copy(pip = it.pip.copy(visible = false, expanded = false)) }
                }
            }
        }
    }

    /** 正在跟的那一问一答还没有原话(接上别处开始的任务时它还没登记):按屏上这个对话的任务补上。 */
    fun fillGoal() = _ui.update { ui ->
        val ex = ui.exchanges.getOrNull(liveIndex) ?: return@update ui
        val filled = withGoal(ex, ui.conversationId)
        if (filled === ex) ui else ui.copy(exchanges = ui.exchanges.toMutableList().also { it[liveIndex] = filled })
    }

    private fun withGoal(ex: Exchange, conversationId: String?): Exchange {
        if (ex.goal.isNotEmpty() || !ex.status.active()) return ex
        return conversationId?.let(runGoal)?.takeIf(String::isNotEmpty)?.let { ex.copy(goal = it) } ?: ex
    }

    private fun elapsedOf(ex: Exchange): Long? = ex.startedAt.takeIf { it > 0 }?.let { (clock() - it).coerceAtLeast(0) }

    private fun endedRunFor(conversationId: String?): String? {
        // 没有对话编号(接上界面重建前就在跑的任务)时只能用显示中的那个
        val byConversation = endedRunIds ?: return endedRunId.value
        return if (conversationId == null) endedRunId.value else byConversation.value[conversationId]
    }

    private fun pipFor(old: PipUi, before: ExchangeStatus, ex: Exchange): PipUi {
        val st = ex.status
        return when (st) {
            is ExchangeStatus.Done -> PipUi(true, PipMode.Done, false)
            is ExchangeStatus.Failed, is ExchangeStatus.Cancelled -> PipUi()
            else -> {
                val needs = st.needsUser()
                // 只有接管刚开始时自动展开(提问、暂停要让输入框和卡片按钮留在眼前);接管结束时收起;
                // 同一次接管里用户收起后不再弹开
                val takeover = st is ExchangeStatus.NeedsTakeover
                val wasTakeover = before is ExchangeStatus.NeedsTakeover
                val expanded = when {
                    takeover && !wasTakeover -> true
                    !takeover && wasTakeover -> false
                    else -> old.expanded
                }
                PipUi(true, if (needs) PipMode.NeedsYou else PipMode.Running, expanded)
            }
        }
    }
}
