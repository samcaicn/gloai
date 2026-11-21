package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.i18n.uiLocale
import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.tool.LegacyNames
import com.wangzan101.mika.chat.BranchCopyResult
import com.wangzan101.mika.chat.BranchOrigin
import com.wangzan101.mika.chat.BranchWorkspace
import com.wangzan101.mika.chat.RetryAttachmentResult
import com.wangzan101.mika.chat.RetryAttachmentStatus
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.chat.DeleteBatch
import com.wangzan101.mika.chat.DeleteImpact
import com.wangzan101.mika.chat.Project
import com.wangzan101.mika.chat.ProjectDeleteImpact
import com.wangzan101.mika.chat.ProjectStore
import com.wangzan101.mika.chat.SharedDraft
import com.wangzan101.mika.chat.SharedDraftStore
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.chat.WorkspaceResolver
import com.wangzan101.mika.cap.sandbox.ImportResult
import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.cap.sandbox.AttachmentFailureReason
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.GoalChecklist
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.task.TaskGate
import com.wangzan101.mika.core.agent.NoticeTurn
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.memory.MemoryItem
import com.wangzan101.mika.core.memory.MemoryNote
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.skills.SkillNote
import com.wangzan101.mika.memory.RunNotes
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.Problem
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.task.QueuedFollowUp
import com.wangzan101.mika.task.QueuedSource
import com.wangzan101.mika.task.unconsumed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.userscreen.UserScreenMessage
import kotlin.coroutines.CoroutineContext

/** 界面对任务服务能做的事;真机上由 TaskService 实现,测试里换成记录调用。 */
interface TaskActions {
    fun start(goal: String, conversationId: String)
    /** 任务进行中在它的对话里插的话;[messageId] 用来跟这句话的去向。 */
    fun steer(conversationId: String, messageId: String, text: String) = start(text, conversationId)
    /** 任务进行中在这个对话里排下一件;先持久化,失败返回 false。[startIfIdle] 为假只落盘,不抢在原话任务前开始。 */
    fun enqueueFollowUp(conversationId: String, messageId: String, text: String, startIfIdle: Boolean = true): Boolean {
        start(text, conversationId)
        return true
    }
    /** 取消一条还没开始的排队;已经开始就返回 false。 */
    fun cancelFollowUp(conversationId: String, messageId: String): Boolean = false
    /** 打开这个对话时,把排在它下面、又没任务在跑的下一件取出来开始。 */
    fun drainFollowUps(conversationId: String) {}
    fun withdrawSteer(conversationId: String, messageId: String): Boolean = false
    fun cancel()
    fun resume()
    fun takeOver()
    fun handBack()
    fun finishedMyself()
    fun skip()
    fun answer(text: String)
    /** [key] 是卡片所属的那次申报。 */
    fun approve(key: Int)
    fun decline(key: Int)
    /** 并行任务时让服务显示这个对话的任务(只管显示:对外的状态流跟着它)。 */
    fun show(conversationId: String) {}

    // 并行任务时应用内的按钮带着屏上的对话,只作用于该对话的任务;它已经没有任务就作废。默认同不带对话的版本。
    fun cancelIn(conversationId: String) = cancel()
    fun resumeIn(conversationId: String) = resume()
    fun takeOverIn(conversationId: String) = takeOver()
    fun handBackIn(conversationId: String) = handBack()
    fun finishedMyselfIn(conversationId: String) = finishedMyself()
    fun skipIn(conversationId: String) = skip()
    fun answerIn(conversationId: String, text: String) = answer(text)
    fun approveIn(conversationId: String, key: Int) = approve(key)
    fun declineIn(conversationId: String, key: Int) = decline(key)
    /** 切换这个对话任务当前 App 的声音(恢复/再静上);没有任务就不动。 */
    fun togglePipSoundIn(conversationId: String) {}
}

enum class StepState { Ok, Run, Wait, Dim }

/**
 * 一步里的一个动作:做了什么([label],工具的界面说法,不是工具名)、对什么、结果如何(运行记录里的 effect)。
 * 点开这一步时逐条列出来——一行标题写的是汇总,想看它到底点了什么、打了什么字,看这里。
 */
data class StepAction(val label: String, val target: String?, val effect: String?, val failed: Boolean)

/**
 * 执行过程里的一行,来自实际做过的事(运行事件里的活动):[title] 是做了什么(读取类合并成「看了微信的聊天列表」),
 * [reason] 是模型给那一步写的用意(没写时为空),[subtitle] 是没成功时的说明,平时为空。
 */
data class StepRow(val step: Int, val title: String, val subtitle: String, val state: StepState, val reason: String = "") {
    /** 这一步在界面上的说法:模型自己的话,没有时用工具的说法。 */
    val text: String get() = if (shouldHideRawReason(reason)) title else reason.ifEmpty { title }

    companion object {
        /**
         * 当界面语言为非中文环境（如当前语言为英文），而模型未遵循指令输出了包含中文字符的用意时，
         * 避免在界面上展示突兀的生硬中文，回退为已本地化的动作标题（如 Tap · WeChat）。
         * 「像中文」要求含汉字且不含假名:日语书面语几乎必混假名(助词/活用),只有假名能把日语从汉字里分出来,
         * 否则日语界面下模型按语言指令写的日语用意会被整条隐藏。
         */
        internal fun shouldHideRawReason(reason: String, isChineseLocale: Boolean = uiLocale().language == "zh"): Boolean {
            if (reason.isEmpty()) return false
            if (isChineseLocale) return false
            return reason.any { it in '\u4e00'..'\u9fa5' } && reason.none { it in '\u3040'..'\u30ff' }
        }
    }
}

sealed interface ExchangeStatus {
    data object Working : ExchangeStatus
    data object WaitingUnlock : ExchangeStatus
    /** 付款接管时 [item]/[amount]/[address] 单独列在卡片上。 */
    data class NeedsTakeover(
        val reason: String, val message: String,
        val item: String? = null, val amount: String? = null, val address: String? = null,
    ) : ExchangeStatus
    data class NeedsInput(val prompt: String, val options: List<String>?) : ExchangeStatus
    /**
     * 等用户同意一次要花钱的操作(只能同意这一次);[title]「要你同意:<动作>」,[body] 一行一个字段(App、对象、完整内容、金额)。
     * [key] 是这次申报的编号,按钮带着它,点在旧卡片上不算数。
     */
    data class NeedsConfirmation(val title: String, val body: String, val key: Int) : ExchangeStatus
    /** 停下来等;[resumable] 时点「继续」接着做,否则条件满足后自动继续(卡片上不给「继续」)。 */
    data class Paused(val why: String, val resumable: Boolean = true) : ExchangeStatus
    /** 还没开始,排在开始队列里;轮到时自动开始,只能取消。 */
    data class Queued(val why: String) : ExchangeStatus
    /**
     * [verified] 为 false 表示办完了但没能核实,[note] 是原因;这时不打绿勾。[unconfirmed] 是回答里核对后仍没找到出处的几处,
     * 卡片上逐条列出。[reviewed] 为 false 是什么也没核对(聊天、回答里没有可核对的值):不打任何标。[evidence] 只有旧记录里模型自己写的「依据:」段才有;新的回答来源从运行记录里取(见 [evidenceSource])。
     */
    data class Done(
        val answer: String, val evidence: String?, val verified: Boolean = true, val note: String? = null,
        val unconfirmed: List<String> = emptyList(), val reviewed: Boolean = true,
    ) : ExchangeStatus {
        val unverifiedLine: String? get() = when {
            unconfirmed.isNotEmpty() -> tr(R.string.chat_unconfirmed, unconfirmed.joinToString(tr(R.string.list_sep)))
            verified || !reviewed -> null
            else -> note?.let { tr(R.string.chat_unverified_because, it) } ?: tr(R.string.chat_unverified)
        }
    }
    data class Failed(val reason: String) : ExchangeStatus
    /**
     * 用户停止了。[summary] 是停止后补上的交代(做过什么、哪些没做完),当作这一轮的回答;还没写好、没什么可交代时为 null。
     * [summarizing] 时交代还在写(「正在整理已做的事…」)。
     */
    data class Cancelled(val summary: String? = null, val summarizing: Boolean = false) : ExchangeStatus
}

/**
 * 一问一答:用户的一句话、执行过程、此刻的状态或结果。[live] 表示是这次打开 App 后跑的任务(带步骤)。
 * [runId] 是任务结束后可以查看的运行记录;在跑或没有记录时为 null。
 */
data class Exchange(
    val goal: String,
    val steps: List<StepRow>,
    val status: ExchangeStatus,
    val live: Boolean,
    val runId: String? = null,
    /** 模型请求在途时只显示状态，不把等待伪装成一次执行步骤。 */
    val waitingForModel: Boolean = false,
    /** 模型这次请求已经给出的可见文字；完成前可能成为进度说明或答复。 */
    val draft: String? = null,
    /** 结果卡片下的记忆提示(记住了 / 更新了 / 忘掉了 / 要我以后都这样做吗)。 */
    val memoryNotes: List<MemoryNote> = emptyList(),
    /** 这次用到的记忆条目编号(代码记录的,不是模型自述)。 */
    val usedMemory: List<String> = emptyList(),
    /** 结果卡片下的技能提示(学会了 / 最近不灵先停用了)。 */
    val skillNotes: List<SkillNote> = emptyList(),
    /** 这一轮导出到「下载/Mika」的文件,显示成文件卡片。 */
    val files: List<SharedFile> = emptyList(),
    /** 这一轮 Mika 生成并留在工作区里的音频,显示成音频卡片。 */
    val audios: List<com.wangzan101.mika.core.chat.GeneratedAudio> = emptyList(),
    /** 这一轮 Mika 生成并留在工作区里的图,显示成照片格子。 */
    val photos: List<com.wangzan101.mika.core.chat.GeneratedPhoto> = emptyList(),
    /** 这次打开 App 后开始跟的时刻(毫秒);不知道时为 0。 */
    val startedAt: Long = 0L,
    /** 结束时算出的用时;没结束或不知道开始时刻时为 null。 */
    val elapsedMs: Long? = null,
    /** 到此刻实际做过的事(运行事件);[steps] 由它推出。 */
    val activities: List<RunEvent.Activity> = emptyList(),
    /** 模型给各步写的话(进度说明或调用用意),按步号。 */
    val notes: Map<Int, String> = emptyMap(),
    /** 此刻在做什么:模型这一步的话,或不经模型连做几步时的进度;没有时为空。 */
    val line: String = "",
    /** 任务进行中用户在这里插的话和它们的去向。 */
    val steers: List<SteerNote> = emptyList(),
    /** 这一轮结束后留在后台运行的那一页的站点(见 [Turn.keptPage]);没留为 null。 */
    val keptPage: String? = null,
    /** 那一页后来关掉的原因([Turn.keptPageClosed]);还开着或不知道为 null。 */
    val keptPageClosed: String? = null,
    /** 每一步实际做的动作(按步号):标题是汇总,点开一步时看这里的明细。 */
    val stepActions: Map<Int, List<StepAction>> = emptyMap(),
    val goals: GoalChecklist? = null,
)

/**
 * 有运行记录可看的那次运行。通知提醒那一轮([NoticeTurn])是 Mika 直接写进对话的,没跑过任务,它的编号只是用来认它的:
 * 界面上「查看过程」、来源、重新开始都按这个给,那一轮就都没有(不然点进去只会说「记录已过期」)。
 * 认轮次、和存下的那份对上([ChatViewModel] 里按 [Exchange.runId])照旧用原来的编号。
 */
val Exchange.recordedRun: String? get() = runId?.takeUnless { it.startsWith(NoticeTurn.RUN_PREFIX) }

/** 在跑时的一行进展:等你时说等什么;模型请求在途时只给中性状态;否则是这一步的话,没有就是最近做的事。 */
fun liveLine(ex: Exchange): String? {
    when (ex.status) {
        is ExchangeStatus.NeedsTakeover -> return tr(R.string.takeover_title)
        is ExchangeStatus.NeedsInput -> return tr(R.string.chat_wait_answer)
        is ExchangeStatus.NeedsConfirmation -> return tr(R.string.chat_wait_approval)
        is ExchangeStatus.Queued -> return tr(R.string.chat_queued)
        else -> Unit
    }
    if (ex.waitingForModel) return tr(R.string.chat_working)
    return ex.line.ifEmpty { null } ?: ex.steps.lastOrNull()?.text
}

/** 插话下面那行小字;还没有去向时不写。[paused] 时任务正等着用户处理,说明这句要等继续后才生效。 */
fun steerLabel(state: SteerState, paused: Boolean = false): String? = when (state) {
    SteerState.Pending, SteerState.Withdrawn -> null
    SteerState.Received -> tr(if (paused) R.string.steer_received_paused else R.string.steer_received)
    SteerState.Joined -> tr(R.string.steer_joined)
    SteerState.Next -> tr(R.string.steer_next)
    SteerState.Cancelled -> tr(R.string.steer_cancelled)
}

