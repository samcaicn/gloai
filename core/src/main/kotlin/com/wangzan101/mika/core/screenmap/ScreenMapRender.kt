package com.wangzan101.mika.core.screenmap

/**
 * 把屏图渲染成给模型看的紧凑地图文字（design D8）。屏没有语义名（D3），名字就用骨架锚点自描述；
 * 图超长只截屏清单，不挤掉元素列表。
 */
object ScreenMapRender {
    /** 你现在在哪个屏；认不出返回空串。 */
    fun renderPosition(graph: ScreenGraph, currentId: String?): String {
        val node = currentId?.let { graph.nodes[it] } ?: return ""
        return "你现在在「${label(node)}」屏"
    }

    /**
     * 进入某 App 时给模型的屏图：有图才给，读不到屏([skeletonTexts] 为 null)不给；
     * 认得出当前屏就带「你在哪 + 从这怎么走」，认不出只给屏清单。
     */
    fun renderMapFor(graph: ScreenGraph?, skeletonTexts: Set<String>?): String? {
        if (graph == null || graph.nodes.isEmpty()) return null
        if (skeletonTexts == null) return null
        val currentId = ScreenRecognizer.identify(skeletonTexts, graph.nodes())?.id
        return renderMap(graph, currentId)
    }

    /**
     * 屏清单 + 从当前屏出发的路。超 [maxScreens] 截屏清单、超 [maxChars] 截整段。
     * 图空返回空串。
     */
    fun renderMap(graph: ScreenGraph, currentId: String?, maxScreens: Int = 12, maxChars: Int = 1200): String {
        if (graph.nodes.isEmpty()) return ""
        val lines = mutableListOf<String>()
        val all = graph.nodes.values.sortedBy { label(it) }
        lines.add("屏（${all.size}）：" + all.take(maxScreens).joinToString(" / ") { label(it) })
        val cur = currentId?.let { graph.nodes[it] }
        if (cur != null) {
            lines.add("现在在「${label(cur)}」屏")
            val outs = cur.transitions.values.filter { it.to != null && it.to != currentId }
            if (outs.isNotEmpty()) {
                lines.add("从这屏可走：")
                outs.forEach { t ->
                    val toLabel = t.to?.let { graph.nodes[it]?.let(::label) } ?: t.to.orEmpty()
                    val what = t.target ?: t.action
                    lines.add("  点「$what」→ $toLabel")
                }
            }
        }
        val out = lines.joinToString("\n")
        return if (out.length <= maxChars) out else out.take(maxChars)
    }

    private fun label(node: ScreenNode): String =
        node.anchors.keys.sorted().joinToString("、").ifEmpty { node.id.take(8) }
}
