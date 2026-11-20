package com.wangzan101.mika.task

import com.wangzan101.mika.i18n.tr
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.ui.MainActivity
import com.wangzan101.mika.core.trigger.Scheduled
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.TriggerEngine
import com.wangzan101.mika.core.trigger.ConditionOutcome
import com.wangzan101.mika.core.trigger.TriggerSnapshot
import com.wangzan101.mika.core.trigger.TriggerWindowState
import com.wangzan101.mika.core.trigger.rescheduledTo
import com.wangzan101.mika.core.trigger.EditedSchedule
import com.wangzan101.mika.core.trigger.afterCheck
import com.wangzan101.mika.core.trigger.conditionCheckGoal
import com.wangzan101.mika.core.trigger.revertFire
import com.wangzan101.mika.settings.FeatureFlags
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 安卓闹钟的薄壳；时间与去重由持久化 TriggerStore 决定，闹钟广播可以重复来。 */
object TriggerRuntime {
    private var recovered = false
    /** 存下并布置闹钟;系统没准许准时闹钟(到点可能不准时或起不来)时引导用户去开一次,结果里照实说有没有提示到。 */
    @Synchronized fun schedule(context: Context, definition: TriggerDefinition, replaceExisting: Boolean = false): Scheduled {
        val store = store(context)
        if (replaceExisting) check(store.edit(definition)) { tr(R.string.trigger_deleted) }
        else store.upsert(definition)
        engine(context, store).rearm(System.currentTimeMillis())
        val granted = canScheduleExactly(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 只有通知真的发出去了才算提示过;没发出去下次再试
        val notified = ExactAlarmGuide.shouldPrompt(granted, prefs.getBoolean(PROMPTED, false)) && promptExactAlarm(context)
        if (notified) prefs.edit().putBoolean(PROMPTED, true).apply()
        return Scheduled(granted, notified)
    }

    /** 模型编辑仍限本对话；在同一锁内复核后保存，不能把已删除或换了归属的计划写回来。 */
    @Synchronized fun edit(context: Context, threadId: String, id: String,
        change: (TriggerDefinition) -> TriggerDefinition): EditedSchedule? {
        val current = store(context).get(id)?.takeIf { it.threadId == threadId } ?: return null
        val plan = change(current)
        if (plan.id != id || plan.threadId != threadId) return null
        return EditedSchedule(plan, schedule(context, plan, replaceExisting = true))
    }

    /** 系统的「闹钟和提醒」授权页(安卓 12 起)。 */
    fun exactAlarmSettings(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 第一次没有权限就建了定时任务:发一条通知,点开就是授权页(任务在后台跑,不直接跳页面)。返回通知有没有发出去。 */
    private fun promptExactAlarm(context: Context): Boolean {
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (!notifications.areNotificationsEnabled()) return false
        TaskService.ensureChannels(context)
        val open = PendingIntent.getActivity(context, PROMPTED.hashCode(), exactAlarmSettings(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return runCatching { notifications.notify(PROMPTED.hashCode(), Notification.Builder(context, TaskService.CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(ExactAlarmGuide.TITLE)
            .setContentText(ExactAlarmGuide.BODY).setStyle(Notification.BigTextStyle().bigText(ExactAlarmGuide.BODY))
            .setContentIntent(open).setAutoCancel(true).build()) }
            .onFailure { Log.w("MikaTrigger", "exact alarm prompt failed", it) }.isSuccess
    }

    @Synchronized fun rearm(context: Context) {
        if (FeatureFlags.get(context).quitRequested()) arm(context, null)
        else engine(context, store(context)).rearm(System.currentTimeMillis())
    }

    /** 完全退出时只撤系统闹钟,不改计划与待处理账本;下次打开 App 由 [rearm] 接着排。 */
    @Synchronized fun suspendAlarms(context: Context) = arm(context, null)

    @Synchronized fun fire(context: Context) {
        // 已发出的广播可能晚于退出到达:不要认领时段,也不要发提醒,重新打开后仍可按原账本恢复
        if (FeatureFlags.get(context).quitRequested()) {
            arm(context, null)
            return
        }
        engine(context, store(context)).fire(System.currentTimeMillis())
    }

    /** 设备时区变化后,跟随旧设备时区的当地时间计划重算到新时区;显式钉了其他时区的不动。没有基线(首次使用)就只记下当前时区。 */
    @Synchronized fun syncTimeZone(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = ZoneId.systemDefault().id
        val previous = prefs.getString(KEY_DEVICE_ZONE, null)
        prefs.edit().putString(KEY_DEVICE_ZONE, current).apply()
        if (previous == null || previous == current) return
        engine(context, store(context)).rezone(System.currentTimeMillis(), previous, ZoneId.of(current))
    }

    @Synchronized fun delivered(context: Context, key: String) {
        val store = store(context)
        store.mark(key, TriggerWindowState.Delivered)
        store.retireDeliveredOneShots()
        engine(context, store).rearm(System.currentTimeMillis())
    }

    @Synchronized fun actionFailed(context: Context, key: String, reason: String) {
        val store = store(context)
        if (store.windows().none { it.key == key }) return
        store.mark(key, TriggerWindowState.Failed, reason.take(160))
        engine(context, store).rearm(System.currentTimeMillis())
    }

    @Synchronized fun interrupted(context: Context, key: String, reason: String) {
        val store = store(context)
        if (!store.mark(key, TriggerWindowState.Unknown, reason)) return
        store.windows().firstOrNull { it.key == key }?.let { window ->
            store.get(window.triggerId)?.let { trigger ->
                if (!hasSavedResult(context, key)) runCatching { reportUncertain(context, trigger, key) }
                    .onFailure { Log.w("MikaTrigger", "could not report interrupted task", it) }
            }
        }
        engine(context, store).rearm(System.currentTimeMillis())
    }

    /** The service can stop after a result is persisted while outcome judging still runs in the app scope. */
    @Synchronized fun recoverAfterServiceStop(context: Context) {
        val store = store(context)
        recoverActive(context, store, "task service stopped before result confirmation")
        rearm(context)
    }

    @Synchronized fun checked(context: Context, key: String, goal: String, threadId: String, outcome: ConditionOutcome) {
        val store = store(context)
        val window = store.windows().firstOrNull { it.key == key && it.state == TriggerWindowState.Dispatched } ?: return
        if (outcome is ConditionOutcome.Error) {
            store.mark(window.key, TriggerWindowState.Failed, outcome.reason)
            return
        }
        outcome as ConditionOutcome.Checked
        val trigger = store.get(window.triggerId) ?: return
        val decision = trigger.afterCheck(outcome, System.currentTimeMillis(), slotMs = window.dueAtMs)
        store.upsert(decision.next)
        if (!decision.fire) {
            store.mark(window.key, TriggerWindowState.Skipped, decision.reason)
            return
        }
        store.mark(window.key, TriggerWindowState.ActionDispatched)
        try { TaskService.startTrigger(context, goal, threadId, key, null, null) }
        catch (e: Exception) { reportStartFailure(context, store, trigger, window.key, e, before = trigger) }
    }

    @Synchronized fun list(context: Context): List<TriggerDefinition> = store(context).all()

    /** 这个计划在 [key] 这一次之前接连失败了几次,见 [com.wangzan101.mika.core.trigger.failureStreakBefore]。 */
    @Synchronized fun failureStreakBefore(context: Context, key: String): Int = com.wangzan101.mika.core.trigger.failureStreakBefore(store(context).windows(), key)

    fun canScheduleExactly(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    @Synchronized fun snapshot(context: Context): TriggerSnapshot = store(context).let {
        TriggerSnapshot(it.all(), it.windows())
    }

    /** 暂停或恢复;改不了(没有这个计划、恢复跑过的一次性计划)时返回 false,见 [TriggerEngine.setEnabled]。 */
    @Synchronized fun setEnabled(context: Context, id: String, enabled: Boolean): Boolean =
        store(context).let { engine(context, it).setEnabled(id, enabled, System.currentTimeMillis()) }

    @Synchronized fun remove(context: Context, id: String) {
        val store = store(context)
        store.remove(id)
        engine(context, store).rearm(System.currentTimeMillis())
    }

    @Synchronized fun reschedule(context: Context, id: String, nextAtMs: Long) {
        val store = store(context)
        store.get(id)?.let { trigger ->
            store.upsert(trigger.rescheduledTo(nextAtMs))
        }
        engine(context, store).rearm(System.currentTimeMillis())
    }

    @Synchronized fun runNow(context: Context, id: String) {
        val store = store(context)
        val trigger = store.get(id) ?: error(tr(R.string.trigger_deleted))
        val window = store.claimNow(id, System.currentTimeMillis()) ?: error(tr(R.string.trigger_busy))
        try {
            if (trigger.reminderText != null) deliverReminder(context, store, trigger, window.key)
            else TaskService.startTrigger(context, trigger.goal, trigger.threadId, window.key,
                trigger.conditionCheckGoal(ZoneId.systemDefault()), trigger.conditionGoal)
            store.mark(window.key, TriggerWindowState.Dispatched)
        } catch (e: Exception) {
            reportStartFailure(context, store, trigger, window.key, e)
            throw e
        } finally {
            engine(context, store).rearm(System.currentTimeMillis())
        }
    }

    private fun store(context: Context): FileTriggerStore {
        val store = FileTriggerStore(File(context.filesDir, "triggers-v3.json"))
        if (!recovered) {
            recoverActive(context, store, "process restarted before result was confirmed")
            recovered = true
        }
        return store
    }

    private fun recoverActive(context: Context, store: FileTriggerStore, reason: String) {
        store.windows().filter { it.state in setOf(
            TriggerWindowState.Claimed, TriggerWindowState.Dispatched, TriggerWindowState.ActionDispatched,
        ) }.forEach {
            val trigger = store.get(it.triggerId)
            if (trigger?.reminderText != null) {
                runCatching { deliverReminder(context, store, trigger, it.key) }
                    .onFailure { error -> store.mark(it.key, TriggerWindowState.Unknown, error::class.simpleName) }
            } else {
                store.mark(it.key, TriggerWindowState.Unknown, reason)
                if (trigger != null && !hasSavedResult(context, it.key))
                    runCatching { reportUncertain(context, trigger, it.key) }
                        .onFailure { error -> Log.w("MikaTrigger", "could not report recovered task", error) }
            }
        }
    }

    private fun engine(context: Context, store: FileTriggerStore) = TriggerEngine(store,
        { at -> arm(context, at) }, isIdle = { !((context.applicationContext as MikaApp).taskState.busy.value) }) { trigger, window ->
            if (trigger.reminderText != null) deliverReminder(context, store, trigger, window.key)
            else try { TaskService.startTrigger(context, trigger.goal, trigger.threadId, window.key,
                    trigger.conditionCheckGoal(ZoneId.systemDefault()), trigger.conditionGoal) }
                catch (e: Exception) { reportStartFailure(context, store, trigger, window.key, e); throw e }
        }

    private fun deliverReminder(context: Context, store: FileTriggerStore, trigger: TriggerDefinition, key: String) {
        val text = trigger.reminderText ?: return
        val conversations = ConversationStore.shared(context)
        val runId = "reminder:$key"
        if (conversations.turns(trigger.threadId).none { it.runId == runId }) {
            conversations.append(trigger.threadId, Turn(trigger.goal, text, "succeeded", runId = runId))
        }
        notifyLocal(context, trigger.threadId, key, tr(R.string.trigger_reminder), text)
        store.mark(key, TriggerWindowState.Delivered)
        store.retireDeliveredOneShots()
    }

    private fun reportStartFailure(context: Context, store: FileTriggerStore, trigger: TriggerDefinition,
        key: String, error: Exception, before: TriggerDefinition? = null) {
        val message = tr(R.string.trigger_not_started_body)
        Log.w("MikaTrigger", "scheduled task could not start", error)
        store.mark(key, TriggerWindowState.Failed, tr(R.string.trigger_start_failed))
        if (before != null) store.get(trigger.id)?.let { store.upsert(it.revertFire(before)) }
        val conversations = ConversationStore.shared(context)
        val runId = "schedule-failed:$key"
        if (conversations.turns(trigger.threadId).none { it.runId == runId }) {
            conversations.append(trigger.threadId, Turn(trigger.goal, message, "failed", runId = runId))
        }
        notifyLocal(context, trigger.threadId, key, tr(R.string.trigger_not_started), message)
    }

    private fun hasSavedResult(context: Context, key: String): Boolean =
        (context.applicationContext as MikaApp).runStore.all().any {
            it.triggerWindowKey == key && it.reply != null
        }

    private fun reportUncertain(context: Context, trigger: TriggerDefinition, key: String) {
        val message = tr(R.string.trigger_interrupted_body)
        val conversations = ConversationStore.shared(context)
        val runId = "schedule-uncertain:$key"
        if (conversations.turns(trigger.threadId).none { it.runId == runId }) {
            conversations.append(trigger.threadId, Turn(trigger.goal, message, "unverified", runId = runId))
        }
        notifyLocal(context, trigger.threadId, key, tr(R.string.trigger_unconfirmed), message)
    }

    private fun notifyLocal(context: Context, threadId: String, key: String, title: String, text: String) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        TaskService.ensureChannels(context)
        val open = PendingIntent.getActivity(context, key.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, threadId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (notifications.areNotificationsEnabled()) {
            runCatching { notifications.notify(key.hashCode(), Notification.Builder(context, TaskService.CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(title)
                .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).applyScheduledQuietHours(context).build()) }
                .onFailure { Log.w("MikaTrigger", "reminder notification failed", it) }
        }
    }

    private fun arm(context: Context, atMs: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, TriggerAlarmReceiver::class.java).setAction(ACTION)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarms.cancel(pending)
        val at = atMs?.takeUnless { FeatureFlags.get(context).quitRequested() }
        if (at != null) {
            if (canScheduleExactly(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private const val ACTION = "com.wangzan101.mika.task.TRIGGER_ALARM"
    private const val PREFS = "triggers"
    private const val PROMPTED = "exact_alarm_prompted"
    private const val KEY_DEVICE_ZONE = "device_timezone"
}

/** 准时闹钟权限的引导:没开时只主动提醒一次,之后在定时任务页一直写明。 */
object ExactAlarmGuide {
    val TITLE: String get() = tr(R.string.trigger_alarm_title)
    val BODY: String get() = tr(R.string.trigger_alarm_body)

    fun shouldPrompt(granted: Boolean, prompted: Boolean): Boolean = !granted && !prompted
}

class TriggerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.wangzan101.mika.task.TRIGGER_ALARM") return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { TriggerRuntime.fire(context.applicationContext) }
            catch (e: Exception) { Log.e("MikaTrigger", "alarm dispatch failed", e) }
            finally { pending.finish() }
        }
    }
}
