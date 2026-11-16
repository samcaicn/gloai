package com.wangzan101.mika.task

import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.ConversationTitler
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.trigger.ConditionOutcome
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.SendOutcome
import com.wangzan101.mika.core.run.ChannelInteraction
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.takeover.TakeoverActivity
import com.wangzan101.mika.ui.MainActivity
import com.wangzan101.mika.ui.ScreenCopy
import com.wangzan101.mika.ui.StatusCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger
import java.time.LocalTime
import com.wangzan101.mika.core.cap.web.KeptPageClose

/**
 * 这条通知照发、照进通知栏,但不响、不振、不弹横幅:用系统的分组提醒规则(只让组摘要提醒,这条是组里的普通通知,
 * 组里没有摘要),和系统自己的「静音通知」是同一个做法;不换频道、不新建频道。安卓 8 起都有,Mika 支持的系统都能用。
 */
internal fun Notification.Builder.alertQuietly(): Notification.Builder =
    setGroup(QUIET_GROUP).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)

internal fun Notification.Builder.applyScheduledQuietHours(context: Context): Notification.Builder =
    if (FeatureFlags.get(context).scheduledQuietHours().isQuietAt(LocalTime.now())) alertQuietly() else this

private const val QUIET_GROUP = "com.wangzan101.mika.quiet"

/**
 * 前台服务:在 Agent 虚拟屏上跑任务,单个任务的状态与执行在 [TaskRun],运行中的任务登记在 [TaskRegistry]。
 * 开关 parallelTasks 关着时同一时间只跑一个,别的对话交来的任务排在后面(不论从哪里交来,都不丢);
 * 打开时不同对话的任务可以同时跑,各占一块虚拟屏,上限见 [ParallelCapability],超出的排队。
 * 每个任务各有一组通知(编号与按钮请求码按 [TaskRun.slot] 分开,按钮带着对话编号),
 * 前台常驻通知是其中一个任务的进度通知,同时有几个任务时注明「n 个任务进行中」。
 * 任务在跑时屏幕常亮([KeepAwake],不改系统设置和亮度);任务期间目标 App 静音(原模式持久化,异常退出后下次启动恢复),
 * AgentLoop 的 gate 由 [TaskGate] 实现:熄屏/锁屏等待解锁,App 被挪到主屏或手机操作助手断开则暂停到用户点「继续」。
 * Agent 请求接管或提问时另发一条醒目的通知,接管流程见 [Takeover]。
 *
 * 前台服务类型用 specialUse:dataSync 在安卓 15 有每日 6 小时上限且语义不符;
 * specialUse 没有运行时限制,只需在清单里写明子类型。
 */
class TaskService : Service() {
    internal val taskState: TaskCenterState get() = (application as com.wangzan101.mika.MikaApp).taskState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** 最近一次 start 时读到的开关;关着时登记处上限恒为 1。 */
    @Volatile private var parallel = false
    private val center by lazy {
        TaskCenter<TaskRun>(::limit, { it.conversationId }, { it.key }, (application as com.wangzan101.mika.MikaApp).runStore,
            (application as com.wangzan101.mika.MikaApp).resources, (application as com.wangzan101.mika.MikaApp).followUpQueue) { c, g ->
            TaskRun(c, g, this, scope, freeSlot(), parallel)
        }
    }
    private val outbox get() = (application as com.wangzan101.mika.MikaApp).outbox
    private val localChannel by lazy { localDeliveryChannel(scheduled = false) }
    private val scheduledLocalChannel by lazy { localDeliveryChannel(scheduled = true) }

    private fun localDeliveryChannel(scheduled: Boolean) = LocalChannel { _, delivery ->
        if (delivery is Delivery.Ask) {
            center.byConversation(delivery.threadId)?.let(::refreshNotifications)
            SendOutcome.Delivered
        } else {
            val resultId = if (delivery is Delivery.Result && center.byConversation(delivery.threadId)?.parallel == false) ID_RESULT
                else nextResultId()
            notifyDelivery(this, delivery, resultId, scheduled)
        }
    }
    /** 前台常驻通知属于哪个任务;收尾后留着,直到换成别的任务。 */
    @Volatile private var foreground: TaskRun? = null
    private val notifyLock = Any()
    @Volatile private var lastStartId = 0

    internal val clipLock get() = center.clipLock
    internal val takeoverQueue get() = center.takeoverQueue
    internal fun rememberRecord(run: TaskRun, recordId: String) = center.markRecord(run, recordId)
    /** 几个任务共用的屏幕常亮锁。 */
    internal val wakeLock by lazy { SharedWakeLock(ScreenWakeLock(this)) }

    private fun limit(): ParallelLimit {
        if (!parallel) return ParallelLimit(1, null)
        val lowRam = getSystemService(ActivityManager::class.java).isLowRamDevice
        return ParallelCapability.detect(Build.VERSION.SDK_INT, lowRam, FeatureFlags.get(this).parallelMax(), taskState.secondDisplayFailed)
            .also { taskState._parallelLimit.value = it }
    }

    /** 登记处里没人用的最小槽位;只在登记处的锁内调用。 */
    private fun freeSlot(): Int {
        val used = center.runs().map { it.slot }.toSet()
        return generateSequence(0) { it + 1 }.first { it !in used }
    }

    internal fun reservePackage(pkg: String, runKey: String, stillUsed: () -> Boolean) = center.reserve(pkg, runKey, stillUsed)
    internal fun packageFree(pkg: String, runKey: String, stillUsed: () -> Boolean) = center.appFree(pkg, runKey, stillUsed)
    internal suspend fun awaitPackage(pkg: String, runKey: String, timeoutMs: Long, free: suspend () -> Boolean) =
        center.awaitApp(pkg, runKey, timeoutMs, free)

    /** 这个对话空着就当场开始;还有任务就留在持久队列里,它收尾时([onRunEnded])交出来。查与留在同一把锁里。 */
    internal fun deferMessage(threadId: String, text: String, messageId: String) {
        val item = QueuedFollowUp(messageId, threadId, text, System.currentTimeMillis(), QueuedSource.Fallback)
        if (center.deferOrStart(threadId, text, messageId, QueuedSource.Fallback)) startFollowUp(item)
        else keepForeground()
    }

    /** 用户明确选了「排队」:这个对话还有任务就写进持久队列;刚好空了就当场开始。 */
    private fun queueFollowUp(threadId: String, text: String, messageId: String, source: QueuedSource) {
        val item = QueuedFollowUp(messageId, threadId, text, System.currentTimeMillis(), source)
        if (center.deferOrStart(threadId, text, messageId, source)) {
            // 这个对话刚空:不先入队,直接走 startNew;若这中间又来了任务,tryStart 会把它原子放回队列
            startFollowUp(item)
        } else {
            keepForeground()
        }
    }

    /** 取这个对话的下一件;只取一件,由 startNew 登记或放回。 */
    private fun drainReady(conversationId: String?) {
        if (teachingBlocks()) return
        center.takeReady(conversationId).forEach(::startFollowUp)
    }

    /** 起动一件排队项;先确认已经写盘,起不来就放回队列,别停在 claim 状态。 */
    private fun startFollowUp(item: QueuedFollowUp) {
        if (!center.persistFollowUp(item)) return
        runCatching {
            startNew(item.text, item.conversationId, provenance = item.provenance, followUp = item,
                channelPeer = item.channelPeer, channelMessageId = item.channelMessageId)
        }.onFailure { center.followUps.add(item) }
    }

    private fun channelFor(peer: ChannelPeer, scheduled: Boolean = false): Channel = when (peer.channelId) {
        LocalChannel.ID -> if (scheduled) scheduledLocalChannel else localChannel
        else -> checkNotNull((application as com.wangzan101.mika.MikaApp).wechat.channelFor(peer.channelId)) {
            "unknown channel ${peer.channelId}"
        }
    }

    private fun acknowledgeChannel(peer: ChannelPeer?, messageId: String?) {
        if (peer != null && messageId != null) channelFor(peer).acknowledge(messageId)
    }

