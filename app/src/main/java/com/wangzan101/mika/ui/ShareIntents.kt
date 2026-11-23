package com.wangzan101.mika.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.os.Process
import com.wangzan101.mika.chat.SharedDraftStore

/** 系统分享进来的内容:一段文字,或带附言的一个文件;多条、对不上、过长的一律拒收。 */
internal sealed interface ShareContent {
    data class Text(val text: String) : ShareContent
    data class File(val text: String, val uri: Uri, val type: String?, val clipUri: Uri?) : ShareContent
    data object Rejected : ShareContent
}

/** 读出分享 intent 的内容;不是分享(ACTION_SEND/ACTION_SEND_MULTIPLE)时为 null。 */
internal fun readShare(intent: Intent): ShareContent? {
    if (intent.action == Intent.ACTION_SEND_MULTIPLE) return ShareContent.Rejected
    if (intent.action != Intent.ACTION_SEND) return null
    val clip = intent.clipData
    if ((clip?.itemCount ?: 0) > 1) return ShareContent.Rejected
    val item = clip?.takeIf { it.itemCount == 1 }?.getItemAt(0)
    val type = intent.type?.substringBefore(';')?.trim()?.lowercase()
    val text = (intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: item?.text)?.toString().orEmpty()
    if (text.length > SharedDraftStore.MAX_TEXT_CHARS) return ShareContent.Rejected
    val stream = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)
    if (intent.hasExtra(Intent.EXTRA_STREAM) && stream == null) return ShareContent.Rejected
    val clipUri = item?.uri
    if (stream != null && clipUri != null && stream != clipUri) return ShareContent.Rejected
    val uri = stream ?: clipUri
    if (uri == null) return if (type != "text/plain" || text.isBlank()) ShareContent.Rejected else ShareContent.Text(text)
    return ShareContent.File(text, uri, type, clipUri)
}

/** 检查分享方的读取权，避免用 Mika 的相册或文件夹权限代读任意 URI。 */
internal fun Activity.shareCanRead(intent: Intent, uri: Uri, clipUri: Uri?): Boolean {
    if (uri.scheme != "content") return false
    val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    return runCatching {
        if (Build.VERSION.SDK_INT >= 35) {
            caller?.let { return@runCatching it.checkContentUriPermission(uri, read) == PackageManager.PERMISSION_GRANTED }
        }
        // 旧系统无法查询启动方，只接受带有读取标志、并且 Mika 确实有 URI 授权的内容。
        (intent.flags and read) != 0 && (clipUri == uri || intent.data == uri) &&
            checkUriPermission(uri, Process.myPid(), Process.myUid(), read) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
}
