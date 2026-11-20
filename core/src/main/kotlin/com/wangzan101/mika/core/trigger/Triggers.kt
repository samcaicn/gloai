package com.wangzan101.mika.core.trigger

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 计划是谁建的:用户手动、模型(schedule_task)、外部脚本(broadcast);只影响展示,出事能追溯。 */
@Serializable
enum class TriggerSource { User, Model, External }

@Serializable
data class TriggerDefinition(
    val id: String,
    val threadId: String,
    val goal: String,
    val nextAtMs: Long,
    /** null 是一次性；间隔触发会合并积压的时段，只补跑一次。 */
    val intervalMs: Long? = null,
    /** 先在沙箱/网页里检查；检查成立才运行 [goal]。 */
    val conditionGoal: String? = null,
    val enabled: Boolean = true,
    /** 到点后只在没有前台任务时执行；忙时看门狗稍后重查。 */
    val idleOnly: Boolean = false,
    /** 上一次有效检查是否满足条件(冷却期内跳过的那次不算);用于只在状态变化时执行动作。 */
    val lastConditionMet: Boolean? = null,
    /** 固定提醒原文；非空时到点直接送达，不启动模型。 */
    val reminderText: String? = null,
    /** 按指定时区的当地钟点重复；空表示使用固定毫秒间隔或只运行一次。 */
    val calendarTime: String? = null,
    val calendarZone: String? = null,
    val calendarDays: List<Int> = emptyList(),
    /** 有条件时:只在条件刚满足时执行(默认),或每次检查满足都执行。 */
    val notifyWhen: ConditionNotify = ConditionNotify.BecomesMet,
    /** 两次派发动作之间至少隔这么久;冷却期内满足的推迟到冷却结束后再算。 */
    val cooldownMs: Long? = null,
    /** 最近一次有效检查记下的关键值。 */
    val lastValue: String? = null,
    /** 比较基准:上次派发动作时的值;从没派发过时是第一次检查到的值。 */
    val baselineValue: String? = null,
    val baselineAtMs: Long? = null,
    /** 上次因条件满足而派发动作的那次检查的计划时间,冷却从这里算。 */
    val lastFiredAtMs: Long? = null,
    /** 多钟点计划；单钟点仍只用 calendarTime，主文件兼容旧版，完整列表由宿主存入扩展状态。 */
    val calendarTimes: List<String> = emptyList(),
    /** 计划是谁建的:用户手动 / 模型 / 外部脚本,只影响展示与追溯。 */
    val source: TriggerSource = TriggerSource.Model,
) {
    init {
        require(id.isNotBlank() && threadId.isNotBlank() && goal.isNotBlank())
        require(nextAtMs >= 0 && (intervalMs == null || intervalMs > 0))
        require(calendarTime == null || (intervalMs == null && calendarZone != null && calendarDays.all { it in 1..7 }))
        require(cooldownMs == null || cooldownMs > 0)
        require(calendarTimes.isEmpty() || (calendarTime == calendarTimes.first() &&
            calendarTimes == normalizedCalendarTimes(calendarTimes)))
    }

    fun calendarClocks(): List<String> = calendarTimes.ifEmpty { listOfNotNull(calendarTime) }
}

@Serializable
enum class TriggerWindowState { Claimed, Dispatched, ActionDispatched, Delivered, Skipped, Failed, Unknown }

@Serializable
data class TriggerWindow(
    val triggerId: String,
    val dueAtMs: Long,
    val claimedAtMs: Long,
    val missedIntervals: Long = 0,
    val state: TriggerWindowState = TriggerWindowState.Claimed,
    val reason: String? = null,
) {
    val key get() = "$triggerId:$dueAtMs"
}

/**
 * 这个计划在 [currentKey] 这一次之前已经接连失败了几次:从紧挨着的上一次往前数失败的,遇到送达了的或结果不明的就停;
 * 条件没满足而跳过的、还没出结果的不算数,也不打断。认不出 [currentKey] 时为 0。
 */
fun failureStreakBefore(windows: List<TriggerWindow>, currentKey: String): Int {
    val current = windows.firstOrNull { it.key == currentKey } ?: return 0
    var streak = 0
    for (w in windows.filter { it.triggerId == current.triggerId && it.dueAtMs < current.dueAtMs }.sortedByDescending { it.dueAtMs }) {
        when (w.state) {
            TriggerWindowState.Failed -> streak++
            TriggerWindowState.Delivered, TriggerWindowState.Unknown -> return streak
            else -> Unit
        }
    }
    return streak
}

