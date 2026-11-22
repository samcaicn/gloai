package com.wangzan101.mika.core.cap.sandbox

import com.wangzan101.mika.core.workspace.DeviceFileImport
import com.wangzan101.mika.core.workspace.DeviceFileSearchQuery
import com.wangzan101.mika.core.workspace.DeviceFileSearchResult
import com.wangzan101.mika.core.workspace.SandboxError
import java.security.MessageDigest

/** Opaque /folders paths are tool identifiers, never Linux mount points or host paths. */
data class FolderEntry(val path: String, val name: String, val directory: Boolean, val writable: Boolean,
                       val size: Long? = null, val displayLocation: String? = null)
data class FolderPage(val entries: List<FolderEntry>, val truncated: Boolean = false)

fun interface FolderFileImporter {
    suspend fun importFile(path: String, workspace: String): DeviceFileImport
}

interface FolderFiles {
    fun list(path: String?): FolderPage
    suspend fun search(query: DeviceFileSearchQuery): DeviceFileSearchResult =
        throw SandboxError("unsupported", "This folder provider does not support search.", true)
    fun read(path: String): ByteArray
    /** Null expectedHash creates only; an existing file requires the hash returned by read_file. */
    fun write(path: String, content: ByteArray, expectedHash: String?, append: Boolean): ByteArray

    companion object {
        const val MAX_BYTES = 1_048_576
        fun owns(path: String) = path == "/folders" || path.startsWith("/folders/")
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun parts(path: String): List<String> {
            val parts = path.removePrefix("/folders/").split('/')
            if (!path.startsWith("/folders/") || path.length > 4096 || parts.any {
                it.isEmpty() || it == "." || it == ".." || it.length > 255 || it.any { c -> c.code < 32 || c == '\\' }
            }) throw SandboxError("invalid_folder_path", "Use a path returned by list_folder_files, without traversal segments.", true)
            return parts
        }
    }
}
