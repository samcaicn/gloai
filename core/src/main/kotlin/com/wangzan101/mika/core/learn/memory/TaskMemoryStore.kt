package com.wangzan101.mika.core.learn.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 整份读写;加密落盘由 app 层实现。 */
interface TaskMemoryPersistence {
    fun load(): List<TaskMemory>
    fun save(items: List<TaskMemory>)
}

/**
 * 跨会话任务摘要的唯一写入口。和 [MemoryStore] 一样:所有写方法共用一把锁,并行任务的写入串行化;
 * 写之前先读盘解密,读不出(密钥库一时不可用)时不写,免得用空表盖掉真正的文件;超出上限丢最旧的。
 * 这类摘要是机器写的经验缓存,没有敏感过滤、待批准和撤销那些记忆才有的东西。
 */
class TaskMemoryStore(
    private val persistence: TaskMemoryPersistence,
    val clock: () -> Long,
    private val ids: () -> String,
    val limit: Int = 200,
) {
    private val lock = Any()
    @Volatile private var loaded = false
    private val _items = MutableStateFlow(tryLoad() ?: emptyList())
    val items: StateFlow<List<TaskMemory>> = _items.asStateFlow()

    /** 文件这次读到了没有;false 时写入一律拒绝,下次再试。 */
    val available: Boolean get() = loaded

    fun reloadIfNeeded(): Boolean = synchronized(lock) {
        if (loaded) return true
        val got = tryLoad() ?: return false
        _items.value = got
        true
    }

    private fun tryLoad(): List<TaskMemory>? = try {
        persistence.load().also { loaded = true }
    } catch (e: Exception) {
        null
    }

    /** 写进一条摘要;读不出、编号重复、写盘失败都返回 false(调用方不依赖它成功)。 */
    fun add(task: TaskMemory): Boolean = synchronized(lock) {
        if (!reloadIfNeeded()) return false
        if (_items.value.any { it.id == task.id }) return false
        commit((_items.value + task).sortedBy { it.createdAt }.takeLast(limit))
        true
    }

    fun forget(id: String): Boolean = synchronized(lock) {
        if (!reloadIfNeeded()) return false
        if (_items.value.none { it.id == id }) return false
        commit(_items.value.filter { it.id != id })
        true
    }

    /** 用户要全部清除:读不出的原文件也一并用空表覆盖。 */
    fun clearAll(): Unit = synchronized(lock) {
        loaded = true
        commit(emptyList())
    }

    private fun commit(next: List<TaskMemory>) {
        check(loaded) { "task memory not loaded" }
        persistence.save(next)
        _items.value = next
    }
}
