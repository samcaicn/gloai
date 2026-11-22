package com.wangzan101.mika.core.cap.devicefiles

import com.wangzan101.mika.core.workspace.DeviceFileEntry
import com.wangzan101.mika.core.workspace.DeviceFileImport
import com.wangzan101.mika.core.workspace.DeviceFileSearchQuery
import com.wangzan101.mika.core.workspace.DeviceFileSearchResult

data class DeviceFilePage(val entries: List<DeviceFileEntry>, val nextCursor: String? = null)
data class DeviceDirectoryHint(val path: String, val name: String, val description: String, val source: String, val status: String)

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
