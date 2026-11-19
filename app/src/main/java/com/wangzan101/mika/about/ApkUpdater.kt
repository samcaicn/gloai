package com.wangzan101.mika.about

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Network
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.wangzan101.mika.core.net.NetProxy
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class DownloadPhase { Idle, Downloading, Paused, Ready, Failed }

data class ApkDownloadState(
    val phase: DownloadPhase = DownloadPhase.Idle,
    val bytes: Long = 0,
    val total: Long = -1,
) {
    val percent: Int? get() = if (total > 0) ((bytes.toDouble() / total) * 100).toInt().coerceIn(0, 100) else null
}

/**
 * 手动直连下载交给系统;保存任务编号,进程重启后仍能查询、取消或安装。方法在 IO 线程调用。
 * 文件放在本应用的外部专属目录(别的 App 读写不到),同一时间只留一个;换版本、删除、装好后都清掉。
 * 自动下载和经代理的手动下载由 Mika 在进程里执行,以便绑定 Wi-Fi 或使用应用代理([ProxiedApkDownload])。
 */
class ApkUpdater(
    private val context: Context,
    private val net: NetProxy = NetProxy.shared,
    /** 当前的镜像设置;下载开始时取一次,下载途中改开关或顺序不影响这一次。 */
    private val mirrorSettings: () -> MirrorSettings = { MirrorSettings.OFF },
) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("apk-update", Context.MODE_PRIVATE)
    private val directory: File get() = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: error("external files unavailable"), "updates")
    private val file: File? get() = prefs.getString("file", null)?.let { File(directory, it) }
    /** 这一份由 Mika 自己下载。 */
    private val viaApp: Boolean get() = prefs.getBoolean("viaApp", false)

    @Synchronized
    fun state(release: ReleaseInfo): ApkDownloadState =
        if (prefs.getString("url", null) != release.apkUrl) ApkDownloadState() else recorded()

    /** 记下的那个下载任务现在的状态;系统里查不到这个任务算失败。 */
    private fun recorded(): ApkDownloadState {
        if (viaApp) return file?.let(proxied::state) ?: ApkDownloadState(DownloadPhase.Failed)
        val id = prefs.getLong("id", -1)
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (cursor.moveToFirst()) {
                fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                val phase = when (number(DownloadManager.COLUMN_STATUS).toInt()) {
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING -> DownloadPhase.Downloading
                    DownloadManager.STATUS_PAUSED -> DownloadPhase.Paused
                    DownloadManager.STATUS_SUCCESSFUL -> if (file?.isFile == true) DownloadPhase.Ready else DownloadPhase.Failed
                    else -> DownloadPhase.Failed
                }
                return ApkDownloadState(phase, number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR), number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            }
        }
        return ApkDownloadState(DownloadPhase.Failed)
    }

    @Synchronized
    /** 自动下载传入当前 Wi-Fi，手动下载沿用系统默认网络。 */
    fun download(release: ReleaseInfo, wifi: Network? = null) {
        val official = requireNotNull(release.apkUrl)
        ApkIntegrity.requireOfficialUrl(official)
        val settings = mirrorSettings()
        val sources = UpdateMirrors.candidates(official, settings)
        sources.forEach { ApkIntegrity.requireTrustedDownloadUrl(it, official, settings.mirrors) }
        val proxy = net.proxyFor(Uri.parse(official).host.orEmpty())
        // 下载的「身份」始终是官方地址:镜像开关或顺序变了,已经下好的同一份包不用重下。
        // 「暂停」不算在途:系统下载器有时会卡在暂停不自动恢复,再点下载就取消重下,给用户一个手动续上的口子。
        if (prefs.getString("url", null) == official && state(release).phase in
            listOf(DownloadPhase.Downloading, DownloadPhase.Ready)) return
        cancel()
        val folder = directory
        check(folder.mkdirs() || folder.isDirectory)
        // 进程被杀、记录丢了时留下的旧文件也一并清掉,目录里只留这一次的。
        folder.listFiles()?.forEach { it.delete() }
        // remove 的后台清理可能晚于下一次下载,不能复用旧任务的文件名。
        val target = File(folder, "update-${UUID.randomUUID()}.apk")
        // 镜像可能不止一个,要在一次任务里逐个换;系统下载器做不到,所以开了镜像就由 Mika 自己下。
        if (wifi != null || proxy != null || sources.size > 1) {
            check(prefs.edit().putBoolean("viaApp", true).putString("url", official).putString("file", target.name).commit()) { "could not save download" }
            proxied.start(sources, target, wifi) {
                verifyApk(it, release)
            }
            return
        }
        val url = official
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Mika ${release.version}")
            .setMimeType("application/vnd.android.package-archive")
            // 只在下载中显示通知:完成后的通知一点就会绕过下面的校验直接打开安装包,所以不留。
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(target))
        val id = manager.enqueue(request)
        if (!prefs.edit().putLong("id", id).putString("url", url).putString("file", target.name).commit()) {
            manager.remove(id)
            error("could not save download")
        }
    }

    @Synchronized
    fun cancel() {
        if (viaApp) proxied.cancel()
        val id = prefs.getLong("id", -1)
        if (id != -1L) manager.remove(id)
        val target = file
        check(target == null || !target.exists() || target.delete())
        check(prefs.edit().clear().commit())
    }

    /** 已经装上(或装了更新的版本)后,下好的安装包没用了:启动时清掉。下载中、暂停、失败的留给关于页处理。 */
    @Synchronized
    fun dropInstalled() {
        val target = file ?: return
        if (recorded().phase != DownloadPhase.Ready) return
        val archive = archiveInfo(target, 0)
        if (archive != null && archive.longVersionCode > installedInfo(0).longVersionCode) return
        cancel()
    }

    /**
     * 只接受本应用、更高版本、同一签名的完整附件;没通过就删掉文件并抛 [ApkRejected]。
     * 系统安装器之后还会再验签名并要求用户确认。
     */
    @Synchronized
    fun installIntent(release: ReleaseInfo): Intent {
        check(state(release).phase == DownloadPhase.Ready)
        ApkIntegrity.requireOfficialUrl(requireNotNull(release.apkUrl))
        val target = requireNotNull(file)
        try {
            verifyApk(target, release)
        } catch (e: ApkRejected) {
            cancel()
            throw e
        }
        val uri = if (viaApp) FileProvider.getUriForFile(context, "${context.packageName}.updatefiles", target)
            else manager.getUriForDownloadedFile(prefs.getLong("id", -1)) ?: error("missing APK")
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun verifyApk(target: File, release: ReleaseInfo) {
        ApkIntegrity.verify(target, release.apkSize, release.apkDigest)
        val archive = archiveInfo(target, SIGNING_FLAGS) ?: throw ApkRejected(ApkRejected.Reason.Corrupt)
        val installed = installedInfo(SIGNING_FLAGS)
        if (archive.packageName != context.packageName || archive.longVersionCode <= installed.longVersionCode) {
            throw ApkRejected(ApkRejected.Reason.NotNewer)
        }
        ApkIntegrity.requireReleaseVersion(archive.versionName, release.version)
        ApkIntegrity.requireSameSigner(installed.signers(), archive.signers(), archive.signerHistory())
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(target: File, flags: Int): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(target.path, flags)

    @Suppress("DEPRECATION")
    private fun installedInfo(flags: Int): PackageInfo = context.packageManager.getPackageInfo(context.packageName, flags)

    /** 当前签名证书;个别系统读安装包时不给 signingInfo,退回旧字段 signatures。 */
    @Suppress("DEPRECATION")
    private fun PackageInfo.signers(): Set<String> =
        (signingInfo?.apkContentsSigners ?: signatures).orEmpty().map { ApkIntegrity.sha256(it.toByteArray()) }.toSet()

    /** 轮换过签名的包带着历史证书;多个签名者时没有历史。 */
    private fun PackageInfo.signerHistory(): Set<String> {
        val info = signingInfo ?: return emptySet()
        if (info.hasMultipleSigners()) return emptySet()
        return info.signingCertificateHistory.orEmpty().map { ApkIntegrity.sha256(it.toByteArray()) }.toSet()
    }

    private companion object {
        /** 进程内只下载一份更新。 */
        val proxied by lazy {
            ProxiedApkDownload(NetProxy.shared.client(OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS)))
        }

        @Suppress("DEPRECATION")
        const val SIGNING_FLAGS = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
    }
}