/**
 * 插话能不能取回编辑。悬浮胶囊插的带着屏幕、浏览器页上选了字插的带着页面说明,取回来时输入框开头是
 * `[[screen:…]]` 那一行或几行页面说明原文,删掉就丢了屏幕和页面,改坏就认不出页面,这两种不给编辑。
 */
internal fun editableSteer(text: String): Boolean = !UserScreenMessage.has(text) && AttachedPage.parse(text).page == null

/** 结果卡片上记忆与技能提示的按钮;真机上由记忆存储与个人技能实现(读写在后台线程),测试里换成记录调用。 */
interface LearningActions {
    suspend fun undo(token: String): Boolean
    suspend fun approve(pendingId: String): Boolean
    /** 撤销令牌已失效(重启过)时「不要」直接删掉那条待批准的做法。 */
    suspend fun forget(memoryId: String): Boolean
    fun viewSkill(draftId: String)
    suspend fun restoreSkill(name: String)
    suspend fun deleteSkill(name: String)
    /** 提示改了(撤销、批准后去掉那一行):存进对话并通知界面。 */
    suspend fun save(notes: RunNotes)
}

/**
 * 选好、等着随下一句话发出去的附件:内容地址([uri])、名字和大小;发送时才复制进对话工作区。
 * [photo] 的在输入框上方是缩略图,其余是文件小卡片。
 */
data class PendingAttachment(
    val uri: String,
    val name: String,
    val size: Long,
    val photo: Boolean = AttachmentMessage.isPhoto(name),
    val workspaceConversationId: String? = null,
    val workspacePath: String? = null,
)

enum class PipMode { Running, NeedsYou, Done }

/**
 * 画中画只放画面:状态只靠边框光(绿色在干活、橙色呼吸要你处理),要你做的事在对话卡片里。
 * [closed]:用户把这个任务的小画面关掉了,只剩右下角一个小按钮(点它再打开;要你处理时它变橙色)。
 * 关掉只管这一个任务,下一个任务照常出小画面。
 */
data class PipUi(
    val visible: Boolean = false,
    val mode: PipMode = PipMode.Running,
    val expanded: Boolean = false,
    val closed: Boolean = false,
)

enum class ComposerMode {
    /** 没有任务,发出去就是新任务。 */
    Idle,
    /** 任务在跑:发出去的话交给这个任务,停止是另一个按钮。 */
    Busy,
    /** Agent 在等回答,发出去就是回答。 */
    Answer,
}

/** 任务进行中发补充消息时,这句话交给谁:当前任务(下一步带上),还是这个对话的下一件任务。 */
enum class FollowUpMode { Steer, Queue }

data class ChatUi(
    val conversationId: String? = null,
    val exchanges: List<Exchange> = emptyList(),
    /** 所属项目分组 id;新对话还没发第一句话时只保存在草稿/界面状态,发送首条消息时写入旁文件。 */
    val projectId: String? = null,
    /** 上次发送被拦下时缺的条件;为空表示没被拦。 */
    val blocked: List<Problem>? = null,
    val pip: PipUi = PipUi(),
    val composer: ComposerMode = ComposerMode.Idle,
    /** 任务进行中补充消息交给谁;主对话默认排队,点消息右侧箭头后改为引导。 */
    val followUpMode: FollowUpMode = FollowUpMode.Queue,
    /** 这个对话明确排队、还没开始的下一件(按先后);开始或取消后从这里拿掉。 */
    val queued: List<QueuedFollowUp> = emptyList(),
    val takeoverStage: Takeover.Stage = Takeover.Stage.None,
    /** 沙箱就绪时输入框旁才有附件按钮。 */
    val attachEnabled: Boolean = false,
    val input: String = "",
    val attachments: List<PendingAttachment> = emptyList(),
    /** 正在把附件复制进工作区(发送时)。 */
    val importing: Boolean = false,
    /** 没收下的附件(太大或读不了);为空不提示。 */
    val rejectedAttachments: List<AttachmentFailure> = emptyList(),
    /** 超过一次能带的个数([AttachTray.LIMIT])没加上的个数;为 0 不提示。 */
    val overLimit: Int = 0,
    /** 系统分享刚预填到新对话的内容;只供输入框展示,不会触发任务。 */
    val sharedDraft: SharedDraft? = null,
    /** 当前会话已有草稿时,后台收到的系统分享先挂在这里,等用户确认替换或取消。 */
    val pendingSharedDraft: SharedDraft? = null,
    /** 输入框上方要随下一句话发出去的引用(长按消息选「引用」);没有为 null。随会话草稿保留。 */
    val quote: QuoteMessage.Quote? = null,
) {
    /** 任务此刻正在后台的虚拟屏上操作(输入框上方那行提示):开过屏、在干活,不在等你或等解锁。 */
    val operatingPhone: Boolean
        get() = pip.visible && pip.mode == PipMode.Running && exchanges.lastOrNull()?.status == ExchangeStatus.Working
}

private data class ComposerDraft(
    val input: String = "",
    val attachments: List<PendingAttachment> = emptyList(),
    val quote: QuoteMessage.Quote? = null,
    val followUpMode: FollowUpMode = FollowUpMode.Queue,
    val sharedDraft: SharedDraft? = null,
    val importing: Boolean = false,
    val rejected: List<AttachmentFailure> = emptyList(),
    val overLimit: Int = 0,
    val projectId: String? = null,
) {
    val hasContent: Boolean get() = input.isNotEmpty() || attachments.isNotEmpty() || quote != null || importing || rejected.isNotEmpty()
    fun applyTo(ui: ChatUi) = ui.copy(input = input, attachments = attachments, quote = quote,
        followUpMode = followUpMode, sharedDraft = sharedDraft, importing = importing,
        rejectedAttachments = rejected, overLimit = overLimit, projectId = projectId ?: ui.projectId)
}

private fun ChatUi.draft() = ComposerDraft(input, attachments, quote, followUpMode, sharedDraft,
    importing, rejectedAttachments, overLimit, projectId)

/**
 * 换对话时统一重建界面:任务和输入内容属于各自对话,附件按钮能力属于设备,不随对话重建丢掉。
 * 每条切换路径都从这里走,再恢复目标对话自己的草稿。系统分享可以显式填入它自己的文字和附件。
 */
private fun ChatUi.rebuildForConversation(
    conversationId: String?,
    exchanges: List<Exchange> = emptyList(),
    takeoverStage: Takeover.Stage = Takeover.Stage.None,
    input: String = "",
    sharedDraft: SharedDraft? = null,
    attachments: List<PendingAttachment> = emptyList(),
    projectId: String? = null,
) = copy(
    conversationId = conversationId,
    exchanges = exchanges,
    projectId = projectId,
    blocked = null,
    pip = PipUi(),
    composer = ComposerMode.Idle,
    input = input,
    followUpMode = FollowUpMode.Queue,
    queued = emptyList(),
    takeoverStage = takeoverStage,
    attachments = attachments,
    importing = false,
    rejectedAttachments = emptyList(),
    overLimit = 0,
    sharedDraft = sharedDraft,
    pendingSharedDraft = null,
    quote = null,
)

/**
 * 聊天界面的状态。步骤列表来自运行事件里实际做过的事(活动);进展那一行是模型这一步的话,
 * 等待模型时仅显示状态。任务在跑时输入框发的话先排队,用户再决定是否改为当前任务的引导。
 * 整个进程只用一个实例,界面关掉再打开时正在跑的任务的步骤还在。
 */
