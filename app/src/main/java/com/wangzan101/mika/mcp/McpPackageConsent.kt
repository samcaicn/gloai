package com.wangzan101.mika.mcp

import com.wangzan101.mika.core.cap.mcp.McpPackageSpec

/**
 * stdio 包下载确认:同意绑定「包名 + 精确版本 + 来源」,任何一项变了都要重新确认。
 * 进程内暂存;包一旦装进工作区缓存,缓存本身就是持久化的「已同意」依据,重装/换工作区才需再确认。
 */
class McpPackageConsent {
    private val accepted = mutableSetOf<String>()

    private fun key(spec: McpPackageSpec): String = "${spec.name}\u0000${spec.version}\u0000${spec.source}"

    fun accepted(spec: McpPackageSpec): Boolean = key(spec) in accepted

    fun accept(spec: McpPackageSpec) {
        accepted += key(spec)
    }

    fun clear() {
        accepted.clear()
    }
}
