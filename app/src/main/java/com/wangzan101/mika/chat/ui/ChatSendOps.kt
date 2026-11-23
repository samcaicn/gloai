package com.wangzan101.mika.chat.ui
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.chat.RetryAttachmentStatus
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.cap.sandbox.ImportResult
import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.task.QueuedFollowUp
import com.wangzan101.mika.task.QueuedSource
import com.wangzan101.mika.task.unconsumed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.wangzan101.mika.chat.ui.ChatReduce.state
import com.wangzan101.mika.chat.ui.ChatReduce.withAttachments

/** 发出去:开任务、插话、排队、引用,停止、接管等任务按钮和画中画。 */
internal class ChatSendOps(private val vm: ChatViewModel, private val session: ChatSession) {
    private val _ui = session._ui
    private val libraryImports = session.libraryImports
    private var sharedDraftSerial by session::sharedDraftSerial
    private val _deleted = session._deleted
    private var liveIndex by session::liveIndex
    private var lingerJob by session::lingerJob
    private var pendingStart by session::pendingStart
    private var screenSeen by session::screenSeen
    private var pipClosed by session::pipClosed
    private val held = session.held
    private val scope = vm.scope
    private val actions = vm.actions
    private val store = vm.store
    private val serviceBusy = vm.serviceBusy
    private val activeConversations = vm.activeConversations
    private val importAttachments = vm.importAttachments
    private val clock = vm.clock
    private val branchWorkspace = vm.branchWorkspace
    private val sharedDrafts = vm.sharedDrafts
    private val teaching = vm.teaching
    private val onTeachingBlocked = vm.onTeachingBlocked
    private val projectStore = vm.projectStore
    private val workspaceResolver = vm.workspaceResolver

