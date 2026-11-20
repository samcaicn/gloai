package com.wangzan101.mika.chat

import android.content.Context
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.core.workspace.SandboxPaths
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.UUID

/** 复制分支工作区的结果:[skipped] 是没收下的项(符号链接、越界路径、非常规文件);[truncated] 为 true 时超过上限、一个文件都没复制。 */
data class BranchCopyResult(
    val copied: Int,
    val skipped: Int = 0,
    /** 超过上限、中止复制。 */
    val truncated: Boolean = false,
)

/** 重新问时，每个请求的附件在本次复制中的结果。 */
enum class RetryAttachmentStatus { COPIED, MISSING, OVER_LIMIT, FAILED }

data class RetryAttachmentResult(val path: String, val status: RetryAttachmentStatus, val sizeBytes: Long? = null)

/** 分支时复制源对话的工作区(只要用户附件 inbox/);阶段 2 由对话视图注入。 */
interface BranchWorkspace {
    /**
     * 只复制源对话工作区的 inbox/;目标工作区不存在就建。
     * 源工作区/沙箱不可用、源没有 inbox 时返回 null;目标已有 inbox 时不覆盖,返回 skipped = 1。
     */
    suspend fun copyInbox(
        sourceId: String,
        targetId: String,
        maxBytes: Long = 200L * 1024 * 1024,
    ): BranchCopyResult?

    /** 复制一次 inbox，按输入顺序返回每个沙箱路径的结果；只有本次实际提交的文件才是 COPIED。 */
    suspend fun copyInboxForRetry(
        sourceId: String,
        targetId: String,
        paths: List<String>,
        maxBytes: Long = 200L * 1024 * 1024,
    ): List<RetryAttachmentResult> = paths.map { RetryAttachmentResult(it, RetryAttachmentStatus.FAILED) }

    /** 发送前检查目标工作区中的文件是否仍可安全读取。COPIED 表示当前可读。 */
    suspend fun checkInboxAttachments(targetId: String, paths: List<String>): List<RetryAttachmentResult> =
        paths.map { RetryAttachmentResult(it, RetryAttachmentStatus.FAILED) }
}

/** 手机上的实现:两个工作区都在 files/sandbox/ 里,复制时拿同一把树锁,和重置、任务共用工作区互斥。 */
class AndroidBranchWorkspace(
    private val dirs: SandboxDirs,
    private val resolveWorkspace: (String) -> WorkspaceKey = WorkspaceKey::Conversation,
) : BranchWorkspace {
    constructor(context: Context) : this(SandboxDirs(context), WorkspaceResolver.resolver(context))

    override suspend fun copyInbox(sourceId: String, targetId: String, maxBytes: Long): BranchCopyResult? =
        dirs.tree.shared {
            withContext(Dispatchers.IO) {
                val src = resolveWorkspace(sourceId)
                val dst = resolveWorkspace(targetId)
                if (src == dst) return@withContext null
                val srcDir = dirs.workspace(src)
                val dstDir = dirs.workspace(dst)
                val inbox = copyInboxTree(srcDir, dstDir, maxBytes) ?: return@withContext null
                val audio = copyGeneratedAudioTree(srcDir, dstDir, maxBytes) ?: return@withContext inbox
                val images = copyGeneratedImagesTree(srcDir, dstDir, maxBytes) ?: return@withContext inbox
                BranchCopyResult(
                    copied = inbox.copied + audio.copied + images.copied,
                    skipped = inbox.skipped + audio.skipped + images.skipped,
                    truncated = inbox.truncated || audio.truncated || images.truncated,
                )
            }
        }

    override suspend fun copyInboxForRetry(
        sourceId: String, targetId: String, paths: List<String>, maxBytes: Long,
    ): List<RetryAttachmentResult> = dirs.tree.shared {
        withContext(Dispatchers.IO) {
            val src = resolveWorkspace(sourceId)
            val dst = resolveWorkspace(targetId)
            if (src == dst) return@withContext checkRetryAttachments(dirs.workspace(dst), paths, dirs.gate)
            val context = currentCoroutineContext()
            copyRetryAttachments(dirs.workspace(src), dirs.workspace(dst), paths, maxBytes, dirs.gate) { context.ensureActive() }
        }
    }

    override suspend fun checkInboxAttachments(targetId: String, paths: List<String>): List<RetryAttachmentResult> =
        dirs.tree.shared { withContext(Dispatchers.IO) { checkRetryAttachments(dirs.workspace(resolveWorkspace(targetId)), paths, dirs.gate) } }
}

private const val INBOX = "inbox"
private const val INBOX_PREFIX = "${SandboxPaths.WORKSPACE}/inbox/"

private fun retryRelative(path: String): String? {
    if (!path.startsWith(INBOX_PREFIX)) return null
    val parts = path.removePrefix(INBOX_PREFIX).split('/')
    if (parts.any { it.isEmpty() || it == "." || it == ".." || '\\' in it || '\u0000' in it }) return null
    return "inbox/" + parts.joinToString("/")
}

