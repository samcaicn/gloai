package com.wangzan101.mika.cap.phone.shell

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.view.Surface
import androidx.annotation.Keep
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.device.shell.HelperHandoff
import com.wangzan101.mika.shell.IMikaShell
import kotlin.system.exitProcess

/**
 * Shizuku UserService entry point exposing [ShellService] over Shizuku's bound binder.
 * Instantiated by Shizuku inside the privileged UserService process via reflection.
 */
@Keep
class ShizukuShellUserService : IMikaShell.Stub {
    private val allowedUid: Int
    private val delegate: ShellService

    @Keep
    constructor(context: Context) : super() {
        ensureMainLooper()
        allowedUid = resolveUid(context)
        delegate = ShellService(allowedUid = allowedUid).apply { recoverMuted() }
    }

    @Keep
    constructor() : super() {
        ensureMainLooper()
        allowedUid = resolveUid(null)
        delegate = ShellService(allowedUid = allowedUid).apply { recoverMuted() }
    }

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int =
        delegate.createDisplay(surface, width, height, dpi)

    override fun releaseDisplay(displayId: Int) = delegate.releaseDisplay(displayId)

    override fun startApp(displayId: Int, packageName: String): Int =
        delegate.startApp(displayId, packageName)

    override fun topTaskId(displayId: Int): Int = delegate.topTaskId(displayId)

    override fun moveTask(taskId: Int, displayId: Int): Boolean =
        delegate.moveTask(taskId, displayId)

    override fun injectKey(displayId: Int, keyCode: Int): Boolean =
        delegate.injectKey(displayId, keyCode)

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean =
        delegate.injectTap(displayId, x, y)

    override fun setAudioMuted(packageName: String, muted: Boolean): Boolean =
        delegate.setAudioMuted(packageName, muted)

    override fun restartApp(displayId: Int, packageName: String): Int =
        delegate.restartApp(displayId, packageName)

    override fun getAudioMode(packageName: String): String? =
        delegate.getAudioMode(packageName)

    override fun setAudioMode(packageName: String, mode: String): Boolean =
        delegate.setAudioMode(packageName, mode)

    override fun displayOfTask(taskId: Int): Int = delegate.displayOfTask(taskId)

    override fun packageOfTask(taskId: Int): String? = delegate.packageOfTask(taskId)

    override fun setClipText(text: String): Int = delegate.setClipText(text)

    override fun restoreClip(): Boolean = delegate.restoreClip()

    override fun startView(displayId: Int, action: String?, uri: String?, packageName: String?): Int =
        delegate.startView(displayId, action, uri, packageName)

    override fun displayOfPackage(packageName: String): Int =
        delegate.displayOfPackage(packageName)

    override fun savedClipText(): String? = delegate.savedClipText()

    override fun restoreClipText(original: String, ours: String): Boolean =
        delegate.restoreClipText(original, ours)

    override fun identity(): IntArray = delegate.identity()

    override fun releaseAll() = delegate.releaseAll()

    override fun openDevtoolsSocket(name: String): ParcelFileDescriptor? =
        delegate.openDevtoolsSocket(name)

    override fun runSystemCommand(displayId: Int, command: String): Bundle =
        delegate.runSystemCommand(displayId, command)

    override fun topActivity(displayId: Int): String? = delegate.topActivity(displayId)

    override fun injectCtrlKey(displayId: Int, keyCode: Int): Int =
        delegate.injectCtrlKey(displayId, keyCode)

    override fun listUsers(): String? = delegate.listUsers()

    override fun listPackages(userId: Int): String? = delegate.listPackages(userId)

    override fun isSystemPackage(packageName: String): Boolean =
        delegate.isSystemPackage(packageName)

    override fun startShare(displayId: Int, packageName: String, text: String): Int =
        delegate.startShare(displayId, packageName, text)

    override fun watchMainScreen(windowMs: Int): Int = delegate.watchMainScreen(windowMs)

    override fun mainArrivals(sinceUptimeMs: Long): Bundle? =
        delegate.mainArrivals(sinceUptimeMs)

    override fun userOfTask(taskId: Int): Int = delegate.userOfTask(taskId)

    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean =
        delegate.injectStroke(displayId, xs, ys, times)

    override fun startSystemAction(displayId: Int, intent: Intent, asMika: PendingIntent?): Int =
        delegate.startSystemAction(displayId, intent, asMika)

    override fun runCommand(command: String): Bundle = delegate.runCommand(command)

    override fun setRestoreWish(flags: Int) = delegate.setRestoreWish(flags)

    override fun appCopyUsers(): Bundle? = delegate.appCopyUsers()

    override fun startAppInUser(displayId: Int, packageName: String, userId: Int, cold: Boolean): Int =
        delegate.startAppInUser(displayId, packageName, userId, cold)

    override fun displayOfPackageInUser(packageName: String, userId: Int): Int =
        delegate.displayOfPackageInUser(packageName, userId)

