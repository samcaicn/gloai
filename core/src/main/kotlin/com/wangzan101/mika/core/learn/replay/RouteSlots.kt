package com.wangzan101.mika.core.learn.replay

/**
 * 从模板的固定字面片段之间取参数,保留完整原文(包括参数首尾的空白)。只接受唯一的分法。
 * 不做语义改写;复杂/歧义模板交还原模型,且两条路径都经 RouteMatcher.validated 校验。
 */
internal object RouteSlots {
    private val SLOT = Regex("\\{(\\d+)\\}")
    private const val MAX_CHARS = 8_000
    private const val MAX_SLOTS = 8
    private const val MAX_SEARCH = 128

    fun extract(route: Route, goal: String): List<String>? {
        if (goal.length > MAX_CHARS || route.template.length > MAX_CHARS || route.slots !in 0..MAX_SLOTS) return null
        val holes = SLOT.findAll(route.template).toList()
        val ids = holes.map { it.groupValues[1].toIntOrNull() ?: return null }
        // 记录器按操作先后编号,不一定按原话顺序;重复或缺少编号的模板不猜。
        if (ids.sorted() != (1..route.slots).toList()) return null
        val parts = SLOT.split(route.template)
        if (holes.isEmpty()) return emptyList<String>().takeIf {
            goal.filterNot(Char::isWhitespace) == parts.single().filterNot(Char::isWhitespace)
        }
        // 参数边界按原始固定文字匹配,不能先删空白再把缩进或结尾换行丢掉。
        // 固定片段的空白写法不同就交还原模型,不猜哪些空白属于参数。
        if (!goal.startsWith(parts.first())) return null
        if (parts.drop(1).dropLast(1).any(String::isBlank)) return null
        val slots = MutableList(route.slots) { "" }
        val matches = mutableListOf<List<String>>()
        var searched = 0
        var exhausted = false
        fun visit(hole: Int, start: Int) {
            if (matches.size > 1 || exhausted) return
            if (++searched > MAX_SEARCH) { exhausted = true; return }
            if (hole == holes.size) {
                if (start == goal.length && RouteMatcher.validated(route, slots, goal) != null) matches += slots.toList()
                return
            }
            val next = parts[hole + 1]
            if (next.isEmpty()) {
                if (start < goal.length) {
                    slots[ids[hole] - 1] = goal.substring(start)
                    visit(hole + 1, goal.length)
                }
                return
            }
            var end = goal.indexOf(next, start + 1)
            while (end >= 0 && matches.size <= 1 && !exhausted) {
                slots[ids[hole] - 1] = goal.substring(start, end)
                visit(hole + 1, end + next.length)
                end = goal.indexOf(next, end + 1)
            }
        }
        visit(0, parts.first().length)
        return matches.singleOrNull().takeUnless { exhausted }
    }
}
