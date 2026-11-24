package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.chat.BranchCopyResult
import com.wangzan101.mika.chat.BranchWorkspace
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.chat.DeleteBatch
import com.wangzan101.mika.chat.DeleteImpact
import com.wangzan101.mika.chat.Project
import com.wangzan101.mika.chat.ProjectDeleteImpact
import com.wangzan101.mika.chat.ProjectStore
import com.wangzan101.mika.chat.SharedDraft
import com.wangzan101.mika.chat.SharedDraftAttachment
import com.wangzan101.mika.chat.SharedDraftStore
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.chat.WorkspaceResolver
import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.cap.sandbox.ImportResult
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.learn.memory.MemoryItem
import com.wangzan101.mika.core.learn.memory.MemoryNote
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.learn.skills.SkillNote
import com.wangzan101.mika.memory.RunNotes
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.task.QueuedFollowUp
import com.wangzan101.mika.task.QueuedSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.wangzan101.mika.core.assembly.knownTools
import kotlin.coroutines.CoroutineContext
import com.wangzan101.mika.chat.ui.ChatReduce.active
import com.wangzan101.mika.chat.ui.ChatReduce.reduce
import com.wangzan101.mika.chat.ui.ChatReduce.sizeLabel
import com.wangzan101.mika.chat.ui.ChatReduce.where
import com.wangzan101.mika.chat.ui.ChatReduce.withAttachments
import com.wangzan101.mika.files.ui.LibraryAddPlan
import com.wangzan101.mika.files.ui.LibraryAttachmentResult
import com.wangzan101.mika.files.ui.LibraryFileHandle
import com.wangzan101.mika.files.ui.LibraryMergeResult
import com.wangzan101.mika.files.ui.LibraryStaged
import com.wangzan101.mika.settings.ui.models.refresh
import com.wangzan101.mika.ui.ToolLabels

/**
 * 聊天界面的状态。步骤列表来自运行事件里实际做过的事(活动);进展那一行是模型这一步的话,
 * 等待模型时仅显示状态。输入框发的话交给这条会话的前台(见 [DeskChatOps]),前台派出的任务在各自的子会话里跑。
 * 整个进程只用一个实例,界面关掉再打开时正在跑的任务的步骤还在。
 */
