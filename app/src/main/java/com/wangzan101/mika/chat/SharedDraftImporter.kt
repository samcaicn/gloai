package com.wangzan101.mika.chat

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.wangzan101.mika.cap.sandbox.Attachments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

/** 把系统分享的文件复制到私有草稿目录，不依赖之后仍有效的 URI 授权。 */
class SharedDraftImporter(private val context: Context, private val store: SharedDraftStore) {
    suspend fun attachment(text: String, uri: Uri, declaredType: String?): SharedDraft? {
        var copied: File? = null
        return try {
            withContext(Dispatchers.IO) {
                if (text.length > SharedDraftStore.MAX_TEXT_CHARS) return@withContext null
                if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@withContext null
                // Android 跨用户 URI 的 authority 可以是「用户编号@提供方」。
                val authority = uri.authority.orEmpty().substringAfterLast('@')
                if (authority.isBlank() || authority == context.packageName || authority.startsWith("${context.packageName}.")) return@withContext null
                if (uri.path?.split('/')?.any { it == ".." } == true) return@withContext null
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
                if (declaredSize > Attachments.MAX_BYTES) return@withContext null
                val mimeMap = MimeTypeMap.getSingleton()
                val sourceName = display?.takeIf { it.isNotBlank() }
                    ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { '.' in it }
                val suffix = sourceName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.matches(EXTENSION) }
                val mime = listOfNotNull(providerType, declaredType).firstOrNull {
                    it.contains('/') && '*' !in it && it != "application/octet-stream"
                } ?: suffix?.let { mimeMap.getMimeTypeFromExtension(it) }
                val photo = mime?.startsWith("image/") == true || declaredType?.startsWith("image/") == true ||
                    providerType?.startsWith("image/") == true
                if (photo && mime != null && !mime.startsWith("image/")) return@withContext null
                val extension = mime?.let { mimeMap.getExtensionFromMimeType(it) }?.takeIf { it.matches(EXTENSION) }
                    ?: suffix ?: if (photo) "img" else "bin"
                val fallbackName = if (photo) "image.$extension" else "attachment.$extension"
                val name = Attachments.safeName(sourceName ?: fallbackName)
                val fileName = "${UUID.randomUUID()}.$extension"
                val final = store.attachmentFile(fileName) ?: return@withContext null
                copied = final
                val tmp = store.attachmentFile("$fileName.tmp") ?: return@withContext null
                val size = resolver.openInputStream(uri)?.use { input ->
                    SharedDraftCopy.copy(input, tmp, final) { tmp.parentFile?.usableSpace ?: 0L }
                } ?: return@withContext null
                if (photo) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    final.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        final.delete()
                        return@withContext null
                    }
                }
                SharedDraft("", text, SharedDraftAttachment(fileName, name, size, photo))
            }
        } catch (e: CancellationException) {
            // withContext 在复制刚完成、结果尚未交给界面的那一刻也可能被取消;这份副本还没绑到草稿,不能留下。
            copied?.delete()
            throw e
        } catch (e: Exception) {
            copied?.delete()
            null
        }
    }

    private companion object {
        val EXTENSION = Regex("[a-z0-9]{1,16}")
    }
}

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
                    if (freeBytes() < minFree) throw IllegalStateException("storage low")
                    val n = input.read(buffer)
                    if (n < 0) break
                    size += n
                    if (size > maxBytes) throw IllegalStateException("too large")
                    output.write(buffer, 0, n)
                }
            }
            if (!tmp.renameTo(final)) throw IllegalStateException("cannot finish")
            return size
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }
}
