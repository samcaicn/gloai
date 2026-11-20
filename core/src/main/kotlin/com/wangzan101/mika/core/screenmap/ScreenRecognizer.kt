package com.wangzan101.mika.core.screenmap

/** 认屏结果：认成哪个屏 [id]、置信度 [confidence]、命中了哪些骨架锚点 [matched]。 */
data class Recognition(val id: String, val confidence: Double, val matched: Set<String>)

/**
 * 认屏：纯函数、零模型调用。给当前屏的骨架文字集合 [texts]（复用 `RouteAnchors.texts` 的口径，
 * 去数字、去列表内容、去输入框、去用户打的字），在 [nodes] 里找最像的一屏。
 *
 * 排序靠 `(重合数, 覆盖率, 稀有度)` 折成的一个分：`confidence = 覆盖率 × 稀有度均值`。
 * 覆盖率 = |S ∩ anchors| / |anchors|；稀有度 = Σ 1/max(1, 该文字在多少屏出现过)。
 * 「确定/返回」哪都有，稀有度把置信度压到阈值下 → 不知道就返回 null，不猜（design D2）。
 */
object ScreenRecognizer {
    fun identify(texts: Set<String>, nodes: List<ScreenNode>, confidenceMin: Double = 0.5): Recognition? {
        if (nodes.isEmpty() || texts.isEmpty()) return null
        // 每个骨架文字在多少个屏的 anchors 里出现过（文档频率）
        val df = nodes.flatMap { it.anchors.keys }.groupingBy { it }.eachCount()

        var best: ScreenNode? = null
        var bestScore = -1.0
        var bestMatched: Set<String> = emptySet()
        var bestOverlap = 0

        for (node in nodes) {
            if (node.anchors.isEmpty()) continue
            val matched = node.anchors.keys.filterTo(linkedSetOf()) { it in texts }
            if (matched.isEmpty()) continue
            val coverage = matched.size.toDouble() / node.anchors.size
            val rarityMean = matched.sumOf { 1.0 / maxOf(1, df[it] ?: 1) } / matched.size
            val score = coverage * rarityMean
            // 分数相同取重合数多者
            if (score > bestScore || (score == bestScore && matched.size > bestOverlap)) {
                best = node
                bestScore = score
                bestMatched = matched
                bestOverlap = matched.size
            }
        }

        val node = best ?: return null
        if (bestScore < confidenceMin) return null
        return Recognition(node.id, bestScore, bestMatched)
    }
}
