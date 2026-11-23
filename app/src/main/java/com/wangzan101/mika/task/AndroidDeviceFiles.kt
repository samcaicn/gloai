package com.wangzan101.mika.task

import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.AttachmentLimits
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.settings.DeviceDirectory
import com.wangzan101.mika.settings.DeviceDirectoryStore
import com.wangzan101.mika.core.cap.devicefiles.DeviceDirectoryHint
import com.wangzan101.mika.core.workspace.DeviceFileEntry
import com.wangzan101.mika.core.workspace.DeviceFileImport
import com.wangzan101.mika.core.cap.devicefiles.DeviceFilePage
import com.wangzan101.mika.core.cap.devicefiles.DeviceFilesPort
import com.wangzan101.mika.core.workspace.DeviceFileSearchQuery
import com.wangzan101.mika.core.workspace.DeviceFileSearchResult
import com.wangzan101.mika.core.workspace.SandboxError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Read device files through the shell helper and copy them into the conversation workspace. */
internal class AndroidDeviceFiles(
    private val hub: HelperHub,
    private val directoryStore: DeviceDirectoryStore,
    private val copies: DeviceWorkspaceImports,
) : DeviceFilesPort {
    constructor(hub: HelperHub, dirs: SandboxDirs, sandbox: AndroidSandbox, directoryStore: DeviceDirectoryStore,
        imports: DeviceImportStore, resolveWorkspace: (String) -> WorkspaceKey = WorkspaceKey::parse,
        conversationIdFor: (String) -> String = { it },
        limits: () -> AttachmentLimits = { AttachmentLimits.DEFAULT },
    ) : this(hub, directoryStore, DeviceWorkspaceImports(dirs, sandbox::freeBytes, imports, resolveWorkspace, conversationIdFor, limits))

    private fun hint(directory: DeviceDirectory): DeviceDirectoryHint {
        val status = try { hub.listFiles(directory.path, null, 1).getString("status") ?: "helper_error" }
        catch (_: Exception) { "helper_unavailable" }
        return DeviceDirectoryHint(directory.path, directory.name, directory.description, directory.source, status)
    }

    override suspend fun listDirectoryHints(): List<DeviceDirectoryHint> = withContext(Dispatchers.IO) {
        directoryStore.directories().map(::hint)
    }

    override suspend fun updateDirectoryDescription(path: String, description: String): DeviceDirectoryHint = withContext(Dispatchers.IO) {
        val updated = try { directoryStore.updateDescription(path, description) }
        catch (_: NoSuchElementException) { throw failure("not_found", "Directory hint not found.") }
        catch (_: IllegalArgumentException) { throw failure("invalid", "Invalid directory description.") }
        hint(updated)
    }

    override suspend fun list(path: String?, cursor: String?, limit: Int): DeviceFilePage = withContext(Dispatchers.IO) {
        if (path == null) {
            val allowed = directoryStore.authorizationSnapshot().directories.map { it.path }.toSet()
            return@withContext DeviceFilePage(directoryStore.directories().filter { it.path in allowed }
                .map { DeviceFileEntry(it.name, it.path, true) })
        }
        val response = callHelper { hub.listFiles(path, cursor, limit.coerceIn(1, 128)) }
        requireOk(response)
        val names = response.getStringArrayList("names") ?: arrayListOf()
        val types = response.getIntArray("types") ?: intArrayOf()
        val sizes = response.getLongArray("sizes")?.takeIf { it.size == names.size }
        val modified = response.getLongArray("modified")?.takeIf { it.size == names.size }
        if (names.size != types.size) throw failure("helper_error", "Invalid directory response from phone helper.")
        val entries = names.mapIndexedNotNull { index, name ->
            when (types[index]) {
                1, 2 -> DeviceFileEntry(name, path.trimEnd('/') + "/" + name, types[index] == 2,
                    sizes?.get(index)?.takeIf { it >= 0 && types[index] == 1 },
                    modified?.get(index)?.takeIf { it >= 0 })
                else -> null
            }
        }
        DeviceFilePage(entries, response.getString("next"))
    }

    override suspend fun search(query: DeviceFileSearchQuery): DeviceFileSearchResult = withContext(Dispatchers.IO) {
        val response = callHelper { hub.searchFiles(query.path, query.name, query.extension, query.modifiedAfter ?: -1, query.modifiedBefore ?: -1, query.maxDepth, query.maxEntries, query.timeoutMs) }
        requireOk(response)
        val paths = response.getStringArrayList("paths") ?: throw failure("helper_error", "Invalid search response from phone helper.")
        val types = response.getIntArray("types")?.takeIf { it.size == paths.size }
        val sizes = response.getLongArray("sizes")?.takeIf { it.size == paths.size }
        val modified = response.getLongArray("modified")?.takeIf { it.size == paths.size }
        val entries = paths.mapIndexedNotNull { index, path ->
            val isDirectory = types?.get(index) == 2
            if (isDirectory && !query.includeDirectories) return@mapIndexedNotNull null
            DeviceFileEntry(
                name = File(path).name,
                path = path,
                isDirectory = isDirectory,
                size = sizes?.get(index)?.takeIf { it >= 0 && !isDirectory },
                modifiedAt = modified?.get(index)?.takeIf { it >= 0 },
            )
        }
        DeviceFileSearchResult(entries, response.getInt("visited"), response.getBoolean("truncated"), response.getString("reason"))
    }

    override suspend fun importFile(path: String, workspace: String): DeviceFileImport =
        copies.fromHelper(path, workspace) {
            val response = callHelper { hub.openFile(path) }
            requireOk(response)
            @Suppress("DEPRECATION")
            response.getParcelable<ParcelFileDescriptor>("fd")
                ?: throw failure("helper_error", "Phone helper did not return a file.")
        }

    companion object {
        /** Remove any leftover staging directory left by builds that used staged hard links. */
        suspend fun cleanupStaging(dirs: SandboxDirs) = withContext(Dispatchers.IO) {
            File(dirs.root.parentFile, "device-import-staging").takeIf { it.exists() }?.deleteRecursively()
        }
    }

    private fun requireOk(response: Bundle) {
        val status = response.getString("status") ?: "helper_error"
        if (status != "ok") throw failure(status, "Could not access the device file ($status).")
    }

    private inline fun <T> callHelper(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw failure("helper_unavailable", "Phone helper is unavailable. Reconnect it and try again.")
    }

    private fun failure(code: String, message: String) = SandboxError(code, message, recoverable = true)
}