private fun failureStatus(e: Exception): RetryAttachmentStatus =
    if (e is SandboxError && e.code == WorkspaceFileGate.FILE_NOT_FOUND) RetryAttachmentStatus.MISSING
    else RetryAttachmentStatus.FAILED

internal fun checkRetryAttachments(workspace: File, paths: List<String>, gate: WorkspaceFileGate): List<RetryAttachmentResult> =
    paths.map { path ->
        val rel = retryRelative(path)
        if (rel == null) RetryAttachmentResult(path, RetryAttachmentStatus.FAILED)
        else try {
            gate.openRead(workspace, rel).use { RetryAttachmentResult(path, RetryAttachmentStatus.COPIED, it.currentSize()) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            RetryAttachmentResult(path, failureStatus(e))
        }
    }

/** 优先复制请求项，再复制其余历史附件；每个目标文件独立提交，失败仅删除本次创建的文件。 */
internal fun copyRetryAttachments(
    source: File, target: File, paths: List<String>, maxBytes: Long, gate: WorkspaceFileGate,
    ensureActive: () -> Unit = {},
): List<RetryAttachmentResult> {
    val requested = paths.distinct()
    val all = LinkedHashSet(requested)
    val inbox = File(source, INBOX)
    fun collect(dir: File, prefix: String) {
        ensureActive()
        if (Files.isSymbolicLink(dir.toPath())) return
        for (child in dir.listFiles().orEmpty().sortedBy { it.name }) {
            if (Files.isSymbolicLink(child.toPath())) continue
            val path = "$prefix${child.name}"
            if (child.isDirectory) collect(child, "$path/")
            else if (child.isFile && retryRelative(path) != null) all.add(path)
        }
    }
    collect(inbox, INBOX_PREFIX)
    var remaining = maxBytes.coerceAtLeast(0)
    val results = mutableMapOf<String, RetryAttachmentResult>()
    for (path in all) {
        ensureActive()
        val rel = retryRelative(path)
        if (rel == null) {
            results[path] = RetryAttachmentResult(path, RetryAttachmentStatus.FAILED)
            continue
        }
        var sourceOpened = false
        try {
            gate.openRead(source, rel).use { input ->
                sourceOpened = true
                if (input.currentSize() > remaining) {
                    results[path] = RetryAttachmentResult(path, RetryAttachmentStatus.OVER_LIMIT)
                } else {
                    if (!target.isDirectory && !target.mkdirs()) throw IOException("cannot create target workspace")
                    gate.createNew(target, rel).use { output ->
                        try {
                            var written = 0L
                            val buffer = ByteArray(64 * 1024)
                            val stream = input.input()
                            while (true) {
                                ensureActive()
                                val n = stream.read(buffer)
                                if (n < 0) break
                                if (n.toLong() > remaining - written) throw RetryLimitException()
                                output.write(if (n == buffer.size) buffer else buffer.copyOf(n))
                                written += n
                            }
                            remaining -= written
                            results[path] = RetryAttachmentResult(path, RetryAttachmentStatus.COPIED, written)
                        } catch (e: Throwable) {
                            output.delete()
                            throw e
                        }
                    }
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: RetryLimitException) {
            results[path] = RetryAttachmentResult(path, RetryAttachmentStatus.OVER_LIMIT)
        } catch (e: Exception) {
            results[path] = RetryAttachmentResult(path, if (sourceOpened) RetryAttachmentStatus.FAILED else failureStatus(e))
        }
    }
    return paths.map { results.getValue(it) }
}

private class RetryLimitException : IOException()

/**
 * 把 [source]/inbox 复制到 [target]/inbox:只收普通文件,不跟随符号链接,路径规范化后必须仍在各自目录下;
 * 先复制到 target/.inbox-tmp-<uuid>,全部成功后再改名。超过 [maxBytes] 就中止、删掉临时目录,返回
 * `BranchCopyResult(copied = 0, truncated = true)`,不留半成品。
 * 源没有 inbox 返回 null;目标已有 inbox 不覆盖,返回 `BranchCopyResult(copied = 0, skipped = 1)`;
 * 复制中出错也返回 null。不拿树锁,手机实现在锁里调它。
 */
internal fun copyInboxTree(source: File, target: File, maxBytes: Long): BranchCopyResult? =
    copyTree(source, target, INBOX, maxBytes, ".inbox-tmp-")

/** 分支时把这一轮生成的图也带上:复制 [source]/out/images 到 [target]/out/images(规则同 inbox)。 */
internal fun copyGeneratedImagesTree(source: File, target: File, maxBytes: Long): BranchCopyResult? =
    copyTree(source, target, "out/images", maxBytes, ".images-tmp-")

private fun copyTree(source: File, target: File, sub: String, maxBytes: Long, tmpPrefix: String): BranchCopyResult? {
    val sourceDir = File(source, sub)
    if (!sourceDir.isDirectory || Files.isSymbolicLink(sourceDir.toPath())) return null
    val sourceRoot = sourceDir.canonicalFile.toPath()
    if (!target.isDirectory && !target.mkdirs()) return null
    val dest = File(target, sub)
    if (dest.exists() || Files.isSymbolicLink(dest.toPath())) return BranchCopyResult(copied = 0, skipped = 1)
    dest.parentFile?.let { mkdirs(it.toPath()) }
    val tmp = File(target, "$tmpPrefix${UUID.randomUUID()}")
    if (!tmp.mkdirs()) return null
    val tmpRoot = tmp.canonicalFile.toPath()
    var copied = 0
    var skipped = 0
    var bytes = 0L
    var truncated = false

    fun walk(dir: File, rel: Path) {
        val children = dir.listFiles() ?: throw IOException("cannot list: $dir")
        for (child in children.sortedBy { it.name }) {
            if (truncated) return
            if (Files.isSymbolicLink(child.toPath())) {
                skipped++
                continue
            }
            val real = child.canonicalFile.toPath()
            if (!real.startsWith(sourceRoot)) {
                skipped++
                continue
            }
            val dst = tmpRoot.resolve(rel.resolve(child.name)).normalize()
            if (!dst.startsWith(tmpRoot)) {
                skipped++
                continue
            }
            when {
                child.isDirectory -> {
                    mkdirs(dst)
                    walk(child, rel.resolve(child.name))
                }
                child.isFile -> {
                    val written = copyFile(child, dst, maxBytes - bytes)
                    if (written == null) {
                        truncated = true
                        return
                    }
                    bytes += written
                    copied++
                }
                // 管道、套接字等用户附件没有的东西不复制
                else -> skipped++
            }
        }
    }

    try {
        walk(sourceDir, Paths.get(""))
    } catch (e: Exception) {
        tmp.deleteRecursively()
        return null
    }
    if (truncated) {
        tmp.deleteRecursively()
        return BranchCopyResult(copied = 0, truncated = true)
    }
    if (!tmp.renameTo(dest)) {
        tmp.deleteRecursively()
        return null
    }
    return BranchCopyResult(copied, skipped)
}

internal fun copyGeneratedAudioTree(source: File, target: File, maxBytes: Long): BranchCopyResult? {
    val sourceInbox = File(source, "out/audio")
    if (!sourceInbox.isDirectory || Files.isSymbolicLink(sourceInbox.toPath())) return null
    val sourceRoot = sourceInbox.canonicalFile.toPath()
    if (!target.isDirectory && !target.mkdirs()) return null
    val dest = File(target, "out/audio")
    if (dest.exists() || Files.isSymbolicLink(dest.toPath())) return BranchCopyResult(copied = 0, skipped = 1)
    val tmp = File(target, ".audio-tmp-${UUID.randomUUID()}")
    if (!tmp.mkdirs()) return null
    val tmpRoot = tmp.canonicalFile.toPath()
    var copied = 0
    var skipped = 0
    var bytes = 0L
    var truncated = false

    fun walk(dir: File, rel: Path) {
        val children = dir.listFiles() ?: throw IOException("cannot list: $dir")
        for (child in children.sortedBy { it.name }) {
            if (truncated) return
            if (Files.isSymbolicLink(child.toPath())) {
                skipped++
                continue
            }
            val real = child.canonicalFile.toPath()
            if (!real.startsWith(sourceRoot)) {
                skipped++
                continue
            }
            val dst = tmpRoot.resolve(rel.resolve(child.name)).normalize()
            if (!dst.startsWith(tmpRoot)) {
                skipped++
                continue
            }
            when {
                child.isDirectory -> {
                    mkdirs(dst)
                    walk(child, rel.resolve(child.name))
                }
                child.isFile -> {
                    val written = copyFile(child, dst, maxBytes - bytes)
                    if (written == null) {
                        truncated = true
                        return
                    }
                    bytes += written
                    copied++
                }
                // 管道、套接字等用户附件没有的东西不复制
                else -> skipped++
            }
        }
    }

    try {
        walk(sourceInbox, Paths.get(""))
    } catch (e: Exception) {
        tmp.deleteRecursively()
        return null
    }
    if (truncated) {
        tmp.deleteRecursively()
        return BranchCopyResult(copied = 0, truncated = true)
    }
    if (!tmp.renameTo(dest)) {
        tmp.deleteRecursively()
        return null
    }
    return BranchCopyResult(copied, skipped)
}

/** 复制一个普通文件,最多写 [max] 字节;超了返回 null(临时目录由调用方整个删掉)。 */
private fun copyFile(source: File, dest: Path, max: Long): Long? {
    dest.parent?.let(::mkdirs)
    var total = 0L
    Files.newInputStream(source.toPath()).use { input ->
        Files.newOutputStream(dest, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) return null
                output.write(buf, 0, n)
            }
        }
    }
    return total
}

private fun mkdirs(path: Path) {
    val f = path.toFile()
    if (!f.isDirectory && !f.mkdirs()) throw IOException("cannot create directory: $path")
}
