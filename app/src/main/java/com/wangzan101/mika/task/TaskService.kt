package com.wangzan101.mika.task

import com.wangzan101.mika.i18n.tr
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.task.ConfirmationCopy
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.SendOutcome
import com.wangzan101.mika.core.run.ChannelInteraction
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.ui.MainActivity
import com.wangzan101.mika.settings.ui.StatusCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
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
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** 最近一次 start 时读到的开关;关着时登记处上限恒为 1。 */
    @Volatile internal var parallel = false
    internal val center by lazy {
        val app = application as com.wangzan101.mika.MikaApp
        val wsOf: (String) -> String = { id -> com.wangzan101.mika.chat.WorkspaceResolver.resolveFor(app, id).wire }
        TaskCenter<TaskRun>(
            ::limit, { it.conversationId }, { it.key }, app.runStore,
            app.resources, app.followUpQueue,
            workspaceOf = { wsOf(it.conversationId) },
            resolveWorkspace = wsOf,
        ) { c, g ->
            TaskRun(c, g, this, scope, freeSlot(), parallel)
        }
    }
    internal val outbox get() = (application as com.wangzan101.mika.MikaApp).outbox
    internal val localChannel by lazy { localDeliveryChannel(scheduled = false) }
    internal val scheduledLocalChannel by lazy { localDeliveryChannel(scheduled = true) }

    /** 前台常驻通知属于哪个任务;收尾后留着,直到换成别的任务。 */
    @Volatile internal var foreground: TaskRun? = null
    internal val notifyLock = Any()
    internal val workflowStarts = AtomicInteger()
    @Volatile internal var lastStartId = 0

    internal val clipLock get() = center.clipLock
    internal val takeoverQueue get() = center.takeoverQueue
    internal fun rememberRecord(run: TaskRun, recordId: String) = center.markRecord(run, recordId)
    /** 几个任务共用的屏幕常亮锁。 */
    internal val wakeLock by lazy { SharedWakeLock(ScreenWakeLock(this)) }

    internal fun reservePackage(pkg: String, runKey: String, stillUsed: () -> Boolean) = center.reserve(pkg, runKey, stillUsed)
    internal fun packageFree(pkg: String, runKey: String, stillUsed: () -> Boolean) = center.appFree(pkg, runKey, stillUsed)
    internal suspend fun awaitPackage(pkg: String, runKey: String, timeoutMs: Long, free: suspend () -> Boolean) =
        center.awaitApp(pkg, runKey, timeoutMs, free)

    internal fun interaction(threadId: String, source: ChannelPeer? = null): ChannelInteraction {
        val peer = source ?: ChannelPeer(LocalChannel.ID, "phone-owner", threadId)
        return ChannelInteraction(outbox, channelFor(peer), peer)
    }

    /** 任务结束后留在后台的那一页(见 [com.wangzan101.mika.cap.web.PageKeeper]):留着时服务不退,前台通知换成它的。 */
    internal val keeper get() = (application as com.wangzan101.mika.MikaApp).pageKeeper

    internal fun otherRunHasDisplay(run: TaskRun) = center.running().any { it !== run && it.displayId.value >= 0 }
    internal fun hasActiveRuns() = center.running().isNotEmpty()
    internal fun hasQueuedRuns() = center.queued().isNotEmpty()

    // ---------- 任务解锁手机后的自动回锁 ----------

    /** 回锁义务:留到最后一个在跑或排队的任务结束;系统确认已锁之前一直挂着。 */
    internal val taskRelock = TaskRelock()
    internal val relockMutex = Mutex()

    internal val pocketGuard by lazy { com.wangzan101.mika.pocket.PocketGuardManager(this) }

    internal fun publishRuns() {
        val r = center.runs()
        pocketGuard.setTasksActive(center.running().isNotEmpty())
        taskState._runs.value = r
        taskState._activeConversations.value = r.map { it.conversationId }
    }

    internal fun id(base: Int, run: TaskRun) = base + SLOT_STRIDE * run.slot

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

    internal fun waitKeyOf(intent: Intent) = intent.getIntExtra(EXTRA_WAIT_KEY, -1).takeIf { it >= 0 }

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
                messageId = intent.getStringExtra(EXTRA_MESSAGE_ID), brief = intent.getStringExtra(EXTRA_BRIEF))
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
            ACTION_START_WORKFLOW -> {
                workflowStarts.incrementAndGet()
                keepForeground()
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val workflows = (application as com.wangzan101.mika.MikaApp).workflows
                    val attemptId = intent.getStringExtra(EXTRA_WORKFLOW_ATTEMPT).orEmpty()
                    try {
                        val dispatch = withContext(Dispatchers.IO) {
                            workflows.store.claim(attemptId)?.also {
                                ConversationStore.shared(this@TaskService).setTitle(it.conversationId, it.step.title)
                            }
                        }
                        if (dispatch != null) {
                            if (teachingBlocks()) withContext(Dispatchers.IO) { workflows.complete(attemptId, false, "", "start_failed") }
                            else withContext(Dispatchers.Main) {
                                begin(getString(R.string.workflow_step_prompt, dispatch.step.instruction, dispatch.input, dispatch.previous),
                                    dispatch.conversationId, workflowAttemptId = dispatch.attemptId)
                            }
                        }
                    } catch (e: CancellationException) {
                        (application as com.wangzan101.mika.MikaApp).workflowInterrupted(attemptId)
                        throw e
                    } catch (e: Exception) {
                        withContext(Dispatchers.IO) { workflows.complete(attemptId, false, "", "start_failed") }
                    } finally {
                        if (workflowStarts.decrementAndGet() == 0 && !taskState._busy.value && keeper.page.value == null) stopSelf(lastStartId)
                    }
                }
                return START_NOT_STICKY
            }
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
        if (workflowStarts.get() == 0 && !taskState._busy.value && keeper.page.value == null) stopSelf(startId)
        return START_NOT_STICKY
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

    override fun onDestroy() {
        pocketGuard.close()
        scope.cancel()
        // 服务没了就撑不住进程:留着的那一页如实关掉,对话里记下
        keeper.close(KeptPageClose.AppExit)
        getSystemService(NotificationManager::class.java).cancel(ID_KEPT)
        if (center.runs().isNotEmpty()) {
            // 标中断后当场善后(定时窗口、补送、告知、写进对话):进程还在,不等下次启动;用 App 级作用域,服务没了也做完
            center.store.interruptUnfinished()
            val app = application as com.wangzan101.mika.MikaApp
            center.runs().forEach { r -> r.workflowAttemptId?.let(app::workflowInterrupted) }
            center.runs().forEach { r -> r.mcpExternalId?.let { app.remoteTasks.ended(it, TaskState.Failed(tr(R.string.task_interrupted), FailKind.Interrupted), r.steers.value, r.runId.value, r.sharedFiles.value) } }
            (application as com.wangzan101.mika.MikaApp).runRecovery.settleInterrupted()
            taskState._state.value = TaskState.Failed(tr(R.string.task_interrupted), FailKind.Interrupted)
            taskState._runs.value = emptyList()
            taskState._activeConversations.value = emptyList()
            taskState.shownRun = null
        }
        taskState._busy.value = false // 收尾被取消时也不能一直挡着新任务
        runCatching { TriggerRuntime.recoverAfterServiceStop(this) }
            .onFailure { Log.w(TAG, "could not recover trigger windows after service stop", it) }
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
            .apply { if (threadId != null) setIdentifier(threadId).putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, threadId) }

        /** 并行时结果通知的下一个编号(循环使用)。 */
        internal fun nextResultId() = ID_RESULT_FIRST + resultCount.getAndIncrement() % RESULT_IDS

        /**
         * 结果与提示发成通知(服务里的与 App 启动时恢复用的共用);[resultId] 是结果通知的编号。
         * [ownResult] 为 false 时结果不发通知:前台正跟着这次运行,由它汇报(见 [ownResultNotice])。
         */
        internal fun notifyDelivery(context: Context, delivery: Delivery, resultId: Int, scheduled: Boolean = false, ownResult: Boolean = true): SendOutcome {
            val nm = context.getSystemService(NotificationManager::class.java)
            when (delivery) {
                // Mika 在前台(用户正看着聊天页)时不弹任务完成通知,结果只在应用内展示;切到别的 App 才保留通知(#731)。
                is Delivery.Result -> if (!MainActivity.foreground && ownResult) nm.notify(resultId, Notification.Builder(context, CHANNEL_RESULT)
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
        internal const val ACTION_START_WORKFLOW = "com.wangzan101.mika.task.START_WORKFLOW"
        internal const val EXTRA_WORKFLOW_ATTEMPT = "workflow_attempt"
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
        /** 定时任务连着失败到这么多次(含这次)起,结果通知里写明连续失败。 */
        internal const val FAILURE_STREAK_NOTICE = 3
        internal const val EXTRA_GOAL = "goal"
        internal const val EXTRA_MESSAGE_ID = "message_id"
        internal const val EXTRA_BRIEF = "brief"
        internal const val EXTRA_SOURCE = "source"
        internal const val EXTRA_CONVERSATION = "conversation"
        internal const val EXTRA_TRIGGER_KEY = "trigger_key"
        internal const val EXTRA_TRIGGER_ACTION = "trigger_action"
        internal const val EXTRA_TRIGGER_THREAD = "trigger_thread"
        internal const val EXTRA_TRIGGER_CONDITION = "trigger_condition"
        internal const val EXTRA_MCP_ID = "mcp_id"
        internal const val EXTRA_ATTENDANCE = "attendance"
        internal const val EXTRA_CHANNEL_ID = "channel_id"
        internal const val EXTRA_PEER_ID = "peer_id"
        internal const val EXTRA_CHANNEL_MESSAGE_ID = "channel_message_id"
        internal const val EXTRA_ANSWER = "answer"
        /** 通知按钮所属任务的编号([TaskRun.key])。 */
        const val EXTRA_RUN = "com.wangzan101.mika.task.RUN"
        /** 等用户回答确认最多这么久,到时按不同意(不在手机旁时任务不会一直挂着,也绝不自动同意)。 */
        const val CONFIRM_TIMEOUT_MS = 15 * 60_000L
        internal const val KEY_REPLY = "reply"
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
        internal const val ID_PROGRESS = 1
        internal const val ID_RESULT = 2
        internal const val ID_ATTENTION = 3
        /** 留在后台的那一页的常驻通知(全局只有一页,不分槽位)。 */
        internal const val ID_KEPT = 4
        /** 解锁守卫与回锁失败警告共用的编号(全局一个,警告换下守卫)。 */
        internal const val ID_RELOCK = 5
        /** 回锁前等主屏空闲的宽限:等待期里用户动过主屏时,这段没再动才锁。 */
        internal const val RELOCK_IDLE_GRACE_MS = 5_000L
        /** 回锁动作发出后等系统确认的次数与间隔。 */
        internal const val RELOCK_POLL_TRIES = 20
        internal const val RELOCK_POLL_MS = 100L
        internal const val REQUEST_CLOSE_KEPT = 99
        /** 第 n 个槽位的任务通知编号与请求码都加 n × 它;第一个槽位与只跑一个任务时一样。 */
        internal const val SLOT_STRIDE = 100
        /** 并行时结果通知的编号段,在槽位编号之外;循环使用,最多同时留这么多条。 */
        internal const val ID_RESULT_FIRST = 10_000
        internal const val RESULT_IDS = 1_000
        internal val resultCount = AtomicInteger()
        internal val TAKEOVER_QUEUED: String get() = tr(R.string.task_takeover_queued)
        internal const val TAG = "MikaTask"
        val TOO_OLD: String get() = tr(R.string.task_too_old)
        internal val PREPARING: String get() = tr(R.string.runtime_preparing)
        val SHELL_DIED: String get() = tr(R.string.task_shell_died)

        /** 循环的状态里哪些可以由发布写出去。 */
        internal fun loopMayPublish(state: TaskState) = state.isActive()

        /** Starts only the persisted, claimable workflow attempt named by this ID. */
        fun startWorkflow(context: Context, attemptId: String) {
            check(!FeatureFlags.get(context).quitRequested())
            context.startForegroundService(Intent(context, TaskService::class.java).setAction(ACTION_START_WORKFLOW)
                .putExtra(EXTRA_WORKFLOW_ATTEMPT, attemptId))
        }

        /**
         * 开始任务;其他对话忙时排队。messageId 非空时按插话处理。
         * [goal] 是用户的原话;[brief] 是前台派活时附的补充说明,以 Mika 的说明进任务上下文,不当用户原话。
         */
        fun start(context: Context, goal: String, conversationId: String? = null, messageId: String? = null, brief: String? = null) {
            if (FeatureFlags.get(context).quitRequested()) return
            context.startForegroundService(
                Intent(context, TaskService::class.java).setAction(ACTION_START)
                    .apply { startExtras(goal, conversationId, messageId, brief).forEach { (k, v) -> putExtra(k, v) } },
            )
        }

        /** [start] 发出的请求带的几项;没有的不带。 */
        internal fun startExtras(goal: String, conversationId: String?, messageId: String?, brief: String?): List<Pair<String, String>> =
            listOfNotNull(EXTRA_GOAL to goal, conversationId?.let { EXTRA_CONVERSATION to it },
                messageId?.let { EXTRA_MESSAGE_ID to it }, brief?.let { EXTRA_BRIEF to it })

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

        internal fun confirm(context: Context, action: String, key: Int, runKey: String?) {
            if (!stateFor(context, runKey).isActive()) return
            context.startService(
                Intent(context, TaskService::class.java).setAction(action).putExtra(ConfirmationNotifier.EXTRA_REQUEST, key)
                    .apply { if (runKey != null) putExtra(EXTRA_RUN, runKey) },
            )
        }

        /** 该任务此刻的状态;任务已不在时为 Idle(动作作废)。[runKey] 为空时是显示中的任务。 */
        internal fun stateFor(context: Context, runKey: String?): TaskState {
            val state = (context.applicationContext as com.wangzan101.mika.MikaApp).taskState
            return if (runKey == null) state.state.value else state.runs.value.firstOrNull { it.key == runKey }?.state?.value ?: TaskState.Idle
        }

        /** [runKey] 为空时作用于显示中的任务,否则只作用于该任务。 */
        internal fun send(context: Context, action: String, waitKey: Int? = null, runKey: String? = null) {
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

        internal fun resultDelivery(deliveryId: String, threadId: String, end: TaskState, failureStreak: Int?): Delivery.Result {
            val (title, text) = describe(end)
            return if (failureStreak == null) Delivery.Result(deliveryId, threadId, text, title = title)
            else Delivery.Result(deliveryId, threadId, "$text\n${tr(R.string.trigger_failed_streak_hint)}",
                title = tr(R.string.trigger_failed_streak, failureStreak))
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
