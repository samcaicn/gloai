package com.wangzan101.mika.chat.ui
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.chat.DeleteBatch
import com.wangzan101.mika.chat.DeleteImpact
import com.wangzan101.mika.chat.Project
import com.wangzan101.mika.chat.ProjectDeleteImpact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.wangzan101.mika.settings.ui.models.refresh

/** 对话与项目:打开、切换、新建、跳转、删除、归档、改名、置顶。 */
internal class ChatConversationOps(private val vm: ChatViewModel, private val session: ChatSession) {
    private val _ui = session._ui
    private val _jump = session._jump
    private var jumpSerial by session::jumpSerial
    private val drafts = session.drafts
    private var draftStateChanged by session::draftStateChanged
    private val deviceImports = session.deviceImports
    private val _deleted = session._deleted
    private val deleting = session.deleting
    private var liveIndex by session::liveIndex
    private var lingerJob by session::lingerJob
    private var pendingStart by session::pendingStart
    private var screenSeen by session::screenSeen
    private var pipClosed by session::pipClosed
    private val _deleteBatch = session._deleteBatch
    private val scope = vm.scope
    private val actions = vm.actions
    private val store = vm.store
    private val activeConversations = vm.activeConversations
    private val clock = vm.clock
    private val deletionImpact = vm.deletionImpact
    private val removeConversation = vm.removeConversation
    private val sharedDrafts = vm.sharedDrafts
    private val projectStore = vm.projectStore
    private val projectDeletionImpact = vm.projectDeletionImpact
    private val removeProject = vm.removeProject
    private val workspaceResolver = vm.workspaceResolver

    /**
     * 切到某个对话(并行时任务在跑也能切):载入它的历史;它有任务在跑就让服务显示那个任务,并接上它此刻的状态。
     * 开关关着时同 [openConversation]。
     */
    fun switchTo(conversationId: String) {
        session.refreshParallel()
        if (!session.parallel()) return openConversation(conversationId)
        if (conversationId == _ui.value.conversationId) return
        vm.drafting.cancelPendingSharedDraft()
        session.keepDraft()
        lingerJob?.cancel()
        if (conversationId != _ui.value.conversationId) vm.live.parkLive()
        val past = session.saved(conversationId)
        liveIndex = -1
        // 没有任务的对话不带别的任务的接管进度;输入内容只恢复目标对话自己的草稿。
        _ui.update { session.restoreDraft(it.rebuildForConversation(conversationId = conversationId, exchanges = past, projectId = session.projectFor(conversationId))) }
        session.markCurrent(CurrentChat.Open(conversationId))
        if (conversationId !in activeConversations.value) { vm.live.unpark(conversationId); actions.drainFollowUps(conversationId); return }
        vm.live.follow(conversationId, vm.live.unpark(conversationId))
    }

    /** 打开个人助手或工作流拥有的对话:只切换展示,不启动另一项工作,并保留用户草稿。 */
    fun openWorkflowConversation(conversationId: String) {
        if (conversationId == _ui.value.conversationId) { vm.live.refresh(); return }
        session.refreshParallel()
        vm.drafting.cancelPendingSharedDraft()
        session.keepDraft()
        lingerJob?.cancel()
        vm.live.parkLive()
        val past = session.saved(conversationId)
        liveIndex = -1
        _ui.update { session.restoreDraft(it.rebuildForConversation(conversationId = conversationId, exchanges = past, projectId = session.projectFor(conversationId))) }
        session.markCurrent(CurrentChat.Open(conversationId))
        if (conversationId !in activeConversations.value) { vm.live.unpark(conversationId); return }
        vm.live.follow(conversationId, vm.live.unpark(conversationId))
    }

    fun openPersonalTask(conversationId: String) {
        openWorkflowConversation(conversationId)
    }

    /**
     * 打开某个对话并跳到指定消息。已经在这个对话里时只设跳转,不重新载入;
     * 任务进行中且没开并行时打不开,跳转也不会留下,调用方可以用 [ChatUi.conversationId] 判断是否成功。
     */
    fun openConversationAt(id: String, target: ChatTarget) {
        if (_ui.value.conversationId != id) openConversation(id)
        if (_ui.value.conversationId == id) _jump.value = ChatJump(id, target, ++jumpSerial)
    }

    /** 聊天页滚到目标后清掉请求;期间又来了新请求时不要误清。 */
    fun consumeJump(jump: ChatJump) {
        _jump.update { if (it == jump) null else it }
    }

    /** 创建新项目。 */
    fun createProject(name: String): Result<Project>? = projectStore?.create(name)

    /** 重命名项目。 */
    fun renameProject(projectId: String, name: String): Result<Project>? = projectStore?.rename(projectId, name)

