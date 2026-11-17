package com.wangzan101.mika.core.skills

import com.wangzan101.mika.core.knowledge.SiteMatch
import com.wangzan101.mika.core.knowledge.SkillPlaces
import com.wangzan101.mika.core.net.UrlHost
import com.wangzan101.mika.core.observation.PlaceKind
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.ToolIndex

/**
 * 这次运行照着做了哪几份个人技能。用 `skill_read` 读过只说明看过,结果要记到真照着做了的技能上(#569):
 *
 * 1. 读完之后成功动过手(点、输入、打开 App、开网页、跑命令……,见 [ACTION_VERBS]);读完就回答或放弃的不算。
 * 2. 技能头部写了 App 或网站时,读完之后进过其中一个(读到过它的界面或页面);没写的只看第 1 条。
 *
 * 都按运行记录里实际发生的算,不要模型自己申报。宁可少认:没认出来只是这次不记,认错了会让好技能被停用。
 */
object SkillFollow {
    /** [places] 给出一份技能写明的 App 和网站;不认识这份技能时为 null,按没写算。 */
    fun followed(events: List<RunEvent>, used: List<RunEvent.SkillUsed>, tools: ToolIndex, places: (String) -> SkillPlaces?): Set<String> =
        used.groupBy { it.name }.filter { (name, reads) ->
            val readAt = reads.minOf { it.atMs }
            actedAfter(events, readAt, tools) && visitedAfter(events, readAt, places(name) ?: SkillPlaces())
        }.keys

    private fun actedAfter(events: List<RunEvent>, at: Long, tools: ToolIndex): Boolean = events.any {
        it is RunEvent.ToolResult && it.atMs > at && tools.verb(it.tool) in ACTION_VERBS && !it.resultJson.contains("\"ok\":false")
    }

    /**
     * 读完之后进过技能写明的 App 或网站。地方从两处认:读到的界面(读屏记录),和每次调用结果里报的 `currentApp` / `currentSite`
     * ——界面一个字也读不出来(自己画界面的 App)时没有读屏记录,结果里照样报着在哪个 App。
     */
    private fun visitedAfter(events: List<RunEvent>, at: Long, places: SkillPlaces): Boolean {
        if (!places.declared) return true
        val domains = places.domains.toList()
        fun app(pkg: String) = pkg in places.packages
        fun site(host: String) = SiteMatch.score(host, domains) != null
        return events.any { e ->
            when {
                e.atMs <= at -> false
                e is RunEvent.Observed -> e.place?.let { place ->
                    when (place.kind) {
                        PlaceKind.App -> app(place.name)
                        PlaceKind.Site -> site(place.name)
                        PlaceKind.Page -> UrlHost.of(place.name)?.let(::site) == true
                        else -> false
                    }
                } == true
                e is RunEvent.ToolResult ->
                    CURRENT_APP.findAll(e.resultJson).any { app(it.groupValues[1]) } ||
                        CURRENT_SITE.findAll(e.resultJson).any { site(it.groupValues[1]) }
                else -> false
            }
        }
    }

    // 运行记录里的结果是截短过的 JSON 文本,不整段解析,只找这两个字段
    private val CURRENT_APP = Regex(""""currentApp"\s*:\s*"([^"]+)"""")
    private val CURRENT_SITE = Regex(""""currentSite"\s*:\s*"([^"]+)"""")
}