class ChatViewModel(
    private val scope: CoroutineScope,
    taskState: StateFlow<TaskState>,
    private val takeoverStage: StateFlow<Takeover.Stage>,
    private val actions: TaskActions,
    private val store: ConversationStore,
    /**
     * 服务还有任务(这个对话上一个任务还在收尾,或别处开始的任务在跑);并行关着时新任务等它收完再交给服务,
     * 免得这个对话的新一句被当成上一个任务的插话。等着时那条消息下面写「等前一个任务做完再开始」。
     */
    private val serviceBusy: StateFlow<Boolean> = MutableStateFlow(false),
    /** 包名换成 App 显示名(确认卡片用)。 */
    private val appLabel: (String) -> String = { it },
    /**
     * 当前任务有没有虚拟屏。屏到第一次打开 App 才建,没建过屏的任务(纯对话、不用手机就能答)不出画中画,
     * 问答和确认都在聊天卡片里。
     */
    private val screenOpen: StateFlow<Boolean> = MutableStateFlow(false),
    /**
     * 刚结束的那个任务的运行记录编号,服务在报结束之前写好;收到结束状态时挂到那一问一答上,
     * 卡片上就有「查看过程」。之后它再变也不改已结束的那条。
     */
    private val endedRunId: StateFlow<String?> = MutableStateFlow(null),
    /**
     * 对话里正在跑的那个任务的运行记录编号(任务一开头就有;还没分到时为 null)。按对话取,和 [endedRunIds] 一样
     * 不看服务对外的「显示中的任务」:并行时那可能是别的对话的。在跑时挂上它,「运行过程」当场就能打开。
     */
    private val liveRunId: (conversationId: String) -> String? = { null },
    /** 每个对话最近结束的任务的运行记录编号;给了就按卡片所在的对话取,不用上面那个(它只跟显示中的任务)。 */
    private val endedRunIds: StateFlow<Map<String, String?>>? = null,
    /** 开关 parallelTasks:打开时不同对话的任务可以同时跑,界面只跟着屏上这个对话的任务。界面没在跟任务时重读。 */
    private val parallelFlag: () -> Boolean = { false },
    /** 服务显示中的任务:(对话, 状态),两者一起写;并行时用它代替 [taskState],别的对话的状态不进来。 */
    private val shownTask: StateFlow<Pair<String, TaskState>?> = MutableStateFlow(null),
    /** 正在跑或排队的任务所在的对话。 */
    private val activeConversations: StateFlow<List<String>> = MutableStateFlow(emptyList()),
    /** 该对话正在跑的任务的原话;切过去接着显示时用。 */
    private val runGoal: (String) -> String? = { null },
    /** 该对话正在跑的任务自己的状态(不论服务此刻显示的是谁);切走后靠它接着记那一问一答的步骤。 */
    private val runState: (String) -> StateFlow<TaskState>? = { null },
    /** 任务结束后补上的记忆与技能提示,按运行编号。 */
    private val runNotes: StateFlow<Map<String, RunNotes>> = MutableStateFlow(emptyMap()),
    private val learning: LearningActions? = null,
    /** 此刻的记忆条目(从加密存储读出);提示的文字和「用到」的内容从这里现取,对话文件里不存内容。 */
    private val memoryItems: StateFlow<List<MemoryItem>> = MutableStateFlow(emptyList()),
    /** 沙箱是否就绪。 */
    private val sandboxReady: StateFlow<Boolean> = MutableStateFlow(false),
    /** 当前任务导出的文件;任务开始时由服务清空。 */
    private val sharedFiles: StateFlow<List<SharedFile>> = MutableStateFlow(emptyList()),
    /** 当前任务生成并留在工作区里的音频;任务开始时由服务清空。 */
    private val generatedAudios: StateFlow<List<com.wangzan101.mika.core.chat.GeneratedAudio>> = MutableStateFlow(emptyList()),
    /** 当前任务生成并留在工作区里的图;任务开始时由服务清空。 */
    private val generatedPhotos: StateFlow<List<com.wangzan101.mika.core.chat.GeneratedPhoto>> = MutableStateFlow(emptyList()),
    /** 把选中的文件(内容 URI 字符串)复制进对话工作区的 inbox/。 */
    private val importAttachments: suspend (conversationId: String, uris: List<String>) -> ImportResult = { _, _ -> ImportResult(emptyList(), emptyList()) },
    /** 读出选中文件(内容 URI 字符串)的名字、大小和是不是照片;读盘的活由调用方放到后台线程。 */
    private val describeAttachments: suspend (uris: List<String>) -> List<PendingAttachment> = { uris -> uris.map { PendingAttachment(it, it.substringAfterLast('/'), 0L) } },
    /** 步骤行里工具的说法。 */
    private val toolLabels: ToolLabels = ToolLabels(knownTools()),
    /** 显示中的任务实际做过的事(运行事件里的活动)。 */
    private val activities: StateFlow<List<RunEvent.Activity>> = MutableStateFlow(emptyList()),
    /** 显示中的任务收到的插话和去向。 */
    private val steers: StateFlow<List<SteerNote>> = MutableStateFlow(emptyList()),
    /** 整个进程的排队队列;这里只取当前对话、还没被服务取走的项。 */
    private val followUps: StateFlow<List<QueuedFollowUp>> = MutableStateFlow(emptyList()),
    /** 停止之后正在写交代的那几次运行(运行编号);那一轮下面显示「正在整理已做的事…」。 */
    private val summarizing: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
    /** 算用时的时钟(毫秒)。 */
    private val clock: () -> Long = System::currentTimeMillis,
    /** 删掉一个对话会连带什么(见 `ConversationDeleter.impact`)。 */
    private val deletionImpact: (String) -> DeleteImpact = { DeleteImpact(runningTask = false, schedules = 0) },
    /** 删掉一个对话和属于它的一切(见 `ConversationDeleter.delete`)。 */
    private val removeConversation: suspend (String) -> Unit = {},
    /** 任务结束后留在后台运行的那一页(全局至多一页);对话页在它来自的那一轮下面显示一行,顶栏写「后台保持着 1 个网页」。 */
    val keptPage: StateFlow<KeptPage?> = MutableStateFlow(null),
    /** 关掉留着的那一页(用户点了那一行上的「关闭」)。 */
    private val closeKept: () -> Unit = {},
    /** 分支时复制源对话工作区的 inbox/(附件);没注入就只复制轮次。 */
    private val branchWorkspace: BranchWorkspace? = null,
    /** 分支标题:在源标题或第一句话后面加本地化后缀(太长时截断);默认原样。 */
    private val branchTitle: (String) -> String = { it },
    /** 系统分享草稿的持久化;内部图片文件名变成本 App provider 的内容地址。 */
    private val sharedDrafts: SharedDraftStore? = null,
    private val sharedDraftUri: (String) -> String = { it },
    /** 读写对话记录、复制分支在哪儿做。 */
    private val io: CoroutineContext = Dispatchers.IO,
    /** 正在跟用户学习时不另开任务，也不把这句话记成已经开始。 */
    private val teaching: () -> Boolean = { false },
    private val onTeachingBlocked: () -> Unit = {},
    /** 朗读一条回答;没配置声音模型时不做。 */
    private val speakText: suspend (String) -> Unit = {},
    private val stopVoice: () -> Unit = {},
    private val voicePlayback: StateFlow<com.wangzan101.mika.voice.VoicePlayback> =
        MutableStateFlow(com.wangzan101.mika.voice.VoicePlayback.Idle),
    private val voiceReady: StateFlow<Boolean> = MutableStateFlow(false),
    /** 每条完成的回答自动朗读;默认关。 */
    private val autoRead: StateFlow<Boolean> = MutableStateFlow(false),
    /** 播放工作区里的一段音频(对话编号, 路径);默认不做。 */
    private val playAudio: suspend (String, String) -> Unit = { _, _ -> },
    private val voiceFailed: (String) -> Unit = {},
    /** 项目分组列表与删项目入口;为 null 时仅按普通对话显示。 */
    private val projectStore: ProjectStore? = null,
    private val projectDeletionImpact: (String) -> ProjectDeleteImpact = { ProjectDeleteImpact(0, 0, 0) },
    private val removeProject: suspend (String) -> Unit = {},
    private val workspaceResolver: WorkspaceResolver? = null,
) {
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

    /** 已经自动朗读过的那条回答;同一条不重复读。 */
    private var autoReadKey: String? = null

    private fun maybeAutoRead(ex: Exchange) {
        if (!autoRead.value) return
        val status = ex.status as? ExchangeStatus.Done ?: return
        val key = ex.runId ?: "${ex.startedAt}-${status.answer.hashCode()}"
        if (key == autoReadKey) return
        autoReadKey = key
        speak(status.answer)
    }

    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()

    /** 从搜索或文件库过来的一次性跳转;聊天页滚到目标后清掉。 */
    private val _jump = MutableStateFlow<ChatJump?>(null)
    val jump: StateFlow<ChatJump?> = _jump.asStateFlow()
    private var jumpSerial = 0L

    private val drafts = MutableStateFlow<Map<String, ComposerDraft>>(emptyMap())
    private var draftStateChanged = false
    private val libraryCopies = mutableMapOf<String, () -> Unit>()
    private val librarySources = mutableMapOf<String, LibraryFileHandle>()
    private val libraryImports = mutableMapOf<String, Int>()
    private val deviceImports = mutableMapOf<String, kotlinx.coroutines.Job>()

    /** 只回收本进程登记的副本；导入中或任何草稿引用中的 URI 继续保留。 */
    private fun releaseUnusedLibraryCopies() {
        val referenced = _ui.value.attachments.map { it.uri }.toSet() +
            drafts.value.values.flatMap { it.attachments.map(PendingAttachment::uri) } + libraryImports.keys
        val unused = libraryCopies.keys.filter { it !in referenced }
        unused.forEach { uri ->
            librarySources.remove(uri)
            libraryCopies.remove(uri)?.let { runCatching { it() } }
        }
    }
    /** 每次有分享放进当前会话就加一;异步操作靠它分辨草稿是被新分享换掉了,还是用户只改了文字。 */
    private var sharedDraftSerial = 0L

    /** 尚未发送第一句话的草稿也能从侧栏找回来;已有历史的对话由侧栏沿用原标题。 */
    val draftConversations = combine(drafts, _ui) { saved, current ->
        val all = saved + listOfNotNull(current.conversationId?.let { it to current.draft() }).toMap()
        all.filterValues { it.hasContent }.map { (id, draft) ->
            ConversationSummary(id,
                ChatCopy.plainTitle(draft.input).takeIf { it.isNotBlank() }
                    ?: draft.attachments.firstOrNull()?.name ?: tr(R.string.chat_new), 0L,
                projectId = projectFor(id) ?: draft.projectId)
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun withDrafts(history: List<ConversationSummary>): List<ConversationSummary> =
        draftConversations.value.filter { draft -> history.none { it.id == draft.id } }.map {
            it.copy(title = store.title(it.id) ?: it.title, pinnedAt = store.pinnedAt(it.id),
                page = store.pageOrigin(it.id), branchOf = store.branchOrigin(it.id), archivedAt = store.archivedAt(it.id),
                restoredAt = store.restoredAt(it.id), projectId = store.projectId(it.id) ?: it.projectId)
        } + history

    private fun draftConversation(): String {
        draftStateChanged = true
        return _ui.value.conversationId ?: store.newConversation().also { id ->
            workspaceResolver?.setDraftProject(id, _ui.value.projectId)
            _ui.update { it.copy(conversationId = id) }
            markCurrent(CurrentChat.Open(id))
        }
    }

    fun input(text: String) {
        if (text == _ui.value.input) return
        val id = draftConversation()
        val currentShared = _ui.value.sharedDraft
        val updatedShared = currentShared?.copy(text = text)?.takeIf { it.text.isNotBlank() || it.image != null }
        if (currentShared != null) {
            if (updatedShared == null) sharedDrafts?.clear(id)
            else sharedDrafts?.save(updatedShared)
        }
        _ui.update { it.copy(input = text, sharedDraft = updatedShared) }
    }

    private fun keepDraft() {
        draftStateChanged = true
        val ui = _ui.value
        val id = ui.conversationId ?: return
        val shared = ui.sharedDraft?.copy(text = ui.input)?.takeIf { it.text.isNotBlank() || it.image != null }
        if (ui.sharedDraft != null) {
            if (shared == null) sharedDrafts?.clear(id)
            else sharedDrafts?.save(shared)
        }
        val draft = ui.draft().copy(sharedDraft = shared)
        drafts.update { if (draft.hasContent) it + (id to draft) else it - id }
    }

    private fun restoreDraft(ui: ChatUi): ChatUi {
        val id = ui.conversationId ?: return ui
        val draft = drafts.value[id] ?: return ui
        drafts.update { it - id }
        return draft.applyTo(ui)
    }

    private fun updateDraft(id: String, change: (ComposerDraft) -> ComposerDraft) {
        if (id in _deleted.value) return
        if (_ui.value.conversationId == id) _ui.update { change(it.draft()).applyTo(it) }
        else drafts.update { saved ->
            val draft = change(saved[id] ?: ComposerDraft())
            if (draft.hasContent) saved + (id to draft) else saved - id
        }
    }

    /** 和原输入框一样通过 Android saved state 恢复文字;附件授权仍只在当前进程保留。 */
    internal fun saveDraftTexts(): List<String> {
        val ui = _ui.value
        val texts = drafts.value.mapValues { it.value.input } + listOfNotNull(ui.conversationId?.let { it to ui.input }).toMap()
        return listOf(ui.conversationId.orEmpty()) + texts.flatMap { (id, text) -> listOf(id, text) }
    }

    internal fun restoreDraftTexts(saved: List<String>) {
        // Activity 重建时进程里的状态更新得更近,不拿旧快照盖回去。
        if (draftStateChanged || saved.isEmpty()) return
        draftStateChanged = true
        saved.drop(1).chunked(2).filter { it.size == 2 }.forEach { (id, text) ->
            updateDraft(id) { it.copy(input = text) }
        }
        val id = saved.first().takeIf { it.isNotEmpty() } ?: return
        if (_ui.value.conversationId == null) {
            _ui.value = restoreDraft(_ui.value.copy(conversationId = id, exchanges = saved(id)))
            markCurrent(CurrentChat.Open(id))
        }
    }
    /** 删掉了(或正在删)的对话:侧栏当场不再列出,不等删完。 */
    private val _deleted = MutableStateFlow<Set<String>>(emptySet())
    val deleted: StateFlow<Set<String>> = _deleted.asStateFlow()
    /** 还没删完的。 */
    private val deleting = mutableSetOf<String>()
    /** 最近一次分支复制工作区的结果(超过上限、目标已有 inbox 等);界面提示完调 [clearBranchCopy]。 */
    private val _branchCopy = MutableStateFlow<BranchCopyResult?>(null)
    val branchCopy: StateFlow<BranchCopyResult?> = _branchCopy.asStateFlow()

    /** 屏上这个对话以外、有任务在跑或排队的对话(顶部「另 n 个任务进行中」)。 */
    val otherRunning: StateFlow<List<String>> = combine(activeConversations, _ui.map { it.conversationId }.distinctUntilChanged()) { active, current ->
        active.filter { it != current }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * 并行时切走的、任务还在跑的对话:那一问一答(原话、标准、步骤、文件)和开没开过屏都留着,
     * 后台按它自己任务的状态接着更新步骤,切回来原样接上。任务结束时丢掉(那一轮由服务存进对话)。
     */
    private class Parked(val exchange: Exchange, val screenSeen: Boolean, val pipClosed: Boolean = false)
    private val parked = mutableMapOf<String, Parked>()
    private val parkedJobs = mutableMapOf<String, Job>()

    /** 把屏上这个对话正在跟的那一问一答停到一边(换到别的对话之前调用)。 */
    private fun parkLive() {
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

    private fun unpark(id: String): Parked? {
        parkedJobs.remove(id)?.cancel()
        return parked.remove(id)
    }

    /** 开关在没有任务时重读,开始任务、换对话时也重读([refreshParallel]):这里在跟的任务中途改开关不改这一轮的行为。 */
    private var parallelOn = parallelFlag()
    private fun parallel(): Boolean {
        if (liveIndex < 0 && activeConversations.value.isEmpty()) parallelOn = parallelFlag()
        return parallelOn
    }

    /**
     * 开始任务、换对话时重读开关(这个界面没在跟任务时):设置里刚改的开关不用重建界面就生效,
     * 和服务每次开始任务时重读一致。别的对话的任务照旧跑,它们在服务里按各自开始时的开关。
     */
    private fun refreshParallel() {
        if (liveIndex < 0) parallelOn = parallelFlag()
    }

    /** 正在跟踪的任务在 exchanges 里的位置;-1 表示没有。 */
    private var liveIndex = -1
    private var lingerJob: Job? = null
    /** 排在服务收尾后面、还没交给服务的开始;在这期间点停止就直接作废它。 */
    private var pendingStart: Job? = null
    /** 正在跟踪的任务开过屏;一旦开过,到任务结束(含完成后停留的那几秒)都显示画中画。 */
    private var screenSeen = false
    /** 用户把正在跟踪的这个任务的小画面关掉了(换任务时清掉,见 [PipUi.closed])。 */
    private var pipClosed = false

    init {
        // 开关关着时也按状态带的对话认:别的对话的任务(悬浮胶囊、定时任务开始的)不接到屏上这个对话里
        scope.launch { taskState.collect { s -> if (!parallel()) shownTask.value?.let { onShown(it.first, s) } ?: onState(s) } }
        scope.launch { shownTask.collect { t -> if (t != null && parallel()) onShown(t.first, t.second) } }
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
                if (id == _ui.value.conversationId) syncSaved(id)
                // 删掉的对话又有人往里存了:重新列出来
                if (id in _deleted.value && id !in deleting && store.turns(id).isNotEmpty()) _deleted.update { it - id }
            }
        }
        // 并行时只收屏上这个对话的任务的接管进度
        scope.launch {
            takeoverStage.collect { s -> if (ownTask()) _ui.update { it.copy(takeoverStage = s) } }
        }
        // 只认任务进行中新列出的标准:订阅时的旧值可能是上一个任务的
        // 有没有屏和状态一样是每个任务各自的:并行时只收屏上这个对话的任务的
        scope.launch { screenOpen.collect { if (it && ownTask()) onScreenOpened() } }
        scope.launch { sandboxReady.collect { r -> _ui.update { it.copy(attachEnabled = r) } } }
        scope.launch { sharedFiles.collect { if (ownTask()) onSharedFiles(it) } }
        scope.launch { autoRead.collect { if (!it) autoReadKey = null } }
        scope.launch { generatedAudios.collect { if (ownTask()) onAudios(it) } }
        scope.launch { generatedPhotos.collect { if (ownTask()) onPhotos(it) } }
        scope.launch { runNotes.collect { notes -> _ui.update { ui -> ui.copy(exchanges = ui.exchanges.map { withNotes(it, notes) }) } } }
        scope.launch { activities.collect { if (ownTask()) onActivities(it) } }
        scope.launch { steers.collect { if (ownTask()) onSteers(it) } }
        // 任务登记进任务列表(原话可以取了)可能晚于它的第一个状态:那时接上的那一问一答补上原话
        scope.launch { activeConversations.collect { fillGoal() } }
        scope.launch { summarizing.collect { set -> _ui.update { ui -> ui.copy(exchanges = ui.exchanges.map { withSummarizing(it, set) }) } } }
        // 分享图片有私有副本,可以恢复;只把当前对话那份放到输入框,其余留在各自草稿里。
        sharedDrafts?.readAll()?.forEach { draft ->
            drafts.update { it + (draft.conversationId to ComposerDraft(input = draft.text,
                attachments = sharedAttachments(draft), sharedDraft = draft)) }
        }
        val current = store.currentChat() as? CurrentChat.Open
        val pending = sharedDrafts?.readPending()?.takeIf { current != null && it.conversationId == current.id }
        if (current != null && (current.id in drafts.value || pending != null)) {
            _ui.value = restoreDraft(_ui.value.rebuildForConversation(conversationId = current.id, exchanges = saved(current.id)))
                .copy(pendingSharedDraft = pending)
        }
    }

    private fun sharedAttachments(draft: SharedDraft) = listOfNotNull(draft.image?.let { image ->
        runCatching { sharedDraftUri(image.fileName) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { PendingAttachment(it, image.name, image.size, photo = true) }
    })

    /** 停止的那一轮:交代还在写时标上「正在整理」;写好了、写不成就去掉。 */
    private fun withSummarizing(ex: Exchange, set: Set<String> = summarizing.value): Exchange {
        val st = ex.status as? ExchangeStatus.Cancelled ?: return ex
        val on = st.summary == null && ex.runId != null && ex.runId in set
        return if (st.summarizing == on) ex else ex.copy(status = st.copy(summarizing = on))
    }

    private fun onActivities(list: List<RunEvent.Activity>) {
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
    private fun onSteers(list: List<SteerNote>) {
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

    /** 服务对外的那几项(接管进度、有没有屏、导出的文件)此刻是不是屏上这个对话的任务的:并行时看显示中的是谁。 */
    private fun ownTask() = !parallel() || shownTask.value?.first == _ui.value.conversationId

    private fun withNotes(ex: Exchange, notes: Map<String, RunNotes>): Exchange {
        val n = ex.runId?.let(notes::get) ?: return ex
        return ex.copy(memoryNotes = n.memoryNotes, usedMemory = n.usedMemory, skillNotes = n.skillNotes)
    }

    /** 提示这一行的文字,内容从此刻的记忆里取;那条已经删掉时写「一条已删除的记忆」。 */
    fun noteText(note: MemoryNote): String = note.text(memoryItems.value.find { it.id == note.memoryId })

    /** 撤销这条记忆改动;令牌已失效(重启过)时只把「撤销」去掉。 */
    fun undoNote(runId: String, note: MemoryNote) {
        val token = note.undoToken ?: return
        scope.launch {
            val ok = learning?.undo(token) ?: false
            editNotes(runId) { ex -> ex.copy(memoryNotes = if (ok) ex.memoryNotes - note else ex.memoryNotes.map { if (it == note) it.copy(undoToken = null) else it }) }
        }
    }

    /** 做法:「好」批准。 */
    fun approveNote(runId: String, note: MemoryNote) {
        val id = note.pendingId ?: return
        scope.launch {
            learning?.approve(id)
            editNotes(runId) { ex -> ex.copy(memoryNotes = ex.memoryNotes - note) }
        }
    }

    /** 做法:「不要」撤销这次新增;令牌没了就直接删掉那条。 */
    fun rejectNote(runId: String, note: MemoryNote) {
        scope.launch {
            val undone = note.undoToken?.let { learning?.undo(it) } ?: false
            if (!undone) note.pendingId?.let { learning?.forget(it) }
            editNotes(runId) { ex -> ex.copy(memoryNotes = ex.memoryNotes - note) }
        }
    }

    /** 「用到 n 条记忆」点开时的内容;已删掉的不列。 */
    fun usedMemoryTexts(ids: List<String>): List<String> {
        val byId = memoryItems.value.associateBy { it.id }
        return ids.mapNotNull { byId[it]?.content }
    }

    fun viewSkill(note: SkillNote) { note.draftId?.let { learning?.viewSkill(it) } }

    fun restoreSkill(runId: String, note: SkillNote) {
        val name = note.suspended ?: return
        scope.launch {
            learning?.restoreSkill(name)
            editNotes(runId) { ex -> ex.copy(skillNotes = ex.skillNotes - note) }
        }
    }

    fun deleteSkill(runId: String, note: SkillNote) {
        val name = note.suspended ?: return
        scope.launch {
            learning?.deleteSkill(name)
            editNotes(runId) { ex -> ex.copy(skillNotes = ex.skillNotes - note) }
        }
    }

    private suspend fun editNotes(runId: String, change: (Exchange) -> Exchange) {
        var saved: Exchange? = null
        _ui.update { ui ->
            ui.copy(exchanges = ui.exchanges.map { if (it.runId == runId) change(it).also { e -> saved = e } else it })
        }
        val ex = saved ?: return
        val conversation = _ui.value.conversationId ?: return
        learning?.save(RunNotes(conversation, runId, ex.memoryNotes, ex.usedMemory, ex.skillNotes))
    }

    private fun onAudios(audios: List<com.wangzan101.mika.core.chat.GeneratedAudio>) {
        if (liveIndex < 0) return
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].copy(audios = audios)
            ui.copy(exchanges = exchanges)
        }
    }

    private fun onSharedFiles(files: List<SharedFile>) {
        if (liveIndex < 0) return
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].copy(files = files)
            ui.copy(exchanges = exchanges)
        }
    }

    private fun onPhotos(photos: List<com.wangzan101.mika.core.chat.GeneratedPhoto>) {
        if (liveIndex < 0) return
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].copy(photos = photos)
            ui.copy(exchanges = exchanges)
        }
    }

    private fun canAttach(ui: ChatUi) = ui.attachEnabled && !ui.importing &&
        (ui.composer == ComposerMode.Idle || ui.followUpMode == FollowUpMode.Queue)

    /** 从相机、相册、文件选择器拿到的文件:没有任务在跑、沙箱就绪时才收;读出名字和大小后挂在输入框上方,随下一句话发出去。 */
    fun attach(uris: List<String>, conversationId: String? = _ui.value.conversationId) {
        if (uris.isEmpty() || conversationId != _ui.value.conversationId || !canAttach(_ui.value)) return
        val id = draftConversation()
        val sharedBefore = _ui.value.sharedDraft
        val serialBefore = sharedDraftSerial
        val wasIdle = _ui.value.composer == ComposerMode.Idle
        scope.launch {
            val items = try { describeAttachments(uris) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            if (wasIdle && _ui.value.composer != ComposerMode.Idle) return@launch
            if (_ui.value.conversationId == id && (!canAttach(_ui.value) || sharedDraftSerial != serialBefore)) return@launch
            if (_ui.value.conversationId != id && drafts.value[id]?.sharedDraft != sharedBefore) return@launch
            updateDraft(id) { draft ->
                val (all, dropped) = AttachTray.add(draft.attachments, items)
                draft.copy(attachments = all, overLimit = dropped, rejected = emptyList())
            }
        }
    }

    /** Copy selected device files into the conversation that opened the browser. */
    internal fun importDeviceAttachments(conversationId: String, paths: List<String>,
        copy: suspend (String, String) -> com.wangzan101.mika.core.cap.devicefiles.DeviceFileImport) {
        if (_ui.value.conversationId != conversationId || !canAttach(_ui.value) || paths.isEmpty()) return
        val slots = (AttachTray.LIMIT - _ui.value.attachments.size).coerceAtLeast(0)
        val selected = paths.distinct().take(slots)
        val dropped = (paths.distinct().size - selected.size).coerceAtLeast(0)
        if (selected.isEmpty()) {
            updateDraft(conversationId) { it.copy(overLimit = dropped) }
            return
        }
        updateDraft(conversationId) { it.copy(importing = true, rejected = emptyList(), overLimit = dropped) }
        val job = scope.launch {
            val added = mutableListOf<PendingAttachment>()
            val failures = mutableListOf<AttachmentFailure>()
            try {
                for (path in selected) {
                    if (conversationId in _deleted.value || conversationId in deleting) break
                    try {
                        val result = copy(path, conversationId)
                        added += PendingAttachment("workspace:$conversationId:${result.path}", result.path.substringAfterLast('/'), result.size,
                            workspaceConversationId = conversationId, workspacePath = result.path)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        val reason = when ((e as? com.wangzan101.mika.core.workspace.SandboxError)?.code) {
                            "too_large" -> AttachmentFailureReason.TOO_LARGE
                            "low_device_space" -> AttachmentFailureReason.LOW_DEVICE_SPACE
                            "workspace_limit" -> AttachmentFailureReason.WORKSPACE_LIMIT
                            else -> AttachmentFailureReason.IO_ERROR
                        }
                        failures += AttachmentFailure(path.substringAfterLast('/'), reason)
                    }
                }
            } finally {
                updateDraft(conversationId) { draft ->
                    val (merged, overflow) = AttachTray.add(draft.attachments, added)
                    draft.copy(importing = false, attachments = merged, rejected = failures, overLimit = draft.overLimit + overflow)
                }
            }
        }
        deviceImports[conversationId] = job
        job.invokeOnCompletion { deviceImports.remove(conversationId, job) }
    }

    /** 为当前新会话分配草稿编号；调用方在主线程调用。 */
    fun libraryTargetConversation(): String = draftConversation()

    /** 目标草稿此刻能不能收文件库的文件；不能时返回 null。调用方在主线程调用。 */
    private fun libraryDraft(conversationId: String): ComposerDraft? {
        if (conversationId.isBlank() || conversationId in _deleted.value || conversationId in deleting || !sandboxReady.value)
            return null
        val current = _ui.value.takeIf { it.conversationId == conversationId }
        if (current != null && !canAttach(current)) return null
        val draft = current?.draft() ?: drafts.value[conversationId] ?: ComposerDraft()
        if (draft.importing || (current == null && conversationId in activeConversations.value)) return null
        return draft
    }

    /**
     * 批量加入前的规划:按传入顺序去掉目标草稿里已有的来源,再截到剩余槽位。
     * 不复制文件；合并时会按当时的目标状态再校验一次。调用方在主线程调用。
     */
    fun planLibraryAttachments(conversationId: String, sources: List<LibraryFileHandle>): LibraryAddPlan? {
        val draft = libraryDraft(conversationId) ?: return null
        var slots = (AttachTray.LIMIT - draft.attachments.size).coerceAtLeast(0)
        var duplicates = 0
        var overLimit = 0
        val accepted = mutableListOf<LibraryFileHandle>()
        val seen = mutableSetOf<LibraryFileHandle>()
        for (source in sources) {
            val duplicate = !seen.add(source) || draft.attachments.any { librarySources[it.uri] == source }
            when {
                duplicate -> duplicates++
                slots <= 0 -> overLimit++
                else -> {
                    accepted += source
                    slots--
                }
            }
        }
        return LibraryAddPlan(accepted, duplicates, overLimit)
    }

    /**
     * 把已经复制好的一批文件合并到目标草稿；只更新一次草稿。
     * 收下的副本登记在 [libraryCopies] 里，没收下的当场释放。调用方在主线程调用。
     */
    fun addLibraryAttachments(conversationId: String, items: List<LibraryStaged>, overLimit: Int = 0): LibraryMergeResult {
        val draft = libraryDraft(conversationId)
        if (draft == null) {
            items.forEach { runCatching { it.release() } }
            return LibraryMergeResult(0, 0, 0, unavailable = true)
        }
        var attachments = draft.attachments
        var added = 0
        var duplicates = 0
        var full = 0
        val accepted = mutableListOf<LibraryStaged>()
        for (item in items) {
            if (attachments.any { librarySources[it.uri] == item.source }) {
                duplicates++
                runCatching { item.release() }
                continue
            }
            val (merged, dropped) = AttachTray.add(attachments, listOf(item.attachment))
            if (dropped != 0) {
                full++
                runCatching { item.release() }
                continue
            }
            attachments = merged
            added++
            accepted += item
        }
        if (added > 0) {
            draftStateChanged = true
            accepted.forEach { item ->
                libraryCopies.putIfAbsent(item.attachment.uri, item.release)
                librarySources.putIfAbsent(item.attachment.uri, item.source)
            }
            updateDraft(conversationId) {
                it.copy(attachments = attachments, rejected = emptyList(), overLimit = overLimit + full)
            }
        }
        return LibraryMergeResult(added, duplicates, full, unavailable = false)
    }

    /** 合并文件库副本到指定会话的草稿，不切换会话或覆盖已有内容。调用方在主线程调用。 */
    fun addLibraryAttachment(conversationId: String, item: PendingAttachment, source: LibraryFileHandle? = null,
                             release: () -> Unit = {}): LibraryAttachmentResult {
        val draft = libraryDraft(conversationId) ?: return LibraryAttachmentResult.Unavailable
        val duplicate = source != null && draft.attachments.any { librarySources[it.uri] == source }
        if (duplicate) {
            val referenced = item.uri in libraryImports || item.uri in libraryCopies ||
                _ui.value.attachments.any { it.uri == item.uri } ||
                drafts.value.values.any { saved -> saved.attachments.any { it.uri == item.uri } }
            if (!referenced) runCatching { release() }
            return LibraryAttachmentResult.Added
        }
        val (items, dropped) = AttachTray.add(draft.attachments, listOf(item))
        if (dropped != 0) return LibraryAttachmentResult.Full
        draftStateChanged = true
        libraryCopies.putIfAbsent(item.uri, release)
        if (source != null) librarySources.putIfAbsent(item.uri, source)
        updateDraft(conversationId) { it.copy(attachments = items, rejected = emptyList(), overLimit = 0) }
        return LibraryAttachmentResult.Added
    }

    /** 面板里最近的照片点一下:选上或取消;已经满了返回 false。 */
    fun toggleAttachment(item: PendingAttachment): Boolean {
        val ui = _ui.value
        if (!canAttach(ui)) return true
        draftConversation()
        val t = AttachTray.toggle(ui.attachments, item)
        if (ui.attachments.any { it.uri == item.uri } && t.items.none { it.uri == item.uri }) removeAttachment(item.uri)
        else _ui.update { it.copy(attachments = t.items) }
        return !t.full
    }

    private fun clearSharedImage(draft: SharedDraft) {
        val currentText = _ui.value.input
        if (currentText.isBlank()) {
            sharedDrafts?.clear(draft.conversationId)
        } else {
            val updated = draft.copy(text = currentText, image = null)
            if (sharedDrafts?.save(updated) != true) sharedDrafts?.removeImage(draft.conversationId)
        }
        _ui.update { current ->
            if (current.sharedDraft == draft) {
                current.copy(sharedDraft = draft.copy(text = current.input, image = null).takeIf { it.text.isNotBlank() })
            } else current
        }
    }

    fun removeAttachment(uri: String) {
        val draft = _ui.value.sharedDraft
        if (draft?.image != null && runCatching { sharedDraftUri(draft.image.fileName) }.getOrNull() == uri) {
            clearSharedImage(draft)
        }
        _ui.update { ui -> ui.copy(attachments = ui.attachments.filterNot { it.uri == uri }) }
        releaseUnusedLibraryCopies()
    }

    fun dismissRejected() = _ui.update { it.copy(rejectedAttachments = emptyList(), overLimit = 0) }

    private fun onScreenOpened() {
        if (liveIndex < 0 || screenSeen) return
        screenSeen = true
        _ui.update { ui ->
            val live = ui.exchanges.getOrNull(liveIndex)
            if (live != null && live.status.active()) ui.copy(pip = gated(ui.pip.copy(visible = true))) else ui
        }
    }

    /**
     * 小画面什么时候给:屏得开过(其余字段照算,开屏那一刻直接用)。用户关掉了这个任务的小画面时只留小按钮,
     * 也不再自动展开(接管开始时同样不弹开,要你处理的事在对话卡片里,小按钮变橙色)。
     */
    private fun gated(p: PipUi) = when {
        !screenSeen -> p.copy(visible = false, expanded = false, closed = false)
        pipClosed -> p.copy(expanded = false, closed = true)
        else -> p.copy(closed = false)
    }


    /** 长按消息选了「引用」:引的内容放到输入框上方,随下一句话发出去(新任务、插话、回答都带)。空的不引。 */
    fun quote(from: QuoteMessage.From, text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) {
            draftConversation()
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
        refreshParallel()
        val ui = _ui.value
        if (ui.importing) return false
        val queuedAttachments = ui.composer != ComposerMode.Idle && ui.followUpMode == FollowUpMode.Queue
        val sendingAttachments = ui.attachments.isNotEmpty() && (ui.composer == ComposerMode.Idle || queuedAttachments)
        val sent = sendQuoted(text.trim(), ui, check)
        if (sent && !sendingAttachments) {
            val shared = ui.sharedDraft
            if (shared != null) {
                if (shared.image == null) {
                    discardSharedDraft(shared)
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
        val id = draftConversation()
        val submitted = _ui.value.draft()
        val serialBefore = sharedDraftSerial
        val importingImage = submitted.sharedDraft?.image?.fileName
        sharedDrafts?.setImporting(importingImage, true)
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
                sharedDrafts?.setImporting(importingImage, false)
                importedUris.forEach { uri ->
                    val count = (libraryImports[uri] ?: 1) - 1
                    if (count == 0) libraryImports.remove(uri) else libraryImports[uri] = count
                }
                releaseUnusedLibraryCopies()
            }
            updateDraft(id) { it.copy(importing = false, rejected = r.failures) }
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
                if (!draftReplaced) discardSharedDraft()
                _ui.update {
                    it.copy(
                        input = if (!draftReplaced && it.input == submitted.input) "" else it.input,
                        quote = if (!draftReplaced && it.quote == submitted.quote) null else it.quote,
                        attachments = if (keepNewerAttachments) currentAttachments else emptyList(),
                        followUpMode = if (keepNewerAttachments && currentAttachments.isNotEmpty() && it.composer != ComposerMode.Idle) FollowUpMode.Queue else it.followUpMode,
                    )
                }
                releaseUnusedLibraryCopies()
            }
        }
        return true
    }

    /** 在对话 [id] 里开始新任务 [goal]:界面上加一问一答,交给服务(并行关着、服务还忙时排在它收尾后面)。 */
    private fun begin(goal: String, id: String, clearDraftAttachments: Boolean = true) {
        lingerJob?.cancel()
        // 真正开始任务后,草稿图片已在工作区(纯文字不需要图片),可以清掉私有暂存副本。
        if (clearDraftAttachments) discardSharedDraft()
        val boundProject = (_ui.value.projectId ?: store.projectId(id))?.takeIf { projectStore == null || projectStore.get(it) != null }
        if (boundProject != null && store.projectId(id) != boundProject) {
            runCatching { store.setProject(id, boundProject) }
        }
        workspaceResolver?.setDraftProject(id, null)
        // 这个对话的编号定下来了:胶囊之后接着它(新开的对话到这一刻才有编号)
        markCurrent(CurrentChat.Open(id))
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
                pip = gated(PipUi(visible = true)),
                composer = ComposerMode.Busy,
                followUpMode = FollowUpMode.Queue,
                // 这一句如果本来在排队条上乐观挂着、现在已经开始,就不能再显示一行
                queued = it.queued.filterNot { q -> q.text == goal },
            )
        }
        // 并行时服务忙着(别的对话的任务在跑)也直接交给它
        if (serviceBusy.value && !parallel()) {
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
            _ui.value.sharedDraft?.takeIf { it.image != null }?.let(::clearSharedImage)
        }
        _ui.update {
            if (it.followUpMode == mode) it else it.copy(followUpMode = mode,
                attachments = if (mode == FollowUpMode.Steer) emptyList() else it.attachments)
        }
        releaseUnusedLibraryCopies()
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

    /** 原话的任务还没交给服务时插的话。 */
    private val held = mutableListOf<SteerNote>()

    /**
     * 只收屏上这个对话的任务的状态;界面刚起来还没有对话时接上正在跑的那个(连同那个对话存下的轮次)。
     * 服务换去显示别的对话的任务(别处刚开始了一个)时,这里正在跟的任务还在跑就让服务显示回来,不让它停在半路。
     */
    private fun onShown(conversationId: String, s: TaskState) {
        // 删掉的对话的任务正在停下:不接到屏上
        if (conversationId in _deleted.value) return
        val current = _ui.value.conversationId
        when {
            current == null -> {
                if (!s.active()) return
                _ui.update {
                    it.copy(
                        conversationId = conversationId,
                        exchanges = saved(conversationId),
                        projectId = projectFor(conversationId),
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
     * 切到某个对话(并行时任务在跑也能切):载入它的历史;它有任务在跑就让服务显示那个任务,并接上它此刻的状态。
     * 开关关着时同 [openConversation]。
     */
    fun switchTo(conversationId: String) {
        refreshParallel()
        if (!parallel()) return openConversation(conversationId)
        if (conversationId == _ui.value.conversationId) return
        cancelPendingSharedDraft()
        keepDraft()
        lingerJob?.cancel()
        if (conversationId != _ui.value.conversationId) parkLive()
        val past = saved(conversationId)
        liveIndex = -1
        // 没有任务的对话不带别的任务的接管进度;输入内容只恢复目标对话自己的草稿。
        _ui.update { restoreDraft(it.rebuildForConversation(conversationId = conversationId, exchanges = past, projectId = projectFor(conversationId))) }
        markCurrent(CurrentChat.Open(conversationId))
        if (conversationId !in activeConversations.value) { unpark(conversationId); actions.drainFollowUps(conversationId); return }
        follow(conversationId, unpark(conversationId))
    }

    /** 打开个人助手或工作流拥有的对话:只切换展示,不启动另一项工作,并保留用户草稿。 */
    fun openWorkflowConversation(conversationId: String) {
        if (conversationId == _ui.value.conversationId) { refresh(); return }
        refreshParallel()
        cancelPendingSharedDraft()
        keepDraft()
        lingerJob?.cancel()
        parkLive()
        val past = saved(conversationId)
        liveIndex = -1
        _ui.update { restoreDraft(it.rebuildForConversation(conversationId = conversationId, exchanges = past, projectId = projectFor(conversationId))) }
        markCurrent(CurrentChat.Open(conversationId))
        if (conversationId !in activeConversations.value) { unpark(conversationId); return }
        follow(conversationId, unpark(conversationId))
    }

    fun openPersonalTask(conversationId: String) {
        openWorkflowConversation(conversationId)
    }

    /**
     * 接上屏上这个对话正在跑的任务(不论从哪里开始的:这里、悬浮胶囊、定时任务)。没在跟时先摆好那一问一答
     * (切走时停着的 [p],或按任务的原话新起一条),再让服务显示它,并按服务此刻对外的各项补齐。
     */
    private fun follow(conversationId: String, p: Parked? = null) {
        val fresh = liveIndex < 0
        if (fresh) {
            // 先把这个对话的那一问一答摆好,再让服务显示它:服务一换显示,状态可能当场就送进来(onShown),
            // 那时这一问一答必须已经在,不然会另起一条没有原话的
            val live = p?.exchange ?: Exchange(runGoal(conversationId).orEmpty(), emptyList(), ExchangeStatus.Working, live = true, startedAt = clock())
            screenSeen = p?.screenSeen ?: false
            pipClosed = p?.pipClosed ?: false
            _ui.update {
                liveIndex = it.exchanges.size
                it.copy(exchanges = it.exchanges + live, composer = ComposerMode.Busy, pip = gated(PipUi(visible = true)),
                    followUpMode = when {
                        it.attachments.isNotEmpty() -> FollowUpMode.Queue
                        it.draft().hasContent -> it.followUpMode
                        else -> FollowUpMode.Queue
                    },
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
        refreshParallel()
        val id = _ui.value.conversationId ?: return
        syncSaved(id)
        if (id !in activeConversations.value) { actions.drainFollowUps(id); return }
        if (pendingStart?.isActive == true) return
        // 开关关着、这里没在跟任务时换对话要等任务结束:那时屏上这个对话的任务就是服务里唯一在跑的
        follow(id, if (parallel()) unpark(id) else null)
    }

    /** 这个对话存下的轮次,连同各轮导出的文件和此刻的记忆与技能提示。 */
    private fun saved(conversationId: String): List<Exchange> =
        runCatching { store.records(conversationId) }.getOrDefault(emptyList()).map { (t, files) -> withSummarizing(withNotes(fromTurn(t).copy(files = files), runNotes.value)) }

    /**
     * 存储里这个对话变了(别处跑完存进一轮、改了某一轮):屏上按存下的那份重排。这里跟过的那几轮按运行编号认
     * (没有编号的按原话),用这里的那份(带步骤和用时),不重复;还没存进去的接在后面;正在跟的那一问一答留在最后。
     */
    private fun syncSaved(conversationId: String) {
        val stored = saved(conversationId)
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
        if (!parallel()) return serial()
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
        _ui.update { it.copy(pip = gated(it.pip)) }
    }

    /** 点小按钮:把这个任务的小画面再打开。 */
    fun reopenPip() {
        pipClosed = false
        _ui.update { it.copy(pip = gated(it.pip)) }
    }

    /** 浮层的声音开关:只发真实音频动作,方向由任务里的真实记录定,不在界面伪造开关状态。 */
    fun togglePipSound() {
        val id = _ui.value.conversationId ?: return
        actions.togglePipSoundIn(id)
    }

    /** 分享切走前,把仍在等串行服务空闲的原任务交给服务排队;只取消界面里的等待协程,不取消任务。 */
    private fun handOffPendingStart() {
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

    /**
     * 系统分享进入对话草稿:只预填输入框/附件,分享内容本身绝不启动任务。
     * - [inCurrentConversation] 为 false(界面未在后台存活时的冷启动分享):进入全新的对话草稿,旧对话正在跑的任务原地保留。
     * - [inCurrentConversation] 为 true(界面已在后台存活):放入当前已打开的会话;若当前会话已有草稿则挂入 [ChatUi.pendingSharedDraft],
     *   由界面弹窗让用户选择「替换」或「取消」。
     */
    fun openSharedDraft(prepared: SharedDraft, inCurrentConversation: Boolean = false): Boolean {
        val drafts = sharedDrafts ?: return false
        refreshParallel()
        if (inCurrentConversation) {
            val hasUnsentDraft = !_ui.value.importing && _ui.value.draft().hasContent
            if (!hasUnsentDraft) return applySharedDraftToCurrent(prepared)
            val id = draftConversation()
            val pending = prepared.copy(conversationId = id)
            // Replacing the pending draft and cleaning up its old image happen after the new draft is saved.
            if ((pending.image != null && sharedAttachments(pending).size != 1) || !drafts.savePending(pending)) {
                drafts.discard(pending.image)
                return false
            }
            _ui.update { it.copy(pendingSharedDraft = pending) }
            return true
        }
        if (pendingStart?.isActive == true &&
            (_ui.value.conversationId == null || _ui.value.exchanges.getOrNull(liveIndex) == null)
        ) {
            drafts.discard(prepared.image)
            return false
        }
        val id = store.newConversation()
        val (draft, attachments) = persistSharedDraft(id, prepared) ?: return false
        cancelPendingSharedDraft()
        keepDraft()
        val previous = _ui.value
        if (previous.conversationId != id) parkLive()
        // 原任务还在等服务收尾时,直接按服务既有的排队能力交过去;它的开始和插话不能跟着新草稿走。
        handOffPendingStart()
        lingerJob?.cancel()
        liveIndex = -1
        screenSeen = false
        pipClosed = false
        _ui.update {
            it.rebuildForConversation(
                conversationId = id,
                sharedDraft = draft,
                input = draft.text,
                attachments = attachments,
            )
        }
        markCurrent(CurrentChat.Open(id))
        return true
    }

    /** 用户在替换草稿弹窗里点了「替换」:用待确认的分享内容覆盖当前会话草稿。 */
    fun confirmPendingSharedDraft(): Boolean {
        val pending = _ui.value.pendingSharedDraft ?: return false
        _ui.update { it.copy(pendingSharedDraft = null) }
        sharedDrafts?.clearPending(deleteImage = false)
        return applySharedDraftToCurrent(pending)
    }

    /** 用户在替换草稿弹窗里点了「取消」或关闭弹窗:保留原草稿,清掉这次分享的临时图片副本。 */
    fun cancelPendingSharedDraft() {
        val pending = _ui.value.pendingSharedDraft
        if (pending != null) {
            _ui.update { if (it.pendingSharedDraft == pending) it.copy(pendingSharedDraft = null) else it }
        }
        sharedDrafts?.clearPending(deleteImage = true)
        if (pending != null) sharedDrafts?.discard(pending.image)
    }

    private fun applySharedDraftToCurrent(prepared: SharedDraft): Boolean {
        val id = _ui.value.conversationId ?: store.newConversation()
        val (draft, attachments) = persistSharedDraft(id, prepared) ?: return false
        cancelPendingSharedDraft()
        draftStateChanged = true
        sharedDraftSerial++
        _ui.update {
            it.copy(
                conversationId = id,
                input = draft.text,
                attachments = attachments,
                sharedDraft = draft,
                quote = null,
                rejectedAttachments = emptyList(),
                overLimit = 0,
                followUpMode = if (draft.image != null && it.composer != ComposerMode.Idle) FollowUpMode.Queue else it.followUpMode,
                pendingSharedDraft = null,
            )
        }
        releaseUnusedLibraryCopies()
        markCurrent(CurrentChat.Open(id))
        return true
    }

    private fun persistSharedDraft(id: String, prepared: SharedDraft): Pair<SharedDraft, List<PendingAttachment>>? {
        val drafts = sharedDrafts ?: return null
        val draft = prepared.copy(conversationId = id)
        val attachments = sharedAttachments(draft)
        if ((draft.image != null && attachments.size != 1) || !drafts.save(draft)) {
            drafts.discard(draft.image)
            return null
        }
        return draft to attachments
    }

    private fun discardSharedDraft(target: SharedDraft? = _ui.value.sharedDraft) {
        val draft = target ?: return
        val isCurrent = _ui.value.sharedDraft == draft || drafts.value[draft.conversationId]?.sharedDraft == draft
        if (!isCurrent) return
        val imageUri = draft.image?.let { image -> runCatching { sharedDraftUri(image.fileName) }.getOrNull() }
        sharedDrafts?.clear(draft.conversationId)
        _ui.update { current ->
            if (current.sharedDraft == draft) current.copy(
                sharedDraft = null,
                // 私有分享图片随草稿一起删掉;普通待发附件仍属于全局输入框。
                attachments = current.attachments.filterNot { it.uri == imageUri },
            ) else current
        }
        drafts.update { saved ->
            val existing = saved[draft.conversationId]
            if (existing?.sharedDraft != draft) saved
            else {
                val next = existing.copy(sharedDraft = null, attachments = existing.attachments.filterNot { it.uri == imageUri })
                if (next.hasContent) saved + (draft.conversationId to next) else saved - draft.conversationId
            }
        }
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
    /** 这个对话存下的项目 id;项目不存在时回落为 null。 */
    private fun projectFor(conversationId: String?): String? =
        conversationId?.let { store.projectId(it) }?.takeIf { projectStore == null || projectStore.get(it) != null }

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
            if (id == _ui.value.conversationId) startOver(id) else unpark(id)
        }
        if (_ui.value.conversationId == null && _ui.value.projectId == projectId) {
            _ui.update { it.copy(projectId = null) }
        }
        releaseUnusedLibraryCopies()
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
    fun openConversation(id: String) {
        if (_ui.value.conversationId == id) return
        refreshParallel()
        if (parallel()) return switchTo(id)
        if (busy()) return
        cancelPendingSharedDraft()
        keepDraft()
        val past = saved(id)
        liveIndex = -1
        _ui.update {
            restoreDraft(it.rebuildForConversation(conversationId = id, exchanges = past, takeoverStage = it.takeoverStage, projectId = projectFor(id)))
        }
        markCurrent(CurrentChat.Open(id))
        // 这个对话的任务是别处开始的(悬浮胶囊、定时任务):打开就接上它
        if (id in activeConversations.value) follow(id) else {
            unpark(id)
            actions.drainFollowUps(id)
        }
    }

    /**
     * 告诉悬浮胶囊用户此刻在哪个对话([CurrentChat]):胶囊的空闲卡片接着它,而不是按对话文件最后被谁写过猜一个。
     * 写不进去不影响界面。
     */
    private fun markCurrent(current: CurrentChat) {
        runCatching { store.setCurrent(current) }
    }

    fun newConversation(projectId: String? = null) {
        val validProject = projectId?.takeIf { projectStore == null || projectStore.get(it) != null }
        refreshParallel()
        if (parallel()) {
            cancelPendingSharedDraft()
            keepDraft()
            // 别的任务可能还在跑:先给新对话编号,它的状态就不会被当成这个对话的
            lingerJob?.cancel()
            parkLive()
            liveIndex = -1
            val fresh = store.newConversation()
            workspaceResolver?.setDraftProject(fresh, validProject)
            _ui.update { it.rebuildForConversation(conversationId = fresh, projectId = validProject) }
            // 还没说过话:胶囊上是一张新对话卡片(编号等说出来再定,见 begin)
            markCurrent(CurrentChat.Fresh)
            return
        }
        if (busy()) return
        cancelPendingSharedDraft()
        keepDraft()
        liveIndex = -1
        _ui.update { it.rebuildForConversation(conversationId = null, takeoverStage = it.takeoverStage, projectId = validProject) }
        markCurrent(CurrentChat.Fresh)
    }

    /** 屏上这个对话此刻有没有任务在跑或排队。 */
    fun currentRunning(): Boolean = _ui.value.conversationId?.let { it in activeConversations.value } == true

    /** 屏上这个对话此刻分不了支:还没存过、有任务在跑或排队、已删或正在删、输入框正忙。 */
    private fun branchBlocked(): Boolean {
        val id = _ui.value.conversationId ?: return true
        return id in activeConversations.value || id in deleted.value || id in deleting || busy()
    }

    /** 长按的消息 [key] 能「从这里分支」时返回轮次;菜单据此显示,[branchFrom] 用的是同一个判断。 */
    fun branchTurn(key: String): Int? = branchableTurn(_ui.value.exchanges, key, branchBlocked())

    /** 长按的消息 [key] 能「从这里重新问」时返回轮次;菜单据此显示,[branchBefore] 用的是同一个判断。 */
    fun retryTurn(key: String): Int? = retryableTurn(_ui.value.exchanges, key, branchBlocked())

    /** 清掉上次分支复制工作区的提示。 */
    fun clearBranchCopy() {
        _branchCopy.value = null
    }

    /**
     * 从当前对话的第 [throughTurn] 轮(0 起,含这一轮)分支:复制轮次和标题/网页来源,再复制工作区 inbox/,成功后切到新对话。
     * 返回新对话编号;[canBranchAt] 不成立(有任务在跑或排队、正在删、输入框正忙、轮次越界、这一轮还没结束)或复制失败时为 null。
     * 工作区复制的结果(超过上限等)放在 [branchCopy] 里,不当作失败。
     */
    suspend fun branchFrom(throughTurn: Int): String? =
        branchInto(throughTurn, afterTurn = true, allowed = ::canBranchAt)

    /**
     * 从当前对话的第 [turn] 条用户消息「重新问」:复制这一轮之前的轮次,返回新对话编号和要预填的文字,不自动发送。
     * 原消息的附件/屏幕不会带进输入框,用 [BranchRetry.needsReattach] 告诉界面提示重新添加。
     * [canRetryAt] 不成立(同 [branchFrom],另加这一轮用户的话是空的)或复制失败时为 null。
     */
    suspend fun branchBefore(turn: Int): BranchRetry? {
        val exchanges = _ui.value.exchanges
        val raw = exchanges.getOrNull(turn)?.goal.orEmpty()
        fun attachmentPaths(text: String) = AttachmentLines.parse(QuoteMessage.words(AttachedPage.words(text))).files.map { it.first }
        val paths = attachmentPaths(raw).distinct()
        val historyPaths = exchanges.take(turn.coerceAtLeast(0)).flatMap { attachmentPaths(it.goal) }.filterNot { it in paths }.distinct()
        var copied = emptyList<RetryAttachmentResult>()
        val targetId = branchInto(turn, afterTurn = false, allowed = ::canRetryAt, retryPaths = paths + historyPaths) { copied = it } ?: return null
        val restored = copied.take(paths.size).filter { it.status == RetryAttachmentStatus.COPIED && it.sizeBytes != null }
        val attachments = restored.map {
            PendingAttachment("workspace:$targetId:${it.path}", it.path.substringAfterLast('/'), it.sizeBytes!!,
                workspaceConversationId = targetId, workspacePath = it.path)
        }
        val (accepted, _) = AttachTray.add(emptyList(), attachments)
        val failures = copied.filter { it.status != RetryAttachmentStatus.COPIED || it.sizeBytes == null } +
            restored.drop(accepted.size).map { it.copy(status = RetryAttachmentStatus.OVER_LIMIT) }
        val prefill = retryText(raw)
        updateDraft(targetId) {
            it.copy(input = prefill, attachments = accepted,
                rejected = if (_ui.value.conversationId == targetId) emptyList() else failures.map { failure ->
                    AttachmentFailure(failure.path.substringAfterLast('/'), AttachmentFailureReason.IO_ERROR)
                })
        }
        val screen = UserScreenMessage.parse(AttachmentMessage.parse(QuoteMessage.words(AttachedPage.words(raw))).words).hasScreen
        return BranchRetry(targetId, prefill, screen || failures.isNotEmpty(), failures, screenNeedsReattach = screen)
    }

    /**
     * [branchFrom] 和 [branchBefore] 共用:[allowed] 成立时新建对话,复制到第 [turn] 轮([afterTurn] 时含这一轮,否则只到它之前)、
     * 标题加后缀、写分支来源,再复制工作区 inbox/,然后切到新对话。返回新对话编号;不允许或复制失败为 null,不切走。
     */
    private suspend fun branchInto(
        turn: Int, afterTurn: Boolean, allowed: (List<Exchange>, Int, Boolean) -> Boolean,
        retryPaths: List<String>? = null, onRetryCopied: (List<RetryAttachmentResult>) -> Unit = {},
    ): String? {
        refreshParallel()
        _branchCopy.value = null
        val ui = _ui.value
        val sourceId = ui.conversationId ?: return null
        if (!allowed(ui.exchanges, turn, branchBlocked())) return null
        val targetId = store.newConversation()
        val base = withContext(io) { store.title(sourceId) }?.takeIf { it.isNotBlank() }
            ?: ChatCopy.title(ui.exchanges).ifBlank { tr(R.string.chat_new) }
        val origin = BranchOrigin(sourceId, turn, afterTurn, at = clock())
        val created = withContext(io) {
            if (afterTurn) store.branch(sourceId, targetId, turn, branchTitle(base), origin)
            else store.branchBefore(sourceId, targetId, turn, branchTitle(base), origin)
        }
        if (!created) return null
        if (!retryPaths.isNullOrEmpty()) {
            val failed = retryPaths.map { RetryAttachmentResult(it, RetryAttachmentStatus.FAILED) }
            val results = try {
                branchWorkspace?.copyInboxForRetry(sourceId, targetId, retryPaths) ?: failed
            } catch (e: CancellationException) { throw e } catch (e: Exception) { failed }
            onRetryCopied(if (results.map { it.path } == retryPaths) results else failed)
        } else {
            _branchCopy.value = try { branchWorkspace?.copyInbox(sourceId, targetId) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        }
        if (_ui.value.conversationId == sourceId && !branchBlocked()) openConversation(targetId)
        return targetId
    }

    /** 删掉 [id] 会连带什么:侧栏的确认弹窗据此写明会停掉任务、删掉定时任务。 */
    fun deleteImpact(id: String): DeleteImpact = deletionImpact(id)

    /**
     * 删掉对话 [id](用户在侧栏确认过):当场从侧栏拿掉;是屏上这个对话就换成一个新的空对话。
     * 停任务、删定时任务、删文件在后台做(见 `ConversationDeleter`)。
     */
    fun deleteConversation(id: String) {
        deleteConversations(listOf(id), report = false)
    }

    /** 一次批量删除的进度和结果;侧栏在底部显示,提示完调 [clearDeleteBatch]。 */
    private val _deleteBatch = MutableStateFlow<DeleteBatch?>(null)
    val deleteBatch: StateFlow<DeleteBatch?> = _deleteBatch.asStateFlow()

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
            if (id == _ui.value.conversationId) startOver(id) else unpark(id)
        }
        releaseUnusedLibraryCopies()
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
            refreshParallel()
            if (busy()) return false
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
        cancelPendingSharedDraft()
        discardSharedDraft()
        lingerJob?.cancel()
        pendingStart?.cancel()
        pendingStart = null
        held.clear()
        unpark(id)
        liveIndex = -1
        screenSeen = false
        pipClosed = false
        val fresh = if (parallel()) store.newConversation() else null
        _ui.update { it.rebuildForConversation(conversationId = fresh) }
        // 屏上换成了一个新的空对话:胶囊不再接着删掉的那个
        markCurrent(CurrentChat.Fresh)
    }

    fun busy() = _ui.value.composer != ComposerMode.Idle

    /** 正在操作的 App 或网站(最近一件有对象的事);顶栏副标题「正在操作 …」用。没有时为 null。 */
    fun livePlace(ex: Exchange): String? =
        ex.activities.lastOrNull { !it.target.isNullOrBlank() }?.target?.let { where(it, appLabel) }

    /** 该对话正在跑的任务的原话;侧栏「进行中」里还没存进对话列表的那条用它当标题。 */
    fun runningGoal(conversationId: String): String? = runGoal(conversationId)

    /** 能不能换到别的对话:并行时随时能换,否则要等这个对话的任务结束。 */
    fun canSwitch() = parallel() || !busy()

    private fun onState(s: TaskState) {
        if (liveIndex < 0) {
            // 任务不是从这里开始的(比如界面重建前就在跑):接着显示,只是没有用户那句话
            if (!s.active()) return
            screenSeen = screenOpen.value
            pipClosed = false
            _ui.update {
                liveIndex = it.exchanges.size
                // 原话、文件按对话取:这条状态属于屏上这个对话的任务
                val goal = it.conversationId?.let(runGoal).orEmpty()
                val files = if (ownTask()) sharedFiles.value else emptyList()
                val audios = if (ownTask()) generatedAudios.value else emptyList()
                val photos = if (ownTask()) generatedPhotos.value else emptyList()
                val ex = Exchange(goal, emptyList(), ExchangeStatus.Working, live = true, files = files, audios = audios, photos = photos, startedAt = clock())
                it.copy(exchanges = it.exchanges + ex,
                    followUpMode = when {
                        it.attachments.isNotEmpty() -> FollowUpMode.Queue
                        it.draft().hasContent -> it.followUpMode
                        else -> FollowUpMode.Queue
                    },
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
                else withSummarizing(withNotes(it.copy(runId = endedRunFor(ui.conversationId), elapsedMs = elapsedOf(old),
                    steers = it.steers.map { n -> if (n.state.unconsumed) n.copy(state = SteerState.Next) else n }), runNotes.value))
            }
            val exchanges = ui.exchanges.toMutableList().also { it[liveIndex] = ex }
            ui.copy(
                exchanges = exchanges,
                pip = gated(pipFor(ui.pip, old.status, ex)),
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
                    delay(DONE_LINGER_MS)
                    _ui.update { it.copy(pip = it.pip.copy(visible = false, expanded = false)) }
                }
            }
        }
    }

    /** 正在跟的那一问一答还没有原话(接上别处开始的任务时它还没登记):按屏上这个对话的任务补上。 */
    private fun fillGoal() = _ui.update { ui ->
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

    companion object {
        const val DONE_LINGER_MS = 3_000L


        private fun TaskState.active() = when (this) {
            TaskState.Idle, is TaskState.Succeeded, is TaskState.Failed, TaskState.Cancelled -> false
            else -> true
        }

        private fun ExchangeStatus.active() = when (this) {
            is ExchangeStatus.Done, is ExchangeStatus.Failed, is ExchangeStatus.Cancelled -> false
            else -> true
        }

        private fun ExchangeStatus.needsUser() =
            this is ExchangeStatus.NeedsTakeover || this is ExchangeStatus.NeedsInput || this is ExchangeStatus.Paused ||
                this is ExchangeStatus.NeedsConfirmation

        internal fun reduce(ex: Exchange, s: TaskState, appLabel: (String) -> String = { it }, toolLabel: (String) -> String = { it }): Exchange =
            state(ex, s, appLabel).let {
                it.copy(steps = rows(it, toolLabel, appLabel), stepActions = actionsOf(it.activities, appLabel, toolLabel))
            }

        /** 按步号归好的动作明细:点开一步时列出来。 */
        private fun actionsOf(activities: List<RunEvent.Activity>, appLabel: (String) -> String, toolLabel: (String) -> String) =
            activities.groupBy { it.step }.mapValues { (_, list) ->
                // 用工具的界面说法(和运行过程页同一份),不把工具名露给用户;经 MCP 的调用说连接器名
                list.map { a ->
                    val label = a.mcpConnector?.let(StatusCopy::calledMcp) ?: toolLabel(a.label)
                    StepAction(label, a.target?.let { where(it, appLabel) }, a.effect, a.failed)
                }
            }

        private fun state(ex: Exchange, s: TaskState, appLabel: (String) -> String): Exchange = when (s) {
            TaskState.Idle -> ex
            is TaskState.Running -> {
                val goals = (s.progress as? RunProgress.Task)?.goals
                // 正在调 MCP 先说哪个连接器,盖过模型写的话
                val progress = s.mcpConnector?.let(StatusCopy::callingMcp) ?: s.progress?.takeIf { goals == null }?.let(StatusCopy::progress)
                // 「在做什么」先用模型随调用写的话,没有再用调用里的用意
                val said = (s.narration ?: s.reason)?.takeIf(String::isNotBlank)
                val waiting = progress == null && s.tool == null && said == null
                ex.copy(
                    status = ExchangeStatus.Working, waitingForModel = waiting, draft = s.preview, goals = goals ?: ex.goals,
                    line = if (waiting) "" else progress ?: said.orEmpty(),
                    notes = if (s.step > 0 && said != null) ex.notes + (s.step to said) else ex.notes,
                )
            }
            // 等能力的前置条件:锁屏时自动继续;排队等开始;其余能点继续的才给继续
            is TaskState.Held -> ex.copy(
                status = when {
                    s.reason == TaskGate.LOCKED -> ExchangeStatus.WaitingUnlock
                    s.reason.queued -> ExchangeStatus.Queued(s.reason.why)
                    else -> ExchangeStatus.Paused(s.reason.why, s.reason.resumable)
                },
                waitingForModel = false, draft = null,
            )
            is TaskState.WaitingTakeover ->
                ex.copy(status = ExchangeStatus.NeedsTakeover(s.reason, s.message, s.item, s.amount, s.address), waitingForModel = false, draft = null)
            is TaskState.WaitingInput -> ex.copy(status = ExchangeStatus.NeedsInput(s.prompt, s.options), waitingForModel = false, draft = null)
            is TaskState.WaitingConfirmation -> ex.copy(
                waitingForModel = false,
                draft = null,
                status = ExchangeStatus.NeedsConfirmation(
                    ConfirmationCopy.title(s.request), ConfirmationCopy.detail(s.request, appLabel(s.request.app)),
                    ConfirmationNotifier.requestKey(s),
                ),
            )
            // 回答原样显示(不再有模型写的依据段);来源由运行记录里代码记下的那份给出
            is TaskState.Succeeded -> ex.copy(status = ExchangeStatus.Done(s.answer.trim(), null, s.verified, s.note, s.unconfirmed, s.reviewed),
                waitingForModel = false, draft = null, line = "")
            is TaskState.Failed -> ex.copy(status = ExchangeStatus.Failed(s.reason), waitingForModel = false, draft = null, line = "")
            TaskState.Cancelled -> ex.copy(status = ExchangeStatus.Cancelled(), waitingForModel = false, draft = null, line = "")
        }

        /**
         * 步骤列表:每件实际做过的事一行。读取类只在知道读的是哪里时出现,连着读同一处合成一行(「看了微信的聊天列表」);
         * 动作写「点击 · 微信」,那一步模型写了话就用它的话;没成功只说没成功,不显示错误码。
         * 最后一行的状态跟着这一问一答:在跑、等你、结束。
         */
        /** 没办成的一步下面那行小字:按规矩没收的说原因,其余说「没成功」。 */
        private fun failedLabel(a: RunEvent.Activity) = ChatCopy.declinedLabel(a.effect) ?: tr(R.string.step_failed)

        internal fun rows(ex: Exchange, toolLabel: (String) -> String, appLabel: (String) -> String): List<StepRow> {
            val out = mutableListOf<StepRow>()
            val said = mutableSetOf<Int>()
            for (a in ex.activities) {
                val where = a.target?.let { where(it, appLabel) }
                val mcp = a.mcpConnector?.let(StatusCopy::calledMcp)
                if (mcp != null) {
                    // 经 MCP 的调用单列一行说连接器;失败也看得出是哪个连接器
                    out += StepRow(a.step, mcp, if (a.failed) failedLabel(a) else "", StepState.Ok)
                } else if (a.kind == "observe") {
                    // 读成功了却不知道读的是哪里:不列;没读成的照样列出来
                    if (where == null && !a.failed) continue
                    val text = if (where == null) toolLabel(a.label)
                        else a.title?.takeIf(String::isNotBlank)?.let { tr(R.string.step_viewed_part, where, it) } ?: tr(R.string.step_viewed, where)
                    val subtitle = if (a.failed) failedLabel(a) else ""
                    val last = out.lastOrNull()
                    if (last?.title != text || last.subtitle != subtitle) out += StepRow(a.step, text, subtitle, StepState.Ok)
                } else {
                    val reason = if (said.add(a.step)) ex.notes[a.step].orEmpty() else ""
                    val title = listOfNotNull(toolLabel(a.label), where).joinToString(" · ")
                    out += StepRow(a.step, title, if (a.failed) failedLabel(a) else "", StepState.Ok, reason)
                }
            }
            if (out.isEmpty()) return out
            val last = when (ex.status) {
                ExchangeStatus.Working -> StepState.Run
                is ExchangeStatus.Done -> StepState.Ok
                is ExchangeStatus.Failed, is ExchangeStatus.Cancelled -> StepState.Dim
                else -> StepState.Wait
            }
            out[out.lastIndex] = out.last().copy(state = last)
            return out
        }

        /** 活动对象给人看的说法:包名换成 App 名,「网站 域名」只留域名。 */
        private fun where(target: String, appLabel: (String) -> String): String? = when {
            target.isBlank() -> null
            target.startsWith(SITE_PREFIX) -> target.removePrefix(SITE_PREFIX)
            com.wangzan101.mika.core.tool.PACKAGE_NAME_RE.matches(target) -> appLabel(target)
            else -> target
        }

        private fun fromTurn(t: Turn): Exchange {
            val status = when (t.status) {
                "succeeded" -> answerParts(t.outcome).let { (a, e) -> ExchangeStatus.Done(a, e) }
                "unverified" -> answerParts(t.outcome).let { (a, e) -> ExchangeStatus.Done(a, e, verified = false, note = t.note, unconfirmed = t.unconfirmed) }
                "cancelled" -> {
                    val rawSummary = Conversation.stopSummary(t)
                    val summary = if (rawSummary != null && uiLocale().language != "zh") {
                        rawSummary
                            .replace("已经停下。停止前:", "Stopped. Before stopping:")
                            .replace("已经停下,停止前做了这些:", "Stopped. Actions before stop:")
                            .replace("已经停下,还没开始操作。", "Stopped before starting any actions.")
                    } else rawSummary
                    ExchangeStatus.Cancelled(summary)
                }
                else -> ExchangeStatus.Failed(t.outcome)
            }
            return Exchange(t.userText, emptyList(), status, live = false, runId = t.runId,
                memoryNotes = t.memoryNotes, usedMemory = t.usedMemory, skillNotes = t.skillNotes,
                audios = t.audios,
                photos = t.photos,
                keptPage = t.keptPage, keptPageClosed = t.keptPageClosed,
                steers = t.steers.mapIndexed { i, text ->
                    SteerNote("turn-$i", text, SteerState.Joined, t.interruptedReplies.getOrNull(i)?.takeIf(String::isNotEmpty),
                        t.finishedReplies.getOrNull(i)?.takeIf(String::isNotEmpty))
                })
        }


        /** 消息末尾每个附件一行:「附件:/workspace/inbox/<名字>(<大小>)」;[files] 是复制进工作区后的 (路径, 字节数)。 */
        internal fun withAttachments(text: String, files: List<Pair<String, Long>>): String =
            AttachmentLines.composeBytes(text, files)

        fun sizeLabel(bytes: Long): String = AttachmentLines.sizeLabel(bytes)
    }
}

/**
 * 结果卡片上的回答和依据文字,只从这里取。新的回答不带依据段(来源由代码从证据账本里找,见 [evidenceSource]);
 * 旧的运行记录、对话文件里模型自己写在末尾的「依据:」段照旧拆出来。[evidence] 给了就直接用。
 */
fun answerParts(answer: String, evidence: String? = null): Pair<String, String?> =
    evidence?.trim()?.takeIf { it.isNotEmpty() }?.let { answer.trim() to it } ?: splitEvidence(answer)

/**
 * 回答的来源:所在的 App 或网站([place],不知道时为 null)、相关的画面编号,和逐条的说明([items],「第 3 步 · 12306 · 车次查询」)。
 * 从运行记录里取:有代码记下的来源(sources 事件)就用它;旧记录用最后存下画面的那一步。
 */
data class EvidenceSource(val place: String?, val screenshots: List<Int>, val items: List<String> = emptyList())

/** 见 [EvidenceSource];[appLabel] 把包名换成 App 名,网站和网址显示域名。 */
fun evidenceSource(events: List<RunEvent>, appLabel: (String) -> String = { it }): EvidenceSource {
    events.filterIsInstance<RunEvent.Sources>().lastOrNull()?.items?.takeIf { it.isNotEmpty() }?.let { list ->
        fun where(p: String?) = p?.let { placeLabel(it, appLabel) }
        val items = list.map { s -> listOfNotNull(tr(R.string.step_number, s.step), where(s.place), s.title).joinToString(" · ") }
        return EvidenceSource(list.asReversed().firstNotNullOfOrNull { where(it.place) }, list.flatMap { it.mediaIds }.distinct(), items)
    }
    val shots = events.filterIsInstance<RunEvent.Media>()
    val lastStep = shots.maxOfOrNull { it.step } ?: return EvidenceSource(null, emptyList())
    val final = shots.filter { it.step == lastStep }
    val place = shots.lastOrNull { it.place != null }?.place?.let { if (it.startsWith(SITE_PREFIX)) it.removePrefix(SITE_PREFIX) else appLabel(it) }
    return EvidenceSource(place, final.map { it.mediaId }.distinct())
}

/** 运行记录里网页那一屏的「地方」写成「网站 <域名>」。 */
private const val SITE_PREFIX = "网站 "

/** 来源里的地方:「网站 域名」和网址显示域名,包名换成 App 名,别的(命令、文件)原样。 */
private fun placeLabel(place: String, appLabel: (String) -> String): String = when {
    place.startsWith(SITE_PREFIX) -> place.removePrefix(SITE_PREFIX)
    place.startsWith("http://") || place.startsWith("https://") -> runCatching { java.net.URI(place).host }.getOrNull() ?: place
    com.wangzan101.mika.core.tool.PACKAGE_NAME_RE.matches(place) -> appLabel(place)
    else -> place
}

/** 「来源」那一行:「来源 · 12306 · 2 张截图」;没有地方、没有截图时省掉那一段。 */
fun sourceLabel(source: EvidenceSource?): String = listOfNotNull(
    tr(R.string.source_label), source?.place, source?.screenshots?.size?.takeIf { it > 0 }?.let { trPlural(R.plurals.screenshots, it) },
).joinToString(" · ")

/**
 * 把答案拆成「结论」和「依据」。旧的运行记录、对话文件里,回答的最后一行以「依据:」开头;
 * 模型不一定照做,所以也认「证据 / 来源」和句末接着写的情况,之后的都算依据。
 * 认不出来时整段都是结论,回答下面不放「来源」。
 */
fun splitEvidence(answer: String): Pair<String, String?> {
    val m = EVIDENCE.find(answer) ?: return answer.trim() to null
    val evidence = answer.substring(m.range.last + 1).trim()
    val main = answer.substring(0, m.range.first + m.groupValues[1].length).trim()
    if (evidence.isEmpty() || main.isEmpty()) return answer.trim() to null
    return main to evidence
}

// 段首或句末标点之后,可带 markdown 的 **、#、- 等前缀
private val EVIDENCE = Regex("""(^|\n|[。.!！?？]\s*)[\s*#>\-]*\**(?:依据|证据|来源)\**\s*[:：]\**""")

/**
 * 工具在界面上的说法:工具声明里的 label(中文)按界面语言换成对应的资源;没有 label 的原样显示工具名。
 * 改名前的运行记录里的旧名按 [legacy] 认成现在的工具。
 */
class ToolLabels(private val tools: ToolIndex, private val legacy: LegacyNames = legacyNames()) {
    fun label(tool: String): String {
        val declared = (tools[tool] ?: legacy.resolve(tool)?.let { tools[it] })?.label ?: return tool
        return TOOL_LABELS[declared]?.let { tr(it) } ?: declared
    }

    companion object {
        /** 工具声明里的 label → 界面文字;中文资源与 label 一字不差(ToolLabelsTest 核对每个工具都有)。 */
        val TOOL_LABELS: Map<String, Int> = mapOf(
            "短信验证码登录" to R.string.sms_login_tool,
            "查看已装的 App" to R.string.tool_list_apps,
            "打开 App" to R.string.tool_open_app,
            "分享文字到 App" to R.string.tool_share_text,
            "分享音频到 App" to R.string.tool_share_audio,
            "分享图片到 App" to R.string.tool_share_image,
            "生成语音" to R.string.tool_audio_generate,
            "保存音频" to R.string.tool_audio_save,
            "转写音频" to R.string.tool_audio_transcribe,
            "说话" to R.string.tool_speak,
            "设闹钟" to R.string.tool_set_alarm,
            "设计时器" to R.string.tool_set_timer,
            "查看闹钟" to R.string.tool_show_alarms,
            "添加日程" to R.string.tool_add_calendar_event,
            "批量添加日程" to R.string.tool_calendar_bulk_insert,
            "拨滚轮" to R.string.tool_set_wheel,
            "滚动查找" to R.string.tool_scroll_find,
            "等待界面条件" to R.string.tool_wait_condition,
            "等待界面事件" to R.string.tool_wait_event,
            "提取界面数据" to R.string.tool_extract,
            "检查弹窗" to R.string.tool_check_dialog,
            "多点手势" to R.string.tool_gesture,
            "系统命令" to R.string.tool_shell,
            "root 命令" to R.string.tool_root,
            "通话状态" to R.string.calls_tool_state,
            "拨打电话" to R.string.calls_tool_dial,
            "AI 接听" to R.string.calls_answer,
            "挂断电话" to R.string.calls_tool_hangup,
            "人工接管" to R.string.calls_takeover,
            "查找界面控件" to R.string.tool_find_element,
            "采集多屏内容" to R.string.tool_collect,
            "连续操作手机" to R.string.tool_phone_run,
            "查看设备文件" to R.string.tool_list_device_files,
            "搜索设备文件" to R.string.tool_search_device_files,
            "复制设备文件" to R.string.tool_copy_device_file,
            "处理文件" to R.string.tool_process_files,
            "运行自带脚本" to R.string.tool_run_script,
            "读文件" to R.string.tool_read_file,
            "看图" to R.string.tool_view_image,
            "写文件" to R.string.tool_write_file,
            "搜索文件" to R.string.tool_search_files,
            "导出文件" to R.string.tool_export_file,
            "查看授权文件夹" to R.string.tool_list_folder_files,
            "生成图片" to R.string.tool_image_generate,
            "修改图片" to R.string.tool_image_edit,
            "保存图片" to R.string.tool_image_save,
            "打开网页" to R.string.tool_open_page,
            "搜索网页" to R.string.web_search_action,
            "切换网页标签" to R.string.tool_switch_tab,
            "切换网页版本" to R.string.tool_switch_version,
            "保持网页开着" to R.string.tool_keep_page,
            "定下 App 做完后留着还是关掉" to R.string.tool_app_after_task,
            "读网页" to R.string.tool_read_page,
            "查看下载" to R.string.tool_downloads,
            "查看网页收到的数据" to R.string.tool_network,
            "等网页变化" to R.string.tool_wait_page,
            "读取网页数据" to R.string.tool_read_script,
            "运行站点命令" to R.string.tool_site_command,
            "上传文件" to R.string.tool_upload,
            "遇到障碍" to R.string.tool_obstacle,
            "查找 App/网站知识" to R.string.tool_find_knowledge,
            "查找技能" to R.string.tool_find_skill,
            "查看 App/网站知识" to R.string.tool_read_knowledge,
            "查看技能" to R.string.tool_read_skill,
            "保存技能" to R.string.tool_save_skill,
            "查找记忆" to R.string.tool_memory_search,
            "写入记忆" to R.string.tool_memory_save,
            "修改记忆" to R.string.tool_memory_edit,
            "查看设置" to R.string.tool_list_settings,
            "改设置" to R.string.tool_set_setting,
            "查看自身状态" to R.string.tool_mika_status,
            "打开 Mika 的页面" to R.string.tool_mika_open_screen,
            "打开文件" to R.string.tool_mika_open_file,
            "发通知" to R.string.tool_mika_notify,
            "复制文字" to R.string.tool_mika_copy,
            "在浏览器里打开" to R.string.tool_mika_open_in_browser,
            "在浏览器里搜索" to R.string.tool_mika_search_in_browser,
            "查看网站登录" to R.string.tool_mika_sites,
            "查看正在看的网页" to R.string.tool_mika_user_page,
            "请你登录网站" to R.string.tool_mika_login_site,
            "核对登录" to R.string.tool_mika_confirm_login,
            "常用网站" to R.string.tool_mika_favorites,
            "说明没办成的原因" to R.string.tool_explain_failure,
            "问你一个问题" to R.string.tool_ask_user,
            "请你接手" to R.string.tool_takeover,
            "请你同意" to R.string.tool_confirm,
            "等待" to R.string.tool_wait,
            "检查屏幕" to R.string.tool_diagnose,
            "返回" to R.string.tool_back,
            "按键" to R.string.tool_press_key,
            "看屏幕" to R.string.tool_observe,
            "读取界面元素" to R.string.tool_list_elements,
            "截图" to R.string.tool_screenshot,
            "滚动" to R.string.tool_scroll,
            "滑动" to R.string.tool_swipe,
            "点击" to R.string.tool_tap,
            "输入文字" to R.string.tool_type,
            "清空输入框" to R.string.tool_clear,
        )
    }
}
