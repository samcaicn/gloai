package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import com.wangzan101.mika.core.screen.observation.Untrusted
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.bool
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.okObj
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.tool.strArray
import com.wangzan101.mika.core.trigger.ConditionNotify
import com.wangzan101.mika.core.trigger.PlanRepeat
import com.wangzan101.mika.core.trigger.PlanValidationException
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.editedTrigger
import com.wangzan101.mika.core.trigger.nextCalendarAt
import com.wangzan101.mika.core.trigger.normalizedCalendarTimes
import com.wangzan101.mika.core.trigger.toPlanDraft
import com.wangzan101.mika.core.trigger.upcoming
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object ScheduleTask : AgentTool {
    override val verb = Verb.ScheduleTask
    override val risk = Risk.External
    override val approvalRisk = ApprovalRisk(ActionRisk.External)
    override val offScreen = true
    override val logged = true
    override val def = ToolDef("schedule_task", Prompts.tool("schedule_task"), mapOf(
        "op" to str("create (default), list, edit, pause, resume, delete, run_now, or reschedule.",
            listOf("create", "list", "edit", "pause", "resume", "delete", "run_now", "reschedule")),
        "triggerId" to str("Plan ID returned by list or create; required for edit/pause/resume/delete/run_now/reschedule."),
        "goal" to str("What to do when the time arrives."),
        "at" to str("Optional first time as ISO 8601 with timezone. Required on create unless repeatAt or repeatTimes is given."),
        "intervalMinutes" to int("Repeat interval in minutes. For edit only, 0 clears the interval and makes it a one-time plan.", 0, 525600),
        "repeatAt" to str("Daily local clock HH:mm, e.g. 09:00. Alternative to repeatTimes. For edit only, an empty string clears calendar repetition."),
        "repeatTimes" to strArray("Daily local clocks HH:mm, e.g. [\"08:00\",\"20:00\"]. Alternative to repeatAt; duplicates are merged. For edit, [] clears calendar repetition.", 1440),
        "repeatZone" to str("IANA time zone for repeatAt/repeatTimes, e.g. Asia/Shanghai; defaults to this device's zone."),
        "weekdays" to strArray("Optional days for repeatAt/repeatTimes: MON,TUE,WED,THU,FRI,SAT,SUN. Omit for every day.", 7),
        "condition" to str("Read only condition checked before the goal. For edit, an empty string removes it."),
        "notifyWhen" to str("With condition: becomes_met (default) runs the goal only when the condition changes from not met to met; each_time_met runs it on every check that finds it met.",
            listOf("becomes_met", "each_time_met")),
        "cooldownMinutes" to int("With condition: minimum minutes between goal runs. For edit, 0 clears the cooldown.", 0, 525600),
        "reminder" to str("Fixed reminder text, delivered without a model. For edit, an empty string switches back to a normal task."),
        "whenIdle" to bool("If true, run only while no foreground task is active; the watchdog retries while busy."),
        "enabled" to bool("Whether this plan is enabled; omitted on edit preserves its paused/enabled state."),
    ), emptyList())

    override fun available(ctx: ToolContext) = ctx.config.scheduleTrigger != null || ctx.config.scheduleAdmin != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val thread = ctx.config.conversationId ?: return ToolExecution(okObj { put("scheduled", false); put("reason", "no_thread") })
        val op = a.str("op") ?: "create"
        if (op != "create") {
            val admin = ctx.config.scheduleAdmin ?: return ToolExecution(okObj { put("ok", false); put("reason", "unavailable") })
            if (op == "list") return ToolExecution(okObj {
                put("plans", buildJsonArray { admin.list(thread).forEach { plan ->
                    add(buildJsonObject {
                        put("id", plan.id); put("threadId", plan.threadId); put("goal", plan.goal); put("enabled", plan.enabled)
                        put("nextAtMs", plan.nextAtMs); put("nextAt", Instant.ofEpochMilli(plan.nextAtMs).toString())
                        plan.reminderText?.let { put("reminder", it) }
                        plan.intervalMs?.let { put("intervalMinutes", it / 60_000) }
                        if (plan.calendarTimes.isNotEmpty()) {
                            put("repeatTimes", buildJsonArray { plan.calendarClocks().forEach { add(JsonPrimitive(it)) } })
                        } else plan.calendarTime?.let { put("repeatAt", it) }
                        plan.calendarZone?.let { put("repeatZone", it) }
                        put("weekdays", buildJsonArray { plan.calendarDays.forEach { add(JsonPrimitive(java.time.DayOfWeek.of(it).name.take(3))) } })
                        put("whenIdle", plan.idleOnly)
                        plan.conditionGoal?.let { put("condition", it); put("notifyWhen", plan.notifyWhen.wire) }
                        plan.cooldownMs?.let { put("cooldownMinutes", it / 60_000) }
                        plan.lastValue?.let { put("lastValue", Untrusted.wrap(it)) }
                        plan.baselineValue?.let { put("baselineValue", Untrusted.wrap(it)) }
                    })
                } })
            })
            val id = a.str("triggerId") ?: return ToolExecution(okObj { put("changed", false); put("reason", "missing_trigger_id") })
            if (op == "edit") {
                val edited = try { admin.edit(thread, id) { current -> editedPlan(a, current) } } catch (e: PlanValidationException) {
                    return ToolExecution(okObj { put("changed", false); put("reason", "invalid_plan"); put("detail", e.reason.name) })
                } ?: return ToolExecution(okObj { put("changed", false); put("reason", "plan_not_found") })
                val updated = edited.plan
                val saved = edited.scheduled
                return ToolExecution(okObj {
                    put("changed", true); put("triggerId", id); put("enabled", updated.enabled)
                    put("nextAt", Instant.ofEpochMilli(updated.nextAtMs).toString())
                    put("upcoming", buildJsonArray { upcoming(updated, System.currentTimeMillis(), 3).forEach { add(JsonPrimitive(Instant.ofEpochMilli(it).toString())) } })
                    if (!saved.onTime) { put("exactAlarm", false); put("userNotified", saved.userNotified)
                        put("message", "Saved, but the phone has not granted alarms & reminders permission; this plan may run late or not start. Tell the user plainly.") }
                })
            }
            val changed = when (op) {
                "pause" -> admin.setEnabled(thread, id, false)
                "resume" -> admin.setEnabled(thread, id, true)
                "delete" -> admin.remove(thread, id)
                "run_now" -> admin.runNow(thread, id)
                "reschedule" -> {
                    val at = runCatching { Instant.parse(a.str("at") ?: error("missing time")).toEpochMilli() }.getOrNull()
                        ?: return ToolExecution(okObj { put("changed", false); put("reason", "invalid_time") })
                    if (at <= System.currentTimeMillis()) return ToolExecution(okObj { put("changed", false); put("reason", "time_not_future") })
                    admin.reschedule(thread, id, at)
                }
                else -> return ToolExecution(okObj { put("changed", false); put("reason", "invalid_op") })
            }
            return ToolExecution(okObj {
                put("changed", changed)
                // 计划在、却没改成:只有恢复认领过(跑过或正在跑)的一次性计划会这样
                if (!changed) put("reason", if (op == "resume" && admin.list(thread).any { it.id == id }) "already_ran" else "plan_not_found")
            })
        }
        val goal = a.str("goal", max = 4000) ?: return ToolExecution(okObj { put("scheduled", false); put("reason", "missing_goal") })
        val first = a.str("at")
        val repeatAt = a.str("repeatAt")
        val repeatTimes = a.strList("repeatTimes", 1, 1440, itemMax = 8)
        if (repeatAt != null && repeatTimes != null) return ToolExecution(okObj { put("scheduled", false); put("reason", "repeat_clock_conflict") })
        val clocks = runCatching { (repeatTimes ?: repeatAt?.let(::listOf))?.let(::normalizedCalendarTimes) }
            .getOrElse { return ToolExecution(okObj { put("scheduled", false); put("reason", "invalid_time") }) }
        val interval = a.int("intervalMinutes", 1, 525600)?.toLong()?.times(60_000)
        if (clocks != null && interval != null) return ToolExecution(okObj {
            put("scheduled", false); put("reason", "repeat_and_interval_conflict")
        })
        val zone = a.str("repeatZone") ?: ZoneId.systemDefault().id
        val days = a.strList("weekdays", 1, 7)?.map { day ->
            listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").indexOf(day.uppercase()) + 1
        }.orEmpty()
        if (days.any { it == 0 } || (clocks == null && days.isNotEmpty())) return ToolExecution(okObj {
            put("scheduled", false); put("reason", "invalid_weekdays")
        })
        val at = runCatching {
            val firstMs = first?.let { Instant.parse(it).toEpochMilli() }
            if (clocks == null) firstMs ?: error("missing time")
            else {
                ZoneId.of(zone)
                nextCalendarAt(clocks, zone, days, maxOf(System.currentTimeMillis(), (firstMs ?: 0) - 1))
            }
        }.getOrElse { return ToolExecution(okObj { put("scheduled", false); put("reason", "invalid_time") }) }
        val condition = a.str("condition", max = 2000)
        val reminder = a.str("reminder", max = 2000)
        if (reminder != null && condition != null) return ToolExecution(okObj {
            put("scheduled", false); put("reason", "reminder_and_condition_conflict")
        })
        val notifyRaw = a.str("notifyWhen")
        val cooldown = a.int("cooldownMinutes", 1, 525600)?.toLong()?.times(60_000)
        if ((notifyRaw != null || cooldown != null) && condition == null) return ToolExecution(okObj {
            put("scheduled", false); put("reason", "condition_option_without_condition")
        })
        val notifyWhen = if (notifyRaw == null) ConditionNotify.BecomesMet else ConditionNotify.ofWire(notifyRaw)
            ?: return ToolExecution(okObj { put("scheduled", false); put("reason", "invalid_notify_when") })
        val trigger = TriggerDefinition(UUID.randomUUID().toString(), thread, goal, at, interval, condition,
            idleOnly = a.bool("whenIdle", false), reminderText = reminder,
            calendarTime = clocks?.first(), calendarZone = if (clocks != null) zone else null, calendarDays = days,
            notifyWhen = notifyWhen, cooldownMs = cooldown, enabled = a.bool("enabled", true),
            calendarTimes = clocks?.takeIf { it.size > 1 }.orEmpty())
        val create = ctx.config.scheduleTrigger ?: return ToolExecution(okObj {
            put("scheduled", false); put("reason", "unavailable")
        })
        val saved = create(trigger)
        return ToolExecution(okObj {
            put("scheduled", true); put("triggerId", trigger.id); put("atMs", at)
            if (!saved.onTime) {
                put("exactAlarm", false)
                put("userNotified", saved.userNotified)
                put("message", "Saved, but the phone has not granted Mika the alarms & reminders permission, so this task may run late or not start at all. " +
                    (if (saved.userNotified) "Mika has just shown the user a notification that opens the setting. "
                    else "The user has not been shown a prompt for it this time. ") +
                    "Tell the user plainly that scheduled tasks need this permission and that it can be turned on from Mika's scheduled tasks page.")
            }
        })
    }

    /** 缺省字段保留；清空钟点/间隔、条件、提醒和冷却有显式的空值语义。验证与页面共用。 */
    private fun editedPlan(a: Args, original: TriggerDefinition): TriggerDefinition {
        val goal = a.str("goal", max = 4000) ?: original.goal
        val reminder = a.str("reminder", min = 0, max = 2000)?.trim()?.ifEmpty { null }
            .let { if (a.o["reminder"] == null || a.o["reminder"] is JsonNull) original.reminderText else it }
        val condition = a.str("condition", min = 0, max = 2000)?.trim() ?: original.conditionGoal.orEmpty()
        val clock = a.str("repeatAt", min = 0)
        val times = a.strList("repeatTimes", 0, 1440, itemMax = 8)
        if (clock != null && times != null) throw ArgError("repeatTimes", "use repeatAt or repeatTimes, not both")
        var clocks = times ?: clock?.let { if (it.isBlank()) emptyList() else listOf(it) } ?: original.calendarClocks()
        val minutes = a.int("intervalMinutes", 0, 525600)
        var interval = minutes?.toLong()?.times(60_000)?.takeIf { it > 0 } ?: original.intervalMs.takeIf { minutes == null }
        if ((times != null || clock != null) && clocks.isNotEmpty()) {
            if (minutes != null && minutes > 0) throw ArgError("intervalMinutes", "cannot combine interval and calendar clocks")
            interval = null
        } else if (minutes != null && minutes > 0) clocks = emptyList()
        val weekdays = a.strList("weekdays", 0, 7)?.map { day ->
            listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").indexOf(day.uppercase()) + 1
        } ?: original.calendarDays
        if (weekdays.any { it == 0 } || (clocks.isEmpty() && a.o["weekdays"] != null && weekdays.isNotEmpty()))
            throw ArgError("weekdays", "requires calendar clocks and valid weekdays")
        val notify = a.str("notifyWhen")?.let { ConditionNotify.ofWire(it) ?: throw ArgError("notifyWhen", "unknown mode") }
            ?: original.notifyWhen
        val cooldown = a.int("cooldownMinutes", 0, 525600)
        if (condition.isBlank() && (a.str("notifyWhen") != null || (cooldown ?: 0) > 0))
            throw ArgError("condition", "notifyWhen and cooldown require a condition")
        val at = a.str("at") ?: Instant.ofEpochMilli(original.nextAtMs).toString()
        val now = System.currentTimeMillis()
        val draft = original.toPlanDraft().copy(
            content = reminder ?: goal, reminder = reminder != null, condition = condition,
            firstAt = at, intervalMinutes = ((interval ?: 0) / 60_000).toString(),
            clock = clocks.joinToString(", "), zone = a.str("repeatZone") ?: original.calendarZone ?: ZoneId.systemDefault().id,
            weekdays = weekdays.toSet(),
            repeat = when { clocks.isNotEmpty() -> if (weekdays.isEmpty()) PlanRepeat.Daily else PlanRepeat.Weekly
                interval != null -> PlanRepeat.Interval; else -> PlanRepeat.Once },
            whenIdle = a.bool("whenIdle", original.idleOnly), enabled = a.bool("enabled", original.enabled), notifyWhen = notify,
            cooldownMinutes = cooldown?.takeIf { it > 0 }?.toString() ?: if (cooldown == null) original.cooldownMs?.let { (it / 60_000).toString() }.orEmpty() else "",
        )
        val floor = if (clocks.isNotEmpty() && a.str("at") != null) {
            val first = runCatching { Instant.parse(at).toEpochMilli() }.getOrElse { throw ArgError("at", "must be ISO 8601 with timezone") }
            maxOf(now, first - 1)
        } else now
        val updated = editedTrigger(draft, original, original.id, original.threadId, floor).copy(goal = goal, reminderText = reminder)
        val timingChanged = listOf("at", "intervalMinutes", "repeatAt", "repeatTimes", "repeatZone", "weekdays")
            .any { a.o[it] != null && a.o[it] !is JsonNull }
        return if (timingChanged) updated else updated.copy(nextAtMs = original.nextAtMs)
    }
}
