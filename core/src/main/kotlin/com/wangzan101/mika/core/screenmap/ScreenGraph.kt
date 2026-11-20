package com.wangzan101.mika.core.screenmap

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一张 App 的屏图：节点 = 结构页，边 = 点击导航。由运行记录确定性重建，`nodes` 键是屏 id。
 */
@Serializable
data class ScreenGraph(val app: String, val nodes: Map<String, ScreenNode>) {
    fun nodes(): List<ScreenNode> = nodes.values.toList()

    /**
     * 从 [from] 屏到 [to] 屏的最短导航序列（BFS，只走换屏的 NAVIGATED 边）。不可达返回 null。
     * 已在 [to] 返回空序列。design D6：相对导航，没完整走过的目标也能拼段。
     */
    fun path(from: String, to: String): List<Transition>? {
        if (from == to) return emptyList()
        if (nodes[from] == null || nodes[to] == null) return null
        data class Step(val node: String, val path: List<Transition>)
        val visited = mutableSetOf(from)
        val queue = ArrayDeque<Step>()
        queue.addLast(Step(from, emptyList()))
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val node = nodes[cur.node] ?: continue
            for (t in node.transitions.values) {
                val next = t.to ?: continue // LEFT_APP 没到本图哪屏
                if (next == cur.node) continue // state_changed / none 的边停在原地
                val path = cur.path + t
                if (next == to) return path
                if (visited.add(next)) queue.addLast(Step(next, path))
            }
        }
        return null
    }

    /**
     * 把 [fragment] 按屏 id 并入:同 id 的 anchors/levels/visits/transitions 计数累加、status provisional→confirmed;
     * 新 id 追加(保留它的 status)。不同 App 的片段不并(返回原图,防归错 App)。
     */
    fun mergedWith(fragment: ScreenGraph): ScreenGraph {
        if (fragment.app != app) return this
        val merged = nodes.toMutableMap()
        for ((id, node) in fragment.nodes) {
            merged[id] = merged[id]?.let { mergeNode(it, node) } ?: node
        }
        return copy(nodes = merged)
    }

    companion object {
        /** 两条路的公共前缀长度（按转移边 key 逐条比）。供回放接图裁掉已走过的公共部分（design D6）。 */
        fun commonPrefix(a: List<Transition>, b: List<Transition>): Int {
            var i = 0
            while (i < a.size && i < b.size && a[i].key() == b[i].key()) i++
            return i
        }

        private fun mergeNode(a: ScreenNode, b: ScreenNode): ScreenNode = a.copy(
            anchors = sumCounts(a.anchors, b.anchors),
            levels = sumCounts(a.levels, b.levels),
            visits = a.visits + b.visits,
            transitions = mergeTransitions(a.transitions, b.transitions),
            status = "confirmed",
        )

        private fun sumCounts(a: Map<String, Int>, b: Map<String, Int>): Map<String, Int> {
            if (b.isEmpty()) return a
            val m = a.toMutableMap()
            for ((k, v) in b) m[k] = (m[k] ?: 0) + v
            return m
        }

        private fun mergeTransitions(a: Map<String, Transition>, b: Map<String, Transition>): Map<String, Transition> {
            if (b.isEmpty()) return a
            val m = a.toMutableMap()
            for ((k, t) in b) {
                val prev = m[k]
                m[k] = prev?.let { it.copy(count = it.count + t.count, offTrack = it.offTrack + t.offTrack) } ?: t
            }
            return m
        }
    }
}

object ScreenGraphCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(g: ScreenGraph): String = json.encodeToString(ScreenGraph.serializer(), g)
    fun decode(s: String): ScreenGraph = json.decodeFromString(ScreenGraph.serializer(), s)
}