/** claimDue 必须把下次时间与待处理账本一起写入，返回后才可派发。 */
interface TriggerStore {
    fun upsert(trigger: TriggerDefinition)
    fun get(id: String): TriggerDefinition?
    fun all(): List<TriggerDefinition>
    fun windows(): List<TriggerWindow>
    fun claimDue(nowMs: Long, eligible: (TriggerDefinition) -> Boolean = { true }): List<TriggerWindow>
    /** 手动执行一次，不改变原来的下一次计划时间。 */
    fun claimNow(id: String, nowMs: Long): TriggerWindow?
    fun mark(key: String, state: TriggerWindowState, reason: String? = null): Boolean
    /** 一次性任务只在结果确定送达后删除。 */
    fun retireDeliveredOneShots()
    fun remove(id: String)
}

@Serializable
data class TriggerSnapshot(val triggers: List<TriggerDefinition>, val windows: List<TriggerWindow>) {
    fun recentWindows(triggerId: String, limit: Int): List<TriggerWindow> = windows.asSequence()
        .filter { it.triggerId == triggerId }
        .sortedWith(compareByDescending<TriggerWindow> { it.claimedAtMs }.thenByDescending { it.dueAtMs })
        .take(limit)
        .toList()
}

/** 纯内存实现，文件实现沿用相同的状态转换。 */
class MemoryTriggerStore(initial: TriggerSnapshot = TriggerSnapshot(emptyList(), emptyList())) : TriggerStore {
    private val triggers = linkedMapOf<String, TriggerDefinition>()
    private val ledger = linkedMapOf<String, TriggerWindow>()

    init {
        initial.triggers.forEach { triggers[it.id] = it }
        initial.windows.forEach { ledger[it.key] = it }
    }

    @Synchronized fun snapshot() = TriggerSnapshot(triggers.values.toList(), ledger.values.toList())

    @Synchronized override fun upsert(trigger: TriggerDefinition) { triggers[trigger.id] = trigger }

    /** 保存编辑并终止旧条件检查的派发资格；已经开始的动作继续收尾。 */
    @Synchronized fun edit(trigger: TriggerDefinition): Boolean {
        val previous = triggers[trigger.id] ?: return false
        if (previous.conditionGoal != null) {
            ledger.values.filter { it.triggerId == trigger.id &&
                (it.state == TriggerWindowState.Claimed || it.state == TriggerWindowState.Dispatched) }
                .forEach { mark(it.key, TriggerWindowState.Skipped, CONDITION_PLAN_EDITED) }
        }
        triggers[trigger.id] = trigger.withConditionHistoryFrom(previous)
        return true
    }

    @Synchronized override fun remove(id: String) {
        triggers.remove(id)
        ledger.entries.removeAll { it.value.triggerId == id }
    }
    @Synchronized override fun get(id: String) = triggers[id]
    @Synchronized override fun all() = triggers.values.toList()
    @Synchronized override fun windows() = ledger.values.toList()

    @Synchronized override fun claimDue(nowMs: Long, eligible: (TriggerDefinition) -> Boolean): List<TriggerWindow> {
        val active = ledger.values.filter { it.state == TriggerWindowState.Claimed ||
            it.state == TriggerWindowState.Dispatched || it.state == TriggerWindowState.ActionDispatched }
            .map { it.triggerId }.toSet()
        val due = triggers.values.filter { it.enabled && it.id !in active && it.nextAtMs <= nowMs &&
            ledger["${it.id}:${it.nextAtMs}"] == null && eligible(it) } // 和 TriggerDefinition.slotTaken 一致
        return due.map { t ->
            val interval = t.intervalMs
            val missed = if (interval == null) 0 else (nowMs - t.nextAtMs) / interval
            val window = TriggerWindow(t.id, t.nextAtMs, nowMs, missed)
            ledger[window.key] = window
            triggers[t.id] = when {
                t.calendarTime != null -> t.copy(nextAtMs = nextCalendarAt(t.calendarClocks(), t.calendarZone!!,
                    t.calendarDays, nowMs))
                interval != null -> t.copy(nextAtMs = t.nextAtMs + (missed + 1) * interval)
                else -> t.copy(enabled = false)
            }
            window
        }
    }

