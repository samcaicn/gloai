package com.wangzan101.mika.personal

import com.wangzan101.mika.core.learn.memory.MemoryContext
import com.wangzan101.mika.core.learn.memory.MemoryItem
import com.wangzan101.mika.core.learn.memory.MemoryKind
import com.wangzan101.mika.core.learn.memory.MemorySource
import com.wangzan101.mika.core.learn.memory.MemoryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalMemoryTextTest {
    private fun item(i: Int, content: String, type: String = "user_said", status: MemoryStatus = MemoryStatus.active, pinned: Boolean = false) = MemoryItem(
        id = "m$i", kind = MemoryKind.fact, content = content, keywords = emptyList(),
        source = MemorySource(type, atMs = 0L), status = status, pinned = pinned, createdAt = 0L, updatedAt = i.toLong(),
    )

    // 置顶在前、新的在前;没批准的不带;Mika 自己归纳的那条,标记在最前面
    @Test fun ordersItemsAndMarksInferredOnesUpFront() {
        val text = personalMemoryText(listOf(
            item(1, "用户喜欢喝茶"),
            item(2, "用户常在张江一带活动", type = MemoryContext.INFERRED),
            item(3, "置顶的事", pinned = true),
            item(4, "还没批准的做法", status = MemoryStatus.pending),
        ))
        assertEquals(listOf("置顶的事", PERSONAL_INFERRED_MARK + "用户常在张江一带活动", "用户喜欢喝茶"), text.lines())
    }

    // 装到上限为止按整条算:每一条要么完整带着(归纳的连同标记),要么不带,不会只剩半条、丢了「不是用户说的」
    @Test fun neverCutsAnItemOrItsMarkAtTheLimit() {
        val body = "字".repeat(200)
        // 32 条各 200 字之后,正好轮到一条归纳的:旧写法整段截到 6500 字,这一条只剩前 68 个字、标记在后面被截掉
        val items = (1..32).map { item(100 - it, body) } + item(1, "归".repeat(200), type = MemoryContext.INFERRED) + (1..7).map { item(-it, body) }
        val text = personalMemoryText(items)
        assertTrue(text.length <= 6500)
        val lines = text.lines()
        assertTrue(lines.all { it == body || it == PERSONAL_INFERRED_MARK + "归".repeat(200) })
        assertFalse("归纳的那条装不下就整条不带", lines.any { it.startsWith("归") })
        // 上限放宽一点就整条带上,标记在前
        assertTrue(personalMemoryText(items, max = 7000).lines().any { it == PERSONAL_INFERRED_MARK + "归".repeat(200) })
    }
}
