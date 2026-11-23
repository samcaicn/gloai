package com.wangzan101.mika.chat.ui
import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.chat.BranchCopyResult
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.chat.DeleteBatch
import com.wangzan101.mika.chat.SharedDraft
import com.wangzan101.mika.chat.SharedDraftAttachment
import com.wangzan101.mika.memory.RunNotes
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import com.wangzan101.mika.chat.ui.ChatReduce.fromTurn
import com.wangzan101.mika.files.ui.LibraryFileHandle

/** 共用状态:屏上这个对话的界面状态、各对话草稿、在跟的任务、删除中的对话,以及各块都要用的草稿与对话小工具。 */
internal class ChatSession(private val vm: ChatViewModel) {
    private val store = vm.store
    private val parallelFlag = vm.parallelFlag
    private val shownTask = vm.shownTask
    private val activeConversations = vm.activeConversations
    private val runNotes = vm.runNotes
    private val summarizing = vm.summarizing
    private val sharedDrafts = vm.sharedDrafts
    private val sharedDraftUri = vm.sharedDraftUri
    private val projectStore = vm.projectStore
    private val workspaceResolver = vm.workspaceResolver

    /** 已经自动朗读过的那条回答;同一条不重复读。 */
    var autoReadKey: String? = null
    val _ui = MutableStateFlow(ChatUi())

    /** 从搜索或文件库过来的一次性跳转;聊天页滚到目标后清掉。 */
    val _jump = MutableStateFlow<ChatJump?>(null)
    var jumpSerial = 0L
    val drafts = MutableStateFlow<Map<String, ComposerDraft>>(emptyMap())
    var draftStateChanged = false
    val libraryCopies = mutableMapOf<String, () -> Unit>()
    val librarySources = mutableMapOf<String, LibraryFileHandle>()
    val libraryImports = mutableMapOf<String, Int>()
    val deviceImports = mutableMapOf<String, kotlinx.coroutines.Job>()

    /** 只回收本进程登记的副本；导入中或任何草稿引用中的 URI 继续保留。 */
    fun releaseUnusedLibraryCopies() {
        val referenced = _ui.value.attachments.map { it.uri }.toSet() +
            drafts.value.values.flatMap { it.attachments.map(PendingAttachment::uri) } + libraryImports.keys
        val unused = libraryCopies.keys.filter { it !in referenced }
        unused.forEach { uri ->
            librarySources.remove(uri)
            libraryCopies.remove(uri)?.let { runCatching { it() } }
        }
    }

    /** 每次有分享放进当前会话就加一;异步操作靠它分辨草稿是被新分享换掉了,还是用户只改了文字。 */
    var sharedDraftSerial = 0L
    var pendingSharedFailures: List<AttachmentFailure> = emptyList()
    var pendingSharedOverLimit: Int = 0

    fun draftConversation(): String {
        draftStateChanged = true
        return _ui.value.conversationId ?: store.newConversation().also { id ->
            workspaceResolver?.setDraftProject(id, _ui.value.projectId)
            _ui.update { it.copy(conversationId = id) }
            markCurrent(CurrentChat.Open(id))
        }
    }

    fun keepDraft() {
        draftStateChanged = true
        val ui = _ui.value
        val id = ui.conversationId ?: return
        val shared = ui.sharedDraft?.copy(text = ui.input)?.takeIf { it.text.isNotBlank() || it.attachments.isNotEmpty() }
        if (ui.sharedDraft != null) {
            if (shared == null) sharedDrafts?.clear(id)
            else sharedDrafts?.save(shared)
        }
        val draft = ui.draft().copy(sharedDraft = shared)
        drafts.update { if (draft.hasContent) it + (id to draft) else it - id }
    }

    fun restoreDraft(ui: ChatUi): ChatUi {
        val id = ui.conversationId ?: return ui
        val draft = drafts.value[id] ?: return ui
        drafts.update { it - id }
        return draft.applyTo(ui)
    }

    fun updateDraft(id: String, change: (ComposerDraft) -> ComposerDraft) {
        if (id in _deleted.value) return
        if (_ui.value.conversationId == id) _ui.update { change(it.draft()).applyTo(it) }
        else drafts.update { saved ->
            val draft = change(saved[id] ?: ComposerDraft())
            if (draft.hasContent) saved + (id to draft) else saved - id
        }
    }

    /** 删掉了(或正在删)的对话:侧栏当场不再列出,不等删完。 */
    val _deleted = MutableStateFlow<Set<String>>(emptySet())

    /** 还没删完的。 */
    val deleting = mutableSetOf<String>()

    /** 最近一次分支复制工作区的结果(超过上限、目标已有 inbox 等);界面提示完调 [clearBranchCopy]。 */
    val _branchCopy = MutableStateFlow<BranchCopyResult?>(null)
    val parked = mutableMapOf<String, Parked>()
    val parkedJobs = mutableMapOf<String, Job>()

    /** 开关在没有任务时重读,开始任务、换对话时也重读([refreshParallel]):这里在跟的任务中途改开关不改这一轮的行为。 */
    var parallelOn = parallelFlag()