class ChatViewModel(
    internal val scope: CoroutineScope,
    taskState: StateFlow<TaskState>,
    internal val takeoverStage: StateFlow<Takeover.Stage>,
    internal val actions: TaskActions,
    internal val store: ConversationStore,
    /**
     * 服务还有任务(这个对话上一个任务还在收尾,或别处开始的任务在跑);并行关着时新任务等它收完再交给服务,
     * 免得这个对话的新一句被当成上一个任务的插话。等着时那条消息下面写「等前一个任务做完再开始」。
     */
    internal val serviceBusy: StateFlow<Boolean> = MutableStateFlow(false),
    /** 包名换成 App 显示名(确认卡片用)。 */
    internal val appLabel: (String) -> String = { it },
    /**
     * 当前任务有没有虚拟屏。屏到第一次打开 App 才建,没建过屏的任务(纯对话、不用手机就能答)不出画中画,
     * 问答和确认都在聊天卡片里。
     */
    internal val screenOpen: StateFlow<Boolean> = MutableStateFlow(false),
    /**
     * 刚结束的那个任务的运行记录编号,服务在报结束之前写好;收到结束状态时挂到那一问一答上,
     * 卡片上就有「查看过程」。之后它再变也不改已结束的那条。
     */
    internal val endedRunId: StateFlow<String?> = MutableStateFlow(null),
    /**
     * 对话里正在跑的那个任务的运行记录编号(任务一开头就有;还没分到时为 null)。按对话取,和 [endedRunIds] 一样
     * 不看服务对外的「显示中的任务」:并行时那可能是别的对话的。在跑时挂上它,「运行过程」当场就能打开。
     */
    internal val liveRunId: (conversationId: String) -> String? = { null },
    /** 每个对话最近结束的任务的运行记录编号;给了就按卡片所在的对话取,不用上面那个(它只跟显示中的任务)。 */
    internal val endedRunIds: StateFlow<Map<String, String?>>? = null,
    /** 开关 parallelTasks:打开时不同对话的任务可以同时跑,界面只跟着屏上这个对话的任务。界面没在跟任务时重读。 */
    internal val parallelFlag: () -> Boolean = { false },
    /** 服务显示中的任务:(对话, 状态),两者一起写;并行时用它代替 [taskState],别的对话的状态不进来。 */
    internal val shownTask: StateFlow<Pair<String, TaskState>?> = MutableStateFlow(null),
    /** 正在跑或排队的任务所在的对话。 */
    internal val activeConversations: StateFlow<List<String>> = MutableStateFlow(emptyList()),
    /** 该对话正在跑的任务的原话;切过去接着显示时用。 */
    internal val runGoal: (String) -> String? = { null },
    /** 该对话正在跑的任务自己的状态(不论服务此刻显示的是谁);切走后靠它接着记那一问一答的步骤。 */
    internal val runState: (String) -> StateFlow<TaskState>? = { null },
    /** 该对话正在跑的任务自己的接管进度;主聊天的问题卡替正在办的一件事画接管按钮时用。 */
    internal val runStage: (String) -> StateFlow<Takeover.Stage>? = { null },
    /** 任务结束后补上的记忆与技能提示,按运行编号。 */
    internal val runNotes: StateFlow<Map<String, RunNotes>> = MutableStateFlow(emptyMap()),
    internal val learning: LearningActions? = null,
    /** 此刻的记忆条目(从加密存储读出);提示的文字和「用到」的内容从这里现取,对话文件里不存内容。 */
    internal val memoryItems: StateFlow<List<MemoryItem>> = MutableStateFlow(emptyList()),
    /** 沙箱是否就绪。 */
    internal val sandboxReady: StateFlow<Boolean> = MutableStateFlow(false),
    /** 当前任务导出的文件;任务开始时由服务清空。 */
    internal val sharedFiles: StateFlow<List<SharedFile>> = MutableStateFlow(emptyList()),
    /** 当前任务生成并留在工作区里的音频;任务开始时由服务清空。 */
    internal val generatedAudios: StateFlow<List<com.wangzan101.mika.core.chat.GeneratedAudio>> = MutableStateFlow(emptyList()),
    /** 当前任务生成并留在工作区里的图;任务开始时由服务清空。 */
    internal val generatedPhotos: StateFlow<List<com.wangzan101.mika.core.chat.GeneratedPhoto>> = MutableStateFlow(emptyList()),
    /** 把选中的文件(内容 URI 字符串)复制进对话工作区的 inbox/。 */
    internal val importAttachments: suspend (conversationId: String, uris: List<String>) -> ImportResult = { _, _ -> ImportResult(emptyList(), emptyList()) },
    /** 读出选中文件(内容 URI 字符串)的名字、大小和是不是照片;读盘的活由调用方放到后台线程。 */
    internal val describeAttachments: suspend (uris: List<String>) -> List<PendingAttachment> = { uris -> uris.map { PendingAttachment(it, it.substringAfterLast('/'), 0L) } },
    /** 步骤行里工具的说法。 */
    internal val toolLabels: ToolLabels = ToolLabels(knownTools()),
    /** 显示中的任务实际做过的事(运行事件里的活动)。 */
    internal val activities: StateFlow<List<RunEvent.Activity>> = MutableStateFlow(emptyList()),
    /** 显示中的任务收到的插话和去向。 */
    internal val steers: StateFlow<List<SteerNote>> = MutableStateFlow(emptyList()),
    /** 通话里在等主人回话时,这个对话里发的下一句交给通话(返回 true,不开任务也不插话);没在等就返回 false。 */
    internal val ownerAnswer: (conversationId: String, text: String) -> Boolean = { _, _ -> false },
    /** 整个进程的排队队列;这里只取当前对话、还没被服务取走的项。 */
    private val followUps: StateFlow<List<QueuedFollowUp>> = MutableStateFlow(emptyList()),
    /** 停止之后正在写交代的那几次运行(运行编号);那一轮下面显示「正在整理已做的事…」。 */
    internal val summarizing: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
    /** 算用时的时钟(毫秒)。 */
    internal val clock: () -> Long = System::currentTimeMillis,
    /** 删掉一个对话会连带什么(见 `ConversationDeleter.impact`)。 */
    internal val deletionImpact: (String) -> DeleteImpact = { DeleteImpact(runningTask = false, schedules = 0) },
    /** 删掉一个对话和属于它的一切(见 `ConversationDeleter.delete`)。 */
    internal val removeConversation: suspend (String) -> Unit = {},
    /** 任务结束后留在后台运行的那一页(全局至多一页);对话页在它来自的那一轮下面显示一行,顶栏写「后台保持着 1 个网页」。 */
    val keptPage: StateFlow<KeptPage?> = MutableStateFlow(null),
    /** 关掉留着的那一页(用户点了那一行上的「关闭」)。 */
    private val closeKept: () -> Unit = {},
    /** 分支时复制源对话工作区的 inbox/(附件);没注入就只复制轮次。 */
    internal val branchWorkspace: BranchWorkspace? = null,
    /** 分支标题:在源标题或第一句话后面加本地化后缀(太长时截断);默认原样。 */
    internal val branchTitle: (String) -> String = { it },
    /** 系统分享草稿的持久化;内部图片文件名变成本 App provider 的内容地址。 */
    internal val sharedDrafts: SharedDraftStore? = null,
    internal val sharedDraftUri: (SharedDraftAttachment) -> String = { it.fileName },
    /** 读写对话记录、复制分支在哪儿做。 */
    internal val io: CoroutineContext = Dispatchers.IO,
    /** 正在跟用户学习时不另开任务，也不把这句话记成已经开始。 */
    internal val teaching: () -> Boolean = { false },
    internal val onTeachingBlocked: () -> Unit = {},
    /** 朗读一条回答;没配置声音模型时不做。 */
    private val speakText: suspend (String) -> Unit = {},
    private val stopVoice: () -> Unit = {},
    private val voicePlayback: StateFlow<com.wangzan101.mika.voice.VoicePlayback> =
        MutableStateFlow(com.wangzan101.mika.voice.VoicePlayback.Idle),
    private val voiceReady: StateFlow<Boolean> = MutableStateFlow(false),
    /** 每条完成的回答自动朗读;默认关。 */
    internal val autoRead: StateFlow<Boolean> = MutableStateFlow(false),
    /** 播放工作区里的一段音频(对话编号, 路径);默认不做。 */
    private val playAudio: suspend (String, String) -> Unit = { _, _ -> },
    private val voiceFailed: (String) -> Unit = {},
    /** 项目分组列表与删项目入口;为 null 时仅按普通对话显示。 */
    internal val projectStore: ProjectStore? = null,
    internal val projectDeletionImpact: (String) -> ProjectDeleteImpact = { ProjectDeleteImpact(0, 0, 0) },
    internal val removeProject: suspend (String) -> Unit = {},
    internal val workspaceResolver: WorkspaceResolver? = null,
    /** 聊天走前台:打的字交给这条会话的前台,派出的任务挂成卡片;没给时(只在测试里)照旧直接开任务。 */
    desk: DeskChatDeps? = null,
) {
    internal val session = ChatSession(this)
    internal val live = ChatLiveFollow(this, session)
    internal val chats = ChatConversationOps(this, session)
    internal val branching = ChatBranchOps(this, session)
    internal val attaching = ChatAttachmentOps(this, session)
    internal val drafting = ChatDraftOps(this, session)
    internal val sending = ChatSendOps(this, session)
    internal val noting = ChatNoteOps(this, session)
    internal val desking: ChatDesk? = desk?.let { ChatDesk(this, session, it) }

    /** 接这条会话的前台;没接前台、或这是前台派出的子任务的对话时为 null(照旧直接对任务说,见 [ChatDesk.handles])。 */
    internal fun deskFor(conversationId: String?): ChatDesk? = desking?.takeIf { it.handles(conversationId) }
    private var autoReadKey by session::autoReadKey
    private val _ui = session._ui
    private val _jump = session._jump
    private val drafts = session.drafts
    private val _deleted = session._deleted
    private val deleting = session.deleting
    private val _branchCopy = session._branchCopy
    private val _deleteBatch = session._deleteBatch

    /** 项目分组列表;为空或不注入时不展示项目分组。 */
    val projects: StateFlow<List<Project>> = projectStore?.projects ?: MutableStateFlow(emptyList())
    private val _collapsedProjects = MutableStateFlow<Set<String>>(emptySet())
    val collapsedProjects: StateFlow<Set<String>> = _collapsedProjects.asStateFlow()

    fun toggleProjectCollapsed(projectId: String) {
        _collapsedProjects.update { if (projectId in it) it - projectId else it + projectId }
    }

    /** 用户在对话页上关掉留在后台的那一页。 */
    fun closeKeptPage() = closeKept()

    /** 说话状态;回答下面的「朗读/停止」按它显示。 */
    val voice: StateFlow<com.wangzan101.mika.voice.VoicePlayback> get() = voicePlayback

    /** 有没有可用的声音模型;没有时不显示朗读入口。 */
    val canSpeak: StateFlow<Boolean> get() = voiceReady

    fun speak(text: String) {
        scope.launch {
            try {
                speakText(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                voiceFailed(tr(R.string.task_error, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun stopSpeaking() = stopVoice()

    /** 播放这一轮生成的一段音频;没有对话编号时不做。 */
    fun playAudio(path: String) {
        val id = _ui.value.conversationId ?: return
        scope.launch { playAudio(id, path) }
    }

    val ui: StateFlow<ChatUi> = _ui.asStateFlow()
    val jump: StateFlow<ChatJump?> = _jump.asStateFlow()

    /** 尚未发送第一句话的草稿也能从侧栏找回来;已有历史的对话由侧栏沿用原标题。 */
    val draftConversations = combine(drafts, _ui) { saved, current ->
        val all = saved + listOfNotNull(current.conversationId?.let { it to current.draft() }).toMap()
        all.filterValues { it.hasContent }.map { (id, draft) ->
            ConversationSummary(id,
                ChatCopy.plainTitle(draft.input).takeIf { it.isNotBlank() }
                    ?: draft.attachments.firstOrNull()?.name ?: tr(R.string.chat_new), 0L,
                projectId = session.projectFor(id) ?: draft.projectId)
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val deleted: StateFlow<Set<String>> = _deleted.asStateFlow()
    val branchCopy: StateFlow<BranchCopyResult?> = _branchCopy.asStateFlow()

    /** 屏上这个对话以外、有任务在跑或排队的对话(顶部「另 n 个任务进行中」)。 */
    val otherRunning: StateFlow<List<String>> = combine(activeConversations, _ui.map { it.conversationId }.distinctUntilChanged()) { active, current ->
        active.filter { it != current }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        // 开关关着时也按状态带的对话认:别的对话的任务(悬浮胶囊、定时任务开始的)不接到屏上这个对话里
        scope.launch { taskState.collect { s -> if (!session.parallel()) shownTask.value?.let { live.onShown(it.first, s) } ?: live.onState(s) } }
        scope.launch { shownTask.collect { t -> if (t != null && session.parallel()) live.onShown(t.first, t.second) } }
        // 排队条:只显示当前对话、明确排队、还没被服务取走的项;fallback 由穿插话那行 SteerState.Next 表示。
        // 和整个 ChatUi 组合(不只是 conversationId):换对话/重建 ChatUi 时即使编号没变,也要把队列补回去。
        scope.launch {
            combine(followUps, _ui) { list, ui ->
                val id = ui.conversationId
                if (id == null) emptyList() else list.filter {
                    it.conversationId == id && !it.claimed && it.source == QueuedSource.Explicit
                }
            }.collect { queued -> _ui.update { if (it.queued == queued) it else it.copy(queued = queued) } }
        }
        // 不论是谁存进屏上这个对话的(悬浮胶囊、定时任务、这里),都按存下的那份补上
        scope.launch {
            store.changes.collect { id ->
                if (id == _ui.value.conversationId) live.syncSaved(id)
                // 删掉的对话又有人往里存了:重新列出来
                if (id in _deleted.value && id !in deleting && store.turns(id).isNotEmpty()) _deleted.update { it - id }
            }
        }
        // 并行时只收屏上这个对话的任务的接管进度
        scope.launch {
            takeoverStage.collect { s -> if (session.ownTask()) _ui.update { it.copy(takeoverStage = s) } }
        }
        // 只认任务进行中新列出的标准:订阅时的旧值可能是上一个任务的
        // 有没有屏和状态一样是每个任务各自的:并行时只收屏上这个对话的任务的
        scope.launch { screenOpen.collect { if (it && session.ownTask()) live.onScreenOpened() } }
        scope.launch { sandboxReady.collect { r -> _ui.update { it.copy(attachEnabled = r) } } }
        scope.launch { sharedFiles.collect { if (session.ownTask()) live.onSharedFiles(it) } }
        scope.launch { autoRead.collect { if (!it) autoReadKey = null } }
        scope.launch { generatedAudios.collect { if (session.ownTask()) live.onAudios(it) } }
        scope.launch { generatedPhotos.collect { if (session.ownTask()) live.onPhotos(it) } }
        scope.launch { runNotes.collect { notes -> _ui.update { ui -> ui.copy(exchanges = ui.exchanges.map { session.withNotes(it, notes) }) } } }
        scope.launch { activities.collect { if (session.ownTask()) live.onActivities(it) } }
        scope.launch { steers.collect { if (session.ownTask()) live.onSteers(it) } }
        // 任务登记进任务列表(原话可以取了)可能晚于它的第一个状态:那时接上的那一问一答补上原话
        scope.launch { activeConversations.collect { live.fillGoal() } }
        scope.launch { summarizing.collect { set -> _ui.update { ui -> ui.copy(exchanges = ui.exchanges.map { session.withSummarizing(it, set) }) } } }
        // 分享附件有私有副本,可以恢复;只把当前对话那份放到输入框,其余留在各自草稿里。
        sharedDrafts?.readAll()?.forEach { draft ->
            drafts.update { it + (draft.conversationId to ComposerDraft(input = draft.text,
                attachments = session.sharedAttachments(draft), sharedDraft = draft)) }
        }
        desking?.start()
        val current = store.currentChat() as? CurrentChat.Open
        val pending = sharedDrafts?.readPending()?.takeIf { current != null && it.conversationId == current.id }
        if (current != null && (current.id in drafts.value || pending != null)) {
            _ui.value = session.restoreDraft(_ui.value.rebuildForConversation(conversationId = current.id, exchanges = session.saved(current.id)))
                .copy(pendingSharedDraft = pending)
        }
    }

    val deleteBatch: StateFlow<DeleteBatch?> = _deleteBatch.asStateFlow()

    /** 正在操作的 App 或网站(最近一件有对象的事);顶栏副标题「正在操作 …」用。没有时为 null。 */
    fun livePlace(ex: Exchange): String? =
        ex.activities.lastOrNull { !it.target.isNullOrBlank() }?.target?.let { where(it, appLabel) }

    /** 该对话正在跑的任务的原话;侧栏「进行中」里还没存进对话列表的那条用它当标题。 */
    fun runningGoal(conversationId: String): String? = runGoal(conversationId)

    /** 能不能换到别的对话:并行时随时能换,否则要等这个对话的任务结束。 */
    fun canSwitch() = session.parallel() || !session.busy()

    // ChatSession
    fun busy() = session.busy()

    // ChatLiveFollow
    fun refresh() = live.refresh()

    // ChatConversationOps
    fun switchTo(conversationId: String) = chats.switchTo(conversationId)
    fun openWorkflowConversation(conversationId: String) = chats.openWorkflowConversation(conversationId)
    fun openPersonalTask(conversationId: String) = chats.openPersonalTask(conversationId)
    fun openConversationAt(id: String, target: ChatTarget) = chats.openConversationAt(id, target)

    /** 这个对话的语音通话记录(聊天页画通话起止线用);没有对话为空。 */
    fun calls(conversationId: String?): kotlinx.coroutines.flow.Flow<List<com.wangzan101.mika.chat.ConversationCall>> =
        conversationId?.let(store::observeCalls) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    /** 前台派过的几件事各自会话起好的标题(「这个对话里的事」用);没起好的不在里面。 */
    suspend fun deskTaskTitles(ids: List<String>): Map<String, String> =
        kotlinx.coroutines.withContext(io) { ids.mapNotNull { id -> store.title(id)?.let { id to it } }.toMap() }
    fun consumeJump(jump: ChatJump) = chats.consumeJump(jump)
    fun createProject(name: String): Result<Project>? = chats.createProject(name)
    fun renameProject(projectId: String, name: String): Result<Project>? = chats.renameProject(projectId, name)
    fun projectDeleteImpact(projectId: String): ProjectDeleteImpact = chats.projectDeleteImpact(projectId)
    fun deleteProject(projectId: String) = chats.deleteProject(projectId)
    fun openConversation(id: String) = chats.openConversation(id)
    fun newConversation(projectId: String? = null) = chats.newConversation(projectId)
    fun currentRunning(): Boolean = chats.currentRunning()
    fun deleteImpact(id: String): DeleteImpact = chats.deleteImpact(id)
    fun deleteConversation(id: String) = chats.deleteConversation(id)
    fun clearDeleteBatch() = chats.clearDeleteBatch()
    fun deleteConversations(ids: Collection<String>) = chats.deleteConversations(ids)
    fun renameConversation(id: String, title: String) = chats.renameConversation(id, title)
    fun setPinned(id: String, pinned: Boolean) = chats.setPinned(id, pinned)
    fun archiveConversation(id: String): Boolean = chats.archiveConversation(id)
    fun unarchiveConversation(id: String) = chats.unarchiveConversation(id)
    fun unarchiveConversations(ids: Collection<String>) = chats.unarchiveConversations(ids)
    fun archiveConversations(ids: Collection<String>): Int = chats.archiveConversations(ids)

    // ChatBranchOps
    fun branchTurn(key: String): Int? = branching.branchTurn(key)
    fun retryTurn(key: String): Int? = branching.retryTurn(key)
    fun clearBranchCopy() = branching.clearBranchCopy()
    suspend fun branchFrom(throughTurn: Int): String? = branching.branchFrom(throughTurn)
    suspend fun branchBefore(turn: Int): BranchRetry? = branching.branchBefore(turn)

    // ChatAttachmentOps
    fun attach(uris: List<String>, conversationId: String? = _ui.value.conversationId) = attaching.attach(uris, conversationId)
    internal fun importDeviceAttachments(conversationId: String, paths: List<String>, copy: suspend (String, String) -> com.wangzan101.mika.core.workspace.DeviceFileImport) = attaching.importDeviceAttachments(conversationId, paths, copy)
    fun libraryTargetConversation(): String = attaching.libraryTargetConversation()

    /** 当前会话的编号;还是空白新对话时占下一条(聊天页停到这条上),给通话这类要落进当前会话的入口用。 */
    fun claimConversation(): String = session.draftConversation()
    fun planLibraryAttachments(conversationId: String, sources: List<LibraryFileHandle>): LibraryAddPlan? = attaching.planLibraryAttachments(conversationId, sources)
    fun addLibraryAttachments(conversationId: String, items: List<LibraryStaged>, overLimit: Int = 0): LibraryMergeResult = attaching.addLibraryAttachments(conversationId, items, overLimit)
    fun addLibraryAttachment(conversationId: String, item: PendingAttachment, source: LibraryFileHandle? = null, release: () -> Unit = {}): LibraryAttachmentResult = attaching.addLibraryAttachment(conversationId, item, source, release)
    fun toggleAttachment(item: PendingAttachment): Boolean = attaching.toggleAttachment(item)
    fun removeAttachment(uri: String) = attaching.removeAttachment(uri)
    fun dismissRejected() = attaching.dismissRejected()

    // ChatDraftOps
    fun withDrafts(history: List<ConversationSummary>): List<ConversationSummary> = drafting.withDrafts(history)
    fun input(text: String) = drafting.input(text)
    internal fun saveDraftTexts(): List<String> = drafting.saveDraftTexts()
    internal fun restoreDraftTexts(saved: List<String>) = drafting.restoreDraftTexts(saved)
    fun openSharedDraft(
        prepared: SharedDraft,
        inCurrentConversation: Boolean = false,
        rejected: List<AttachmentFailure> = emptyList(),
        overLimit: Int = 0,
    ): Boolean = drafting.openSharedDraft(prepared, inCurrentConversation, rejected, overLimit)
    fun confirmPendingSharedDraft(): Boolean = drafting.confirmPendingSharedDraft()
    fun cancelPendingSharedDraft() = drafting.cancelPendingSharedDraft()

    // ChatSendOps
    fun quote(from: QuoteMessage.From, text: String) = sending.quote(from, text)
    fun clearQuote() = sending.clearQuote()
    fun send(text: String, check: Prerequisites.Check): Boolean = sending.send(text, check)
    fun startQueued() = sending.startQueued()
    fun cancelQueued(id: String): Boolean = sending.cancelQueued(id)
    fun editQueued(id: String): String? = sending.editQueued(id)
    fun withdrawSteer(messageId: String): Boolean = sending.withdrawSteer(messageId)
    fun editSteer(messageId: String): String? = sending.editSteer(messageId)
    fun dismissBlocked() = sending.dismissBlocked()
    fun cancel() = sending.cancel()
    fun resume() = sending.resume()
    fun takeOver() = sending.takeOver()
    fun handBack() = sending.handBack()
    fun finishedMyself() = sending.finishedMyself()
    fun skip() = sending.skip()
    fun answer(text: String) = sending.answer(text)
    fun approve(key: Int) = sending.approve(key)
    fun decline(key: Int) = sending.decline(key)
    fun expandPip() = sending.expandPip()
    fun attendPip() = sending.attendPip()
    fun collapsePip() = sending.collapsePip()
    fun closePip() = sending.closePip()
    fun reopenPip() = sending.reopenPip()
    fun togglePipSound() = sending.togglePipSound()

    /** 让服务显示前台派出的那件任务 [taskId](它的对话编号就是任务编号):小窗的画面跟着换,屏上的对话不变。 */
    fun showDeskScreen(taskId: String) = actions.show(taskId)

    /** 前台派出的那件任务小窗里的声音开关。 */
    fun togglePipSoundIn(taskId: String) = actions.togglePipSoundIn(taskId)

    // ChatDesk
    /** 聊天页在不在屏上:在的时候后台的汇报由前台在页面上说一句,不在时交给兜底(写回会话、发通知)。 */
    fun pageShown(shown: Boolean) { desking?.pageShown(shown) }

    /** 前台派出的一件在问问题,用户在问题卡上点了 [words]。 */
    fun answerDeskTask(taskId: String, words: String) { desking?.answer(taskId, words) }

    /** 停下前台派出的一件。 */
    fun cancelDeskTask(taskId: String) { desking?.cancel(taskId) }

    /** 正在办的一件事([taskId] 即它跑的那个对话)要你动手时,问题卡上的按钮:只作用于这一件;停止照样经由 Mika。 */
    internal fun deskNeedOps(taskId: String) = NeedOps(
        cancel = { cancelDeskTask(taskId) }, resume = { actions.resumeIn(taskId) }, takeOver = { actions.takeOverIn(taskId) },
        handBack = { actions.handBackIn(taskId) }, finishedMyself = { actions.finishedMyselfIn(taskId) }, skip = { actions.skipIn(taskId) },
        approve = { actions.approveIn(taskId, it) }, decline = { actions.declineIn(taskId, it) },
    )

    // ChatNoteOps
    fun noteText(note: MemoryNote): String = noting.noteText(note)
    fun undoNote(runId: String, note: MemoryNote) = noting.undoNote(runId, note)
    fun approveNote(runId: String, note: MemoryNote) = noting.approveNote(runId, note)
    fun rejectNote(runId: String, note: MemoryNote) = noting.rejectNote(runId, note)
    fun usedMemoryTexts(ids: List<String>): List<String> = noting.usedMemoryTexts(ids)
    fun viewSkill(note: SkillNote) = noting.viewSkill(note)
    fun restoreSkill(runId: String, note: SkillNote) = noting.restoreSkill(runId, note)
    fun deleteSkill(runId: String, note: SkillNote) = noting.deleteSkill(runId, note)

    companion object {
        const val DONE_LINGER_MS = 3_000L

        internal fun reduce(ex: Exchange, s: TaskState, appLabel: (String) -> String = { it }, toolLabel: (String) -> String = { it }): Exchange =
            ChatReduce.reduce(ex, s, appLabel, toolLabel)

        internal fun withAttachments(text: String, files: List<Pair<String, Long>>): String = ChatReduce.withAttachments(text, files)

        fun sizeLabel(bytes: Long): String = ChatReduce.sizeLabel(bytes)
    }
}
