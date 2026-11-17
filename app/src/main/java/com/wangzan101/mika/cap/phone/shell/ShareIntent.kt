package com.wangzan101.mika.cap.phone.shell

import android.content.ClipData
import android.content.Intent
import android.net.Uri

/** 文字分享:安卓的 ACTION_SEND,类型 text/plain,文字放在 EXTRA_TEXT,只交给一个包。 */
object ShareIntent {
    const val TYPE = "text/plain"

    fun of(packageName: String, text: String): Intent = Intent(Intent.ACTION_SEND).setType(TYPE).putExtra(Intent.EXTRA_TEXT, text)
        .setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 文件分享:ACTION_SEND + EXTRA_STREAM,只交给一个包;content URI 允许对方读取。 */
    fun ofFile(packageName: String, uri: Uri, mime: String, text: String?): Intent = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .setPackage(packageName)
        .apply {
            if (!text.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, text)
            setClipData(ClipData.newRawUri(null, uri))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /**
     * 系统接口用不了时 `am start` 的参数(不经 shell 解释,文字原样作一个参数)。[component] 是这个包只有一个收分享的页面时
     * 它的短名,直接指定;有几个时只限定包,由系统问用哪一个。
     */
    fun amArgs(displayId: Int, packageName: String, text: String, component: String?): List<String> =
        listOf("am", "start", "--display", "$displayId", "-a", Intent.ACTION_SEND, "-t", TYPE, "--es", Intent.EXTRA_TEXT, text) +
            (component?.let { listOf("-n", it) } ?: listOf("-p", packageName))

    /** 文件分享的 `am start` 参数:EXTRA_STREAM 用 `--eu` 传,并允许对方读这个 content URI。 */
    fun amArgsFile(displayId: Int, packageName: String, uri: Uri, mime: String, text: String?, component: String?): List<String> =
        listOf("am", "start", "--display", "$displayId", "-a", Intent.ACTION_SEND, "-t", mime,
            "--eu", Intent.EXTRA_STREAM, uri.toString(), "--grant-read-uri-permission") +
            (text?.takeIf { it.isNotBlank() }?.let { listOf("--es", Intent.EXTRA_TEXT, it) } ?: emptyList()) +
            (component?.let { listOf("-n", it) } ?: listOf("-p", packageName))
}
