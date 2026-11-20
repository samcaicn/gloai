package com.wangzan101.mika.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Q1 原型验证：稳定键（contentKey = role|name）能否跨快照「认同一条、跳过已做」。
 *
 * 元素结构照真实读屏结果（ref/role/name/center/bounds），这里只读 ref/role/name——center/bounds
 * 属于真正点按时的定位，不参与去重键。快照间的「重排 ref」照真实行为模拟（ref 每次读屏都重新分配）。
 */
class EachCursorTest {

    private val cursor = EachCursor()

    private fun el(ref: String, role: String, name: String): JsonObject = buildJsonObject {
        put("ref", JsonPrimitive(ref))
        put("role", JsonPrimitive(role))
        put("name", JsonPrimitive(name))
    }

    /** 同一个列表读了两次：ref 全部重排，但 contentKey 跨快照一致。 */
    @Test
    fun refsChangeAcrossSnapshotsButContentKeysStayStable() {
        val first = cursor.match(
            listOf(el("@e1", "ViewGroup", "Java工程师"), el("@e2", "ViewGroup", "产品经理"), el("@e3", "ViewGroup", "运营")),
            role = "ViewGroup", text = null,
        )
        val second = cursor.match(
            listOf(el("@e9", "ViewGroup", "Java工程师"), el("@e4", "ViewGroup", "产品经理"), el("@e7", "ViewGroup", "运营")),
            role = "ViewGroup", text = null,
        )
        // ref 变了（这正是不能用 ref 当键的原因）
        assertEquals(listOf("@e1", "@e2", "@e3"), first.map { it.ref })
        assertEquals(listOf("@e9", "@e4", "@e7"), second.map { it.ref })
        // 但稳定键跨快照一致
        assertEquals(first.map { it.contentKey }, second.map { it.contentKey })
        assertEquals(listOf("ViewGroup|Java工程师", "ViewGroup|产品经理", "ViewGroup|运营"), first.map { it.contentKey })
    }

    /** 同名条目在同一快照里用 ordinal 区分，不重不漏。 */
    @Test
    fun identicalNamesGetOrdinalWithinSnapshot() {
        val items = cursor.match(
            listOf(el("@e1", "ViewGroup", "运营"), el("@e2", "ViewGroup", "运营"), el("@e3", "ViewGroup", "产品经理")),
            role = "ViewGroup", text = null,
        )
        // contentKey 相同（说明内容不可区分），ordinal 是同一屏内区分它们的唯一办法
        assertEquals(listOf("ViewGroup|运营", "ViewGroup|运营", "ViewGroup|产品经理"), items.map { it.contentKey })
        assertEquals(listOf(0, 1, 0), items.map { it.ordinal })
    }

    /** 按文字筛选条目：只匹配名字里带关键字的，其余忽略。 */
    @Test
    fun matchFiltersByText() {
        val items = cursor.match(
            listOf(el("@e1", "Button", "打招呼"), el("@e2", "ViewGroup", "Java工程师 · 打招呼"), el("@e3", "Button", "打招呼")),
            role = "Button", text = "打招呼",
        )
        assertEquals(listOf("@e1", "@e3"), items.map { it.ref })
        assertEquals(listOf(0, 1), items.map { it.ordinal })
    }

    /** 点按要落在可点祖先上：actionRef 优先于自身 ref（命中非可点的文字节点时，ref 是不可点的那个）。 */
    @Test
    fun actionRefPreferredOverOwnRef() {
        val items = cursor.match(
            listOf(buildJsonObject {
                put("ref", JsonPrimitive("@t1"))
                put("actionRef", JsonPrimitive("@e1"))
                put("role", JsonPrimitive("TextView"))
                put("name", JsonPrimitive("打招呼"))
            }),
            role = null, text = null,
        )
        assertEquals(listOf("@e1"), items.map { it.ref })
    }
}
