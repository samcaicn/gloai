package com.wangzan101.mika.task

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.AttachmentLimits
import com.wangzan101.mika.cap.sandbox.Attachments
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.core.workspace.DeviceFileImport
import com.wangzan101.mika.core.workspace.SandboxError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.UUID

/** Import both kinds of device grants into the same inbox and history. */
internal class DeviceWorkspaceImports(
    private val dirs: SandboxDirs,
    private val freeBytes: () -> Long,
    private val imports: DeviceImportStore,
    private val resolveWorkspace: (String) -> WorkspaceKey,
    private val conversationIdFor: (String) -> String,
    /** 每次导入现取设置里的档位(聊天附件与设备导入共用),改完下一次导入即生效。 */
    private val limits: () -> AttachmentLimits = { AttachmentLimits.DEFAULT },
) {
    suspend fun fromFolder(path: String, workspace: String, open: (String) -> InputStream) = locked {
        // Open through the live SAF grant before touching the workspace or consulting its history.
        open(path).use { stream -> copy(path, workspace, open = { stream }) }
    }

    suspend fun fromHelper(path: String, workspace: String, open: () -> ParcelFileDescriptor) = locked {
        open().use { fd ->
            copy(path, workspace,
                open = { ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd.fileDescriptor)) },
                metadata = { metadata(fd) })
        }
    }

    private suspend fun locked(block: suspend () -> DeviceFileImport): DeviceFileImport = dirs.tree.shared {
        importLock.withLock { withContext(Dispatchers.IO) { block() } }
    }

    private suspend fun copy(path: String, workspace: String, open: () -> InputStream,
        metadata: () -> SourceMetadata? = { null }): DeviceFileImport {
        val key = resolveWorkspace(workspace)
        val initial = metadata()
        if (initial != null) {
            val records = try { imports.listByWorkspace(key.wire) } catch (_: Exception) {
                throw SandboxError("record_error", "Could not read device import records.", true)
            }
            val existing = records.asReversed().firstOrNull { record ->
                record.sourcePath == path && record.size == initial.size &&
                    record.sourceModifiedSeconds == initial.seconds && record.sourceModifiedNanos == initial.nanos &&
                    readableCopy(key, record)
            }
            if (existing != null) return DeviceFileImport(existing.workspacePath, existing.size)
        }
        val root = dirs.workspace(key)
        val result = Attachments.importOne(root, dirs.gate, Attachments.safeName(path.substringAfterLast('/')),
            freeBytes, { AndroidSandbox.treeBytes(root.toPath()) }, open,
            onCopied = { (workspacePath, size) ->
                val stable = initial?.takeIf { it == metadata() && it.size == size }
                try {
                    imports.add(DeviceImportRecord(UUID.randomUUID().toString(), conversationIdFor(workspace), path,
                        workspacePath, size, System.currentTimeMillis(), stable?.seconds, stable?.nanos, key.wire))
                } catch (_: Exception) {
                    throw SandboxError("record_error", "Could not save the device import record.", true)
                }
            },
            logFailure = { error ->
                if (error is SecurityException) throw SandboxError("device_access_denied", "Access to the device file was denied.", true)
            }, limits = limits())
        val entry = result.added.singleOrNull() ?: run {
            val reason = result.failures.firstOrNull()?.reason?.name?.lowercase() ?: "io_error"
            throw SandboxError(reason, "Could not copy the device file to the workspace ($reason).", true)
        }
        return DeviceFileImport(entry.first, entry.second)
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

    private companion object { val importLock = Mutex() }
}
