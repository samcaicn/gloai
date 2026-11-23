package com.wangzan101.mika.core.device.shell

/**
 * 手机内置助手走哪条通道启动。
 * [Auto] 优先用可用的 Shizuku,不行时按原有 root / 无线调试兜底;
 * [WirelessDebugging] 忽略 Shizuku,只走原有路线;
 * [Shizuku] 只走 Shizuku,绝不回落到 root 或无线调试。
 */
enum class ShellTransport {
    Auto,
    WirelessDebugging,
    Shizuku;

    companion object {
        fun parse(raw: String?): ShellTransport {
            val normalized = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return Auto
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: Auto
        }
    }
}

/** 平台上看到的 Shizuku 状态:有没有装、服务跑没跑、有没有给 Mika 授权。 */
data class ShizukuPlatformState(
    val installed: Boolean = false,
    val running: Boolean = false,
    val permissionGranted: Boolean = false,
) {
    val ready: Boolean get() = installed && running && permissionGranted

    fun failure(): BuiltinShellFailure = when {
        !installed -> BuiltinShellFailure.ShizukuNotInstalled
        !running -> BuiltinShellFailure.ShizukuNotRunning
        !permissionGranted -> BuiltinShellFailure.ShizukuPermissionNeeded
        else -> BuiltinShellFailure.ShizukuBindFailed
    }

    companion object {
        val Unavailable = ShizukuPlatformState()
    }
}
