package com.wangzan101.mika.chat.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.os.Process
import com.wangzan101.mika.chat.SharedDraftStore

/** 系统分享进来的内容:一段文字,或带附言的一个或多个文件;对不上、过长、非法的一律拒收。 */
internal sealed interface ShareContent {
    data class Text(val text: String) : ShareContent
    data class Files(
        val text: String,
        val uris: List<Uri>,
        val type: String?,
        val clipUris: Set<Uri>,
        val overLimit: Int = 0,
    ) : ShareContent
    data object Rejected : ShareContent
}

internal sealed interface ParsedShare<out U> {
    data class Text(val text: String) : ParsedShare<Nothing>
    data class Files<U>(
        val text: String,
        val uris: List<U>,
        val type: String?,
        val clipUris: Set<U>,
        val overLimit: Int = 0,
    ) : ParsedShare<U>
    data object Rejected : ParsedShare<Nothing>
}

internal fun isShareIntent(intent: Intent): Boolean =
    intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE

/** 纯逻辑解析分享字段,便于在 JVM 单测里覆盖单文件、多文件与各类异常输入。 */
internal fun <U> parseShareInput(
    multiple: Boolean,
    type: String?,
    text: String,
    streamUris: List<U>,
    clipUris: List<U>,
    clipItemCount: Int,
    malformedStream: Boolean,
    maxAttachments: Int = AttachTray.LIMIT,
): ParsedShare<U> {
    val normalizedType = type?.substringBefore(';')?.trim()?.lowercase()
    if (text.length > SharedDraftStore.MAX_TEXT_CHARS) return ParsedShare.Rejected
    if (malformedStream) return ParsedShare.Rejected
    if (!multiple) {
        if (clipItemCount > 1) return ParsedShare.Rejected
        val stream = streamUris.singleOrNull()
        val clipUri = clipUris.singleOrNull()
        if (stream != null && clipUri != null && stream != clipUri) return ParsedShare.Rejected
        val uri = stream ?: clipUri
        if (uri == null) {
            return if (normalizedType != "text/plain" || text.isBlank()) ParsedShare.Rejected else ParsedShare.Text(text)
        }
        return ParsedShare.Files(text, listOf(uri), normalizedType, setOfNotNull(clipUri))
    }
    if (streamUris.isNotEmpty() && clipUris.isNotEmpty() && streamUris.toSet() != clipUris.toSet()) {
        return ParsedShare.Rejected
    }
    val allUris = (if (streamUris.isNotEmpty()) streamUris else clipUris).distinct()
    if (allUris.isEmpty()) {
        return if (normalizedType != "text/plain" || text.isBlank()) ParsedShare.Rejected else ParsedShare.Text(text)
    }
    val accepted = allUris.take(maxAttachments)
    val overLimit = (allUris.size - accepted.size).coerceAtLeast(0)
    return ParsedShare.Files(text, accepted, normalizedType, clipUris.toSet(), overLimit)
}

/** 读出分享 intent 的内容;不是分享(ACTION_SEND/ACTION_SEND_MULTIPLE)时为 null。 */
internal fun readShare(intent: Intent): ShareContent? {
    val multiple = when (intent.action) {
        Intent.ACTION_SEND -> false
        Intent.ACTION_SEND_MULTIPLE -> true
        else -> return null
    }
    val clip = intent.clipData
    val clipCount = clip?.itemCount ?: 0
    val clipUris = if (clip == null) emptyList() else (0 until clipCount).mapNotNull { clip.getItemAt(it).uri }
    val text = if (!multiple) {
        val item = clip?.takeIf { clipCount == 1 }?.getItemAt(0)
        (intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: item?.text)?.toString().orEmpty()
    } else {
        val direct = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT) }.getOrNull()?.toString()
        val list = if (direct == null) {
            runCatching { intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT) }.getOrNull()
                ?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString("\n")
        } else null
        val fromClip = if (direct == null && list == null && clip != null) {
            (0 until clipCount).mapNotNull { clip.getItemAt(it).text?.toString()?.takeIf(String::isNotBlank) }
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n")
        } else null
        direct ?: list ?: fromClip ?: ""
    }
    var malformedStream = false
    val streamUris: List<Uri> = if (!intent.hasExtra(Intent.EXTRA_STREAM)) {
        emptyList()
    } else if (!multiple) {
        val single = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)
        if (single == null) {
            malformedStream = true
            emptyList()
        } else listOf(single)
    } else {
        val parsed = if (Build.VERSION.SDK_INT >= 33) {
            val raw = runCatching { intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) }.getOrNull()
            raw?.takeIf { it.isNotEmpty() && it.none { u -> u == null } }
        } else {
            @Suppress("DEPRECATION")
            val raw = runCatching { intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM) }.getOrNull()
            raw?.takeIf { it.isNotEmpty() && it.all { p -> p is Uri } }?.map { it as Uri }
        }
        if (parsed == null) {
            malformedStream = true
            emptyList()
        } else parsed
    }
    return when (val parsed = parseShareInput(multiple, intent.type, text, streamUris, clipUris, clipCount, malformedStream)) {
        ParsedShare.Rejected -> ShareContent.Rejected
        is ParsedShare.Text -> ShareContent.Text(parsed.text)
        is ParsedShare.Files -> ShareContent.Files(parsed.text, parsed.uris, parsed.type, parsed.clipUris, parsed.overLimit)
    }
}

/** 检查分享方的读取权，避免用 Mika 的相册或文件夹权限代读任意 URI。 */
internal fun Activity.shareCanRead(intent: Intent, uri: Uri, clipUris: Set<Uri>): Boolean {
    if (uri.scheme != "content") return false
    val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    return runCatching {
        if (Build.VERSION.SDK_INT >= 35) {
            caller?.let { return@runCatching it.checkContentUriPermission(uri, read) == PackageManager.PERMISSION_GRANTED }
        }
        // 旧系统无法查询启动方，只接受带有读取标志、并且 Mika 确实有 URI 授权的内容。
        (intent.flags and read) != 0 && (uri in clipUris || intent.data == uri) &&
            checkUriPermission(uri, Process.myPid(), Process.myUid(), read) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
}
