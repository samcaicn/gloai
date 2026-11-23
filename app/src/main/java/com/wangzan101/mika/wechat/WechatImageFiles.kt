package com.wangzan101.mika.wechat

import android.content.Context
import android.net.Uri
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.Attachments
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.chat.WorkspaceResolver
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.cap.wechat.WechatApi
import com.wangzan101.mika.core.cap.wechat.WechatDownloadedImage
import com.wangzan101.mika.core.cap.wechat.WechatImage
import com.wangzan101.mika.core.cap.wechat.WechatImages
import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

sealed interface WechatImageSource
data class DownloadWechatImage(val image: WechatImage) : WechatImageSource

/** Media stays in the host; the generic task input contains only ordinary workspace attachment paths. */
interface WechatImageFiles {
    suspend fun receive(peer: ChannelPeer, images: List<WechatImageSource>,
        accept: (List<SavedWechatImage>) -> Boolean): Boolean
    suspend fun read(image: Delivery.Image): ByteArray
}

internal data class PreparedWechatImage(val bytes: ByteArray, val extension: String)

internal fun androidWechatImages(context: Context, api: WechatApi): WechatImageFiles {
    val dirs = SandboxDirs(context)
    val sandbox = AndroidSandbox(context, SandboxState.get(context))
    return StoredWechatImages(
        File(context.cacheDir, "wechat-images"), api::downloadImage, ::prepareWechatImage,
        { thread -> dirs.workspace(WorkspaceResolver.resolveFor(context, thread)) }, dirs.gate, dirs.tree,
        // 微信层不在存储档位范围内:单文件仍是协议层 WechatImages.MAX_BYTES(10 MB),工作区余量仍用固定 WORKSPACE_MAX
        { thread -> sandbox.freeBytes() >= Attachments.MIN_FREE && sandbox.workspaceBytes(WorkspaceResolver.resolveFor(context, thread)) <= Attachments.WORKSPACE_MAX },
        { uri -> context.contentResolver.openInputStream(Uri.parse(uri)) },
    )
}

/** A batch is imported atomically with its journal entry. Rejected/old-session batches leave no workspace files. */
internal class StoredWechatImages(
    private val tempDir: File,
    private val download: suspend (WechatImage) -> WechatDownloadedImage,
    private val prepare: (WechatDownloadedImage) -> PreparedWechatImage,
    private val workspace: (String) -> File,
    private val gate: WorkspaceFileGate,
    private val tree: SandboxTree,
    private val room: (String) -> Boolean,
    private val openExport: (String) -> InputStream?,
) : WechatImageFiles {
    override suspend fun receive(peer: ChannelPeer, images: List<WechatImageSource>,
        accept: (List<SavedWechatImage>) -> Boolean): Boolean = withContext(Dispatchers.IO) {
        if (images.isEmpty() || images.size > WechatImages.MAX_IMAGES) throw IOException("Image count limit exceeded")
        val downloads = images.filterIsInstance<DownloadWechatImage>()
        if (downloads.isEmpty()) return@withContext tree.shared {
            val stored = images.filterIsInstance<SavedWechatImage>()
            stored.forEach { verify(peer, it) }
            accept(stored)
        }
        if (!room(peer.threadId)) throw IOException("Not enough space for images")
        val batch = File(tempDir, "receive-${UUID.randomUUID()}")
        if (!batch.mkdirs()) throw IOException("Cannot stage images")
        try {
            val hashes = mutableListOf<String>()
            val sources = downloads.mapIndexed { index, source ->
                currentCoroutineContext().ensureActive()
                if (!room(peer.threadId)) throw IOException("Not enough space for images")
                val prepared = prepare(download(source.image))
                currentCoroutineContext().ensureActive()
                hashes += sha256(prepared.bytes)
                val name = "wechat-${index + 1}.${prepared.extension}"
                name to File(batch, name).apply { writeBytes(prepared.bytes) }
            }
            val taskContext = currentCoroutineContext()
            Attachments.withTaskFiles(sources, workspace(peer.threadId), gate, tree, { room(peer.threadId) }) { stored ->
                taskContext.ensureActive()
                if (!room(peer.threadId)) throw IOException("Not enough space for images")
                val imported = stored.mapIndexed { index, (path, size) -> SavedWechatImage(path, size, hashes[index]) }.iterator()
                val ordered = images.map {
                    when (it) {
                        is DownloadWechatImage -> imported.next()
                        is SavedWechatImage -> it.also { file -> verify(peer, file) }
                    }
                }
                if (!accept(ordered)) throw DiscardedImageBatch()
                true
            }
        } catch (_: DiscardedImageBatch) { false }
        finally { batch.deleteRecursively() }
    }

    private fun verify(peer: ChannelPeer, image: SavedWechatImage) {
        if (image.bytes !in 1L..WechatImages.MAX_BYTES.toLong()) throw IOException("Invalid cached image size")
        gate.openRead(workspace(peer.threadId), image.path.removePrefix("/workspace/"), under = "inbox").use { file ->
            if (file.size != image.bytes || sha256(file.read(0, image.bytes.toInt())) != image.sha256)
                throw IOException("The quoted image has changed")
        }
    }

    override suspend fun read(image: Delivery.Image): ByteArray = withContext(Dispatchers.IO) {
        readWechatExport(image, openExport)
    }
}

private fun sha256(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private class DiscardedImageBatch : RuntimeException()

/** Only explicit MediaStore exports can be sent. Verify the recorded size/hash before uploading any bytes. */
internal suspend fun readWechatExport(image: Delivery.Image, open: (String) -> InputStream?): ByteArray {
    val uri = try { URI(image.uri) } catch (_: Exception) { throw IOException("Invalid image export") }
    if (uri.scheme != "content" || uri.authority != "media" || uri.query != null || uri.fragment != null)
        throw IOException("Not a shared image export")
    if (image.size <= 0 || image.size > WechatImages.MAX_BYTES) throw IOException("Image size limit exceeded")
    val bytes = (open(image.uri) ?: throw IOException("Image export is unavailable")).use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (out.size().toLong() + count > image.size) throw IOException("Image export changed")
            out.write(buffer, 0, count)
        }
        if (out.size().toLong() != image.size) throw IOException("Image export changed")
        out.toByteArray()
    }
    image.sha256?.let { expected ->
        val actual = sha256(bytes)
        if (!actual.equals(expected, ignoreCase = true)) throw IOException("Image export changed")
    }
    WechatImages.validate(bytes)
    return bytes
}
