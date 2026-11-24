package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.uiLocale
import com.wangzan101.mika.chat.SharedDraft
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.GoalChecklist
import com.wangzan101.mika.core.agent.NoticeTurn
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.learn.memory.MemoryNote
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.learn.skills.SkillNote
import com.wangzan101.mika.settings.Problem
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.task.QueuedFollowUp
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.screen.user.UserScreenMessage

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
    /**
     * 前台这一轮的回话(聊天走前台时):不为 null 表示这一行是前台的一问一答,画成前台气泡,不画任务卡片;
     * 空串是还在想(画三个点)。办好了但前台一句话没说(只派了单)时不画空气泡。
     */
    val deskReply: String? = null,
    /** 前台这一轮派出(或汇报所指)的任务编号,按先后;每件在这一问一答下面画一张任务卡片。 */
    val deskTasks: List<String> = emptyList(),
    /** 前台这一轮给已有任务补话、回话或取消时的一枚小标签(只在这次打开时显示,不落盘)。 */
    val deskTool: String? = null,
    /** 这一轮是哪一通语音通话里说的([com.wangzan101.mika.core.agent.Turn.callId]);打字聊的为 null。 */
    val callId: String? = null,
    /** 存下这一轮的时刻;还没存的(正在进行的)为 null。没说话的通话按它排进对话里。 */
    val atMs: Long? = null,
)

/**
 * 有运行记录可看的那次运行。通知提醒那一轮([NoticeTurn])是 Mika 直接写进对话的,没跑过任务,它的编号只是用来认它的:
 * 界面上「查看过程」、来源、重新开始都按这个给,那一轮就都没有(不然点进去只会说「记录已过期」)。
 * 认轮次、和存下的那份对上([ChatViewModel] 里按 [Exchange.runId])照旧用原来的编号。
 */
val Exchange.recordedRun: String? get() = runId?.takeUnless { it.startsWith(NoticeTurn.RUN_PREFIX) || it.startsWith(DeskChatOps.RUN_PREFIX) }

/**
 * 这一轮新派出去的任务。前台汇报那一行(没有用户的话)的第一件是被汇报的那件([deskRef]),不算新派;
 * 汇报那一轮前台顺手又派的排在后面,算新派。「这个对话里的事」按它列。
 */
val Exchange.deskCards: List<String> get() = if (goal.isEmpty()) deskTasks.drop(1) else deskTasks

/** 前台汇报那一行汇报的是哪件;不是汇报为 null。 */
val Exchange.deskRef: String? get() = if (goal.isEmpty()) deskTasks.firstOrNull() else null

/** 前台这一行画不画字:还在想(三个点)、说了话、或没回成都画;办好了却一句没说(只派了单)不画空气泡。 */
internal fun deskReplyShown(ex: Exchange): Boolean =
    ex.deskReply != null && (ex.status == ExchangeStatus.Working || ex.deskReply.isNotBlank() || ex.status is ExchangeStatus.Failed)

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
    /** 这个对话里直接开的任务在跑(带附件开的,或旧对话里的):没打字时给停止按钮;打的字照样交给前台。 */
    Busy,
    /** Agent 在等回答,发出去就是回答。 */
    Answer,
}

/** 悬浮胶囊里任务进行中发补充消息时,这句话交给谁:当前任务(下一步带上),还是这个对话的下一件任务(聊天页走前台,不再用它)。 */
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
    /** 这个对话明确排队、还没开始的下一件(按先后);开始或取消后从这里拿掉。 */
    val queued: List<QueuedFollowUp> = emptyList(),
    /** 这条会话前台派过的任务和各自阶段(按派单先后);在办的显示在输入框上方的「在办」条里,每件对应对话里的一张卡片。 */
    val deskTasks: List<com.wangzan101.mika.core.frontdesk.DeskTaskView> = emptyList(),
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

internal data class ComposerDraft(
    val input: String = "",
    val attachments: List<PendingAttachment> = emptyList(),
    val quote: QuoteMessage.Quote? = null,
    val sharedDraft: SharedDraft? = null,
    val importing: Boolean = false,
    val rejected: List<AttachmentFailure> = emptyList(),
    val overLimit: Int = 0,
    val projectId: String? = null,
) {
    val hasContent: Boolean get() = input.isNotEmpty() || attachments.isNotEmpty() || quote != null || importing || rejected.isNotEmpty()
    fun applyTo(ui: ChatUi) = ui.copy(input = input, attachments = attachments, quote = quote,
        sharedDraft = sharedDraft, importing = importing,
        rejectedAttachments = rejected, overLimit = overLimit, projectId = projectId ?: ui.projectId)
}

internal fun ChatUi.draft() = ComposerDraft(input, attachments, quote, sharedDraft,
    importing, rejectedAttachments, overLimit, projectId)

/**
 * 换对话时统一重建界面:任务和输入内容属于各自对话,附件按钮能力属于设备,不随对话重建丢掉。
 * 每条切换路径都从这里走,再恢复目标对话自己的草稿。系统分享可以显式填入它自己的文字和附件。
 */
internal fun ChatUi.rebuildForConversation(
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
    queued = emptyList(),
    deskTasks = emptyList(),
    takeoverStage = takeoverStage,
    attachments = attachments,
    importing = false,
    rejectedAttachments = emptyList(),
    overLimit = 0,
    sharedDraft = sharedDraft,
    pendingSharedDraft = null,
    quote = null,
)
