package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.knowledge.KnowledgeEntry
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.observation.PlaceKind
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Ordinal of the work in progress. This is deliberately independent of completion evidence. */
data class TaskStage(val number: Int, val total: Int, val label: String, val detail: String) {
    val ordinal get() = "$number/$total"
    val compact get() = "$ordinal $label".takeIf { label.isNotBlank() && it.length <= 7 } ?: ordinal
}

private data class Stage(val label: String, val detail: String, val pkg: String? = null)

class TaskStages(private val changed: () -> Unit = {}) {
    @Volatile var current: TaskStage? = null
        private set
    private var plan = emptyList<Stage>()
    private var appNames = emptyMap<String, App>()
    private var explicit = false
    private var attempted = false

    internal fun prepare(words: String, installed: List<Pair<String, String>>, knowledge: List<KnowledgeEntry>) {
        attempted = false
        appNames = stageAppNames(installed, knowledge)
        plan = orderedApps(words, appNames)
        explicit = plan.isNotEmpty()
        if (explicit) publish(1) else if (current != null) {
            current = null
            changed()
        }
    }

    /** Only an ambiguous, tool-using task needs the extra, bounded, tool-free request. */
    internal suspend fun planIfNeeded(words: String, model: ChatModel) {
        if (explicit || attempted || words.isBlank()) return
        attempted = true
        val raw = try {
            withTimeoutOrNull(6_000) {
                val message = Message(Role.User, listOf(Part.Text(words)), Author.User)
                model.complete(Prompts.role("task_stages"), listOf(message), emptyList())
                    .parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        val json = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (json.length > 4096) return
        val rows = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonArray ?: return
        if (rows.size !in 1..9) return
        val stages = rows.map {
            val row = it as? JsonObject ?: return
            val title = (row["goal"] as? JsonPrimitive)?.takeIf { v -> v.isString }?.content?.trim()
                ?.takeIf { v -> v.length in 1..64 && v.none(Char::isISOControl) } ?: return
            val label = (row["label"] as? JsonPrimitive)?.content.orEmpty()
            // Only reviewed generic labels enter the collapsed, publicly visible notification.
            val app = (row["app"] as? JsonPrimitive)?.takeIf { v -> v.isString }?.content?.trim()?.lowercase()
            Stage(label.takeIf { v -> v in actionLabels }.orEmpty(), title, appNames[app]?.pkg)
        }
        plan = stages
        publish(1)
    }

    internal fun hint(): String? {
        val stage = current ?: return null
        if (explicit || plan.size == 1 || stage.number == plan.size) return null
        val next = stage.number + 1
        return Feedback.format("stage_hint", "number" to stage.number, "current" to JsonPrimitive(stage.detail),
            "next" to next, "goal" to JsonPrimitive(plan[next - 1].detail))
    }

    internal fun reported(number: Int) {
        if (!explicit && number == (current?.number ?: return) + 1 && number <= plan.size) publish(number)
    }

    /** Only the task's own successful screen action reaches the next app stage. */
    internal fun action(verb: Verb?, args: JsonObject, result: ToolExecution) {
        val number = current?.number ?: return
        val nextApp = plan.getOrNull(number)?.pkg ?: return
        val currentApp = plan[number - 1].pkg ?: return
        if (nextApp == currentApp || result.content["ok"] != JsonPrimitive(true)) return
        val place = result.place
        val pkg = when {
            place != null -> place.takeIf { it.kind == PlaceKind.App }?.name
            verb == Verb.LaunchApp -> (args["package"] as? JsonPrimitive)?.content
            else -> null
        } ?: return
        if (pkg == nextApp) publish(number + 1)
    }

    private fun publish(number: Int) {
        val entry = plan[number - 1]
        current = TaskStage(number, plan.size, entry.label, entry.detail)
        changed()
    }
}

private val actionLabels = setOf("查票", "订票", "退票", "改签", "查址", "选店", "攻略", "比价", "搜索", "筛选", "核对",
    "整理", "汇总", "预订", "记录", "规划", "查询", "对比", "Tickets", "Book", "Refund", "Find", "Check", "Compare", "Save", "Plan", "Summarize")

private data class App(val pkg: String, val name: String, val label: String, val aliases: List<String> = emptyList())
private val commonApps = listOf(
    App("com.autonavi.minimap", "高德地图", "高德", listOf("高德")),
    App("com.dianping.v1", "大众点评", "点评", listOf("点评")),
    App("com.xingin.xhs", "小红书", "小红书"),
    App("ctrip.android.view", "携程旅行", "携程", listOf("携程")),
    App("com.MobileTicket", "铁路12306", "12306", listOf("12306")),
)
private val ambiguousOrder = Regex("或者|或是|还是|如果|任选其一|任选|任意顺序|不用|不要|别去|之前|以前|\\bor\\b|\\bbefore\\b|或(?=用|去|在|打开)", RegexOption.IGNORE_CASE)
private val appPrefix = Regex("(?:按|依次|顺序[是为：:]?|在|用|打开|到|上|切到|切换到|进入|回到|去|先|然后|接着|最后|再|\\b(?:in|on|open|then|use)\\s+)\\s*$", RegexOption.IGNORE_CASE)
private val listSeparator = Regex("\\s*(?:→|->|、|，|,|再|然后|接着)\\s*")

// “12306 查票，携程看酒店” names each app at the head of its own clause.
private val clauseBoundary = Regex("""[,，;；。!！:：→]\s*$""")
private val choiceQuestion = Regex("""^\s*(?:里|中|里面)?\s*(?:哪个|哪一个|选一个|二选一|三选一)""")

private fun stageAppNames(installed: List<Pair<String, String>>, known: List<KnowledgeEntry>): Map<String, App> {
    val installedNames = installed.toMap()
    val apps = commonApps + installed.map { (pkg, name) -> App(pkg, name, name) } +
        known.flatMap { entry -> entry.packages.map { App(it, entry.display, entry.display) } }
    val names = mutableMapOf<String, App>()
    for (app in apps.distinctBy { it.pkg }) {
        val aliases = known.filter { app.pkg in it.packages }.flatMap { entry ->
            (entry.aliases + entry.display).filter { (entry.packageAliases[it] ?: entry.packages.first()) == app.pkg }
        }
        for (name in app.aliases + app.name + installedNames[app.pkg].orEmpty() + aliases) {
            if (name.length >= 2) names.putIfAbsent(name.lowercase(), app)
        }
    }
    return names
}

private fun orderedApps(words: String, names: Map<String, App>): List<Stage> {
    if (words.isBlank() || ambiguousOrder.containsMatchIn(words)) return emptyList()
    val present = names.keys.filter { words.contains(it, ignoreCase = true) }
    if (present.isEmpty()) return emptyList()
    val pattern = present.sortedByDescending { it.length }.joinToString("|") { name ->
        val start = if (name.first().code < 128 && name.first().isLetterOrDigit()) "(?<![A-Za-z0-9])" else ""
        val end = if (name.last().code < 128 && name.last().isLetterOrDigit()) "(?![A-Za-z0-9])" else ""
        start + Regex.escape(name) + end
    }
    val mentions = Regex(pattern, RegexOption.IGNORE_CASE).findAll(words).toList()
    if (mentions.size < 2 || choiceQuestion.containsMatchIn(words.substring(mentions.last().range.last + 1))) return emptyList()
    val namesOnly = words.trim().split(Regex("""\s+""")).all { it.lowercase() in names }
    val targets = mutableListOf<MatchResult>()
    // Inspect the actual text before each name. Adjacent words can be a verb ("高德搜索")
    // or the object of a search ("前五条笔记") even when they also name installed apps.
    for ((i, match) in mentions.withIndex()) {
        val gap = words.substring(if (i == 0) 0 else mentions[i - 1].range.last + 1, match.range.first)
        if (i > 0 && gap.trim() == "或") return emptyList()
        val startsClause = if (listSeparator.matches(gap)) targets.lastOrNull() == mentions.getOrNull(i - 1)
            else clauseBoundary.containsMatchIn(gap)
        if (namesOnly || (i == 0 && gap.isBlank()) || appPrefix.containsMatchIn(gap) || startsClause) targets += match
    }
    val result = mutableListOf<Stage>()
    for ((i, match) in targets.withIndex()) {
        val app = names.getValue(match.value.lowercase())
        if (result.lastOrNull()?.pkg == app.pkg) continue
        val clause = words.substring(match.range.last + 1, targets.getOrNull(i + 1)?.range?.first ?: words.length)
        val label = if (app.pkg != "com.MobileTicket") app.label else when {
            Regex("(退票|改签)(规则|政策|手续费)").containsMatchIn(clause) -> "查询"
            "退票" in clause -> "退票"
            "改签" in clause -> "改签"
            Regex("买票|订票|购票").containsMatchIn(clause) -> "订票"
            Regex("余票|查票|查车票|查火车票|查[^，,。；;]{0,16}(?:票|车次)").containsMatchIn(clause) -> "查票"
            else -> app.label
        }
        val detail = if (label != app.label) "${app.name} · $label" else app.name
        result += Stage(label, detail, app.pkg)
    }
    return result.takeIf { it.size >= 2 }.orEmpty()
}
