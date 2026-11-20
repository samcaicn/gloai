package com.wangzan101.mika.core.screenmap

import com.wangzan101.mika.core.record.RunEvent

/**
 * 从运行记录确定性重建屏图（design D4）。原料是 `RunEvent.ActionTarget` 的 before/after 骨架锚点：
 * 每个动作前、后各 visit / 建屏，动作加一条转移边。屏身份 = 首次骨架锚点签名（`ScreenId.of(app, 签名)`），
 * 同签名再见到 = 同一屏（计数累加、provisional→confirmed）；不同签名 = 新屏（宁可分开不可合错）。
 * 重放顺序固定 → 同一批 events 跑两遍逐字节相同。
 */
class ScreenMapBuilder {
    fun apply(runId: String, events: List<RunEvent>): Map<String, ScreenGraph> {
        val graphs = linkedMapOf<String, MutableMap<String, ScreenNode>>()
        for (e in events) {
            val at = e as? RunEvent.ActionTarget ?: continue
            if (at.incomplete) continue
            val beforeApp = at.before.packageName
            val afterApp = at.after.packageName
            val beforeNode = beforeApp?.let { visitOrCreate(graphs, it, at.before.texts) } ?: continue
            val afterNode = afterApp?.let { visitOrCreate(graphs, it, at.after.texts) } ?: continue

            val effect = when {
                beforeApp != afterApp -> TransitionEffect.LEFT_APP
                afterNode.id != beforeNode.id -> TransitionEffect.NAVIGATED
                at.effective -> TransitionEffect.STATE_CHANGED
                else -> TransitionEffect.NONE
            }
            val to = if (effect == TransitionEffect.LEFT_APP) null else afterNode.id
            val edge = Transition(action = at.tool, target = at.target?.name?.takeIf { it.isNotEmpty() }, effect = effect, to = to, count = 1)

            val nodes = graphs.getValue(beforeApp)
            val cur = nodes.getValue(beforeNode.id)
            val key = edge.key()
            val prev = cur.transitions[key]
            nodes[beforeNode.id] = cur.copy(transitions = cur.transitions + (key to (prev?.let { it.copy(count = it.count + 1) } ?: edge)))
        }
        return graphs.mapValues { (app, nodes) -> ScreenGraph(app, nodes.toMap()) }
    }

    private fun visitOrCreate(graphs: MutableMap<String, MutableMap<String, ScreenNode>>, app: String, texts: List<String>): ScreenNode {
        val nodes = graphs.getOrPut(app) { linkedMapOf() }
        val id = ScreenId.of(app, ScreenId.signature(texts))
        val existing = nodes[id]
        return if (existing != null) {
            val updated = existing.copy(
                anchors = increment(existing.anchors, texts),
                visits = existing.visits + 1,
                status = "confirmed",
            )
            nodes[id] = updated
            updated
        } else {
            val node = ScreenNode(id = id, app = app, anchors = texts.associateWith { 1 }, visits = 1, status = "provisional")
            nodes[id] = node
            node
        }
    }

    private fun increment(anchors: Map<String, Int>, texts: List<String>): Map<String, Int> {
        val m = anchors.toMutableMap()
        for (t in texts) m[t] = (m[t] ?: 0) + 1
        return m
    }
}
