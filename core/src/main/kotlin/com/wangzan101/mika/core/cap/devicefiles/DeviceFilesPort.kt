package com.wangzan101.mika.core.cap.devicefiles

/** A device-visible entry. [path] is an opaque identifier understood by this port. */
data class DeviceFileEntry(val name: String, val path: String, val isDirectory: Boolean, val size: Long? = null, val modifiedAt: Long? = null)
data class DeviceFilePage(val entries: List<DeviceFileEntry>, val nextCursor: String? = null)
data class DeviceFileImport(val path: String, val size: Long)
data class DeviceDirectoryHint(val path: String, val name: String, val description: String, val source: String, val status: String)
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

/**
 * Platform access to user-visible device files. Null path lists accessible roots.
 * Check platform permissions and allowed roots on every call; never expose app-private files.
 * List pages must be stable and contain at most [limit] entries.
 * Import only regular files into the named conversation's workspace, under its tree lock,
 * with size and storage limits and no overwrite. Remove partial files on failure or cancellation.
 * Return the actual /workspace path and size; throw SandboxError with a safe message on expected errors.
 */
interface DeviceFilesPort {
    suspend fun list(path: String?, cursor: String?, limit: Int): DeviceFilePage
    suspend fun importFile(path: String, workspace: String): DeviceFileImport
    suspend fun search(query: DeviceFileSearchQuery): DeviceFileSearchResult
    // Platform implementations supply persisted hints and validate updates against existing directories.
    suspend fun listDirectoryHints(): List<DeviceDirectoryHint> = throw com.wangzan101.mika.core.workspace.SandboxError("unsupported", "Directory hints are unavailable.", true)
    suspend fun updateDirectoryDescription(path: String, description: String): DeviceDirectoryHint = throw com.wangzan101.mika.core.workspace.SandboxError("unsupported", "Directory hints are unavailable.", true)
}
