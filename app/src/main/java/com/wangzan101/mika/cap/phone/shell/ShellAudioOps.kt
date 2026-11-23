package com.wangzan101.mika.cap.phone.shell

import android.os.Binder
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.MODE_RE
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.parsePlayAudioMode

// 助手服务:静音、音频模式与通话音频身份
internal fun ShellService.audioMuted(packageName: String, user: Int?, muted: Boolean): Boolean = synchronized(this.muted) {
    val key = AppAudioKey.of(packageName, user)
    if (muted) {
        if (!this.muted.contains(key)) {
            val (code, out) = appops("get", packageName, user)
            if (code != 0) return false
            this.muted.put(key, parsePlayAudioMode(out))
        }
        appops("set", packageName, user, "deny").first == 0
    } else {
        val ok = restoreAudioMode(packageName, user, AudioMuteLedger.restoreMode(this.muted[key]))
        if (ok) this.muted.remove(key)
        ok
    }
}

/** 按记录的键写回一项;键不合格式的当作已处理,免得一直留着。 */
internal fun ShellService.writeRecorded(key: String, mode: String): Boolean {
    val (pkg, user) = AppAudioKey.parse(key) ?: return true
    return restoreAudioMode(pkg, user, mode)
}

internal fun ShellService.restoreAudioMode(packageName: String, user: Int?, mode: String): Boolean {
    if (appops("set", packageName, user, mode).first == 0) return true
    // 有些系统禁止 shell 写 AppOps。静音没生效时，回读确认原值仍在就能清掉记录。
    val (code, output) = appops("get", packageName, user)
    return code == 0 && AudioMuteLedger.readMode(output) == mode
}

/** [user] 为空是 Mika 所在的用户。 */
internal fun ShellService.appops(verb: String, packageName: String, user: Int?, mode: String? = null): Pair<Int, String> {
    val u = (user ?: myUser).toString()
    return if (mode == null) sh("appops", verb, "--user", u, packageName, "PLAY_AUDIO")
    else sh("appops", verb, "--user", u, packageName, "PLAY_AUDIO", mode)
}

/** 别的用户编号原样给出;就是 Mika 所在的用户时为 null(按本来那份处理);不合法时也为 null,调用方据此拒绝。 */
internal fun ShellService.otherUser(userId: Int): Int? = userId.takeIf { it >= 0 && it != myUser }

internal fun ShellService.audioMode(packageName: String, user: Int?): String? {
    val (code, out) = appops("get", packageName, user)
    return if (code == 0) parsePlayAudioMode(out) else null
}

internal fun ShellService.audioMode(packageName: String, user: Int?, mode: String): Boolean {
    if (!MODE_RE.matches(mode)) return false
    val ok = restoreAudioMode(packageName, user, AudioMuteLedger.restoreMode(mode))
    if (ok) synchronized(muted) { muted.remove(AppAudioKey.of(packageName, user)) }
    return ok
}

internal inline fun <T> ShellService.asCallAudioShell(block: () -> T): T {
    val identity = Binder.clearCallingIdentity()
    return try { block() } finally { Binder.restoreCallingIdentity(identity) }
}
