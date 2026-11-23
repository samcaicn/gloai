package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.core.device.shell.A11yLoss
import com.wangzan101.mika.core.device.shell.A11yLossRecord
import com.wangzan101.mika.core.device.shell.RecoveryCapability
import com.wangzan101.mika.core.device.shell.RecoveryConsent
import com.wangzan101.mika.core.device.shell.RecoveryConsentStore
import com.wangzan101.mika.core.device.shell.RestoreOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「手机操作」那几项能力的记录,以及上次开机自动恢复的结果。开机广播和设置页在同一进程里看同一份。
 *
 * 每项两个布尔值:原来的键(如 `accessibility`)记「已生效」,含义和以前一样(以前只在命令成功后写 true);
 * `<键>_declined` 记「用户亲手关掉」。旧版本只有前一个键:写着 false 的分不出是没动过还是关掉过,
 * 一律当没动过——新键不存在就是没关掉过,所以升级不用另做迁移,这几项会在连接可用时按默认打开。
 */
class RecoverySettings(private val prefs: SharedPreferences) : RecoveryConsentStore {
    private val _consent = MutableStateFlow(read())
    override val consent: StateFlow<RecoveryConsent> = _consent.asStateFlow()

    private val _lastRestore = MutableStateFlow(readOutcome())
    val lastRestore: StateFlow<RestoreOutcome?> = _lastRestore.asStateFlow()

    private val _lastA11yLoss = MutableStateFlow(readLoss())
    /** 上一次 Mika 启动时发现无障碍服务被拿掉或没绑定(诊断信息用)。 */
    val lastA11yLoss: StateFlow<A11yLossRecord?> = _lastA11yLoss.asStateFlow()

    /** Mika 的无障碍服务连上过:之后启动时没连上才算问题,从没设置过的不算。 */
    var accessibilitySeen: Boolean
        get() = prefs.getBoolean(A11Y_SEEN, false)
        set(v) { if (v != accessibilitySeen) prefs.edit().putBoolean(A11Y_SEEN, v).apply() }

    fun recordA11yLoss(record: A11yLossRecord) {
        prefs.edit().putString(A11Y_LOSS, record.loss.name).putLong(A11Y_LOSS_AT, record.atMs).apply()
        _lastA11yLoss.value = record
    }

    private fun readLoss(): A11yLossRecord? {
        val loss = prefs.getString(A11Y_LOSS, null)?.let { n -> A11yLoss.entries.firstOrNull { it.name == n } } ?: return null
        return A11yLossRecord(loss, prefs.getLong(A11Y_LOSS_AT, 0))
    }

    @Synchronized override fun save(consent: RecoveryConsent) {
        val restore = RecoveryCapability.RestoreAfterReboot
        val restoreChanged = consent.isApplied(restore) != _consent.value.isApplied(restore)
        prefs.edit().apply {
            for ((c, key) in KEYS) {
                putBoolean(key, consent.isApplied(c))
                putBoolean(key + DECLINED, !consent.wants(c))
            }
            if (restoreChanged) remove(LAST_RESTORE)
        }.commit()
        _consent.value = consent
        // 重新打开或关掉后,旧的开机结果不再说明问题
        if (restoreChanged) _lastRestore.value = null
    }

    fun recordRestore(outcome: RestoreOutcome) {
        prefs.edit().putString(LAST_RESTORE, outcome.name).commit()
        _lastRestore.value = outcome
    }

    private fun read() = RecoveryConsent(
        applied = KEYS.filter { (_, key) -> prefs.getBoolean(key, false) }.keys,
        declined = KEYS.filter { (_, key) -> prefs.getBoolean(key + DECLINED, false) }.keys,
    )

    private fun readOutcome(): RestoreOutcome? =
        prefs.getString(LAST_RESTORE, null)?.let { n -> RestoreOutcome.entries.firstOrNull { it.name == n } }

    companion object {
        private const val PREFS = "builtin_shell_recovery"
        private val KEYS = mapOf(
            RecoveryCapability.Accessibility to "accessibility",
            RecoveryCapability.BatteryWhitelist to "battery_whitelist",
            RecoveryCapability.RestoreAfterReboot to "restore_after_reboot",
            RecoveryCapability.AutoWirelessDebugging to "auto_wireless_debugging",
        )
        private const val DECLINED = "_declined"
        private const val LAST_RESTORE = "last_restore"
        private const val A11Y_SEEN = "a11y_seen"
        private const val A11Y_LOSS = "a11y_loss"
        private const val A11Y_LOSS_AT = "a11y_loss_at"

        /** 产品里的那份(由 [com.wangzan101.mika.cap.phone.PhoneServices] 建)读写的偏好文件。 */
        fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