    override fun wakeDevice(): Boolean = delegate.wakeDevice()

    override fun dismissDeviceKeyguard(): Boolean = delegate.dismissDeviceKeyguard()

    override fun enterDeviceCredential(kind: Int, credential: ByteArray, clear: Int): Boolean =
        delegate.enterDeviceCredential(kind, credential, clear)

    override fun getAudioModeInUser(packageName: String, userId: Int): String? =
        delegate.getAudioModeInUser(packageName, userId)

    override fun setAudioMutedInUser(packageName: String, userId: Int, muted: Boolean): Boolean =
        delegate.setAudioMutedInUser(packageName, userId, muted)

    override fun setAudioModeInUser(packageName: String, userId: Int, mode: String): Boolean =
        delegate.setAudioModeInUser(packageName, userId, mode)

    override fun visibleOnMainScreen(packageName: String, userId: Int): Boolean =
        delegate.visibleOnMainScreen(packageName, userId)

    override fun finishDisplay(displayId: Int, keep: Array<String>?, close: Array<String>?): Bundle? =
        delegate.finishDisplay(displayId, keep, close)

    override fun revealDeviceCredential(): Boolean = delegate.revealDeviceCredential()

    override fun injectAsciiText(displayId: Int, taskId: Int, packageName: String, text: String): Int =
        delegate.injectAsciiText(displayId, taskId, packageName, text)

    override fun revealDeviceCredentialTap(x: Int, y: Int): Boolean =
        delegate.revealDeviceCredentialTap(x, y)

    override fun probeCallAudio(mode: Int): Bundle = delegate.probeCallAudio(mode)

    override fun openCallAudio(mode: Int, owner: IBinder): Bundle = delegate.openCallAudio(mode, owner)

    override fun stopCallAudio(owner: IBinder) = delegate.stopCallAudio(owner)

    override fun flushCallAudio(owner: IBinder): Boolean = delegate.flushCallAudio(owner)

    override fun callAudioStats(owner: IBinder): Bundle = delegate.callAudioStats(owner)

    override fun listFiles(path: String?, afterName: String?, limit: Int): Bundle =
        delegate.listFiles(path, afterName, limit)

    override fun openFile(path: String?): Bundle = delegate.openFile(path)

    override fun searchFiles(
        path: String?,
        name: String?,
        extension: String?,
        modifiedAfter: Long,
        modifiedBefore: Long,
        maxDepth: Int,
        maxEntries: Int,
        timeoutMs: Int,
    ): Bundle = delegate.searchFiles(path, name, extension, modifiedAfter, modifiedBefore, maxDepth, maxEntries, timeoutMs)

    override fun setDeviceFilePolicy(
        protocol: Int,
        session: String?,
        revision: Long,
        roots: Array<out String>?,
        recursive: BooleanArray?,
    ): Bundle = delegate.setDeviceFilePolicy(protocol, session, revision, roots, recursive)

    override fun startShareFile(displayId: Int, packageName: String, uri: String, mime: String, text: String): Int =
        delegate.startShareFile(displayId, packageName, uri, mime, text)

    override fun resizeDisplay(displayId: Int, surface: Surface, width: Int, height: Int, dpi: Int): Boolean =
        delegate.resizeDisplay(displayId, surface, width, height, dpi)

    override fun destroy() {
        runCatching { delegate.releaseAll() }
        exitProcess(0)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == DESTROY_AIDL_TRANSACTION || code == DESTROY_RAW_TRANSACTION) {
            val caller = Binder.getCallingUid()
            if (caller != allowedUid && caller != Process.myUid() && caller != HelperHandoff.SHELL_UID && caller != 0) {
                throw SecurityException("caller $caller is not allowed")
            }
            runCatching { delegate.releaseAll() }
            reply?.writeNoException()
            exitProcess(0)
        }
        return delegate.onTransact(code, data, reply, flags)
    }

    private companion object {
        const val DESTROY_RAW_TRANSACTION = 16777114
        const val DESTROY_AIDL_TRANSACTION = FIRST_CALL_TRANSACTION + DESTROY_RAW_TRANSACTION

        fun ensureMainLooper() {
            if (Looper.getMainLooper() == null) {
                @Suppress("DEPRECATION")
                Looper.prepareMainLooper()
            }
        }

        fun resolveUid(context: Context?): Int {
            val fromContext = runCatching {
                context?.takeIf { it.packageName == BuildConfig.APPLICATION_ID }?.applicationInfo?.uid
            }.getOrNull()
            if (fromContext != null && fromContext > 0 && fromContext != HelperHandoff.SHELL_UID && fromContext != Process.SYSTEM_UID) {
                return fromContext
            }
            val pm = runCatching { context?.packageManager }.getOrNull() ?: ShellContext.create().packageManager
            return pm.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid
        }
    }
}
