package com.wangzan101.mika.cap.phone.shell

import android.app.AppOpsManager
import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PersistableBundle
import android.os.Process
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.CLIP_LABEL
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.OPSTR_READ_CLIPBOARD
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.READ_CLIPBOARD_IN_BACKGROUND
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.EXTRA_IS_SENSITIVE
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.EXTRA_SUPPRESS_CLIPBOARD_OVERLAY

// 助手服务:剪贴板读写保护与设备文件策略
/** 剪贴板此刻是不是 Mika 放进去的 [ours];读不到(有内容但取不到)时不当成空的。 */
internal fun ShellService.nowState(ours: String?): ShellClip.Now {
    val now = clipboard.primaryClip
    return when (ShellClip.read(now != null, clipboard.hasPrimaryClip(), clipboard.primaryClipDescription != null)) {
        ShellClip.Read.Empty -> ShellClip.Now.Empty
        ShellClip.Read.Unreadable -> ShellClip.Now.Unreadable
        ShellClip.Read.Has -> {
            val ourClip = now!!.description.label == CLIP_LABEL && now.itemCount == 1 && ours != null &&
                now.getItemAt(0).text?.toString() == ours
            if (ourClip) ShellClip.Now.Ours else ShellClip.Now.Other
        }
    }
}

/** shell 要有后台读剪贴板的权限、且读剪贴板的权限操作没被关掉,才算读得到。 */
internal fun ShellService.canReadClipboard(): Boolean = runCatching {
    val granted = context.checkPermission(READ_CLIPBOARD_IN_BACKGROUND, Process.myPid(), Process.myUid()) ==
        PackageManager.PERMISSION_GRANTED
    val ops = context.getSystemService(AppOpsManager::class.java)
    granted && ops.unsafeCheckOpNoThrow(OPSTR_READ_CLIPBOARD, Process.myUid(), ShellContext.SHELL_PACKAGE) ==
        AppOpsManager.MODE_ALLOWED
}.getOrDefault(false)

internal fun ShellService.suppressOverlay(clip: ClipData): ClipData {
    val extras = clip.description.extras?.let { PersistableBundle(it) } ?: PersistableBundle()
    extras.putBoolean(EXTRA_SUPPRESS_CLIPBOARD_OVERLAY, true)
    clip.description.extras = extras
    return clip
}

/**
 * Mika 粘贴的内容(可能是地址、手机号等)标成敏感:系统和输入法的剪贴板预览、历史不显示原文。
 * 只标 Mika 自己放进去的,还原用户的剪贴板时保持原样。
 */
internal fun ShellService.markSensitive(clip: ClipData): ClipData {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return clip
    val extras = clip.description.extras?.let { PersistableBundle(it) } ?: PersistableBundle()
    extras.putBoolean(EXTRA_IS_SENSITIVE, true)
    clip.description.extras = extras
    return clip
}

internal inline fun ShellService.deviceFileCall(block: (DeviceFilePolicy) -> Bundle): Bundle = asCallAudioShell {
    val snapshot = filePolicy ?: return@asCallAudioShell Bundle().apply { putString("status", "policy_unsynced") }
    val response = block(snapshot.policy)
    synchronized(filePolicyLock) {
        if (filePolicy !== snapshot) {
            response.getParcelable<ParcelFileDescriptor>("fd")?.close()
            Bundle().apply { putString("status", "policy_changed") }
        } else {
            response.getParcelable<ParcelFileDescriptor>("fd")?.let { returnedCallPipes.set(listOf(it)) }
            response
        }
    }
}