    @Synchronized override fun claimNow(id: String, nowMs: Long): TriggerWindow? {
        if (triggers[id] == null || ledger.values.any { it.triggerId == id && it.state in setOf(
                TriggerWindowState.Claimed, TriggerWindowState.Dispatched, TriggerWindowState.ActionDispatched) }) return null
        val at = generateSequence(nowMs) { it + 1 }.first { "$id:$it" !in ledger }
        return TriggerWindow(id, at, nowMs).also { ledger[it.key] = it }
    }

    @Synchronized override fun mark(key: String, state: TriggerWindowState, reason: String?): Boolean {
        val old = ledger[key] ?: return false
        if (old.state == TriggerWindowState.Delivered || old.state == TriggerWindowState.Skipped || old.state == TriggerWindowState.Failed) return false
        ledger[key] = old.copy(state = state, reason = reason)
        if (state == TriggerWindowState.Delivered || state == TriggerWindowState.Skipped ||
            state == TriggerWindowState.Failed || state == TriggerWindowState.Unknown)
            pruneCompleted(old.triggerId)
        return true
    }

    private fun pruneCompleted(id: String) {
        ledger.values.filter { it.triggerId == id && it.state in setOf(
            TriggerWindowState.Delivered, TriggerWindowState.Skipped, TriggerWindowState.Failed,
            TriggerWindowState.Unknown) }
            .sortedByDescending { it.dueAtMs }.drop(100).forEach { ledger.remove(it.key) }
    }

    @Synchronized override fun retireDeliveredOneShots() {
        val retired = triggers.values.filter { it.intervalMs == null && it.calendarTime == null && ledger.values.any { w ->
            w.triggerId == it.id && w.dueAtMs == it.nextAtMs && w.state == TriggerWindowState.Delivered } }
            .map { it.id }
        retired.forEach { id -> triggers.remove(id); ledger.entries.removeAll { it.value.triggerId == id } }
    }
}

/**
 * 这个计划眼下这一次的时段已经记过账(认领过,跑过或正在跑):[TriggerStore.claimDue] 不会再认领它,两边的条件要一致。
 * 一次性计划认领后下次时间不往前走,就是这样;循环的计划认领时就换到下一个时段了。
 */
fun TriggerDefinition.slotTaken(windows: List<TriggerWindow>): Boolean =
    windows.any { it.triggerId == id && it.dueAtMs == nextAtMs }

/**
 * 计划页的开关能不能拨:已启用的总能暂停;已停用的看这次时段跑过没有,和 [TriggerEngine.setEnabled] 一致。
 * 一次性计划这次时段跑过就不能再开(要再跑得改时间或立即执行);手动运行只记在别的时段,不算占用。
 */
fun TriggerDefinition.canToggle(windows: List<TriggerWindow>): Boolean = enabled || !slotTaken(windows)

/** 以当地日历递进，跨夏令时仍保持同一钟点；不存在的钟点使用系统的向前调整规则。 */
fun nextCalendarAt(time: String, zone: String, days: List<Int>, afterMs: Long): Long =
    nextCalendarAt(listOf(time), zone, days, afterMs)

/** 同一天的多个时刻统一选下一次；夏令时跳时导致两个钟点落在同一瞬间时只派发一次。 */
fun nextCalendarAt(times: List<String>, zone: String, days: List<Int>, afterMs: Long): Long {
    require(times.isNotEmpty())
    val localTimes = times.map(LocalTime::parse)
    val zoneId = ZoneId.of(zone)
    var date = Instant.ofEpochMilli(afterMs).atZone(zoneId).toLocalDate()
    repeat(9) {
        if (days.isEmpty() || date.dayOfWeek.value in days) {
            val candidate = localTimes.map { date.atTime(it).atZone(zoneId).toInstant().toEpochMilli() }
                .filter { it > afterMs }.minOrNull()
            if (candidate != null) return candidate
        }
        date = date.plusDays(1)
    }
    error("calendar schedule has no matching day")
}

private val CALENDAR_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

/** UI 和模型共用的分钟钟点校验、去重与排序。 */
fun normalizedCalendarTimes(times: List<String>): List<String> {
    require(times.isNotEmpty())
    return times.map { raw ->
        val time = LocalTime.parse(raw.trim())
        require(time.second == 0 && time.nano == 0)
        CALENDAR_CLOCK.format(time)
    }.distinct().sorted()
}

/** 修改下次时间时,日历计划也改用新钟点重复;保存与预览必须共用这份转换。 */
fun TriggerDefinition.rescheduledTo(nextAtMs: Long): TriggerDefinition {
    val local = calendarZone?.let { zone ->
        CALENDAR_CLOCK.format(Instant.ofEpochMilli(nextAtMs).atZone(ZoneId.of(zone)))
    }
    val times = if (calendarTime == null) emptyList()
        else normalizedCalendarTimes(listOfNotNull(local) + calendarTimes.drop(1))
    return copy(nextAtMs = nextAtMs, enabled = true, calendarTime = times.firstOrNull(), calendarTimes = times)
}

