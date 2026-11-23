package com.wangzan101.mika.task

import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.wangzan101.mika.R
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.takeover.TakeoverActivity
import com.wangzan101.mika.chat.ui.ScreenCopy
import com.wangzan101.mika.settings.ui.StatusCopy
import kotlinx.coroutines.cancel
import com.wangzan101.mika.task.TaskService.Companion.openApp
import com.wangzan101.mika.task.TaskService.Companion.INPUT_TIMEOUT_MINUTES
import com.wangzan101.mika.task.TaskService.Companion.ACTION_CANCEL
import com.wangzan101.mika.task.TaskService.Companion.ACTION_RESUME
import com.wangzan101.mika.task.TaskService.Companion.ACTION_TAKE_OVER
import com.wangzan101.mika.task.TaskService.Companion.ACTION_HAND_BACK
import com.wangzan101.mika.task.TaskService.Companion.ACTION_FINISHED_MYSELF
import com.wangzan101.mika.task.TaskService.Companion.ACTION_SKIP
import com.wangzan101.mika.task.TaskService.Companion.EXTRA_WAIT_KEY
import com.wangzan101.mika.task.TaskService.Companion.ACTION_ANSWER
import com.wangzan101.mika.task.TaskService.Companion.ACTION_APPROVE
import com.wangzan101.mika.task.TaskService.Companion.ACTION_DECLINE
import com.wangzan101.mika.task.TaskService.Companion.FAILURE_STREAK_NOTICE
import com.wangzan101.mika.task.TaskService.Companion.EXTRA_ANSWER
import com.wangzan101.mika.task.TaskService.Companion.EXTRA_RUN
import com.wangzan101.mika.task.TaskService.Companion.KEY_REPLY
import com.wangzan101.mika.task.TaskService.Companion.CHANNEL_ATTENTION
import com.wangzan101.mika.task.TaskService.Companion.CHANNEL_PROGRESS
import com.wangzan101.mika.task.TaskService.Companion.ID_PROGRESS
import com.wangzan101.mika.task.TaskService.Companion.SLOT_STRIDE
import com.wangzan101.mika.task.TaskService.Companion.TAKEOVER_QUEUED
import com.wangzan101.mika.task.TaskService.Companion.TAG
import com.wangzan101.mika.task.TaskService.Companion.PREPARING
import com.wangzan101.mika.task.TaskService.Companion.cancel
import com.wangzan101.mika.task.TaskService.Companion.send
import com.wangzan101.mika.task.TaskService.Companion.resultDelivery
import com.wangzan101.mika.task.TaskService.Companion.describe

// 任务服务:进度、需要处理与结果通知
/** 已交还、循环还没往下走的那一小段:不再显示接管按钮。 */
internal fun TaskService.awaitingUser(run: TaskRun, state: TaskState) =
    state !is TaskState.WaitingTakeover || run.takeoverStage.value != Takeover.Stage.None

/** 主屏一次只交一个 App:接管排在别的任务后面时先不给接管按钮。 */
internal fun TaskService.takeoverFirst(run: TaskRun) = run.takeover?.let(takeoverQueue::isFirst) ?: true

/** 用 [run] 的进度通知编号进前台;还没有任务对象时用第一个槽位的。 */
internal fun TaskService.startInForeground(run: TaskRun?, n: Notification) {
    val nid = run?.let { id(ID_PROGRESS, it) } ?: ID_PROGRESS
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(nid, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
        startForeground(nid, n)
    }
}

