package com.wangzan101.mika.core.skills

import com.wangzan101.mika.core.knowledge.SiteMatch
import com.wangzan101.mika.core.knowledge.SkillPlaces
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.observation.PlaceKind
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.parseArgs
import com.wangzan101.mika.core.tool.placeAfter
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject

/**
 * 这次运行照着做了哪几份个人技能。用 `skill_read` 读过只说明看过,结果要记到真照着做了的技能上(#569):
 *
 * 1. 读完之后成功动过手(点、输入、打开 App、开网页、跑命令……,见 [ACTION_VERBS]);读完就回答或放弃的不算。
 * 2. 技能头部写了 App 或网站时,同一次成功动作的结果必须能确认它在其中一个地方执行。
 *
 * 都按运行记录里实际发生的算,不要模型自己申报。宁可少认:没认出来只是这次不记,认错了会让好技能被停用。
 */
object SkillFollow {
    /** [places] 给出一份技能写明的 App 和网站;不认识这份技能时为 null,按没写算。 */
    fun followed(events: List<RunEvent>, used: List<RunEvent.SkillUsed>, tools: ToolIndex, places: (String) -> SkillPlaces?): Set<String> =
        used.groupBy { it.name }.filter { (name, reads) ->
            val readAt = reads.minOf { it.atMs }
            actedAfter(events, readAt, tools, places(name) ?: SkillPlaces())
        }.keys

    private fun actedAfter(events: List<RunEvent>, at: Long, tools: ToolIndex, places: SkillPlaces): Boolean {
        val domains = places.domains.toList()
        return events.filterIsInstance<RunEvent.ToolResult>().any { result ->
            val verb = tools.verb(result.tool)
            if (result.atMs <= at || verb !in ACTION_VERBS) return@any false
            val content = parseArgs(result.resultJson)
            if (content["ok"] != JsonPrimitive(true)) return@any false
            if (!places.declared) return@any true
            val place = actionPlace(verb, content) ?: return@any false
            when (place.kind) {
                PlaceKind.App -> place.name in places.packages
                PlaceKind.Site -> SiteMatch.score(place.name, domains) != null
                else -> false
            }
        }
    }

    private fun actionPlace(verb: Verb?, content: JsonObject): Place? {
        content.argStr("actionApp")?.let { return Place.app(it) }
        content.argStr("actionSite")?.let { return Place.site(it) }
        if (verb == Verb.LaunchApp) {
            val opened = content["opened"] as? JsonObject
            opened?.takeUnless { it["picker"] == JsonPrimitive(true) || it["needsUser"] == JsonPrimitive(true) }
                ?.argStr("package")?.let { return Place.app(it) }
        }
        if (verb != Verb.LaunchApp && verb != Verb.Navigate &&
            (content["effect"] as? JsonObject)?.argStr("kind") == "place_changed") return null
        return placeAfter(verb, content)
    }
}
