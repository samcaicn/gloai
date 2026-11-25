package com.wangzan101.mika.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.MainActivity
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 自动热更新(照 WeAuto 的 update-android 通道):启动时查 Worker 更新代理
 * (部署在 worker/mika-update,用只读 GitHub token 拉 CI artifact 元数据),
 * 发现比当前 versionCode 新的构建就发一条通知;点通知进主界面下载,经
 * /api/update-android/download 回包(zip 云端流式中转,大陆设备不直连 GitHub/Azure),
 * 校验后拉起系统安装器。安装确认仍由系统与用户完成。
 *
 * 版本口径:CI 把 run_number 注入 versionCode(-PciRun),artifact 名尾部数字同为
 * run 号 —— 两者直接比大小即判断新旧。签名必须与已装版本一致才允许安装。
 */
object HotUpdate {
    private const val TAG = "MikaHotUpdate"
    private const val CHECK_INTERVAL_MS = 6 * 3600_000L
    private const val CHANNEL = "hot_update"
    private const val NOTIFICATION_ID = 4201
    const val ACTION_DISMISS = "com.wangzan101.mika.update.HOT_UPDATE_DISMISS"

    /** Worker 更新代理的地址;部署 worker/mika-update 后按实际域名改这里。 */
    const val WORKER_BASE = "https://mika.tuptup.top"

    data class Info(val versionCode: Long, val sizeBytes: Long, val artifact: String)

    private fun prefs(context: Context) = context.getSharedPreferences("hot-update", Context.MODE_PRIVATE)

    /** 查询最新构建;限频 6 小时。无更新/网络失败/任何异常都返回 null(热更新绝不能影响主功能)。 */
    fun check(context: Context, force: Boolean = false): Info? {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (!force) {
            val last = prefs.getLong("lastCheck", 0)
            if (last > 0 && now - last < CHECK_INTERVAL_MS) return null
        }
        prefs.edit().putLong("lastCheck", now).apply()
        return try {
            val cur = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            val resp = httpGetJson(WORKER_BASE.trimEnd('/') + "/api/update-android")
            if (!resp.optBoolean("ok")) return null
            val info = Info(
                versionCode = resp.optLong("version_code", 0),
                sizeBytes = resp.optLong("size_in_bytes", 0),
                artifact = resp.optString("artifact", ""),
            )
            if (info.versionCode <= 0 || info.versionCode <= cur) {
                Log.i(TAG, "up to date: cur=$cur latest=${info.versionCode}")
                null
            } else {
                Log.i(TAG, "update available: cur=$cur latest=${info.versionCode} artifact=${info.artifact}")
                prefs.edit().putLong("lastFoundRun", info.versionCode).apply()
                info
            }
        } catch (e: Exception) {
            Log.w(TAG, "check failed: ${e.message}")
            null
        }
    }

    /** 启动时的一次自动检查:有新包且用户没忽略过就发通知。 */
    fun autoCheck(context: Context) {
        val info = check(context) ?: return
        if (info.versionCode <= prefs(context).getLong("dismissedRun", 0)) return
        notify(context, info)
    }

    /**
     * 经云端回包下载 zip 并解出 APK。重复下载会覆盖临时文件。
     * 解出的包按 ABI 挑:设备是 arm64 就用 arm64 小包,否则用 universal 包。
     * 返回可安装的 APK 文件;失败抛 [java.io.IOException]。
     */
    @Throws(Exception::class)
    fun downloadApk(context: Context): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val zip = File(dir, "update.zip")
        val url = WORKER_BASE.trimEnd('/') + "/api/update-android/download"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                // 百 MB 的包走云中转,读超时放宽
                readTimeout = 300_000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw java.io.IOException("更新包下载 HTTP $code")
            }
            conn.inputStream.use { input ->
                zip.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
            }
        } finally {
            conn?.disconnect()
        }
        val apkDir = File(
            File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "updates"),
            "hot",
        ).apply { mkdirs() }
        val preferArm64 = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
        val apk = File(apkDir, "hot-update.apk")
        apkDir.listFiles()?.forEach { it.delete() }
        var found: File? = null
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                    val candidate = File(apkDir, entry.name.substringAfterLast('/'))
                    candidate.outputStream().use { zin.copyTo(it) }
                    if (found == null || (preferArm64 && entry.name.contains("arm64"))) found = candidate
                }
                entry = zin.nextEntry
            }
        }
        val picked = found ?: throw java.io.IOException("压缩包里没有找到安装包")
        // 挑中的包固定名,安装地址才算得上稳定;其余清掉
        picked.copyTo(apk, overwrite = true)
        picked.delete()
        zip.delete()
        verifyApk(context, apk)
        return apk
    }

    /** 只装本应用、更高版本、同一签名的完整包;不通过就删文件并抛 [java.io.IOException]。 */
    private fun verifyApk(context: Context, target: File) {
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(target.path, flags)
            ?: throw java.io.IOException("安装包读不出来,可能下载不完整")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        if (archive.packageName != context.packageName) throw java.io.IOException("安装包不是 Mika")
        if (archive.longVersionCode <= installed.longVersionCode) throw java.io.IOException("安装包不比当前版本新")
        fun signers(info: android.content.pm.PackageInfo): Set<String> =
            (info.signingInfo?.apkContentsSigners ?: info.signatures).orEmpty()
                .map { sha256(it.toByteArray()) }.toSet()
        if (signers(archive) != signers(installed)) throw java.io.IOException("安装包签名与已装版本不一致")
    }

    private fun sha256(bytes: ByteArray): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    /** 拉起系统安装器(FileProvider 暴露 APK;安装确认仍由系统与用户完成)。 */
    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".updatefiles", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(i)
    }

    /** 是否已授予「安装未知应用」权限;未授予时用 [openInstallPermSettings] 引导。 */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermSettings(context: Context) {
        val i = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:" + context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(i)
    }

    /** 点通知进主界面,由 MainActivity 下载并拉起安装器。 */
    fun openUpdate(context: Context) {
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_OPEN_HOT_UPDATE, true)
        context.startActivity(i)
    }

    /** 用户点了「忽略」:这一版不再打扰,通知撤掉。 */
    fun dismiss(context: Context) {
        prefs(context).edit()
            .putLong("dismissedRun", prefs(context).getLong("lastFoundRun", 0))
            .apply()
        notificationManager(context).cancel(NOTIFICATION_ID)
    }

    private fun notificationManager(context: Context) =
        context.getSystemService(NotificationManager::class.java)

    private fun notify(context: Context, info: Info) {
        val manager = notificationManager(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.hotupdate_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_HOT_UPDATE, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val dismiss = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, HotUpdateReceiver::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val sizeMb = info.sizeBytes / (1024 * 1024)
        val text = context.getString(R.string.hotupdate_notification_text, info.versionCode, sizeMb)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(context.getString(R.string.hotupdate_notification_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.hotupdate_update_now), open)
            .addAction(0, context.getString(R.string.hotupdate_dismiss), dismiss)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "cannot post update notification", it) }
    }

    private fun httpGetJson(url: String): JSONObject {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return JSONObject(body)
        } finally {
            conn?.disconnect()
        }
    }
}

/** 通知上的「忽略」按钮:只记一个号、撤掉通知,不做别的。 */
class HotUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == HotUpdate.ACTION_DISMISS) HotUpdate.dismiss(context)
    }
}
