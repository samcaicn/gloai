package com.wangzan101.mika.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q2 原型验证：滚动找更多（reveal）时，contentKey 仍稳、到底能停；ordinal 位置依赖、不能当跨屏键。
 *
 * 模拟一个可滚动列表：`views` 是滚动过程中逐屏读到的元素（每滚一次，窗口下移、露出新条目）。
 */
class ScrollCursorTest {

    private fun el(ref: String, role: String, name: String): JsonObject = buildJsonObject {
        put("ref", JsonPrimitive(ref))
        put("role", JsonPrimitive(role))
        put("name", JsonPrimitive(name))
    }

    private fun view(vararg names: String): List<JsonObject> =
        names.mapIndexed { i, n -> el("@e${i + 1}", "ViewGroup", n) }

    /** 驱动一遍：按游标决定做/滚，返回处理顺序、最终 done 集合与最后一步。 */
    private fun simulate(views: List<List<JsonObject>>, role: String?, text: String?): Triple<List<String>, Set<String>, ScrollCursor.Step> {
        val cursor = ScrollCursor(role, text)
        val order = mutableListOf<String>()
        var idx = 0
        var justScrolled = false
        var final: ScrollCursor.Step = ScrollCursor.Step.Scroll
        repeat(1000) {
            if (idx >= views.size) return Triple(order, cursor.done.toSet(), ScrollCursor.Step.Scroll)
            when (val s = cursor.advance(views[idx], justScrolled)) {
                is ScrollCursor.Step.DoNext -> {
                    order += s.item.contentKey
                    cursor.finish(s.item)
                    justScrolled = false
                }
                ScrollCursor.Step.Scroll -> { idx++; justScrolled = true }
                ScrollCursor.Step.End -> { final = s; return Triple(order, cursor.done.toSet(), final) }
            }
        }
        return Triple(order, cursor.done.toSet(), final)
    }

    /** 5 条内容唯一的列表：逐条做一遍、滚到底、准确停。 */
    @Test
    fun revealsAndStopsAtEnd() {
        val views = listOf(view("A", "B", "C"), view("B", "C", "D"), view("C", "D", "E"), view("D", "E"))
        val (order, done, final) = simulate(views, "ViewGroup", null)

        // 每条恰好一次，按可见顺序 A→E；滚出屏的 A/B/C 没有重做
        assertEquals(listOf("ViewGroup|A", "ViewGroup|B", "ViewGroup|C", "ViewGroup|D", "ViewGroup|E"), order)
        assertEquals(5, done.size)
        assertTrue(final is ScrollCursor.Step.End)
    }

    /** 同名条目（contentKey 相同）会收敛成「做一次」——元素信息不足以跨滚动区分两条同名条目，这是已知边界。 */
    @Test
    fun identicalContentCollapsesToOnce() {
        val cursor = ScrollCursor("ViewGroup", null)
        val two = listOf(el("@e1", "ViewGroup", "运营"), el("@e2", "ViewGroup", "运营"))

        val s1 = cursor.advance(two, false)
        assertTrue(s1 is ScrollCursor.Step.DoNext)
        cursor.finish((s1 as ScrollCursor.Step.DoNext).item)

        // 第二条同名条目 contentKey 已被 done，被当作「做过」跳过；根治靠更细的 item 匹配（让 contentKey 唯一）
        val s2 = cursor.advance(two, false)
        assertTrue(s2 is ScrollCursor.Step.Scroll)
        assertEquals(setOf("ViewGroup|运营"), cursor.done)
    }

    /** ordinal 是位置依赖的：滚出一条同名条目后，剩下的那条 ordinal 从 1 变 0，所以不能当跨屏键。 */
    @Test
    fun ordinalIsNotStableAcrossScroll() {
        val cursor = EachCursor()
        val first = cursor.match(listOf(el("@e1", "ViewGroup", "运营"), el("@e2", "ViewGroup", "运营"), el("@e3", "ViewGroup", "C")), "ViewGroup", null)
        assertEquals(listOf(0, 1, 0), first.map { it.ordinal })

        // 滚过之后：第一条「运营」滚出屏，剩下的那条「运营」ordinal 变成 0（位置变了，键跟着变）
        val after = cursor.match(listOf(el("@e9", "ViewGroup", "运营"), el("@e4", "ViewGroup", "C")), "ViewGroup", null)
        assertEquals(listOf(0, 0), after.map { it.ordinal })
        // 但 contentKey 没变——它才是跨屏身份
        assertEquals(listOf("ViewGroup|运营", "ViewGroup|C"), after.map { it.contentKey })
    }
}