    fun parallel(): Boolean {
        if (liveIndex < 0 && activeConversations.value.isEmpty()) parallelOn = parallelFlag()
        return parallelOn
    }

    /**
     * 开始任务、换对话时重读开关(这个界面没在跟任务时):设置里刚改的开关不用重建界面就生效,
     * 和服务每次开始任务时重读一致。别的对话的任务照旧跑,它们在服务里按各自开始时的开关。
     */
    fun refreshParallel() {
        if (liveIndex < 0) parallelOn = parallelFlag()
    }

    /** 正在跟踪的任务在 exchanges 里的位置;-1 表示没有。 */
    var liveIndex = -1
    var lingerJob: Job? = null

    /** 排在服务收尾后面、还没交给服务的开始;在这期间点停止就直接作废它。 */
    var pendingStart: Job? = null

    /** 正在跟踪的任务开过屏;一旦开过,到任务结束(含完成后停留的那几秒)都显示画中画。 */
    var screenSeen = false

    /** 用户把正在跟踪的这个任务的小画面关掉了(换任务时清掉,见 [PipUi.closed])。 */
    var pipClosed = false

    fun attachmentUri(attachment: SharedDraftAttachment): String? =
        runCatching { sharedDraftUri(attachment) }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun sharedAttachments(draft: SharedDraft): List<PendingAttachment> = draft.attachments.mapNotNull { attachment ->
        attachmentUri(attachment)?.let { PendingAttachment(it, attachment.name, attachment.size, attachment.photo) }
    }

    /** 停止的那一轮:交代还在写时标上「正在整理」;写好了、写不成就去掉。 */
    fun withSummarizing(ex: Exchange, set: Set<String> = summarizing.value): Exchange {
        val st = ex.status as? ExchangeStatus.Cancelled ?: return ex
        val on = st.summary == null && ex.runId != null && ex.runId in set
        return if (st.summarizing == on) ex else ex.copy(status = st.copy(summarizing = on))
    }

    /** 服务对外的那几项(接管进度、有没有屏、导出的文件)此刻是不是屏上这个对话的任务的:并行时看显示中的是谁。 */
    fun ownTask() = !parallel() || shownTask.value?.first == _ui.value.conversationId

    fun withNotes(ex: Exchange, notes: Map<String, RunNotes>): Exchange {
        val n = ex.runId?.let(notes::get) ?: return ex
        return ex.copy(memoryNotes = n.memoryNotes, usedMemory = n.usedMemory, skillNotes = n.skillNotes)
    }

    /**
     * 能不能加附件:接了前台时随时能加(带附件的一句派成一件新任务,有没有任务在跑都一样);
     * 没接前台(只在测试里)时附件随新任务直接开,只在这条会话没有任务在跑时能加。
     */
    fun canAttach(ui: ChatUi) = ui.attachEnabled && !ui.importing && (vm.deskFor(ui.conversationId) != null || ui.composer == ComposerMode.Idle)

    /**
     * 小画面什么时候给:屏得开过(其余字段照算,开屏那一刻直接用)。用户关掉了这个任务的小画面时只留小按钮,
     * 也不再自动展开(接管开始时同样不弹开,要你处理的事在对话卡片里,小按钮变橙色)。
     */
    fun gated(p: PipUi) = when {
        !screenSeen -> p.copy(visible = false, expanded = false, closed = false)
        pipClosed -> p.copy(expanded = false, closed = true)
        else -> p.copy(closed = false)
    }

    /** 这个对话存下的轮次,连同各轮导出的文件和此刻的记忆与技能提示。 */
    fun saved(conversationId: String): List<Exchange> =
        runCatching { store.records(conversationId) }.getOrDefault(emptyList()).map { (t, files) -> withSummarizing(withNotes(fromTurn(t).copy(files = files), runNotes.value)) }

    /** 这个对话存下的项目 id;项目不存在时回落为 null。 */
    fun projectFor(conversationId: String?): String? =
        conversationId?.let { store.projectId(it) }?.takeIf { projectStore == null || projectStore.get(it) != null }

    /**
     * 告诉悬浮胶囊用户此刻在哪个对话([CurrentChat]):胶囊的空闲卡片接着它,而不是按对话文件最后被谁写过猜一个。
     * 写不进去不影响界面。
     */
    fun markCurrent(current: CurrentChat) {
        runCatching { store.setCurrent(current) }
    }

    /** 一次批量删除的进度和结果;侧栏在底部显示,提示完调 [clearDeleteBatch]。 */
    val _deleteBatch = MutableStateFlow<DeleteBatch?>(null)

    fun busy() = _ui.value.composer != ComposerMode.Idle
}

/**
 * 并行时切走的、任务还在跑的对话:那一问一答(原话、标准、步骤、文件)和开没开过屏都留着,
 * 后台按它自己任务的状态接着更新步骤,切回来原样接上。任务结束时丢掉(那一轮由服务存进对话)。
 */
internal class Parked(val exchange: Exchange, val screenSeen: Boolean, val pipClosed: Boolean = false)
