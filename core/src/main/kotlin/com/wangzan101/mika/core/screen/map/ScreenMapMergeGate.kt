package com.wangzan101.mika.core.screen.map

import com.wangzan101.mika.core.run.record.RunEvent

/**
 * merge gate 自检（design D7）：两道必须为 0 的关卡，作为 `screen-map` 的自检/测试用，不是运行指标。
 * 拦「合错」这一向（两个不同的页被当成一个、或屏归错了 App）；「拆」的方向不设门槛（D9）。
 */
object ScreenMapMergeGate {
    /**
     * 两个标了不同语义名、却被 [ScreenRecognizer.identify] 认成同一屏的屏 → 合错的嫌疑。
     * 返回 (被认错的屏 id, 被认成了谁) 的对；[labeled] 缺名（空/不标）时不判（本版屏不标名，天然为空）。
     */
    fun wrongMerge(nodes: List<ScreenNode>, labeled: Map<String, String>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (a in nodes) {
            val labelA = labeled[a.id] ?: continue
            val rec = ScreenRecognizer.identify(a.anchors.keys.toSet(), nodes) ?: continue
            if (rec.id == a.id) continue
            val labelB = labeled[rec.id] ?: continue
            if (labelA != labelB) out += a.id to rec.id
        }
        return out
    }

    /**
     * 事件里的屏被记到了错的 App 下：同一屏签名出现在两个不同的非空包名下（且两个包名都已有图）。
     * 归 system / unknown（包名为 null）或没图的包名不算（D7「允许，只是不记」）。返回这些被归错 App 的屏签名。
     */
    fun wrongOwner(events: List<RunEvent>, graphs: Map<String, ScreenGraph>): List<String> {
        val owners = mutableMapOf<String, MutableSet<String>>()
        for (e in events) {
            val at = e as? RunEvent.ActionTarget ?: continue
            if (at.incomplete) continue
            for (a in listOf(at.before, at.after)) {
                val pkg = a.packageName ?: continue
                if (pkg !in graphs) continue
                val sig = ScreenId.signature(a.texts)
                owners.getOrPut(sig) { mutableSetOf() }.add(pkg)
            }
        }
        return owners.filterValues { it.size > 1 }.keys.sorted()
    }
}
