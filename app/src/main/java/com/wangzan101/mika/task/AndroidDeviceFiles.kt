package com.wangzan101.mika.task

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.Attachments
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.settings.DeviceDirectory
import com.wangzan101.mika.settings.DeviceDirectoryStore
import com.wangzan101.mika.core.cap.devicefiles.DeviceDirectoryHint
import com.wangzan101.mika.core.cap.devicefiles.DeviceFileEntry
import com.wangzan101.mika.core.cap.devicefiles.DeviceFileImport
import com.wangzan101.mika.core.cap.devicefiles.DeviceFilePage
import com.wangzan101.mika.core.cap.devicefiles.DeviceFilesPort
import com.wangzan101.mika.core.cap.devicefiles.DeviceFileSearchQuery
import com.wangzan101.mika.core.cap.devicefiles.DeviceFileSearchResult
import com.wangzan101.mika.core.workspace.SandboxError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/** Read device files through the shell helper and copy them into the conversation workspace. */
internal class AndroidDeviceFiles(
    private val hub: HelperHub,
    private val dirs: SandboxDirs,
    private val sandbox: AndroidSandbox,
    private val directoryStore: DeviceDirectoryStore,
    private val imports: DeviceImportStore,
    private val resolveWorkspace: (String) -> WorkspaceKey = WorkspaceKey::parse,
    private val conversationIdFor: (String) -> String = { it },
) : DeviceFilesPort {
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

    override suspend fun importFile(path: String, workspace: String): DeviceFileImport = dirs.tree.shared {
        importLock.withLock { withContext(Dispatchers.IO) {
            val response = callHelper { hub.openFile(path) }
            requireOk(response)
            @Suppress("DEPRECATION")
            val descriptor = response.getParcelable<ParcelFileDescriptor>("fd")
                ?: throw failure("helper_error", "Phone helper did not return a file.")
            descriptor.use { fd ->
                val metadata = metadata(fd)
                val key = resolveWorkspace(workspace)
                val convId = conversationIdFor(workspace)
                val records = try { imports.listByWorkspace(key.wire) } catch (e: Exception) {
                    throw failure("record_error", "Could not read device import records.")
                }
                if (metadata != null) {
                    val existing = records.asReversed().firstOrNull { record ->
                        record.sourcePath == path && record.size == metadata.size &&
                            record.sourceModifiedSeconds == metadata.seconds && record.sourceModifiedNanos == metadata.nanos &&
                            readableCopy(key, record)
                    }
                    if (existing != null) return@withContext DeviceFileImport(existing.workspacePath, existing.size)
                }
                val root = dirs.workspace(key)
                val base = Attachments.safeName(File(path).name)
                val result = Attachments.importOne(
                    root,
                    dirs.gate,
                    base,
                    sandbox::freeBytes,
                    { AndroidSandbox.treeBytes(root.toPath()) },
                    { ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd.fileDescriptor)) },
                    logFailure = { e -> Log.w(TAG, "Device file import failed for \"$base\" (${e.javaClass.simpleName}: ${e.message})") },
                )
                val entry = result.added.singleOrNull()
                if (entry == null) {
                    val reason = result.failures.firstOrNull()?.reason?.name?.lowercase() ?: "io_error"
                    throw failure(reason, "Could not copy the device file to the workspace ($reason).")
                }
                // Only retain metadata when the open source stayed unchanged throughout the copy.
                val stable = metadata?.takeIf { it == metadata(fd) && it.size == entry.second }
                try {
                    imports.add(DeviceImportRecord(UUID.randomUUID().toString(), convId, path, entry.first, entry.second,
                        System.currentTimeMillis(), stable?.seconds, stable?.nanos, workspaceId = key.wire))
                } catch (e: Exception) {
                    throw failure("record_error", "The file was copied, but its import record could not be saved.")
                }
                DeviceFileImport(entry.first, entry.second)
            }
        } }
    }

    private data class SourceMetadata(val size: Long, val seconds: Long, val nanos: Long)

    private fun metadata(fd: ParcelFileDescriptor): SourceMetadata? = try {
        val stat = Os.fstat(fd.fileDescriptor)
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size < 0) null
        else SourceMetadata(stat.st_size, stat.st_mtim.tv_sec, stat.st_mtim.tv_nsec)
    } catch (_: Exception) { null }

    private fun readableCopy(key: WorkspaceKey, record: DeviceImportRecord): Boolean {
        val prefix = "/workspace/inbox/"
        if (!record.workspacePath.startsWith(prefix)) return false
        val name = record.workspacePath.removePrefix(prefix)
        if (name.isEmpty() || '/' in name || name == "." || name == "..") return false
        return try {
            dirs.gate.openRead(dirs.workspace(key), "inbox/$name").use {
                it.currentSize() == record.size && (record.size == 0L || it.read(0, 1).size == 1)
            }
        } catch (_: Exception) { false }
    }

    companion object {
        private const val TAG = "AndroidDeviceFiles"
        private val importLock = Mutex()

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
