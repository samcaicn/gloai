package com.wangzan101.mika.cap.phone.setup

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.core.device.shell.HelperHandoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 「用电脑启动」的脚本:电脑经 USB 调试跑 `adb shell sh <外部文件目录>/usb-start.sh`,起的是和无线调试同一个助手,
 * 交接也走同一个 [HelperHub.offer](同样核对 shell 身份、口令、签名)。
 * 助手不在时写带一次性口令的脚本;助手连着时换成不带口令的说明,磁盘上不留能用的口令。
 * 外部文件目录 App 自己写不用权限,shell 也读得到;别的 App 读不到。
 */
class UsbStartScript(context: Context, private val hub: HelperHub) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    /** 脚本在手机上的位置;外部存储暂时不可用时为 null。 */
    private fun file(): File? = app.getExternalFilesDir(null)?.let { File(it, HelperHandoff.USB_SCRIPT) }

    /** 电脑上要跑的那条命令(引导页显示、复制的就是它)。 */
    fun command(): String =
        HelperHandoff.usbCommand(file()?.absolutePath ?: "/storage/emulated/0/Android/data/${app.packageName}/files/${HelperHandoff.USB_SCRIPT}")

    /** App 启动时调:跟着助手在不在、口令有没有被作废(解除配对)重写脚本。 */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch { HelperHandoff.usbScriptWrites(hub.shell, hub.nonceResets).collect { write(connected = it) } }
    }

    /** 有人要用助手却没有、打开引导页时调:再写一遍(口令还新就沿用,见 [HelperHub.usbNonce])。 */
    fun refresh() {
        scope.launch { write(connected = hub.shell.value != null) }
    }

    @Synchronized
    private fun write(connected: Boolean) {
        val f = file() ?: return
        val text = runCatching {
            HelperHandoff.usbFile(
                connected, app.applicationInfo.sourceDir, app.packageName, Process.myUid(), { ownCertPin(app) }, hub::usbNonce, f.absolutePath,
            )
        }.getOrElse {
            Log.w(TAG, "cannot build ${f.name}", it)
            return
        }
        // 先写临时文件再改名:电脑那边正在读时不会读到半截
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.delete()
                check(tmp.renameTo(f)) { "rename failed" }
            }
        }.onFailure { Log.w(TAG, "cannot write ${f.name}", it) }
    }

    private companion object {
        const val TAG = "MikaUsbStart"
    }
}

/** Mika 签名证书的指纹:助手每次交接前都拿它核对 Mika(两种启动方式共用),取自本 App 自己的安装信息。 */
internal fun ownCertPin(context: Context): String {
    val signers = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        .signingInfo?.apkContentsSigners?.map { it.toByteArray() }
    return HelperHandoff.certPin(signers) ?: error("cannot read own signing certificate")
}
