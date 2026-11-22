package com.wangzan101.mika.core.workspace

/** A device-visible entry. [path] is an opaque identifier understood by this port. */
data class DeviceFileEntry(val name: String, val path: String, val isDirectory: Boolean, val size: Long? = null, val modifiedAt: Long? = null)
data class DeviceFileImport(val path: String, val size: Long)
data class DeviceFileSearchQuery(
    val path: String,
    val name: String?,
    val extension: String?,
    val modifiedAfter: Long?,
    val modifiedBefore: Long?,
    val maxDepth: Int,
    val maxEntries: Int,
    val timeoutMs: Int,
    val includeDirectories: Boolean = false,
)
data class DeviceFileSearchResult(val entries: List<DeviceFileEntry>, val visited: Int, val truncated: Boolean, val reason: String? = null)
