package com.wangzan101.mika.core.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Q3+Q4 原型验证：就地循环与失败语义。
 */
class EachRunnerTest {

    private fun el(ref: String, role: String, name: String): JsonObject = buildJsonObject {
        put("ref", JsonPrimitive(ref))
        put("role", JsonPrimitive(role))
        put("name", JsonPrimitive(name))
    }

    /** Q3：就地循环（做一条就地移除一条，列表上移）与「打开→返回」对执行器无差别，contentKey 照常去重。 */
    @Test
    fun inPlaceRemovalDedupsWhenItemsShiftUp() = runBlocking {
        val list = mutableListOf("A", "B", "C")
        val runner = EachRunner("ViewGroup", null, EachRunner.OnError.Stop)
        val processed = mutableListOf<String>()

        val summary = runner.run(
            read = { list.mapIndexed { i, n -> el("@e$i", "ViewGroup", n) } },
            scroll = { /* 就地循环没有滚动 */ },
            doItem = { item -> processed += item.name; list.remove(item.name); true },
        )

        assertEquals(listOf("A", "B", "C"), processed)  // 每条恰好一次，尽管列表逐条上移
        assertEquals(3, summary.done)
        assertEquals(0, summary.failed)
        assertNull(summary.stoppedAt)
    }

    /** Q4 Stop（安全默认）：第一条失败就停，失败那条不记 done（留给上层决定重试），后面的不再做。 */
    @Test
    fun stopHaltsAtFirstFailure() = runBlocking {
        val items = listOf("A", "B", "C", "D", "E")
        val runner = EachRunner("ViewGroup", null, EachRunner.OnError.Stop)
        val summary = runner.run(
            read = { items.mapIndexed { i, n -> el("@e$i", "ViewGroup", n) } },
            scroll = { },
            doItem = { it.name != "B" },   // B 失败
        )
        assertEquals(1, summary.done)                    // 只有 A
        assertEquals(1, summary.failed)                  // B
        assertEquals("ViewGroup|B", summary.stoppedAt)   // 停在 B
        assertEquals(listOf("ViewGroup|A", "ViewGroup|B"), summary.perItem.map { it.contentKey })
    }

    /** Q4 Skip：失败也记 done、继续做后面的，投递类「一条失败不挡一屏」用这个。 */
    @Test
    fun skipContinuesPastFailure() = runBlocking {
        val items = listOf("A", "B", "C", "D", "E")
        val runner = EachRunner("ViewGroup", null, EachRunner.OnError.Skip)
        val summary = runner.run(
            read = { items.mapIndexed { i, n -> el("@e$i", "ViewGroup", n) } },
            scroll = { },
            doItem = { it.name != "B" },
        )
        assertEquals(4, summary.done)                    // A,C,D,E
        assertEquals(1, summary.failed)                  // B
        assertNull(summary.stoppedAt)                    // 跑完，没停
        assertEquals(5, summary.perItem.size)
        assertEquals(
            listOf(true, false, true, true, true),
            summary.perItem.map { it.ok },
        )
    }
}