    fun projectDeleteImpact(projectId: String): ProjectDeleteImpact = projectDeletionImpact(projectId)

    /** 删除项目及其全部成员对话和共享工作区 `p-<projectId>`。 */
    fun deleteProject(projectId: String) {
        val members = store.list(includeArchived = true).filter { it.projectId == projectId }.map { it.id }
        val draftMembers = drafts.value.filterValues { it.projectId == projectId }.keys
        val openMember = _ui.value.conversationId?.takeIf {
            _ui.value.projectId == projectId || store.projectId(it) == projectId
        }
        val targets = (members + draftMembers + listOfNotNull(openMember)).distinct().filter { deleting.add(it) }
        targets.forEach { id ->
            drafts.update { it - id }
            sharedDrafts?.clear(id)
            workspaceResolver?.setDraftProject(id, null)
            _deleted.update { it + id }
            if (id == _ui.value.conversationId) startOver(id) else vm.live.unpark(id)
        }
        if (_ui.value.conversationId == null && _ui.value.projectId == projectId) {
            _ui.update { it.copy(projectId = null) }
        }
        session.releaseUnusedLibraryCopies()
        scope.launch {
            try {
                removeProject(projectId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _deleted.update { it - targets.toSet() }
            } finally {
                deleting.removeAll(targets.toSet())
            }
        }
    }

    /** 切到某个对话;任务在跑时不切。 */
    fun openConversation(requested: String) {
        // Mika 办一件事用的子对话不给用户看(通知、小画面、旧链接都可能指向它):打开它所属的那条聊天
        val id = store.deskParent(requested) ?: requested
        if (_ui.value.conversationId == id) return
        session.refreshParallel()
        if (session.parallel()) return switchTo(id)
        if (session.busy()) return
        vm.drafting.cancelPendingSharedDraft()
        session.keepDraft()
        val past = session.saved(id)
        liveIndex = -1
        _ui.update {
            session.restoreDraft(it.rebuildForConversation(conversationId = id, exchanges = past, takeoverStage = it.takeoverStage, projectId = session.projectFor(id)))
        }
        session.markCurrent(CurrentChat.Open(id))
        // 这个对话的任务是别处开始的(悬浮胶囊、定时任务):打开就接上它
        if (id in activeConversations.value) vm.live.follow(id) else {
            vm.live.unpark(id)
            actions.drainFollowUps(id)
        }
    }

    fun newConversation(projectId: String? = null) {
        val validProject = projectId?.takeIf { projectStore == null || projectStore.get(it) != null }
        session.refreshParallel()
        if (session.parallel()) {
            vm.drafting.cancelPendingSharedDraft()
            session.keepDraft()
            // 别的任务可能还在跑:先给新对话编号,它的状态就不会被当成这个对话的
            lingerJob?.cancel()
            vm.live.parkLive()
            liveIndex = -1
            val fresh = store.newConversation()
            workspaceResolver?.setDraftProject(fresh, validProject)
            _ui.update { it.rebuildForConversation(conversationId = fresh, projectId = validProject) }
            // 还没说过话:胶囊上是一张新对话卡片(编号等说出来再定,见 begin)
            session.markCurrent(CurrentChat.Fresh)
            return
        }
        if (session.busy()) return
        vm.drafting.cancelPendingSharedDraft()
        session.keepDraft()
        liveIndex = -1
        _ui.update { it.rebuildForConversation(conversationId = null, takeoverStage = it.takeoverStage, projectId = validProject) }
        session.markCurrent(CurrentChat.Fresh)
    }

    /** 屏上这个对话此刻有没有任务在跑或排队。 */
    fun currentRunning(): Boolean = _ui.value.conversationId?.let { it in activeConversations.value } == true

    /** 删掉 [id] 会连带什么:侧栏的确认弹窗据此写明会停掉任务、删掉定时任务。 */
    fun deleteImpact(id: String): DeleteImpact = deletionImpact(id)

    /**
     * 删掉对话 [id](用户在侧栏确认过):当场从侧栏拿掉;是屏上这个对话就换成一个新的空对话。
     * 停任务、删定时任务、删文件在后台做(见 `ConversationDeleter`)。
     */
    fun deleteConversation(id: String) {
        deleteConversations(listOf(id), report = false)
    }

    fun clearDeleteBatch() {
        _deleteBatch.value = null
    }

    /**
     * 用户在侧栏多选后一次删掉 [ids]:当场把它们从侧栏拿掉,再逐条在后台删。
     * 删的时候 [deleteBatch] 里写着一共几条、删完几条、失败几条,侧栏据此显示进度和结果。
     */
    fun deleteConversations(ids: Collection<String>) {
        deleteConversations(ids, report = true)
    }

    private fun deleteConversations(ids: Collection<String>, report: Boolean) {
        val targets = ids.filter { deleting.add(it) }
        if (targets.isEmpty()) return
        targets.forEach { id ->
            drafts.update { it - id }
            sharedDrafts?.clear(id)
            _deleted.update { it + id }
            if (id == _ui.value.conversationId) startOver(id) else vm.live.unpark(id)
        }
        session.releaseUnusedLibraryCopies()
        if (report) _deleteBatch.value = DeleteBatch(targets.size, 0, 0)
        scope.launch {
            var done = 0
            var failed = 0
            // 一条删不掉(存储读写失败等)不挡住后面的;最后照实说几条失败
            targets.forEach { id ->
                try {
                    deviceImports[id]?.join()
                    removeConversation(id)
                } catch (e: CancellationException) {
                    val unfinished = targets.drop(done).toSet()
                    deleting.removeAll(unfinished)
                    _deleted.update { it - unfinished }
                    throw e
                } catch (_: Exception) {
                    failed++
                    _deleted.update { it - id }
                }
                deleting -= id
                done++
                if (report) _deleteBatch.value = DeleteBatch(targets.size, done, failed)
            }
        }
    }

    /** 用户在侧栏给对话 [id] 改名:换成 [title](去掉两头空白;空的不改)。之后起好的标题不会再盖掉它。 */
    fun renameConversation(id: String, title: String) {
        if (id in _deleted.value) return
        runCatching { store.rename(id, title) }
    }

    /** 侧栏里置顶或取消置顶对话 [id]。 */
    fun setPinned(id: String, pinned: Boolean) {
        if (id in _deleted.value) return
        runCatching { store.setPinned(id, pinned, clock()) }
    }

    /**
     * 侧栏里归档对话 [id]:从列表收起,内容、工作区、定时任务都不动。
     * 归档的是屏上这个对话时,先确认现在能换对话,再换成一个新的空对话(草稿留着,取消归档后接着用)。
     * 跑着或排着任务的对话不归档,返回 false。
     */
    fun archiveConversation(id: String): Boolean {
        if (id in _deleted.value || id in deleting) return false
        if (id in activeConversations.value) return false
        val current = _ui.value.conversationId == id
        if (current) {
            session.refreshParallel()
            if (session.busy()) return false
        }
        val ok = runCatching { store.setArchived(id, true, clock()) }.getOrDefault(false)
        // 先归档再换:换到新对话时侧栏重读,原来那个已经不在列表里了
        if (ok && current) newConversation()
        return ok
    }

    /** 侧栏里取消归档对话 [id];它回到原来的位置(置顶的还回置顶)。 */
    fun unarchiveConversation(id: String) {
        if (id in _deleted.value) return
        runCatching { store.setArchived(id, false) }
    }

    /** 归档视图里多选恢复:一条条取消归档;已经删掉的跳过。 */
    fun unarchiveConversations(ids: Collection<String>) {
        ids.filter { it !in _deleted.value }.forEach { runCatching { store.setArchived(it, false) } }
    }

    /**
     * 普通侧栏里多选后一次归档 [ids]:未保存首轮的草稿、在跑的、删掉的、当前正在看的跳过
     * (当前对话归档要换新对话,批量里不悄悄换)。返回实际归档了几个。
     */
    fun archiveConversations(ids: Collection<String>): Int {
        val current = _ui.value.conversationId
        // 与侧栏判断已保存对话的依据一致,不能只看 ID:未发送的草稿也有 ID。
        val savedIds = store.list(includeArchived = true).mapTo(mutableSetOf()) { it.id }
        return ids.count { id ->
            id in savedIds && id != current && id !in _deleted.value && id !in deleting && id !in activeConversations.value &&
                runCatching { store.setArchived(id, true, clock()) }.getOrDefault(false)
        }
    }

    /** 屏上的对话被删了:不再跟它的任务(还没交给服务的开始直接作废),换成新的空对话。 */
    private fun startOver(id: String) {
        draftStateChanged = true
        vm.drafting.cancelPendingSharedDraft()
        vm.drafting.discardSharedDraft()
        lingerJob?.cancel()
        pendingStart?.cancel()
        pendingStart = null
        vm.live.unpark(id)
        liveIndex = -1
        screenSeen = false
        pipClosed = false
        val fresh = if (session.parallel()) store.newConversation() else null
        _ui.update { it.rebuildForConversation(conversationId = fresh) }
        // 屏上换成了一个新的空对话:胶囊不再接着删掉的那个
        session.markCurrent(CurrentChat.Fresh)
    }
}
