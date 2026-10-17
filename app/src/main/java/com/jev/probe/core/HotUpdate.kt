package com.jev.probe.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.jev.probe.jev.ApiException
import com.jev.probe.jev.HttpJson
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 自动热更新：启动时查 Worker 更新代理（/api/update-android，Worker 用只读
 * GitHub token 拉 CI artifact 元数据），发现比当前 versionCode 新的构建就提示，
 * 用户确认后经 /api/update-android/download 回包（zip 经云端流式中转，大陆设备
 * 不直连 GitHub/Azure）下载并拉起系统安装器。安装仍由系统确认，符合平台规则。
 *
 * 版本口径：CI 把 run_number 注入 versionCode（build.gradle.kts ciRun 属性），
 * artifact 名尾部数字同为 run 号——两者直接比大小即判断新旧。
 */
object HotUpdate {

    private const val TAG = "HotUpdate"
    private const val CHECK_INTERVAL_MS = 6 * 3600_000L
    private const val APK_NAME = "app-release.apk"

    data class Info(val versionCode: Int, val sizeBytes: Long, val artifact: String)

    /** 查询最新构建；无更新/网络失败/任何异常都返回 null（热更新绝不能影响主功能）。 */
    fun check(context: Context, force: Boolean = false): Info? {
        val prefs = Prefs
        if (!force) {
            val last = prefs.hotUpdateLastCheck
            if (last > 0 && System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return null
        }
        prefs.hotUpdateLastCheck = System.currentTimeMillis()
        return try {
            val cur = context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            val resp = HttpJson.getJson(Prefs.WORKER_BASE.trimEnd('/') + "/api/update-android")
            if (!resp.optBoolean("ok")) return null
            val info = Info(
                versionCode = resp.optInt("version_code", 0),
                sizeBytes = resp.optLong("size_in_bytes", 0),
                artifact = resp.optString("artifact", "")
            )
            if (info.versionCode <= 0 || info.versionCode <= cur) {
                Log.i(TAG, "up to date: cur=$cur latest=${info.versionCode}")
                null
            } else {
                Log.i(TAG, "update available: cur=$cur latest=${info.versionCode} artifact=${info.artifact}")
                info
            }
        } catch (e: Exception) {
            Log.w(TAG, "check failed: ${e.message}")
            null
        }
    }

    /**
     * 经云端回包下载 zip 并解出 APK。重复下载会覆盖临时文件。
     * 返回可安装的 APK 文件；失败抛 [ApiException]/[java.io.IOException]。
     */
    @Throws(Exception::class)
    fun downloadApk(context: Context): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val zip = File(dir, "update.zip")
        val url = Prefs.WORKER_BASE.trimEnd('/') + "/api/update-android/download"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                // 44MB 的包走云中转，读超时放宽
                readTimeout = 120_000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw ApiException("更新包下载", code, conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(120) ?: "")
            }
            conn.inputStream.use { input ->
                zip.outputStream().use { out ->
                    input.copyTo(out, 64 * 1024)
                }
            }
        } finally {
            conn?.disconnect()
        }
        // artifact zip 里只有 dist/app-release.apk 一个文件（build.yml Collect APKs）
        val apk = File(dir, APK_NAME)
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                    apk.outputStream().use { zin.copyTo(it) }
                    break
                }
                entry = zin.nextEntry
            }
        }
        if (!apk.exists() || apk.length() == 0L) throw ApiException("更新包下载", null, "压缩包里没有找到安装包")
        zip.delete()
        return apk
    }

    /** 拉起系统安装器（FileProvider 暴露 APK；安装确认仍由系统与用户完成）。 */
    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(i)
    }

    /** 是否已授予「安装未知应用」权限；未授予时用 [openInstallPermSettings] 引导。 */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallPermSettings(context: Context) {
        val i = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:" + context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(i)
    }
}
