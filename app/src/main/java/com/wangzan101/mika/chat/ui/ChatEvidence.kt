package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.core.run.record.RunEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 结果卡片上的回答和依据文字,只从这里取。新的回答不带依据段(来源由代码从证据账本里找,见 [evidenceSource]);
 * 旧的运行记录、对话文件里模型自己写在末尾的「依据:」段照旧拆出来。[evidence] 给了就直接用。
 */
fun answerParts(answer: String, evidence: String? = null): Pair<String, String?> =
    evidence?.trim()?.takeIf { it.isNotEmpty() }?.let { answer.trim() to it } ?: splitEvidence(answer)

/**
 * 回答的来源:所在的 App 或网站([place],不知道时为 null)、相关的画面编号,和逐条的说明([items],「第 3 步 · 12306 · 车次查询」)。
 * 从运行记录里取:有代码记下的来源(sources 事件)就用它;旧记录用最后存下画面的那一步。
 */
data class EvidenceSource(val place: String?, val screenshots: List<Int>, val items: List<String> = emptyList())

/** 见 [EvidenceSource];[appLabel] 把包名换成 App 名,网站和网址显示域名。 */
fun evidenceSource(events: List<RunEvent>, appLabel: (String) -> String = { it }): EvidenceSource {
    events.filterIsInstance<RunEvent.Sources>().lastOrNull()?.items?.takeIf { it.isNotEmpty() }?.let { list ->
        fun where(p: String?) = p?.let { placeLabel(it, appLabel) }
        val items = list.map { s -> listOfNotNull(tr(R.string.step_number, s.step), where(s.place), s.title).joinToString(" · ") }
        return EvidenceSource(list.asReversed().firstNotNullOfOrNull { where(it.place) }, list.flatMap { it.mediaIds }.distinct(), items)
    }
    val shots = events.filterIsInstance<RunEvent.Media>()
    val lastStep = shots.maxOfOrNull { it.step } ?: return EvidenceSource(null, emptyList())
    val final = shots.filter { it.step == lastStep }
    val place = shots.lastOrNull { it.place != null }?.place?.let { if (it.startsWith(SITE_PREFIX)) it.removePrefix(SITE_PREFIX) else appLabel(it) }
    return EvidenceSource(place, final.map { it.mediaId }.distinct())
}

/** 运行记录里网页那一屏的「地方」写成「网站 <域名>」。 */
internal const val SITE_PREFIX = "网站 "

/** 来源里的地方:「网站 域名」和网址显示域名,包名换成 App 名,别的(命令、文件)原样。 */
private fun placeLabel(place: String, appLabel: (String) -> String): String = when {
    place.startsWith(SITE_PREFIX) -> place.removePrefix(SITE_PREFIX)
    place.startsWith("http://") || place.startsWith("https://") -> runCatching { java.net.URI(place).host }.getOrNull() ?: place
    com.wangzan101.mika.core.tool.PACKAGE_NAME_RE.matches(place) -> appLabel(place)
    else -> place
}

/** 「来源」那一行:「来源 · 12306 · 2 张截图」;没有地方、没有截图时省掉那一段。 */
fun sourceLabel(source: EvidenceSource?): String = listOfNotNull(
    tr(R.string.source_label), source?.place, source?.screenshots?.size?.takeIf { it > 0 }?.let { trPlural(R.plurals.screenshots, it) },
).joinToString(" · ")

/**
 * 把答案拆成「结论」和「依据」。旧的运行记录、对话文件里,回答的最后一行以「依据:」开头;
 * 模型不一定照做,所以也认「证据 / 来源」和句末接着写的情况,之后的都算依据。
 * 认不出来时整段都是结论,回答下面不放「来源」。
 */
fun splitEvidence(answer: String): Pair<String, String?> {
    val m = EVIDENCE.find(answer) ?: return answer.trim() to null
    val evidence = answer.substring(m.range.last + 1).trim()
    val main = answer.substring(0, m.range.first + m.groupValues[1].length).trim()
    if (evidence.isEmpty() || main.isEmpty()) return answer.trim() to null
    return main to evidence
}

// 段首或句末标点之后,可带 markdown 的 **、#、- 等前缀
private val EVIDENCE = Regex("""(^|\n|[。.!！?？]\s*)[\s*#>\-]*\**(?:依据|证据|来源)\**\s*[:：]\**""")
