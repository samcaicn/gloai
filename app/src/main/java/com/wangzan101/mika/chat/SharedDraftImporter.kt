package com.wangzan101.mika.chat

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.cap.sandbox.AttachmentFailureReason
import com.wangzan101.mika.cap.sandbox.Attachments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

/** 批量导入系统分享文件的结果:成功复制的草稿与单项失败原因。 */
data class SharedDraftImportResult(
    val draft: SharedDraft,
    val failures: List<AttachmentFailure> = emptyList(),
)

/** 把系统分享的文件复制到私有草稿目录，不依赖之后仍有效的 URI 授权。 */
class SharedDraftImporter(private val context: Context, private val store: SharedDraftStore) {
    suspend fun attachment(text: String, uri: Uri, declaredType: String?): SharedDraft? =
        attachments(text, listOf(uri), declaredType)?.draft

    /**
     * 复制一个或多个外部内容 URI 到私有草稿目录。
     * - 至少有一个文件复制成功时返回草稿与单项失败列表;全部失败返回 null。
     * - 中途取消时删除已复制的全部私有副本,不留下孤儿文件。
     */
    suspend fun attachments(text: String, uris: List<Uri>, declaredType: String?): SharedDraftImportResult? {
        if (text.length > SharedDraftStore.MAX_TEXT_CHARS || uris.isEmpty()) return null
        val copiedFiles = mutableListOf<File>()
        return try {
            withContext(Dispatchers.IO) {
                val succeeded = mutableListOf<SharedDraftAttachment>()
                val failures = mutableListOf<AttachmentFailure>()
                for (uri in uris.take(SharedDraftStore.MAX_ATTACHMENTS)) {
                    currentCoroutineContext().ensureActive()
                    when (val outcome = copyOne(uri, declaredType, copiedFiles)) {
                        is SingleOutcome.Copied -> succeeded += outcome.attachment
                        is SingleOutcome.Failed -> failures += outcome.failure
                    }
                }
                if (succeeded.isEmpty()) {
                    copiedFiles.forEach { it.delete() }
                    return@withContext null
                }
                SharedDraftImportResult(SharedDraft("", text, succeeded), failures)
            }
        } catch (e: CancellationException) {
            // withContext 在复制刚完成、结果尚未交给界面的那一刻也可能被取消;这些副本还没绑到草稿,不能留下。
            copiedFiles.forEach { it.delete() }
            throw e
        } catch (e: Exception) {
            copiedFiles.forEach { it.delete() }
            null
        }
    }

    private sealed interface SingleOutcome {
        data class Copied(val attachment: SharedDraftAttachment) : SingleOutcome
        data class Failed(val failure: AttachmentFailure) : SingleOutcome
    }