/** 编辑/创建时预览:首个不早于 [afterMs] 的触发也计入,再按计划自身的时区与重复规则递进。 */
fun upcoming(def: TriggerDefinition, afterMs: Long, count: Int): List<Long> {
    if (count <= 0) return emptyList()
    val out = ArrayList<Long>(count)
    when {
        def.calendarTime != null -> {
            var at = if (def.nextAtMs >= afterMs) def.nextAtMs
            else nextCalendarAt(def.calendarClocks(), def.calendarZone!!, def.calendarDays, afterMs)
            repeat(count) {
                out += at
                at = nextCalendarAt(def.calendarClocks(), def.calendarZone!!, def.calendarDays, at)
            }
        }
        def.intervalMs != null && def.intervalMs > 0 -> {
            var t = def.nextAtMs
            if (t < afterMs) t += ((afterMs - t - 1) / def.intervalMs + 1) * def.intervalMs
            repeat(count) { out += t; t += def.intervalMs }
        }
        else -> out += def.nextAtMs
    }
    return out
}

/** 排期由宿主实现；核心负责先记账再派发，异常重启时不会重复执行已认领的时段。 */
class TriggerEngine(private val store: TriggerStore, private val scheduleAlarm: (Long?) -> Unit,
    private val isIdle: () -> Boolean = { true }, private val idleRetryMs: Long = 60_000L,
    private val dispatch: (TriggerDefinition, TriggerWindow) -> Unit) {
    fun rearm(nowMs: Long) {
        store.windows().filter { it.state == TriggerWindowState.Claimed }
            .forEach { store.mark(it.key, TriggerWindowState.Unknown, "process restarted before dispatch confirmation") }
        val idle = isIdle()
        val windows = store.windows()
        val active = windows.filter { it.state == TriggerWindowState.Claimed ||
            it.state == TriggerWindowState.Dispatched || it.state == TriggerWindowState.ActionDispatched }
            .map { it.triggerId }.toSet()
        // 时段已记过账的计划 claimDue 不会认领,不为它排闹钟:按到期时间排就是「马上响」,响了又认领不了,一直空转
        scheduleAlarm(store.all().filter { it.enabled && (it.id in active || !it.slotTaken(windows)) }.minOfOrNull {
            if (it.id in active) nowMs + idleRetryMs
            else maxOf(if (it.idleOnly && !idle) nowMs + idleRetryMs else nowMs, it.nextAtMs)
        })
    }

    /**
     * 暂停或恢复 [id];没有这个计划、或要恢复的计划这一次的时段已经记过账([slotTaken],认领过的一次性计划)时不改,返回 false:
     * 恢复了也不会再跑,要再跑用改时间或立即执行(计划页对这种计划也不给开关)。
     */
    fun setEnabled(id: String, enabled: Boolean, nowMs: Long): Boolean {
        val trigger = store.get(id) ?: return false
        if (enabled && trigger.slotTaken(store.windows())) return false
        store.upsert(trigger.copy(enabled = enabled))
        rearm(nowMs)
        return true
    }

    /** 时区变化后,跟随设备旧时区的当地时间计划重算到新时区;显式钉了其他时区的计划不动。 */
    fun rezone(nowMs: Long, fromZone: String, toZone: ZoneId) {
        if (fromZone == toZone.id) return
        store.all().filter { it.enabled && it.calendarTime != null && it.calendarZone == fromZone }
            .forEach { t ->
                store.upsert(
                    t.copy(
                        calendarZone = toZone.id,
                        nextAtMs = nextCalendarAt(t.calendarClocks(), toZone.id, t.calendarDays, nowMs),
                    )
                )
            }
        rearm(nowMs)
    }

    fun fire(nowMs: Long) {
        val idle = isIdle()
        store.claimDue(nowMs) { !it.idleOnly || idle }.forEach { window ->
            val trigger = store.get(window.triggerId) ?: return@forEach
            try {
                dispatch(trigger, window)
                store.mark(window.key, TriggerWindowState.Dispatched)
            } catch (e: Exception) {
                store.mark(window.key, TriggerWindowState.Unknown, e::class.simpleName)
            }
        }
        rearm(nowMs)
    }
}
