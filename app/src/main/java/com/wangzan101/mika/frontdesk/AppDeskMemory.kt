package com.wangzan101.mika.frontdesk

import com.wangzan101.mika.core.learn.memory.MemoryIndex
import com.wangzan101.mika.core.learn.memory.MemoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/** 放进前台上下文的相关记忆总共最多这么多字。 */
const val DESK_MEMORY_MAX_CHARS = 1_500

/** 每句话最多取这么多条相关记忆。 */
private const val DESK_MEMORY_ITEMS = 5

/** 查一次记忆最多等这么久;读记忆文件卡住时只少这一段,前台照常说话。 */
const val DESK_MEMORY_TIMEOUT_MS = 3_000L

/**
 * 前台每句用户原话的相关记忆:记忆总开关([enabled])开着时按这句话检索,一条一行,总长不超过 [DESK_MEMORY_MAX_CHARS];
 * 开关关着、没检索到、或超过 [timeoutMs] 还没读出来时为 null。只读本机记忆,不联网。
 */
class AppDeskMemory(
    private val enabled: () -> Boolean,
    private val items: () -> List<MemoryItem>,
    private val timeoutMs: Long = DESK_MEMORY_TIMEOUT_MS,
) {
    suspend fun recall(text: String): String? {
        if (!enabled()) return null
        return withTimeoutOrNull(timeoutMs) {
            runInterruptible(Dispatchers.IO) { render(MemoryIndex(items()).search(text, DESK_MEMORY_ITEMS).map { it.first }) }
        }
    }

    private fun render(found: List<MemoryItem>): String? {
        val out = StringBuilder()
        for (item in found) {
            val line = "- " + item.content
            val next = if (out.isEmpty()) line else "\n$line"
            if (out.length + next.length > DESK_MEMORY_MAX_CHARS) {
                // 第一条就超长时截短放进去,别的放不下就不放了。
                if (out.isEmpty()) out.append(line.takeWhole(DESK_MEMORY_MAX_CHARS))
                break
            }
            out.append(next)
        }
        return out.toString().ifBlank { null }
    }
}
