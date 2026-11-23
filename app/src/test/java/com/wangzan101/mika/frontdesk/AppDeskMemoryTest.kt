package com.wangzan101.mika.frontdesk

import com.wangzan101.mika.core.learn.memory.MemoryItem
import com.wangzan101.mika.core.learn.memory.MemoryKind
import com.wangzan101.mika.core.learn.memory.MemorySource
import com.wangzan101.mika.core.learn.memory.MemoryStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

class AppDeskMemoryTest {
    private fun item(id: String, content: String, keywords: List<String> = emptyList()) =
        MemoryItem(id, MemoryKind.preference, content, keywords, MemorySource("manual", atMs = 1), MemoryStatus.active, createdAt = 1, updatedAt = 1)

    @Test fun recallsMatchingItems() = runBlocking {
        val memory = AppDeskMemory({ true }, { listOf(item("a", "喜欢靠窗的座位", listOf("座位")), item("b", "住在上海")) })
        assertEquals("- 喜欢靠窗的座位", memory.recall("订票要靠窗座位"))
    }

    @Test fun switchedOffOrNothingFoundIsNull() = runBlocking {
        assertNull(AppDeskMemory({ false }, { listOf(item("a", "喜欢靠窗的座位", listOf("座位"))) }).recall("座位"))
        assertNull(AppDeskMemory({ true }, { listOf(item("a", "住在上海")) }).recall("天气"))
    }

    @Test fun totalLengthIsCapped() = runBlocking {
        val items = (1..5).map { item("i$it", "座位偏好第${it}条" + "很".repeat(600), listOf("座位")) }
        val text = AppDeskMemory({ true }, { items }).recall("座位")!!
        assertTrue(text.length <= DESK_MEMORY_MAX_CHARS)
        assertTrue(text.isNotEmpty())
    }

    // 读记忆卡住时只少这一段,不能把前台拖住。
    @Test fun hungLookupTimesOut() = runBlocking {
        val never = CountDownLatch(1)
        val memory = AppDeskMemory({ true }, { never.await(); emptyList() }, timeoutMs = 200)
        assertNull(memory.recall("座位"))
    }
}
