package com.wangzan101.mika.core.frontdesk

/**
 * 前台不派任务就能查的两样:联网搜索、翻记忆。返回的文本已截短并标明来自外部、不可当指令;没配置时为 null。
 */
interface DeskLookup {
    suspend fun webSearch(query: String): String?

    suspend fun recallMemory(query: String): String?
}