    /**
     * 每个新登记的任务只确认一次，不转发运行阶段。立即进入可靠送达账本并占住发送顺序，
     * 网络发送在后台进行；重复消息不会建任务，因此也不会再确认。
     */
    private fun acceptChannel(run: TaskRun) {
        val peer = run.channelPeer ?: return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            outbox.deliver(channelFor(peer), peer,
                Delivery.Notice("accepted:${run.key}", peer.threadId, tr(R.string.channel_task_accepted)))
        }
    }

    internal fun interaction(threadId: String, source: ChannelPeer? = null): ChannelInteraction {
        val peer = source ?: ChannelPeer(LocalChannel.ID, "phone-owner", threadId)
        return ChannelInteraction(outbox, channelFor(peer), peer)
    }

    /** 任务结束后留在后台的那一页(见 [com.wangzan101.mika.cap.web.PageKeeper]):留着时服务不退,前台通知换成它的。 */
    private val keeper get() = (application as com.wangzan101.mika.MikaApp).pageKeeper

    internal fun otherRunHasDisplay(run: TaskRun) = center.running().any { it !== run && it.displayId.value >= 0 }
    internal fun hasActiveRuns() = center.running().isNotEmpty()
    internal fun hasQueuedRuns() = center.queued().isNotEmpty()

    private fun publishRuns() {
        val r = center.runs()
        taskState._runs.value = r
        taskState._activeConversations.value = r.map { it.conversationId }
    }

    private fun id(base: Int, run: TaskRun) = base + SLOT_STRIDE * run.slot

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        // 留着的那一页变了:通知跟着换;关掉了而没有任务时退出前台、停掉服务
        // 只跟「留着 → 换了 / 关了」:开头那个「没有」不算,免得和正在开始的任务抢着停服务
        scope.launch {
            var had = false
            keeper.page.collect { p ->
                if (p == null && !had) return@collect
                had = p != null
                onKeptPage(p)
            }
        }
        // 没结束的运行在 App 启动时就处理过了;这里只补一道(进程里只做一次),并把此刻在跑的运行排除在外
        if (!FeatureFlags.get(this).quitRequested()) {
            (application as com.wangzan101.mika.MikaApp).runRecovery.recover(center.runs().map { RunId(it.key) }.toSet())
        }
    }

    private fun waitKeyOf(intent: Intent) = intent.getIntExtra(EXTRA_WAIT_KEY, -1).takeIf { it >= 0 }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // 系统重交旧启动请求、通知按钮或外部入口都可能在完全退出后到这里:不处理动作,也不让服务驻留
        if (FeatureFlags.get(this).quitRequested()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // 通知按钮带着任务编号,只作用于那个任务(它已结束就不起作用);应用内的按钮作用于显示中的任务
        val run = if (intent?.hasExtra(EXTRA_RUN) == true) intent.getStringExtra(EXTRA_RUN)?.let(center::byKey) else taskState.shownRun
        when (intent?.action) {
            ACTION_START -> begin(intent.getStringExtra(EXTRA_GOAL).orEmpty(), intent.getStringExtra(EXTRA_CONVERSATION),
                messageId = intent.getStringExtra(EXTRA_MESSAGE_ID))
            ACTION_ENQUEUE_FOLLOWUP -> {
                val thread = intent.getStringExtra(EXTRA_CONVERSATION)
                val text = intent.getStringExtra(EXTRA_GOAL).orEmpty()
                val id = intent.getStringExtra(EXTRA_MESSAGE_ID) ?: java.util.UUID.randomUUID().toString()
                val source = QueuedSource.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SOURCE) }
                    ?: QueuedSource.Explicit
                if (thread != null && text.isNotBlank()) queueFollowUp(thread, text, id, source)
            }
            ACTION_DRAIN_FOLLOWUPS -> drainReady(intent.getStringExtra(EXTRA_CONVERSATION))
            ACTION_START_TRIGGER -> begin(intent.getStringExtra(EXTRA_GOAL).orEmpty(), intent.getStringExtra(EXTRA_CONVERSATION),
                provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended),
                triggerKey = intent.getStringExtra(EXTRA_TRIGGER_KEY),
                triggerActionGoal = intent.getStringExtra(EXTRA_TRIGGER_ACTION),
                triggerThreadId = intent.getStringExtra(EXTRA_TRIGGER_THREAD),
                triggerCondition = intent.getStringExtra(EXTRA_TRIGGER_CONDITION))
            ACTION_START_MCP -> intent.getStringExtra(EXTRA_MCP_ID)?.let { id ->
                if ((application as com.wangzan101.mika.MikaApp).remoteTasks.claimStart(id)) {
                    begin(intent.getStringExtra(EXTRA_GOAL).orEmpty(), intent.getStringExtra(EXTRA_CONVERSATION),
                        provenance = RunProvenance(RunOrigin.Mcp,
                            Attendance.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_ATTENDANCE) } ?: Attendance.Unattended),
                        mcpExternalId = id)
                }
            }
            ACTION_START_CHANNEL -> {
                val peer = ChannelPeer(intent.getStringExtra(EXTRA_CHANNEL_ID).orEmpty(),
                    intent.getStringExtra(EXTRA_PEER_ID).orEmpty(), intent.getStringExtra(EXTRA_CONVERSATION).orEmpty())
                begin(intent.getStringExtra(EXTRA_GOAL).orEmpty(), peer.threadId,
                    provenance = RunProvenance(RunOrigin.Channel, Attendance.Reachable),
                    channelPeer = peer, channelMessageId = intent.getStringExtra(EXTRA_CHANNEL_MESSAGE_ID))
            }
            ACTION_CANCEL_MCP -> intent.getStringExtra(EXTRA_MCP_ID)?.let { id -> center.runs().firstOrNull { it.mcpExternalId == id } }
                ?.let { r -> if (center.cancelIfQueued(r)) cancelQueued(r) else r.cancel() }
            ACTION_CANCEL -> if (run != null && center.cancelIfQueued(run)) cancelQueued(run) else run?.cancel()
            ACTION_RESUME -> run?.resume()
            ACTION_TAKE_OVER -> run?.takeOver(waitKeyOf(intent))
            ACTION_HAND_BACK -> run?.handBack(waitKeyOf(intent))
            ACTION_FINISHED_MYSELF -> run?.finishedMyself(waitKeyOf(intent))
            ACTION_SKIP -> run?.skip(waitKeyOf(intent))
            ACTION_PUT_ASIDE -> run?.putAside(waitKeyOf(intent))
            ACTION_ANSWER -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
                    ?: intent.getStringExtra(EXTRA_ANSWER)
                if (!text.isNullOrBlank()) run?.answer(text.trim(), ::answerTooLate)
            }
            ACTION_CLOSE_KEPT_PAGE -> keeper.close(KeptPageClose.User)
            ACTION_APPROVE, ACTION_DECLINE -> {
                val key = if (intent.hasExtra(ConfirmationNotifier.EXTRA_REQUEST)) intent.getIntExtra(ConfirmationNotifier.EXTRA_REQUEST, 0) else null
                run?.confirm(if (intent.action == ACTION_APPROVE) ConfirmChoice.Once else ConfirmChoice.Deny, key)
            }
        }
        if (!taskState._busy.value && keeper.page.value == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    /** 正在采集或暂停的示范里不开始任务，也不把排队项取走。 */
    private fun teachingBlocks(): Boolean {
        val app = application as? com.wangzan101.mika.MikaApp ?: return false
        return app.teachReady && app.teach.blocksTasks()
    }

    private fun begin(newGoal: String, requestedConversation: String?,
        provenance: RunProvenance = RunProvenance(), triggerKey: String? = null,
        triggerActionGoal: String? = null, triggerThreadId: String? = null, mcpExternalId: String? = null, messageId: String? = null,
        channelPeer: ChannelPeer? = null, channelMessageId: String? = null, triggerCondition: String? = null) {
        if (teachingBlocks()) {
            if (provenance.origin == RunOrigin.Ui) {
                android.widget.Toast.makeText(this, tr(R.string.teach_blocked_task), android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        parallel = FeatureFlags.get(this).speedFlags().parallelTasks
        // 同一对话里正在执行或排队的任务收到新原话时，交给它的 Inbox。
        requestedConversation?.let(center::byConversation)?.let { existing ->
            if (provenance.origin == RunOrigin.Ui) {
                if (messageId != null) existing.steer(newGoal, messageId) else existing.steer(newGoal)
                keepForeground()
                return
            }
        }
        // 插话到的时候那个任务已经收尾:作为这个对话的下一件事,不因服务还在收尾而丢掉。
        // 走同一个持久队列:即使对话刚好空着,也先写盘再认领和开始。
        if (messageId != null && requestedConversation != null) {
            val item = QueuedFollowUp(messageId, requestedConversation, newGoal, System.currentTimeMillis(), QueuedSource.Fallback,
                provenance = provenance, channelPeer = channelPeer, channelMessageId = channelMessageId)
            startFollowUp(item)
            keepForeground()
            return
        }
        if (triggerKey != null && center.runs().any { it.triggerWindowKey == triggerKey &&
                it.conditionCheck == (triggerActionGoal != null) }) {
            keepForeground()
            return
        }
        startNew(newGoal, requestedConversation, provenance, triggerKey, triggerActionGoal, triggerThreadId, mcpExternalId,
            triggerCondition = triggerCondition, channelPeer = channelPeer, channelMessageId = channelMessageId)
    }

    /**
     * 活任务检查之后的公共部分:建/排一个任务。[followUp] 是排队里取出来的项(或引导 fallback):
     * 运行真正 `markRunning` 时才结束它的持久排队生命周期;这个对话刚好又有任务时,tryStart 会在同一个锁里把它放回队列。
     */
    private fun startNew(newGoal: String, requestedConversation: String?,
        provenance: RunProvenance = RunProvenance(), triggerKey: String? = null,
        triggerActionGoal: String? = null, triggerThreadId: String? = null, mcpExternalId: String? = null,
        followUp: QueuedFollowUp? = null, triggerCondition: String? = null,
        channelPeer: ChannelPeer? = null, channelMessageId: String? = null) {
        if (teachingBlocks()) {
            if (followUp != null) center.followUps.add(followUp)
            else if (provenance.origin == RunOrigin.Ui) {
                android.widget.Toast.makeText(this, tr(R.string.teach_blocked_task), android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        parallel = FeatureFlags.get(this).speedFlags().parallelTasks
        // 别的对话的任务正在跑、并行关着(或已到上限)时不丢:登记处让它排在后面,那个任务收尾时放出来。
        // 对话页先等服务空下来再交过来,但查完到交到之间别处(定时、电脑)可能先开始了一个,这里同样排上
        val store = ConversationStore.shared(this)
        val conversationId = requestedConversation ?: store.newConversation()
        // 定时和电脑交来的任务不丢:这个对话还有任务时排在它后面
        val queueHere = provenance.origin == RunOrigin.Trigger || provenance.origin == RunOrigin.Mcp || provenance.origin == RunOrigin.Channel
        when (val r = center.tryStart(conversationId, newGoal, queueInConversation = queueHere,
            channelPeer = channelPeer, channelMessageId = channelMessageId, followUp = followUp)) {
            StartResult.DuplicateChannelMessage -> acknowledgeChannel(channelPeer, channelMessageId)
            StartResult.DuplicateFollowUp -> keepForeground()
            StartResult.AlreadyRunningInConversation -> keepForeground()
            is StartResult.Queued -> {
                nameConversation(store, conversationId, newGoal, provenance)
                r.run.provenance = provenance
                r.run.triggerWindowKey = triggerKey
                r.run.triggerActionGoal = triggerActionGoal
                r.run.triggerCondition = triggerCondition
                r.run.triggerThreadId = triggerThreadId
                if (triggerKey != null) center.store.get(com.wangzan101.mika.core.run.RunId(r.run.key))?.let {
                    center.store.save(it.copy(triggerWindowKey = triggerKey, userText = triggerCondition))
                }
                r.run.mcpExternalId = mcpExternalId
                r.run.channelPeer = channelPeer
                r.run.channelMessageId = channelMessageId
                acceptChannel(r.run)
                acknowledgeChannel(channelPeer, channelMessageId)
                taskState._busy.value = true
                taskState._conversationId.value = conversationId
                // 先登记再显示:界面收到它的状态时,任务列表里已经有它(对话、原话)
                publishRuns()
                // 清空成功标准、运行编号,显示排队
                if (center.running().none { it.conversationId == conversationId }) r.run.show()
                r.run.queue(r.position)
                keepForeground()
            }
            is StartResult.Started -> {
                nameConversation(store, conversationId, newGoal, provenance)
                taskState._busy.value = true
                taskState._conversationId.value = conversationId
                // 清空成功标准、运行编号,状态置为「正在准备」
                val run = r.run
                run.provenance = provenance
                run.triggerWindowKey = triggerKey
                run.triggerActionGoal = triggerActionGoal
                run.triggerCondition = triggerCondition
                run.triggerThreadId = triggerThreadId
                if (triggerKey != null) center.store.get(com.wangzan101.mika.core.run.RunId(run.key))?.let {
                    center.store.save(it.copy(triggerWindowKey = triggerKey, userText = triggerCondition))
                }
                run.mcpExternalId = mcpExternalId
                run.channelPeer = channelPeer
                run.channelMessageId = channelMessageId
                acceptChannel(run)
                acknowledgeChannel(channelPeer, channelMessageId)
                // 先登记再显示:界面收到它的状态时,任务列表里已经有它(对话、原话)
                publishRuns()
                run.show()
                val fg = foreground
                if (fg != null && fg !== run && fg in center.running()) keepForeground()
                else {
                    foreground = run
                    startInForeground(run, progressNotification(run, run.state.value))
                }
                launch(run, store)
            }
        }
    }

    /** 用户(或电脑)在新对话里交来的第一句话:后台请模型起一个标题记下(只起一次,起不出来就不记)。 */
    private fun nameConversation(store: ConversationStore, id: String, first: String, provenance: RunProvenance) {
        if (provenance.origin != RunOrigin.Ui && provenance.origin != RunOrigin.Mcp && provenance.origin != RunOrigin.Channel) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val env = TaskEnvironment.of(this@TaskService)
                // 起标题也属于这段对话,和对话里的任务用同一个会话号
                val session = ModelSession.of(id)
                ConversationTitler.ensure(store, id, first) {
                    listOfNotNull(env.lightModelFactory(this@TaskService, session), env.modelFactory(this@TaskService, session))
                }
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

    /** 已经在前台(或即将收尾)时再次进入前台:用前台那个任务的进度通知。 */
    private fun keepForeground() {
        val fg = foreground
        if (fg != null) startInForeground(fg, progressNotification(fg, fg.state.value))
        else startInForeground(null, preparingNotification())
    }

    private fun launch(run: TaskRun, store: ConversationStore) {
        run.job = scope.launch {
            center.markRunning(run)
            val result = try {
                run.execute(runCatching { store.turns(run.conversationId) }.getOrDefault(emptyList()).map(::withKeptState))
            } catch (e: CancellationException) {
                TaskState.Cancelled
            } catch (e: RemoteException) {
                run.shellDied = true
                TaskState.Failed(SHELL_DIED, FailKind.Environment)
            } catch (e: UnattendedLockException) {
                TaskState.Failed(e.message.orEmpty(), FailKind.Environment)
            } catch (e: com.wangzan101.mika.core.access.AppAccessDenied) {
                val reason = when (e.code) {
                    "app_blocked" -> tr(R.string.task_app_blocked, e.packageName ?: tr(R.string.task_app_this_app))
                    "app_access_unavailable" -> tr(R.string.task_app_access_unavailable)
                    "app_access_protected" -> tr(R.string.task_app_protected)
                    else -> tr(R.string.task_app_unverified)
                }
                TaskState.Failed(reason, FailKind.Environment)
            } catch (e: Exception) {
                TaskState.Failed(tr(R.string.task_error, e.message ?: e::class.simpleName), FailKind.Environment)
            }
            // shell 进程没了,虚拟屏和上面的 App 一起没了;任务没法接着做
            val end = if (run.shellDied && result !is TaskState.Succeeded) TaskState.Failed(SHELL_DIED, FailKind.Environment) else result
            // 停下了:界面当场显示已停止,交代随后补上(先登记「正在整理」,界面收到终态时就知道还有话要来)
            val draft = if (end == TaskState.Cancelled) run.draftText() else null
            val stopRunId = run.runId.value?.takeIf { end == TaskState.Cancelled && !run.conditionCheck && run.hasStopMaterial(draft) }
            stopRunId?.let { id -> taskState._summarizing.update { it + id } }
            run.mcpExternalId?.let { (application as com.wangzan101.mika.MikaApp).remoteTasks.ended(it, end, run.steers.value, run.runId.value, run.sharedFiles.value) }
            run.end(end)
            if (!run.conditionCheck) recordTurn(store, run, end, run.runId.value, run.sharedFiles.value, run.joinedSteers(), run.usedGroups, run.joinedInterrupted(), run.joinedFinished())
            stopRunId?.let { summarizeStop(run, it, draft, store) }
            if (end is TaskState.Failed && !run.conditionCheck) run.runId.value?.let { summarizeFailure(run, it, end.reason, store) }
            if (run.conditionCheck) {
                val outcome = if (end is TaskState.Succeeded) ConditionOutcome.parse(end.answer)
                    else ConditionOutcome.Error((end as? TaskState.Failed)?.reason ?: tr(R.string.task_condition_interrupted))
                TriggerRuntime.checked(this@TaskService, run.triggerWindowKey.orEmpty(), run.triggerActionGoal.orEmpty(),
                    run.triggerThreadId.orEmpty(), outcome)
                if (outcome is ConditionOutcome.Error) {
                    val failure = TaskState.Failed(tr(R.string.task_check_unfinished, outcome.reason), FailKind.Environment)
                    recordTurn(store, run, failure, run.runId.value)
                    runCatching { notifyResult(run, failure, "condition:${run.key}") }
                        .onFailure { Log.w(TAG, "condition failure notification failed", it) }
                }
                center.markDelivered(run)
                onRunEnded(run, store)
                return@launch
            }
            val deliveryId = "result:${run.key}"
            run.clearTakeoverStage()
            synchronized(notifyLock) { getSystemService(NotificationManager::class.java).cancel(id(ID_ATTENTION, run)) }
            // 先发结果通知,再在后台学(提取记忆、记技能结果、起草技能);用进程级作用域,服务退出也做完
            var delivered = false
            val app = applicationContext
            runCatching {
                // Persist the whole delivery before marking the result ready; recovery must know about its images too.
                // A storage failure skips sending but must still release this task's slot below.
                prepareResult(run, end, deliveryId)
                center.markResult(run, describe(end).second, deliveryId, successful = end is TaskState.Succeeded)
                RunLearning.launchAfter(MemoryRuntime.of(this@TaskService).scope, {
                    delivered = notifyResult(run, end, deliveryId) == DeliveryState.Delivered
                    // 定时任务的结果当场按结局记(和以前一样),判下来没办成再改记;不靠服务一直在
                    if (delivered) run.triggerWindowKey?.let { key ->
                        runCatching {
                            if (end is TaskState.Succeeded) TriggerRuntime.delivered(app, key)
                            else TriggerRuntime.actionFailed(app, key, describe(end).second)
                        }.onFailure { Log.w(TAG, "could not record trigger result", it) }
                    }
                }) {
                    // 交出之后:回答按用户的要求判结局(记进运行记录和这一轮对话),只从办成的学
                    val judged = runCatching { run.settleOutcome(end, store) }.onFailure { Log.w(TAG, "outcome judging failed", it) }.getOrNull()
                    // 回答如实说没办成、只办了一部分的,和放弃一样记成没办成;没判成的照交出算
                    val notDone = judged?.outcome == RunOutcome.Partial || judged?.outcome == RunOutcome.NotPossible
                    if (delivered && notDone && end is TaskState.Succeeded) run.triggerWindowKey?.let { key ->
                        runCatching { TriggerRuntime.actionFailed(app, key, judged?.reason?.ifBlank { null } ?: describe(end).second) }
                            .onFailure { Log.w(TAG, "could not downgrade trigger result", it) }
                    }
                    if (run.provenance.origin == RunOrigin.Ui) runCatching { run.learnAfter(end, judged?.outcome, store) }
                        .onFailure { Log.w(TAG, "learning after run failed", it) }
                }
            }.onFailure { Log.w(TAG, "result notification failed", it) }
            if (delivered) center.markDelivered(run)
            onRunEnded(run, store)
        }
        // 收尾前被取消(服务退出)、放出来还没跑就被停掉时,替它拿着的屏幕锁没交出去也没放开:这里放开
        run.job?.invokeOnCompletion { run.wakeHandoff?.release(); run.wakeHandoff = null }
    }

    /**
     * 停止之后写交代,记成这一轮的回答(结局照旧是取消)。用进程级作用域在后台写:不挡着收尾、下一件事和再点停止,服务退出也写完。
     * 写好或写不成都从「正在整理」里拿掉。
     */
    private fun summarizeStop(run: TaskRun, runId: String, draft: String?, store: ConversationStore) {
        MemoryRuntime.of(this).scope.launch {
            try {
                val text = runCatching { run.stopSummary(draft) }.onFailure { Log.w(TAG, "stop summary failed", it) }.getOrNull()
                if (text != null) withContext(Dispatchers.IO) {
                    runCatching { store.updateTurn(run.conversationId, runId) { it.copy(outcome = text, outcomeSystemType = null) } }
                        .onFailure { Log.w(TAG, "could not save stop summary", it) }
                }
            } finally {
                taskState._summarizing.update { it - runId }
            }
        }
    }

    /** 失败通知仍只给短原因;对话里补上已确认的进度,下一次「继续」不会把它当成没做过。 */
    private fun summarizeFailure(run: TaskRun, runId: String, reason: String, store: ConversationStore) {
        MemoryRuntime.of(this).scope.launch {
            val text = runCatching { run.checkpointSummary() }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "failure summary failed", it) }.getOrNull()
            if (text != null) withContext(Dispatchers.IO) {
                runCatching { store.updateTurn(run.conversationId, runId) { it.copy(outcome = reason + "\n" + text) } }
                    .onFailure { Log.w(TAG, "could not save failure summary", it) }
            }
        }
    }

    /** 任务收尾之后:都结束了就退出前台;还有别的任务就把前台交给它们,并放出排队的任务。 */
    private fun onRunEnded(run: TaskRun, store: ConversationStore) {
        val wasShown = taskState.shownRun === run
        val started = center.onEnded(run)
        // 收尾时替接班的拿着的屏幕锁交给第一个放出来的;没有放出来的(排着的被取消、同一对话还有任务在跑)就放开
        run.wakeHandoff?.let { lease ->
            run.wakeHandoff = null
            started.firstOrNull()?.let { it.wakeHandoff = lease } ?: lease.release()
        }
        publishRuns()
        if (wasShown) started.firstOrNull { it.conversationId == run.conversationId }?.show()
        // 对话空出来就开始留给它的下一件,不等别的对话的任务
        val ready = center.takeReady(run.conversationId)
        if (center.runs().isEmpty()) {
            if (ready.isNotEmpty()) {
                ready.forEach(::startFollowUp)
                return
            }
            // 还留着一页在后台运行:前台通知换成它的,服务不退(进程靠它撑着);关掉时由 [onKeptPage] 退出
            val kept = keeper.page.value
            if (kept != null) {
                synchronized(notifyLock) {
                    startForeground(ID_KEPT, keptNotification(kept))
                    getSystemService(NotificationManager::class.java).cancel(id(ID_PROGRESS, run))
                }
                foreground = null
                taskState._busy.value = false
                return
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            // 先取编号再放开:放开之后才到的新任务会带来更新的编号,这里的 stopSelf 就不会把它停掉
            val id = lastStartId
            taskState._busy.value = false
            stopSelf(id)
            return
        }
        // 先换成「正在准备」:放出来的任务可能正好接过前台,通知不能还写着排队
        started.forEach { it.dequeue() }
        if (foreground === run) center.running().firstOrNull()?.let { next ->
            foreground = next
            startInForeground(next, progressNotification(next, next.state.value))
        }
        synchronized(notifyLock) { getSystemService(NotificationManager::class.java).cancel(id(ID_PROGRESS, run)) }
        started.forEach {
            launch(it, store)
            refreshNotifications(it)
        }
        center.queued().forEachIndexed { i, q -> q.queue(i + 1) }
        center.running().forEach(::refreshNotifications)
        ready.forEach(::startFollowUp)
    }

    /** 排队中的任务被取消(已移出队伍):没开跑,直接记为已取消;排在它后面的下一件接着取。 */
    private fun cancelQueued(run: TaskRun) {
        run.handoffUnconsumed(cancelled = true)
        run.mcpExternalId?.let { (application as com.wangzan101.mika.MikaApp).remoteTasks.ended(it, TaskState.Cancelled, run.steers.value, run.runId.value, run.sharedFiles.value) }
        run.end(TaskState.Cancelled)
        recordTurn(ConversationStore.shared(this), run, TaskState.Cancelled, null)
        publishRuns()
        center.queued().forEachIndexed { i, q -> q.queue(i + 1) }
        drainReady(run.conversationId)
    }

    /**
     * 留着的那一页变了:还留着就发(或换)它的常驻通知——没有任务在跑时它就是前台通知;关掉了就撤掉通知,
     * 这时没有任务就退出前台、停掉服务。
     */
    private fun onKeptPage(page: com.wangzan101.mika.cap.web.KeptPage?) {
        val nm = getSystemService(NotificationManager::class.java)
        synchronized(notifyLock) {
            val idle = center.runs().isEmpty()
            if (page != null) {
                if (idle && foreground == null) startForeground(ID_KEPT, keptNotification(page)) else nm.notify(ID_KEPT, keptNotification(page))
                return
            }
            nm.cancel(ID_KEPT)
            if (!idle || taskState._busy.value) return
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(lastStartId)
        }
    }

    /** 留在后台的那一页的常驻通知:站点、离自动关还剩多久(倒计时),按钮「关闭网页」「打开对话」。 */
    private fun keptNotification(page: com.wangzan101.mika.cap.web.KeptPage): Notification {
        val close = PendingIntent.getService(
            this, REQUEST_CLOSE_KEPT, Intent(this, TaskService::class.java).setAction(ACTION_CLOSE_KEPT_PAGE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.kept_notif_title))
            .setContentText(page.host)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // 时间那里倒计时到自动关的那一刻(通知文字不会过时)
            .setWhen(page.closesAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            .setContentIntent(openApp(page.conversationId))
            .addAction(Notification.Action.Builder(null, tr(R.string.kept_close), close).build())
            .addAction(Notification.Action.Builder(null, tr(R.string.kept_open_chat), openApp(page.conversationId)).build())
            .build()
    }

    /**
     * 给模型的上文里,留页那一轮的页面此刻是不是还开着:记着「留着」却已经不在了(Mika 中途退出过)的,按已关算。
     */
    private fun withKeptState(t: Turn): Turn =
        if (t.keptPage != null && t.keptPageClosed == null && keeper.page.value?.runId != t.runId) t.copy(keptPageClosed = KeptPageClose.AppExit.wire) else t

    /** 任务结束时记一轮,后续消息可带上它作参考。写盘失败不影响任务结果。 */
    private fun recordTurn(store: ConversationStore, run: TaskRun, end: TaskState, runId: String?, files: List<SharedFile> = emptyList(),
        steers: List<String> = emptyList(), groups: List<String> = emptyList(), interrupted: List<String> = emptyList(),
        finished: List<String> = emptyList()) {
        val goal = run.triggerCondition ?: run.goal
        val turn = when (end) {
            // 没核对过的(聊天、回答里没有可核对的值)和核实过的一样记成 succeeded;核对过却没能核实的才记 unverified
            is TaskState.Succeeded -> if (end.doubtful) Turn(goal, end.answer, "unverified", end.note, unconfirmed = end.unconfirmed, verified = false)
                else Turn(goal, end.answer, "succeeded", verified = end.verified.takeIf { end.reviewed })
            is TaskState.Failed -> Turn(goal, end.reason, "failed")
            TaskState.Cancelled -> Turn(goal, Conversation.CANCELLED_OUTCOME, "cancelled", outcomeSystemType = "task_cancelled")
            else -> return
        }.copy(runId = runId, remoteTaskId = run.mcpExternalId, steers = steers, groups = groups, interruptedReplies = interrupted, finishedReplies = finished)
            // 这一轮留下了一页:记下站点(已经关了的连同原因),对话页那一轮下面据此显示
            .let { t -> keeper.recordFor(runId)?.let { (host, closed) -> t.copy(keptPage = host, keptPageClosed = closed) } ?: t }
        runCatching { store.append(run.conversationId, turn, files) }.onFailure { Log.w(TAG, "could not save conversation turn", it) }
    }

    /**
     * 按此刻的状态重画通知。多个协程都会调用,串行并在锁内取最新状态:
     * 否则拿着旧状态的一方可能在新状态撤掉通知之后又把它发出来。
     */
    internal fun refreshNotifications(run: TaskRun) {
        synchronized(notifyLock) {
            val shown = run.shownState() ?: return
            if (!shown.isActive() || center.isQueued(run)) return
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(id(ID_PROGRESS, run), progressNotification(run, shown))
            val attention = attentionNotification(run, shown)
            if (attention != null) nm.notify(id(ID_ATTENTION, run), attention) else nm.cancel(id(ID_ATTENTION, run))
        }
    }

    /** 已交还、循环还没往下走的那一小段:不再显示接管按钮。 */
    private fun awaitingUser(run: TaskRun, state: TaskState) =
        state !is TaskState.WaitingTakeover || run.takeoverStage.value != Takeover.Stage.None

    /** 主屏一次只交一个 App:接管排在别的任务后面时先不给接管按钮。 */
    private fun takeoverFirst(run: TaskRun) = run.takeover?.let(takeoverQueue::isFirst) ?: true

    /** 用 [run] 的进度通知编号进前台;还没有任务对象时用第一个槽位的。 */
    private fun startInForeground(run: TaskRun?, n: Notification) {
        val nid = run?.let { id(ID_PROGRESS, it) } ?: ID_PROGRESS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(nid, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(nid, n)
        }
    }

    private fun progressNotification(run: TaskRun, state: TaskState): Notification {
        val (_, text) = describe(state) { ConfirmationNotifier.appLabel(this, it) }
        val warning = run.muteFailedFor?.let { "\n" + tr(R.string.task_may_make_sound) }.orEmpty() +
            (if (state is TaskState.WaitingTakeover && !takeoverFirst(run)) "\n$TAKEOVER_QUEUED" else "")
        val b = Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.task_notif_title, ScreenCopy.plain(run.triggerCondition ?: run.goal)))
            .setContentText(text + warning)
            .setStyle(Notification.BigTextStyle().bigText(text + warning))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
        val active = center.running().size
        if (active >= 2) b.setSubText(trPlural(R.plurals.task_active, active))
        when (state) {
            is TaskState.WaitingTakeover ->
                if (awaitingUser(run, state) && takeoverFirst(run)) takeoverActions(run).forEach(b::addAction)
                else b.addAction(action(run, tr(R.string.chat_stop), ACTION_CANCEL, 2))
            is TaskState.WaitingInput -> {
                b.addAction(replyAction(run, state.options))
                b.addAction(action(run, tr(R.string.chat_stop), ACTION_CANCEL, 2))
            }
            else -> {
                if (state is TaskState.Held && state.reason.resumable) b.addAction(action(run, tr(R.string.chat_continue), ACTION_RESUME, 1))
                b.addAction(action(run, tr(R.string.chat_stop), ACTION_CANCEL, 2))
            }
        }
        return b.build()
    }

    /** 忙着却还没有任务对象时(收尾被打断等)也得马上进前台。 */
    private fun preparingNotification(): Notification = Notification.Builder(this, CHANNEL_PROGRESS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Mika")
        .setContentText(PREPARING)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp())
        .build()

    private fun answerTooLate() = Handler(Looper.getMainLooper()).post {
        Toast.makeText(this, getString(R.string.input_too_late, INPUT_TIMEOUT_MINUTES), Toast.LENGTH_LONG).show()
    }

    /**
     * 等待接管或回答时另发的醒目通知;其余状态没有。
     * 悬浮胶囊正显示在亮着的屏幕上时照发,但安静地进通知栏(胶囊已经变橙,见 [alertQuietly]);锁屏、胶囊关着或画不出来时照旧弹出。
     */
    private fun attentionNotification(run: TaskRun, state: TaskState): Notification? {
        if (!awaitingUser(run, state)) return null
        val quiet = (application as com.wangzan101.mika.MikaApp).capsule.showing.value
        if (state is TaskState.WaitingConfirmation) {
            return ConfirmationNotifier.build(
                this, CHANNEL_ATTENTION, state, TaskService::class.java, ACTION_APPROVE, ACTION_DECLINE, openApp(),
                codeOffset = SLOT_STRIDE * run.slot, runExtra = EXTRA_RUN to run.key, quiet = quiet,
            )
        }
        if (state is TaskState.WaitingTakeover && !takeoverFirst(run)) return null
        val (title, text) = when (state) {
            is TaskState.WaitingTakeover -> {
                val extra = when (run.takeover?.problem?.value) {
                    Takeover.Problem.MoveToMainFailed -> getString(R.string.takeover_move_to_main_failed)
                    Takeover.Problem.WebBusy -> getString(R.string.takeover_web_busy)
                    Takeover.Problem.MoveBackFailed -> getString(R.string.takeover_move_back_failed)
                    null -> if (run.takeoverStage.value == Takeover.Stage.HandedOver) getString(R.string.takeover_handed_over_hint) else null
                }
                val payment = StatusCopy.paymentLines(state.item, state.amount, state.address)
                val behind = run.takeover?.let(takeoverQueue::behind) ?: 0
                val waiting = if (behind > 0) trPlural(R.plurals.task_behind, behind) else null
                takeoverTitle(state.reason) to listOfNotNull(state.message, payment, extra, waiting).joinToString("\n")
            }
            is TaskState.WaitingInput -> getString(R.string.input_title) to
                "${state.prompt}\n${getString(R.string.no_answer_note, INPUT_TIMEOUT_MINUTES)}"
            else -> return null
        }
        val b = Notification.Builder(this, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true) // 同一次等待里换按钮不再响铃;离开等待时撤掉,下一次会重新提醒
            .setContentIntent(openApp())
        if (quiet) b.alertQuietly()
        when (state) {
            is TaskState.WaitingTakeover -> takeoverActions(run).forEach(b::addAction)
            is TaskState.WaitingInput -> {
                b.addAction(replyAction(run, state.options))
                // 安卓通知最多显示 3 个按钮,所以是「回复」+ 前两个选项;
                // 全部选项都放进回复的快捷选项(RemoteInput choices),多出的选项在那里能选到
                state.options.orEmpty().take(2).forEachIndexed { i, o -> b.addAction(answerAction(run, o, i)) }
            }
            else -> Unit
        }
        return b.build()
    }

    private fun takeoverTitle(reason: String): String {
        val label = when (reason) {
            "captcha" -> R.string.takeover_reason_captcha
            "login" -> R.string.takeover_reason_login
            "access_blocked" -> R.string.takeover_reason_access_blocked
            "payment" -> R.string.takeover_reason_payment
            "decision" -> R.string.takeover_reason_decision
            else -> return getString(R.string.takeover_title)
        }
        return getString(R.string.takeover_title_reason, getString(label))
    }

    private fun takeoverActions(run: TaskRun): List<Notification.Action> {
        val cancel = action(run, getString(R.string.action_cancel_task), ACTION_CANCEL, 2)
        return if (run.takeoverStage.value == Takeover.Stage.HandedOver) listOf(
            takeoverAction(run, getString(R.string.action_hand_back), ACTION_HAND_BACK, 4),
            takeoverAction(run, getString(R.string.action_finished_myself), ACTION_FINISHED_MYSELF, 5),
            cancel,
        ) else listOf(
            takeoverAction(run, getString(R.string.action_take_over), ACTION_TAKE_OVER, 3),
            takeoverAction(run, getString(R.string.action_skip), ACTION_SKIP, 7),
            cancel,
        )
    }

    /** 经由不显示界面的 Activity:从通知启动 Activity 才会收起通知栏。 */
    private fun takeoverAction(run: TaskRun, title: String, action: String, code: Int): Notification.Action {
        val pi = PendingIntent.getActivity(
            this, code + SLOT_STRIDE * run.slot, Intent(this, TakeoverActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_RUN, run.key)
                .apply { run.takeover?.let { putExtra(EXTRA_WAIT_KEY, it.waitKey) } },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, title, pi).build()
    }

    private fun replyAction(run: TaskRun, options: List<String>?): Notification.Action {
        val input = RemoteInput.Builder(KEY_REPLY).setLabel(getString(R.string.reply_hint))
            .apply { if (!options.isNullOrEmpty()) setChoices(options.toTypedArray()) }
            .build()
        // 回复内容要由系统填进 Intent,只能用可变的 PendingIntent(Intent 是显式的)
        val pi = PendingIntent.getService(
            this, 6 + SLOT_STRIDE * run.slot, Intent(this, TaskService::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_RUN, run.key),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, getString(R.string.action_reply), pi).addRemoteInput(input).build()
    }

    private fun answerAction(run: TaskRun, option: String, index: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, 10 + index + SLOT_STRIDE * run.slot,
            Intent(this, TaskService::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_ANSWER, option).putExtra(EXTRA_RUN, run.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, option, pi).build()
    }

    private suspend fun prepareResult(run: TaskRun, end: TaskState, deliveryId: String) {
        val (title, text) = describe(end)
        val peer = run.channelPeer ?: ChannelPeer(LocalChannel.ID, "phone-owner", run.conversationId)
        ResultDelivery.prepare(outbox, channelFor(peer, run.triggerWindowKey != null), peer,
            Delivery.Result(deliveryId, run.conversationId, text, title = title), run.sharedFiles.value)
    }

    private suspend fun notifyResult(run: TaskRun, end: TaskState, deliveryId: String): DeliveryState {
        prepareResult(run, end, deliveryId)
        val (title, text) = describe(end)
        val peer = run.channelPeer ?: ChannelPeer(LocalChannel.ID, "phone-owner", run.conversationId)
        val started = SystemClock.elapsedRealtime()
        val delivered = ResultDelivery.send(outbox, channelFor(peer, run.triggerWindowKey != null), peer,
            Delivery.Result(deliveryId, run.conversationId, text, title = title))
        val finished = SystemClock.elapsedRealtime()
        val sinceReady = if (run.resultReadyAtMs > 0) finished - run.resultReadyAtMs else -1
        Log.i(TAG, "result $delivered: delivery ${finished - started} ms, since ready $sinceReady ms")
        return delivered
    }

    private fun action(run: TaskRun, title: String, action: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, code + SLOT_STRIDE * run.slot, Intent(this, TaskService::class.java).setAction(action).putExtra(EXTRA_RUN, run.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, title, pi).build()
    }

    private fun openApp(threadId: String? = null) = openApp(this, threadId)

    override fun onDestroy() {
        scope.cancel()
        // 服务没了就撑不住进程:留着的那一页如实关掉,对话里记下
        keeper.close(KeptPageClose.AppExit)
        getSystemService(NotificationManager::class.java).cancel(ID_KEPT)
        if (center.runs().isNotEmpty()) {
            // 标中断后当场善后(定时窗口、补送、告知、写进对话):进程还在,不等下次启动;用 App 级作用域,服务没了也做完
            center.store.interruptUnfinished()
            val app = application as com.wangzan101.mika.MikaApp
            center.runs().forEach { r -> r.mcpExternalId?.let { app.remoteTasks.ended(it, TaskState.Failed(tr(R.string.task_interrupted), FailKind.Interrupted), r.steers.value, r.runId.value, r.sharedFiles.value) } }
            (application as com.wangzan101.mika.MikaApp).runRecovery.settleInterrupted()
            taskState._state.value = TaskState.Failed(tr(R.string.task_interrupted), FailKind.Interrupted)
            taskState._runs.value = emptyList()
            taskState._activeConversations.value = emptyList()
            taskState.shownRun = null
        }
        taskState._busy.value = false // 收尾被取消时也不能一直挡着新任务
        super.onDestroy()
    }

    companion object {
        /** 建通知频道;重复建没有副作用。App 启动时的恢复也要用,所以不依赖服务实例。 */
        internal fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val names = mapOf(CHANNEL_PROGRESS to tr(R.string.channel_progress), CHANNEL_RESULT to tr(R.string.channel_result), CHANNEL_ATTENTION to tr(R.string.channel_attention))
            // 旧的结果频道建成了较低级别,App 调不高;换新 id,旧的删掉免得设置里多一项
            nm.deleteNotificationChannel(OLD_CHANNEL_RESULT)
            CHANNEL_IMPORTANCE.forEach { (id, importance) -> nm.createNotificationChannel(NotificationChannel(id, names.getValue(id), importance)) }
        }

        internal fun openApp(context: Context, threadId: String? = null): PendingIntent = PendingIntent.getActivity(
            context, threadId?.hashCode() ?: 0, openAppIntent(context, threadId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** 打开 Mika;给了 [threadId] 时直接到那个对话。通知和悬浮胶囊共用。 */
        internal fun openAppIntent(context: Context, threadId: String? = null): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (threadId != null) putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, threadId) }

        /** 并行时结果通知的下一个编号(循环使用)。 */
        internal fun nextResultId() = ID_RESULT_FIRST + resultCount.getAndIncrement() % RESULT_IDS

        /** 结果与提示发成通知(服务里的与 App 启动时恢复用的共用);[resultId] 是结果通知的编号。 */
        internal fun notifyDelivery(context: Context, delivery: Delivery, resultId: Int, scheduled: Boolean = false): SendOutcome {
            val nm = context.getSystemService(NotificationManager::class.java)
            when (delivery) {
                is Delivery.Result -> nm.notify(resultId, Notification.Builder(context, CHANNEL_RESULT)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(delivery.title)
                    .setContentText(delivery.text)
                    .setStyle(Notification.BigTextStyle().bigText(delivery.text))
                    .setContentIntent(openApp(context, delivery.threadId))
                    .setAutoCancel(true)
                    .apply { if (scheduled) applyScheduledQuietHours(context) }
                    .build())
                is Delivery.Notice -> nm.notify(nextResultId(), Notification.Builder(context, CHANNEL_RESULT)
                    .setSmallIcon(R.drawable.ic_notification).setContentTitle("Mika")
                    .setContentText(delivery.text).setContentIntent(openApp(context, delivery.threadId)).setAutoCancel(true)
                    .apply { if (scheduled) applyScheduledQuietHours(context) }
                    .build())
                is Delivery.Ask, is Delivery.Progress, is Delivery.Image -> {}
            }
            return SendOutcome.Delivered
        }

        /** 追问等回答的时限:交给 AgentLoop,通知和卡片上写的分钟数也从这里来。 */
        const val INPUT_TIMEOUT_MS = AgentLoop.DEFAULT_INPUT_TIMEOUT_MS
        const val INPUT_TIMEOUT_MINUTES = INPUT_TIMEOUT_MS / 60_000
        const val ACTION_START = "com.wangzan101.mika.task.START"
        const val ACTION_ENQUEUE_FOLLOWUP = "com.wangzan101.mika.task.ENQUEUE_FOLLOWUP"
        const val ACTION_DRAIN_FOLLOWUPS = "com.wangzan101.mika.task.DRAIN_FOLLOWUPS"
        const val ACTION_START_TRIGGER = "com.wangzan101.mika.task.START_TRIGGER"
        const val ACTION_START_MCP = "com.wangzan101.mika.task.START_MCP"
        const val ACTION_START_CHANNEL = "com.wangzan101.mika.task.START_CHANNEL"
        const val ACTION_CANCEL_MCP = "com.wangzan101.mika.task.CANCEL_MCP"
        const val ACTION_CANCEL = "com.wangzan101.mika.task.CANCEL"
        const val ACTION_RESUME = "com.wangzan101.mika.task.RESUME"
        const val ACTION_TAKE_OVER = "com.wangzan101.mika.task.TAKE_OVER"
        const val ACTION_HAND_BACK = "com.wangzan101.mika.task.HAND_BACK"
        const val ACTION_FINISHED_MYSELF = "com.wangzan101.mika.task.FINISHED_MYSELF"
        const val ACTION_SKIP = "com.wangzan101.mika.task.SKIP"
        /** 网页接管界面按了系统返回键:先放着,回到等待接管。 */
        const val ACTION_PUT_ASIDE = "com.wangzan101.mika.task.PUT_ASIDE"
        /** 接管通知按钮带的「第几次等待」,见 [Takeover.waitKey]。 */
        const val EXTRA_WAIT_KEY = "com.wangzan101.mika.task.WAIT_KEY"
        const val ACTION_ANSWER = "com.wangzan101.mika.task.ANSWER"
        const val ACTION_APPROVE = "com.wangzan101.mika.task.APPROVE"
        /** 关掉任务结束后留在后台的那一页(通知上的按钮)。 */
        const val ACTION_CLOSE_KEPT_PAGE = "com.wangzan101.mika.task.CLOSE_KEPT_PAGE"
        const val ACTION_DECLINE = "com.wangzan101.mika.task.DECLINE"
        private const val EXTRA_GOAL = "goal"
        private const val EXTRA_MESSAGE_ID = "message_id"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_CONVERSATION = "conversation"
        private const val EXTRA_TRIGGER_KEY = "trigger_key"
        private const val EXTRA_TRIGGER_ACTION = "trigger_action"
        private const val EXTRA_TRIGGER_THREAD = "trigger_thread"
        private const val EXTRA_TRIGGER_CONDITION = "trigger_condition"
        private const val EXTRA_MCP_ID = "mcp_id"
        private const val EXTRA_ATTENDANCE = "attendance"
        private const val EXTRA_CHANNEL_ID = "channel_id"
        private const val EXTRA_PEER_ID = "peer_id"
        private const val EXTRA_CHANNEL_MESSAGE_ID = "channel_message_id"
        private const val EXTRA_ANSWER = "answer"
        /** 通知按钮所属任务的编号([TaskRun.key])。 */
        const val EXTRA_RUN = "com.wangzan101.mika.task.RUN"
        /** 等用户回答确认最多这么久,到时按不同意(不在手机旁时任务不会一直挂着,也绝不自动同意)。 */
        const val CONFIRM_TIMEOUT_MS = 15 * 60_000L
        private const val KEY_REPLY = "reply"
        const val CHANNEL_ATTENTION = "task_attention"
        const val CHANNEL_PROGRESS = "task_progress"
        const val CHANNEL_RESULT = "task_result_v2"
        internal const val OLD_CHANNEL_RESULT = "task_result"
        /** 各通知频道建立时的级别;「需要你」(接管、提问、确认)和结果要弹出,进度保持安静。用户之后调低的,App 改不回来。 */
        internal val CHANNEL_IMPORTANCE = mapOf(
            CHANNEL_PROGRESS to NotificationManager.IMPORTANCE_LOW,
            CHANNEL_RESULT to NotificationManager.IMPORTANCE_HIGH,
            CHANNEL_ATTENTION to NotificationManager.IMPORTANCE_HIGH,
        )
        private const val ID_PROGRESS = 1
        internal const val ID_RESULT = 2
        private const val ID_ATTENTION = 3
        /** 留在后台的那一页的常驻通知(全局只有一页,不分槽位)。 */
        private const val ID_KEPT = 4
        private const val REQUEST_CLOSE_KEPT = 99
        /** 第 n 个槽位的任务通知编号与请求码都加 n × 它;第一个槽位与只跑一个任务时一样。 */
        private const val SLOT_STRIDE = 100
        /** 并行时结果通知的编号段,在槽位编号之外;循环使用,最多同时留这么多条。 */
        private const val ID_RESULT_FIRST = 10_000
        private const val RESULT_IDS = 1_000
        private val resultCount = AtomicInteger()
        private val TAKEOVER_QUEUED: String get() = tr(R.string.task_takeover_queued)
        private const val TAG = "MikaTask"
        val TOO_OLD: String get() = tr(R.string.task_too_old)
        internal val PREPARING: String get() = tr(R.string.runtime_preparing)
        val SHELL_DIED: String get() = tr(R.string.task_shell_died)

        /** 循环的状态里哪些可以由发布写出去。 */
        internal fun loopMayPublish(state: TaskState) = state.isActive()

        /** 开始一个任务;别的对话的任务在跑而不能同时跑时排在它后面。[conversationId] 为空表示新开一个对话(编号见 [conversationId])。 */
        /** [messageId] 给了时这是任务进行中插的话,界面按它跟这句话的去向。 */
        fun start(context: Context, goal: String, conversationId: String? = null, messageId: String? = null) {
            if (FeatureFlags.get(context).quitRequested()) return
            context.startForegroundService(
                Intent(context, TaskService::class.java).setAction(ACTION_START).putExtra(EXTRA_GOAL, goal)
                    .apply { if (conversationId != null) putExtra(EXTRA_CONVERSATION, conversationId) }
                    .apply { if (messageId != null) putExtra(EXTRA_MESSAGE_ID, messageId) },
            )
        }

        /**
         * 用户明确选了「排队」:先在调用方把原话写进持久账本,再让服务认领。
         * 后台起服务被系统挡下时排队项也已经安全保存,下次打开对话再跑。
         * [startIfIdle] 为假只落盘:原话任务还在等前一个不相关任务收尾,不能抢在它前面开始。
         */
        fun enqueueFollowUp(context: Context, conversationId: String, messageId: String, text: String,
            source: QueuedSource = QueuedSource.Explicit, startIfIdle: Boolean = true): Boolean {
            if (text.isBlank() || FeatureFlags.get(context).quitRequested()) return false
            val app = context.applicationContext as? com.wangzan101.mika.MikaApp ?: return false
            val item = QueuedFollowUp(messageId, conversationId, text, System.currentTimeMillis(), source)
            if (!app.followUpQueue.add(item)) return false
            if (startIfIdle) runCatching {
                context.startService(
                    Intent(context, TaskService::class.java).setAction(ACTION_ENQUEUE_FOLLOWUP)
                        .putExtra(EXTRA_GOAL, text).putExtra(EXTRA_CONVERSATION, conversationId)
                        .putExtra(EXTRA_MESSAGE_ID, messageId).putExtra(EXTRA_SOURCE, source.name),
                )
            }
            return true
        }

        /** 打开对话时:这个对话还有排队的下一件、又没有任务在跑,就让服务取一件开始。 */
        fun drainFollowUps(context: Context, conversationId: String) {
            if (FeatureFlags.get(context).quitRequested()) return
            val app = context.applicationContext as? com.wangzan101.mika.MikaApp ?: return
            if (!app.followUpQueue.contains(conversationId)) return
            runCatching {
                context.startService(
                    Intent(context, TaskService::class.java).setAction(ACTION_DRAIN_FOLLOWUPS)
                        .putExtra(EXTRA_CONVERSATION, conversationId),
                )
            }
        }

        /** [checkGoal] 是只读检查目标,[condition] 是给人看的用户条件原文;动作仍使用 [goal]。 */
        fun startTrigger(context: Context, goal: String, threadId: String, windowKey: String,
            checkGoal: String? = null, condition: String? = null) {
            if (FeatureFlags.get(context).quitRequested()) return
            val checking = checkGoal != null
            context.startForegroundService(Intent(context, TaskService::class.java).setAction(ACTION_START_TRIGGER)
                .putExtra(EXTRA_GOAL, checkGoal ?: goal).putExtra(EXTRA_CONVERSATION, threadId)
                .putExtra(EXTRA_TRIGGER_KEY, windowKey).putExtra(EXTRA_TRIGGER_THREAD, threadId)
                .apply { if (checking) putExtra(EXTRA_TRIGGER_ACTION, goal).putExtra(EXTRA_TRIGGER_CONDITION, condition) })
        }

        /** 渠道交来的任务保留回复地址；消息编号由登记处持久化去重。 */
        fun startChannel(context: Context, peer: ChannelPeer, messageId: String, goal: String) {
            if (FeatureFlags.get(context).quitRequested()) return
            context.startForegroundService(Intent(context, TaskService::class.java).setAction(ACTION_START_CHANNEL)
                .putExtra(EXTRA_GOAL, goal).putExtra(EXTRA_CONVERSATION, peer.threadId)
                .putExtra(EXTRA_CHANNEL_ID, peer.channelId).putExtra(EXTRA_PEER_ID, peer.peerId)
                .putExtra(EXTRA_CHANNEL_MESSAGE_ID, messageId))
        }

        /** 电脑交来的任务([RemoteTaskHub]):记在 [conversation] 这个对话里,来源记成 MCP。 */
        fun startMcp(context: Context, goal: String, conversation: String, externalId: String, attendance: Attendance) {
            if (FeatureFlags.get(context).quitRequested()) return
            context.startForegroundService(Intent(context, TaskService::class.java).setAction(ACTION_START_MCP)
                .putExtra(EXTRA_GOAL, goal).putExtra(EXTRA_CONVERSATION, conversation)
                .putExtra(EXTRA_MCP_ID, externalId).putExtra(EXTRA_ATTENDANCE, attendance.name))
        }

        fun cancelMcp(context: Context, externalId: String) {
            val state = (context.applicationContext as com.wangzan101.mika.MikaApp).taskState
            if (state.runs.value.none { it.mcpExternalId == externalId }) return
            context.startService(Intent(context, TaskService::class.java).setAction(ACTION_CANCEL_MCP)
                .putExtra(EXTRA_MCP_ID, externalId))
        }

        /** [runKey] 见 [send];应用内的按钮带着屏上那个对话的任务编号,不会落到别的任务上。 */
        fun cancel(context: Context, runKey: String? = null) = send(context, ACTION_CANCEL, runKey = runKey)

        /** 用户点了「继续」:被挪走的 App 会被挪回 Mika 的屏幕。 */
        fun resume(context: Context, runKey: String? = null) = send(context, ACTION_RESUME, runKey = runKey)

        /** 「我来处理」:把 App 挪到主屏并恢复它的声音。 */
        fun takeOver(context: Context, waitKey: Int? = null, runKey: String? = null) = send(context, ACTION_TAKE_OVER, waitKey, runKey)

        /** 「处理好了」:挪回虚拟屏、重新静音,Agent 先读屏核实再继续。 */
        fun handBack(context: Context, waitKey: Int? = null, runKey: String? = null) = send(context, ACTION_HAND_BACK, waitKey, runKey)

        /** 「我自己做完了」:挪回虚拟屏,Agent 只核实结果并结束。 */
        fun finishedMyself(context: Context, waitKey: Int? = null, runKey: String? = null) =
            send(context, ACTION_FINISHED_MYSELF, waitKey, runKey)

        /** 「跳过」:不处理这一步,Agent 收到「没有发生交回」后自己换办法或如实汇报。 */
        fun skip(context: Context, waitKey: Int? = null, runKey: String? = null) = send(context, ACTION_SKIP, waitKey, runKey)

        fun putAside(context: Context, waitKey: Int? = null, runKey: String? = null) = send(context, ACTION_PUT_ASIDE, waitKey, runKey)

        /** 回答 Agent 的提问(ask_user)。 */
        fun answer(context: Context, text: String, runKey: String? = null) {
            if (!stateFor(context, runKey).isActive()) return
            context.startService(
                Intent(context, TaskService::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_ANSWER, text)
                    .apply { if (runKey != null) putExtra(EXTRA_RUN, runKey) },
            )
        }

        /** 同意编号为 [key] 的那次申报([ConfirmationNotifier.requestKey]);已经换成别的申报时不算数。 */
        fun approve(context: Context, key: Int, runKey: String? = null) = confirm(context, ACTION_APPROVE, key, runKey)

        /** 不同意:Agent 不再尝试这个操作,汇报现状后结束。 */
        fun decline(context: Context, key: Int, runKey: String? = null) = confirm(context, ACTION_DECLINE, key, runKey)

        private fun confirm(context: Context, action: String, key: Int, runKey: String?) {
            if (!stateFor(context, runKey).isActive()) return
            context.startService(
                Intent(context, TaskService::class.java).setAction(action).putExtra(ConfirmationNotifier.EXTRA_REQUEST, key)
                    .apply { if (runKey != null) putExtra(EXTRA_RUN, runKey) },
            )
        }

        /** 该任务此刻的状态;任务已不在时为 Idle(动作作废)。[runKey] 为空时是显示中的任务。 */
        private fun stateFor(context: Context, runKey: String?): TaskState {
            val state = (context.applicationContext as com.wangzan101.mika.MikaApp).taskState
            return if (runKey == null) state.state.value else state.runs.value.firstOrNull { it.key == runKey }?.state?.value ?: TaskState.Idle
        }

        /** [runKey] 为空时作用于显示中的任务,否则只作用于该任务。 */
        private fun send(context: Context, action: String, waitKey: Int? = null, runKey: String? = null) {
            if (!stateFor(context, runKey).isActive()) return
            context.startService(
                Intent(context, TaskService::class.java).setAction(action).apply {
                    if (waitKey != null) putExtra(EXTRA_WAIT_KEY, waitKey)
                    if (runKey != null) putExtra(EXTRA_RUN, runKey)
                },
            )
        }

        internal fun TaskState.isActive() = when (this) {
            TaskState.Idle, is TaskState.Succeeded, is TaskState.Failed, TaskState.Cancelled -> false
            else -> true
        }

        /** 通知的标题和正文;[appLabel] 把包名换成 App 名。 */
        internal fun describe(state: TaskState, appLabel: (String) -> String = { it }): Pair<String, String> = when (state) {
            TaskState.Idle -> "Mika" to ""
            is TaskState.Running -> tr(R.string.task_running) to (state.progress?.let(StatusCopy::progress) ?: when {
                state.step == 0 -> state.note
                else -> tr(R.string.task_step_note, state.step, state.note).trimEnd(':', ' ')
            })
            is TaskState.Held -> when {
                state.reason == TaskGate.LOCKED -> tr(R.string.task_wait_unlock) to tr(R.string.task_wait_unlock_body)
                state.reason.queued -> tr(R.string.chat_queued) to state.reason.why
                state.reason.resumable -> tr(R.string.chat_paused) to state.reason.why
                else -> tr(R.string.chat_waiting_for_you) to state.reason.why
            }
            is TaskState.WaitingTakeover -> tr(R.string.takeover_title) to state.message
            is TaskState.WaitingInput -> tr(R.string.input_title) to state.prompt
            is TaskState.WaitingConfirmation -> ConfirmationCopy.title(state.request) to ConfirmationCopy.body(state.request, appLabel(state.request.app))
            is TaskState.Succeeded ->
                if (!state.doubtful) tr(R.string.task_done) to state.answer
                else tr(R.string.chat_unverified) to (state.note?.let { tr(R.string.task_reason, it) + "\n" }.orEmpty() + state.answer)
            is TaskState.Failed -> tr(R.string.task_not_done) to state.reason
            TaskState.Cancelled -> tr(R.string.task_stopped) to tr(R.string.task_stopped_body)
        }
    }
}
