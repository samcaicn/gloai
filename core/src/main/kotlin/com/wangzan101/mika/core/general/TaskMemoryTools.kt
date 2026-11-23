package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.learn.memory.TaskMemory
import com.wangzan101.mika.core.learn.memory.TaskMemoryIndex
import com.wangzan101.mika.core.learn.memory.TaskMemoryStore
import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Concurrency
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

private const val TASK_SEARCH_LIMIT = 10

private val TASK_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)

private fun taskTime(ms: Long, zone: ZoneId): String = TASK_TIME.format(Instant.ofEpochMilli(ms).atZone(zone))

/** 跨会话任务摘要的索引 / 时间线 / 单步原文,三层分开给,默认不把全量塞进上下文。 */
internal object TaskMemorySearch : AgentTool {
    override val verb = Verb.TaskMemorySearch
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("task_memory_search",
        Prompts.tool("task_memory_search"),
        mapOf(
            "query" to str("Words to look for in the task title, its one-line conclusion or its steps, 1-100 characters."),
            "app" to str("Optional package name to filter by (only tasks that touched this app)."),
            "since" to str("Optional lower bound on when the task ran, as yyyy-MM-dd."),
        ), emptyList())

    override fun available(ctx: ToolContext) = ctx.config.taskMemory != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val query = a.str("query", max = 100) ?: ""
        val app = a.str("app", max = 200)
        val since = a.str("since", max = 10)?.let { s ->
            try { LocalDate.parse(s.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            catch (e: DateTimeParseException) { return fail("since_invalid", Feedback.format("task_memory.since_invalid", "since" to s.trim())) }
        }
        val store = ctx.config.taskMemory!!
        val found = TaskMemoryIndex(store.items.value).search(query, app, since, TASK_SEARCH_LIMIT)
        return ok {
            put("tasks", JsonArray(found.map { m -> TaskMemoryIndex.hit(m).let { h ->
                buildJsonObject {
                    put("id", h.id); put("goal", h.goal); put("conclusion", h.conclusion)
                    put("apps", JsonArray(h.apps.map(::JsonPrimitive)))
                    put("sites", JsonArray(h.sites.map(::JsonPrimitive)))
                    put("at", taskTime(h.createdAt, ZoneId.systemDefault()))
                    put("steps", h.steps)
                }
            } }))
            if (found.isEmpty()) put("message", Feedback["task_memory.none"])
        }
    }
}

/** 选中一条任务摘要后,读它的时间线(只给每一步的标签,不给原文)。 */
internal object TaskMemoryTimeline : AgentTool {
    override val verb = Verb.TaskMemoryTimeline
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("task_memory_timeline",
        Prompts.tool("task_memory_timeline"),
        mapOf("id" to str("Exact id returned by task_memory_search. Never guess an id.")), listOf("id"))

    override fun available(ctx: ToolContext) = ctx.config.taskMemory != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val m = task(a.str("id", max = 200)!!, ctx.config.taskMemory!!)
            ?: return fail("not_found", Feedback["task_memory.not_found"], recoverable = true)
        val zone = ZoneId.systemDefault()
        return ok {
            put("id", m.id); put("goal", m.goal); put("conclusion", m.conclusion)
            put("apps", JsonArray(m.apps.map(::JsonPrimitive)))
            put("sites", JsonArray(m.sites.map(::JsonPrimitive)))
            put("at", taskTime(m.createdAt, zone))
            put("steps", JsonArray(m.steps.mapIndexed { i, s ->
                buildJsonObject { put("n", i + 1); put("at", taskTime(s.atMs, zone)); put("label", s.label) }
            }))
        }
    }
}

/** 时间线里的一步读原文:那一步读到的屏/页/命令输出(截短、已脱敏)。 */
internal object TaskMemoryStepRead : AgentTool {
    override val verb = Verb.TaskMemoryStep
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("task_memory_step",
        Prompts.tool("task_memory_step"),
        mapOf(
            "id" to str("Exact id returned by task_memory_search. Never guess an id."),
            "step" to int("Which step to read, from task_memory_timeline (1 = first).", 1),
        ), listOf("id", "step"))

    override fun available(ctx: ToolContext) = ctx.config.taskMemory != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val m = task(a.str("id", max = 200)!!, ctx.config.taskMemory!!)
            ?: return fail("not_found", Feedback["task_memory.not_found"], recoverable = true)
        val n = a.int("step", 1, Int.MAX_VALUE)!!
        val s = m.steps.getOrNull(n - 1)
            ?: return fail("step_not_found", Feedback.format("task_memory.step_not_found", "steps" to m.steps.size), recoverable = true)
        val zone = ZoneId.systemDefault()
        return ok {
            put("id", m.id); put("goal", m.goal)
            put("step", n); put("at", taskTime(s.atMs, zone)); put("label", s.label); put("detail", s.detail)
        }
    }

    override fun observations(o: kotlinx.serialization.json.JsonObject, r: ToolExecution, log: com.wangzan101.mika.core.tool.EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        return listOfNotNull((r.content["detail"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let {
            Observation(ObsKind.Read, it, source = "task_memory")
        })
    }
}

private fun task(id: String, store: TaskMemoryStore): TaskMemory? = store.items.value.firstOrNull { it.id == id }
