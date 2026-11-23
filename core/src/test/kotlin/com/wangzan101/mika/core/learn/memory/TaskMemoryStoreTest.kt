package com.wangzan101.mika.core.learn.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskMemoryStoreTest {
    private class FakePersistence(var stored: List<TaskMemory> = emptyList()) : TaskMemoryPersistence {
        var saves = 0
        override fun load() = stored
        override fun save(items: List<TaskMemory>) { saves++; stored = items }
    }

    private var now = 1_000L
    private var seq = 0
    private fun store(p: FakePersistence = FakePersistence(), limit: Int = 200) =
        TaskMemoryStore(p, clock = { now }, ids = { "id${++seq}" }, limit = limit)

    private fun task(id: String, createdAt: Long = now, goal: String = "目标") =
        TaskMemory(id, goal, "结论", emptyList(), emptyList(), createdAt, emptyList())

    @Test fun addForgetClearAll() {
        val s = store()
        assertTrue(s.add(task("a")))
        assertTrue(s.add(task("b", goal = "另一个")))
        assertEquals(listOf("a", "b"), s.items.value.map { it.id })
        assertTrue(s.forget("a"))
        assertEquals(listOf("b"), s.items.value.map { it.id })
        assertFalse(s.forget("nope"))
        s.clearAll()
        assertTrue(s.items.value.isEmpty())
    }

    @Test fun limitDropsOldest() {
        val s = store(limit = 2)
        assertTrue(s.add(task("1", createdAt = 1L)))
        assertTrue(s.add(task("2", createdAt = 2L)))
        assertTrue(s.add(task("3", createdAt = 3L)))
        assertEquals(listOf("2", "3"), s.items.value.map { it.id })
    }

    @Test fun duplicateIdRejected() {
        val s = store()
        assertTrue(s.add(task("a")))
        assertFalse(s.add(task("a", goal = "重复")))
        assertEquals(1, s.items.value.size)
    }

    @Test fun unreadableFileNeverOverwrittenThenRetried() {
        var failures = 2
        val p = object : TaskMemoryPersistence {
            var stored = listOf(task("old"))
            var saves = 0
            override fun load(): List<TaskMemory> { if (failures-- > 0) throw java.security.KeyStoreException("busy"); return stored }
            override fun save(items: List<TaskMemory>) { saves++; stored = items }
        }
        val s = TaskMemoryStore(p, clock = { now }, ids = { "id${++seq}" })
        assertFalse(s.available)
        assertTrue(s.items.value.isEmpty())
        assertFalse(s.add(task("new"))) // 前两次读都失败,拒绝写入
        assertEquals(0, p.saves)
        // 第三次读到了:原有的还在,新的加在后面
        assertTrue(s.add(task("new")))
        assertTrue(s.available)
        assertEquals(listOf("old", "new"), p.stored.map { it.id })
    }

    @Test fun clearAllWorksEvenWhenFileUnreadable() {
        val p = object : TaskMemoryPersistence {
            override fun load(): List<TaskMemory> = throw java.security.KeyStoreException("busy")
            override fun save(items: List<TaskMemory>) = Unit
        }
        val s = TaskMemoryStore(p, clock = { now }, ids = { "id${++seq}" })
        s.clearAll() // 清除不依赖读到文件
        assertTrue(s.available)
        assertTrue(s.items.value.isEmpty())
    }
}
