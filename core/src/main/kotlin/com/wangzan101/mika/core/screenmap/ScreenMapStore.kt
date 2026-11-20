package com.wangzan101.mika.core.screenmap

/** 屏图的存取口。读写失败等于「没有图」，调用方只计数、不影响任务（design D8）。 */
interface ScreenMapStore {
    fun load(app: String): ScreenGraph?
    fun save(app: String, graph: ScreenGraph)
    fun apps(): List<String>
}
