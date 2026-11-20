package com.wangzan101.mika.core.screenmap

/**
 * 收尾长图:把本次运行长出的屏/边片段([ScreenMapBuilder.apply] 的产物)按屏 id 并进已存图。
 * 每 App 读旧图 → [ScreenGraph.mergedWith] 合并 → 写回;读写失败往上抛,由调用方 guarded(design D4:失败不影响任务)。
 */
object ScreenMapGrower {
    fun grow(store: ScreenMapStore, fragments: Map<String, ScreenGraph>) {
        for ((app, fragment) in fragments) {
            if (fragment.nodes.isEmpty()) continue
            val existing = store.load(app) ?: ScreenGraph(app, emptyMap())
            store.save(app, existing.mergedWith(fragment))
        }
    }
}