    /** 长按消息选了「引用」:引的内容放到输入框上方,随下一句话发出去(新任务、插话、回答都带)。空的不引。 */
    fun quote(from: QuoteMessage.From, text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) {
            session.draftConversation()
            _ui.update { it.copy(quote = QuoteMessage.Quote(from, t)) }
        }
    }

    fun clearQuote() = _ui.update { it.copy(quote = null) }

    /** 发送输入框里的话;有引用时引用写在话的前面(见 [QuoteMessage]),发出去后清掉。返回 false 表示没发出去(条件不满足,或任务在跑)。 */
    fun send(text: String, check: Prerequisites.Check): Boolean {
        if (teaching()) {
            onTeachingBlocked()
            return false
        }
        session.refreshParallel()
        val ui = _ui.value
        if (ui.importing) return false
        val queuedAttachments = ui.composer != ComposerMode.Idle && ui.followUpMode == FollowUpMode.Queue
        val sendingAttachments = ui.attachments.isNotEmpty() && (ui.composer == ComposerMode.Idle || queuedAttachments)
        val sent = sendQuoted(text.trim(), ui, check)
        if (sent && !sendingAttachments) {
            val shared = ui.sharedDraft
            if (shared != null) {
                if (shared.attachments.isEmpty()) {
                    vm.drafting.discardSharedDraft(shared)
                } else {
                    val textCleared = shared.copy(text = "")
                    sharedDrafts?.save(textCleared)
                    _ui.update { if (it.sharedDraft == shared) it.copy(sharedDraft = textCleared) else it }
                }
            }
            _ui.update {
                it.copy(input = if (it.input == text) "" else it.input, quote = null)
            }
        }
        return sent
    }

    private fun sendQuoted(words: String, ui: ChatUi, check: Prerequisites.Check): Boolean {
        // 附件可跟新任务或明确排队的下一件走;先复制进工作区,再把路径随原话一起持久化。
        val queuedAttachments = ui.composer != ComposerMode.Idle && ui.followUpMode == FollowUpMode.Queue
        if ((ui.composer == ComposerMode.Idle || queuedAttachments) && ui.attachments.isNotEmpty()) {
            return sendWithAttachments(QuoteMessage.compose(ui.quote, words), ui.attachments, check)
        }
        // 只有引用、没写话时不发
        if (words.isEmpty()) return false
        val goal = QuoteMessage.compose(ui.quote, words)
        when (ui.composer) {
            ComposerMode.Answer -> {
                act({ actions.answer(goal) }) { actions.answerIn(it, goal) }
                return true
            }
            ComposerMode.Busy -> return if (_ui.value.followUpMode == FollowUpMode.Queue) enqueue(goal, check) else steer(goal, check)
            ComposerMode.Idle -> Unit
        }
        if (!check.canStartTask) {
            _ui.update { it.copy(blocked = check.blockers) }
            return false
        }
        begin(goal, ui.conversationId ?: store.newConversation())
        return true
    }

    /**
     * 带着附件开新任务:先把附件复制进对话工作区的 inbox/(期间输入框上方显示正在添加),
     * 全部复制成功后,把每个附件写成一行「附件:<路径>(<大小>)」接在话后面开始任务。
     * 有附件没收下时保留草稿并列出失败项,由用户重试或移除后再发送。
     * 复制期间换了对话或开始了别的任务就不发了(复制进去的文件留在原对话的工作区里)。
     */
    private fun sendWithAttachments(text: String, items: List<PendingAttachment>, check: Prerequisites.Check): Boolean {
        if (!check.canStartTask) {
            _ui.update { it.copy(blocked = check.blockers) }
            return false
        }
        val id = session.draftConversation()
        val submitted = _ui.value.draft()
        val serialBefore = sharedDraftSerial
        val importingFileNames = submitted.sharedDraft?.attachments?.map { it.fileName }.orEmpty()
        sharedDrafts?.setImporting(importingFileNames, true)
        val importedUris = items.map { it.uri }.distinct()
        importedUris.forEach { uri -> libraryImports[uri] = (libraryImports[uri] ?: 0) + 1 }
        _ui.update { it.copy(importing = true, rejectedAttachments = emptyList(), overLimit = 0) }
        scope.launch {
            val r = try {
                val workspaceItems = items.filter { it.workspacePath != null }
                val checks = if (workspaceItems.all { it.workspaceConversationId == id }) {
                    branchWorkspace?.checkInboxAttachments(id, workspaceItems.map { it.workspacePath!! }).orEmpty()
                } else emptyList()
                if (workspaceItems.isEmpty()) {
                    importAttachments(id, items.map { it.uri })
                } else if (checks.size != workspaceItems.size || checks.any { it.status != RetryAttachmentStatus.COPIED || it.sizeBytes == null }) {
                    ImportResult(emptyList(), workspaceItems.map { it.name })
                } else {
                    var checked = 0
                    val added = mutableListOf<Pair<String, Long>>()
                    val rejected = mutableListOf<String>()
                    val failures = mutableListOf<AttachmentFailure>()
                    for (item in items) {
                        if (item.workspacePath != null) {
                            val result = checks[checked++]
                            added += result.path to result.sizeBytes!!
                        } else {
                            val result = importAttachments(id, listOf(item.uri))
                            added += result.added
                            rejected += result.rejected
                            failures += result.failures
                        }
                    }
                    ImportResult(added, rejected, failures)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportResult(emptyList(), items.map { it.name })
            } finally {
                sharedDrafts?.setImporting(importingFileNames, false)
                importedUris.forEach { uri ->
                    val count = (libraryImports[uri] ?: 1) - 1
                    if (count == 0) libraryImports.remove(uri) else libraryImports[uri] = count
                }
                session.releaseUnusedLibraryCopies()
            }
            session.updateDraft(id) { it.copy(importing = false, rejected = r.failures) }
            if (r.rejected.isNotEmpty()) return@launch
            val goal = withAttachments(text, r.added)
            val now = _ui.value
            if (goal.isEmpty() || now.conversationId != id || id in _deleted.value) return@launch
            val currentAttachments = now.attachments
            val draftReplaced = sharedDraftSerial != serialBefore
            val keepNewerAttachments = draftReplaced || currentAttachments != submitted.attachments
            val accepted = when {
                now.composer == ComposerMode.Idle -> { begin(goal, id, clearDraftAttachments = !keepNewerAttachments); true }
                now.composer != ComposerMode.Idle && now.followUpMode == FollowUpMode.Queue -> enqueue(goal, check)
                else -> false
            }
            if (accepted) {
                if (!draftReplaced) vm.drafting.discardSharedDraft()
                _ui.update {
                    it.copy(
                        input = if (!draftReplaced && it.input == submitted.input) "" else it.input,
                        quote = if (!draftReplaced && it.quote == submitted.quote) null else it.quote,
                        attachments = if (keepNewerAttachments) currentAttachments else emptyList(),
                        followUpMode = if (keepNewerAttachments && currentAttachments.isNotEmpty() && it.composer != ComposerMode.Idle) FollowUpMode.Queue else it.followUpMode,
                    )
                }
                session.releaseUnusedLibraryCopies()
            }
        }
        return true
    }

    /** 在对话 [id] 里开始新任务 [goal]:界面上加一问一答,交给服务(并行关着、服务还忙时排在它收尾后面)。 */
    private fun begin(goal: String, id: String, clearDraftAttachments: Boolean = true) {
        lingerJob?.cancel()
        // 真正开始任务后,草稿附件已在工作区,可以清掉私有暂存副本。
        if (clearDraftAttachments) vm.drafting.discardSharedDraft()
        val boundProject = (_ui.value.projectId ?: store.projectId(id))?.takeIf { projectStore == null || projectStore.get(it) != null }
        if (boundProject != null && store.projectId(id) != boundProject) {
            runCatching { store.setProject(id, boundProject) }
        }
        workspaceResolver?.setDraftProject(id, null)
        // 这个对话的编号定下来了:胶囊之后接着它(新开的对话到这一刻才有编号)
        session.markCurrent(CurrentChat.Open(id))
        // 上一个任务收尾时先撤屏再报结束,这里读到的就是新任务开始前的样子;
        // 并行时那是别的对话的任务的屏,新任务还没有屏
        // 新消息开始时不能继承上一次运行的屏；真正建好本次的屏后由 screenOpen 打开小窗。
        screenSeen = false
        pipClosed = false
        _ui.update {
            liveIndex = it.exchanges.size
            val nextAttachments = if (clearDraftAttachments) emptyList() else it.attachments
            it.copy(
                conversationId = id,
                exchanges = it.exchanges + Exchange(goal, emptyList(), ExchangeStatus.Working, live = true, startedAt = clock()),
                blocked = null,
                attachments = nextAttachments,
                pip = session.gated(PipUi(visible = true)),
                composer = ComposerMode.Busy,
                followUpMode = FollowUpMode.Queue,
                // 这一句如果本来在排队条上乐观挂着、现在已经开始,就不能再显示一行
                queued = it.queued.filterNot { q -> q.text == goal },
            )
        }
        // 并行时服务忙着(别的对话的任务在跑)也直接交给它
        if (serviceBusy.value && !session.parallel()) {
            // 等着的这段时间说清楚在等什么,不让它看着像卡住了;交给服务后由任务自己的状态接着写
            _ui.update { ui ->
                val exchanges = ui.exchanges.toMutableList()
                exchanges[liveIndex] = exchanges[liveIndex].copy(line = tr(R.string.chat_wait_previous_task))
                ui.copy(exchanges = exchanges)
            }
            // 交过去时别处(定时、电脑)可能刚好先开始了一个:服务把它排在那个后面,不丢
            pendingStart = scope.launch {
                serviceBusy.first { !it }
                pendingStart = null
                actions.start(goal, id)
                // 等着期间插的话:原话的任务先开始,再交给它
                held.toList().also { held.clear() }.forEach { n -> actions.steer(id, n.id, n.text) }
                // 排队项早已持久化;原话任务收尾时服务按 FIFO 取出,不在这里重写或抢跑
            }
        } else {
            actions.start(goal, id)
        }
    }

    /**
     * 任务在跑时发的话:挂在正在跟的那一问一答下面,交给这个任务;它的去向由服务报回来([steers])。
     * 它可能成为下一件事,所以和开新任务过同一道检查。任务还没交给服务([pendingStart])时先留着,开始之后再发。
     */
    private fun steer(text: String, check: Prerequisites.Check): Boolean {
        val id = _ui.value.conversationId ?: return false
        if (liveIndex !in _ui.value.exchanges.indices) return false
        if (!check.canStartTask) {
            _ui.update { it.copy(blocked = check.blockers) }
            return false
        }
        val note = SteerNote(java.util.UUID.randomUUID().toString(), text, SteerState.Pending)
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            exchanges[liveIndex] = exchanges[liveIndex].let { it.copy(steers = it.steers + note) }
            ui.copy(exchanges = exchanges)
        }
        if (pendingStart?.isActive == true) held += note else actions.steer(id, note.id, text)
        return true
    }

    /** 切换任务进行中补充消息的默认去向;只影响下次发送,不改已经发出的消息。 */
    fun setFollowUpMode(mode: FollowUpMode) {
        if (_ui.value.importing) return
        if (mode == FollowUpMode.Steer) {
            _ui.value.sharedDraft?.takeIf { it.attachments.isNotEmpty() }?.let(vm.attaching::clearSharedAttachments)
        }
        _ui.update {
            if (it.followUpMode == mode) it else it.copy(followUpMode = mode,
                attachments = if (mode == FollowUpMode.Steer) emptyList() else it.attachments)
        }
        session.releaseUnusedLibraryCopies()
    }

    /** 任务在跑时把这句话先写进这个对话的持久 FIFO;写失败返回 false,输入框保留原话。 */
    private fun enqueue(text: String, check: Prerequisites.Check): Boolean {
        val id = _ui.value.conversationId ?: return false
        if (!check.canStartTask) {
            _ui.update { it.copy(blocked = check.blockers) }
            return false
        }
        val item = QueuedFollowUp(java.util.UUID.randomUUID().toString(), id, text, clock(), QueuedSource.Explicit)
        // 原话还在等别的任务收尾时也只落盘,不能让下一件抢在它前面开始。
        return actions.enqueueFollowUp(id, item.id, text, startIfIdle = pendingStart?.isActive != true)
    }

    /** 当前没有任务在跑时,把排队的第一件开始(排队条上的「现在开始」)。 */
    fun startQueued() {
        val id = _ui.value.conversationId ?: return
        actions.drainFollowUps(id)
    }

    /** 取消一条还没开始的持久排队;已经轮到了返回 false。 */
    fun cancelQueued(id: String): Boolean {
        val conversationId = _ui.value.conversationId ?: return false
        if (!actions.cancelFollowUp(conversationId, id)) return false
        _ui.update { it.copy(queued = it.queued.filterNot { q -> q.id == id }) }
        return true
    }

    /** 把一条还没开始的排队改为当前任务的引导;竞争失败时保留或恢复排队。 */
    fun guideQueued(id: String, check: Prerequisites.Check): Boolean {
        val ui = _ui.value
        if (ui.composer != ComposerMode.Busy) return false
        val item = ui.queued.firstOrNull { it.id == id } ?: return false
        if (!check.canStartTask) {
            _ui.update { it.copy(blocked = check.blockers) }
            return false
        }
        if (!cancelQueued(id)) return false
        if (steer(item.text, check)) return true
        return enqueue(item.text, check)
    }

    /**
     * 取回一条排队:先取消,成功才把文字放回输入框(引用照旧解析)。带屏幕/页面标记的只能取消,不能编辑。
     */
    fun editQueued(id: String): String? {
        val ui = _ui.value
        if (ui.input.isNotBlank() || ui.quote != null || ui.attachments.isNotEmpty() || ui.importing) return null
        val item = ui.queued.firstOrNull { it.id == id } ?: return null
        if (!editableSteer(item.text)) return null
        if (!cancelQueued(id)) return null
        val parsed = QuoteMessage.parse(item.text)
        _ui.update { it.copy(input = parsed.words, quote = parsed.quote) }
        return parsed.words
    }

    fun withdrawSteer(messageId: String): Boolean {
        val ui = _ui.value
        val id = ui.conversationId ?: return false
        val note = ui.exchanges.getOrNull(liveIndex)?.steers?.firstOrNull { it.id == messageId } ?: return false
        if (!note.state.unconsumed || note.remote) return false
        if (!held.removeAll { it.id == messageId } && !actions.withdrawSteer(id, messageId)) return false
        _ui.update { now -> now.copy(exchanges = now.exchanges.map { ex ->
            ex.copy(steers = ex.steers.map { if (it.id == messageId) it.copy(state = SteerState.Withdrawn) else it })
        }) }
        return true
    }

    fun editSteer(messageId: String): String? {
        val ui = _ui.value
        if (ui.input.isNotBlank() || ui.quote != null || ui.attachments.isNotEmpty() || ui.importing) return null
        val note = ui.exchanges.getOrNull(liveIndex)?.steers?.firstOrNull { it.id == messageId } ?: return null
        if (!editableSteer(note.text) || !withdrawSteer(messageId)) return null
        val parsed = QuoteMessage.parse(note.text)
        _ui.update { it.copy(input = parsed.words, quote = parsed.quote) }
        return parsed.words
    }

    fun dismissBlocked() = _ui.update { it.copy(blocked = null) }

    /** 停止:任务连同还没交到它手里的插话一起作废(插话不再当成下一件事)。 */
    fun cancel() {
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].let { ex ->
                ex.copy(steers = ex.steers.map { if (it.state.unconsumed) it.copy(state = SteerState.Cancelled) else it })
            }
            ui.copy(exchanges = exchanges)
        }
        val pending = pendingStart
        if (pending == null || !pending.isActive) {
            // 这个对话在服务里有任务(在跑或排着队)就只停它:不带编号的停止按服务显示中的任务算,
            // 这条还排在别处开始的任务后面时,停掉的会是那个任务
            val id = _ui.value.conversationId
            if (id != null && id in activeConversations.value) actions.cancelIn(id) else act({ actions.cancel() }) { actions.cancelIn(it) }
            return
        }
        // 任务还没交给服务:作废这次开始;服务里正在收尾的是上一个任务,不去碰它
        pending.cancel()
        pendingStart = null
        held.clear()
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].copy(status = ExchangeStatus.Cancelled())
            // 明确排队的下一件有独立持久生命周期,取消原话开始也不删它。
            ui.copy(exchanges = exchanges, pip = PipUi(), composer = ComposerMode.Idle)
        }
        liveIndex = -1
    }

    fun resume() = act({ actions.resume() }) { actions.resumeIn(it) }

    fun takeOver() = act({ actions.takeOver() }) { actions.takeOverIn(it) }

    fun handBack() = act({ actions.handBack() }) { actions.handBackIn(it) }

    fun finishedMyself() = act({ actions.finishedMyself() }) { actions.finishedMyselfIn(it) }

    fun skip() = act({ actions.skip() }) { actions.skipIn(it) }

    fun answer(text: String) = act({ actions.answer(text) }) { actions.answerIn(it, text) }

    fun approve(key: Int) = act({ actions.approve(key) }) { actions.approveIn(it, key) }

    fun decline(key: Int) = act({ actions.decline(key) }) { actions.declineIn(it, key) }

    /**
     * 应用内的按钮:开关关着时作用于服务显示中的任务(M1);并行时带上按下那一刻屏上的对话,只作用于它的任务,
     * 这个对话没有任务就不发。
     */
    private inline fun act(serial: () -> Unit, scoped: (String) -> Unit) {
        if (!session.parallel()) return serial()
        val id = _ui.value.conversationId ?: return
        if (id in activeConversations.value) scoped(id)
    }

    fun expandPip() = _ui.update { if (it.pip.visible) it.copy(pip = it.pip.copy(expanded = true)) else it }

    /** 要你处理时点画中画:收起它,由界面滚到要你处理的那张卡片。 */
    fun attendPip() = collapsePip()

    fun collapsePip() = _ui.update { it.copy(pip = it.pip.copy(expanded = false)) }

    /** 用户关掉这个任务的小画面:只留右下角的小按钮,这个任务结束前不再自己浮出来。 */
    fun closePip() {
        if (liveIndex < 0) return
        pipClosed = true
        _ui.update { it.copy(pip = session.gated(it.pip)) }
    }

    /** 点小按钮:把这个任务的小画面再打开。 */
    fun reopenPip() {
        pipClosed = false
        _ui.update { it.copy(pip = session.gated(it.pip)) }
    }

    /** 浮层的声音开关:只发真实音频动作,方向由任务里的真实记录定,不在界面伪造开关状态。 */
    fun togglePipSound() {
        val id = _ui.value.conversationId ?: return
        actions.togglePipSoundIn(id)
    }

    /** 分享切走前,把仍在等串行服务空闲的原任务交给服务排队;只取消界面里的等待协程,不取消任务。 */
    fun handOffPendingStart() {
        val pending = pendingStart?.takeIf { it.isActive } ?: return
        val id = _ui.value.conversationId
        val live = _ui.value.exchanges.getOrNull(liveIndex)
        pendingStart = null
        pending.cancel()
        if (id == null || live == null) {
            held.clear()
            return
        }
        actions.start(live.goal, id)
        held.toList().also { held.clear() }.forEach { n -> actions.steer(id, n.id, n.text) }
    }
}