internal fun TaskService.progressNotification(run: TaskRun, state: TaskState): Notification {
    val stage = run.chipStages.current
    val chip = if (Build.VERSION.SDK_INT >= 36) taskChip(run.provenance.origin, state, stage) else null
    val text = if (chip != null && state is TaskState.Running) chipDetail(state, stage)
        else describe(state) { ConfirmationNotifier.appLabel(this, it) }.second
    val warning = run.muteFailedFor?.let { "\n" + tr(R.string.task_may_make_sound) }.orEmpty() +
        (if (state is TaskState.WaitingTakeover && !takeoverFirst(run)) "\n$TAKEOVER_QUEUED" else "")
    val b = Notification.Builder(this, CHANNEL_PROGRESS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(tr(R.string.task_notif_title, ScreenCopy.plain(run.triggerCondition ?: run.goal)))
        .setContentText(text + warning)
        .setStyle(Notification.BigTextStyle().bigText(text + warning))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp(run.conversationId))
    if (Build.VERSION.SDK_INT >= 36 && chip != null) {
        b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        b.setShortCriticalText(chip)
        // The public EXTRA_REQUEST_PROMOTED_ONGOING constant is exposed by SDK 36.1.
        b.extras.putBoolean("android.requestPromotedOngoing", true)
    }
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
internal fun TaskService.preparingNotification(): Notification = Notification.Builder(this, CHANNEL_PROGRESS)
    .setSmallIcon(R.drawable.ic_notification)
    .setContentTitle("Mika")
    .setContentText(PREPARING)
    .setOngoing(true)
    .setOnlyAlertOnce(true)
    .setContentIntent(openApp())
    .build()

internal fun TaskService.answerTooLate() = Handler(Looper.getMainLooper()).post {
    Toast.makeText(this, getString(R.string.input_too_late, INPUT_TIMEOUT_MINUTES), Toast.LENGTH_LONG).show()
}

/**
 * 等待接管或回答时另发的醒目通知;其余状态没有。
 * 悬浮胶囊正显示在亮着的屏幕上时照发,但安静地进通知栏(胶囊已经变橙,见 [alertQuietly]);锁屏、胶囊关着或画不出来时照旧弹出。
 */
internal fun TaskService.attentionNotification(run: TaskRun, state: TaskState): Notification? {
    if (!awaitingUser(run, state)) return null
    val quiet = (application as com.wangzan101.mika.MikaApp).capsule.showing.value
    if (state is TaskState.WaitingConfirmation) {
        return ConfirmationNotifier.build(
            this, CHANNEL_ATTENTION, state, TaskService::class.java, ACTION_APPROVE, ACTION_DECLINE, openApp(run.conversationId),
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
        .setContentIntent(openApp(run.conversationId))
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

internal fun TaskService.takeoverTitle(reason: String): String {
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

internal fun TaskService.takeoverActions(run: TaskRun): List<Notification.Action> {
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
internal fun TaskService.takeoverAction(run: TaskRun, title: String, action: String, code: Int): Notification.Action {
    val pi = PendingIntent.getActivity(
        this, code + SLOT_STRIDE * run.slot, Intent(this, TakeoverActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_RUN, run.key)
            .apply { run.takeover?.let { putExtra(EXTRA_WAIT_KEY, it.waitKey) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return Notification.Action.Builder(null, title, pi).build()
}

internal fun TaskService.replyAction(run: TaskRun, options: List<String>?): Notification.Action {
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

internal fun TaskService.answerAction(run: TaskRun, option: String, index: Int): Notification.Action {
    val pi = PendingIntent.getService(
        this, 10 + index + SLOT_STRIDE * run.slot,
        Intent(this, TaskService::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_ANSWER, option).putExtra(EXTRA_RUN, run.key),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return Notification.Action.Builder(null, option, pi).build()
}

internal suspend fun TaskService.prepareResult(run: TaskRun, end: TaskState, deliveryId: String): Delivery.Result {
    val result = resultDelivery(deliveryId, run.conversationId, end, failureStreak(run, end))
    val peer = run.channelPeer ?: ChannelPeer(LocalChannel.ID, "phone-owner", run.conversationId)
    ResultDelivery.prepare(outbox, channelFor(peer, run.triggerWindowKey != null), peer, result, run.sharedFiles.value)
    return result
}

internal suspend fun TaskService.notifyResult(run: TaskRun, result: Delivery.Result): DeliveryState {
    val peer = run.channelPeer ?: ChannelPeer(LocalChannel.ID, "phone-owner", run.conversationId)
    val started = SystemClock.elapsedRealtime()
    val delivered = ResultDelivery.send(outbox, channelFor(peer, run.triggerWindowKey != null), peer, result)
    val finished = SystemClock.elapsedRealtime()
    val sinceReady = if (run.resultReadyAtMs > 0) finished - run.resultReadyAtMs else -1
    Log.i(TAG, "result $delivered: delivery ${finished - started} ms, since ready $sinceReady ms")
    return delivered
}

/**
 * 定时任务这次又没成,连上之前接连失败的到了 [FAILURE_STREAK_NOTICE] 次及以上时是总次数(含这次):
 * 通知标题写明连着失败了几次、提醒去暂停或修改,免得每次只看到一条普通的失败。查不到记录时按没有连续失败算。
 */
internal fun TaskService.failureStreak(run: TaskRun, end: TaskState): Int? {
    val key = run.triggerWindowKey ?: return null
    if (end is TaskState.Succeeded || end == TaskState.Cancelled) return null
    val streak = runCatching { TriggerRuntime.failureStreakBefore(this, key) }.getOrDefault(0) + 1
    return streak.takeIf { it >= FAILURE_STREAK_NOTICE }
}

internal fun TaskService.action(run: TaskRun, title: String, action: String, code: Int): Notification.Action {
    val pi = PendingIntent.getService(
        this, code + SLOT_STRIDE * run.slot, Intent(this, TaskService::class.java).setAction(action).putExtra(EXTRA_RUN, run.key),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return Notification.Action.Builder(null, title, pi).build()
}

internal fun TaskService.openApp(threadId: String? = null) = openApp(this, threadId)