    private suspend fun copyOne(uri: Uri, declaredType: String?, copiedFiles: MutableList<File>): SingleOutcome {
        var finalFile: File? = null
        var fallbackLabel = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
        return try {
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
                return SingleOutcome.Failed(AttachmentFailure(Attachments.safeName(fallbackLabel), AttachmentFailureReason.IO_ERROR))
            }
            // Android 跨用户 URI 的 authority 可以是「用户编号@提供方」。
            val authority = uri.authority.orEmpty().substringAfterLast('@')
            if (authority.isBlank() || authority == context.packageName || authority.startsWith("${context.packageName}.")) {
                return SingleOutcome.Failed(AttachmentFailure(Attachments.safeName(fallbackLabel), AttachmentFailureReason.IO_ERROR))
            }
            if (uri.path?.split('/')?.any { it == ".." } == true) {
                return SingleOutcome.Failed(AttachmentFailure(Attachments.safeName(fallbackLabel), AttachmentFailureReason.IO_ERROR))
            }
            val resolver = context.contentResolver
            val providerType = runCatching { resolver.getType(uri) }.getOrNull()?.substringBefore(';')?.trim()?.lowercase()
            var display: String? = null
            var declaredSize = -1L
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val nameColumn = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeColumn = c.getColumnIndex(OpenableColumns.SIZE)
                        if (nameColumn >= 0) display = c.getString(nameColumn)
                        if (sizeColumn >= 0 && !c.isNull(sizeColumn)) declaredSize = c.getLong(sizeColumn)
                    }
                }
            }
            val sourceName = display?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { '.' in it }
            if (sourceName != null) fallbackLabel = sourceName
            if (declaredSize > Attachments.MAX_BYTES) {
                return SingleOutcome.Failed(AttachmentFailure(Attachments.safeName(fallbackLabel), AttachmentFailureReason.TOO_LARGE))
            }
            val mimeMap = MimeTypeMap.getSingleton()
            val suffix = sourceName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.matches(EXTENSION) }
            val mime = listOfNotNull(providerType, declaredType).firstOrNull {
                it.contains('/') && '*' !in it && it != "application/octet-stream"
            } ?: suffix?.let { mimeMap.getMimeTypeFromExtension(it) }
            val photo = mime?.startsWith("image/") == true || declaredType?.startsWith("image/") == true ||
                providerType?.startsWith("image/") == true
            if (photo && mime != null && !mime.startsWith("image/")) {
                return SingleOutcome.Failed(AttachmentFailure(Attachments.safeName(fallbackLabel), AttachmentFailureReason.IO_ERROR))
            }
            val extension = mime?.let { mimeMap.getExtensionFromMimeType(it) }?.takeIf { it.matches(EXTENSION) }
                ?: suffix ?: if (photo) "img" else "bin"
            val fallbackName = if (photo) "image.$extension" else "attachment.$extension"
            val name = Attachments.safeName(sourceName ?: fallbackName)
            fallbackLabel = name
            val fileName = "${UUID.randomUUID()}.$extension"
            val final = store.attachmentFile(fileName)
                ?: return SingleOutcome.Failed(AttachmentFailure(name, AttachmentFailureReason.IO_ERROR))
            finalFile = final
            copiedFiles += final
            val tmp = store.attachmentFile("$fileName.tmp")
                ?: return SingleOutcome.Failed(AttachmentFailure(name, AttachmentFailureReason.IO_ERROR)).also {
                    copiedFiles.remove(final)
                }
            val size = resolver.openInputStream(uri)?.use { input ->
                SharedDraftCopy.copy(input, tmp, final) { tmp.parentFile?.usableSpace ?: 0L }
            } ?: run {
                copiedFiles.remove(final)
                final.delete()
                return SingleOutcome.Failed(AttachmentFailure(name, AttachmentFailureReason.IO_ERROR))
            }
            if (photo) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                final.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    copiedFiles.remove(final)
                    final.delete()
                    return SingleOutcome.Failed(AttachmentFailure(name, AttachmentFailureReason.IO_ERROR))
                }
            }
            SingleOutcome.Copied(SharedDraftAttachment(fileName, name, size, photo))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SharedDraftCopyException) {
            finalFile?.let { copiedFiles.remove(it); it.delete() }
            SingleOutcome.Failed(AttachmentFailure(Attachments.safeName(fallbackLabel), e.reason))
        } catch (_: Exception) {
            finalFile?.let { copiedFiles.remove(it); it.delete() }
            SingleOutcome.Failed(AttachmentFailure(Attachments.safeName(fallbackLabel), AttachmentFailureReason.IO_ERROR))
        }
    }

    private companion object {
        val EXTENSION = Regex("[a-z0-9]{1,16}")
    }
}

internal class SharedDraftCopyException(val reason: AttachmentFailureReason, message: String) : IllegalStateException(message)

/** 有界复制:每个块都检查取消和空间;失败或取消时不留下半份临时文件。 */
internal object SharedDraftCopy {
    suspend fun copy(
        input: InputStream,
        tmp: File,
        final: File,
        maxBytes: Long = Attachments.MAX_BYTES,
        minFree: Long = Attachments.MIN_FREE,
        freeBytes: () -> Long,
    ): Long {
        try {
            tmp.parentFile?.mkdirs()
            var size = 0L
            tmp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (freeBytes() < minFree) throw SharedDraftCopyException(AttachmentFailureReason.LOW_DEVICE_SPACE, "storage low")
                    val n = input.read(buffer)
                    if (n < 0) break
                    size += n
                    if (size > maxBytes) throw SharedDraftCopyException(AttachmentFailureReason.TOO_LARGE, "too large")
                    output.write(buffer, 0, n)
                }
            }
            if (!tmp.renameTo(final)) throw SharedDraftCopyException(AttachmentFailureReason.IO_ERROR, "cannot finish")
            return size
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }
}
