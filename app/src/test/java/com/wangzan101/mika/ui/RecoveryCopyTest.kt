package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.shell.AutoOnBlock
import com.wangzan101.mika.core.shell.HelperRestart
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.RecoveryConsent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryCopyTest {
    @get:Rule val texts = ChineseTexts()

    @Test
    fun pendingNoteOnlyForDefaultOnesNotYetApplied() {
        val a11y = RecoveryCapability.Accessibility
        val fresh = RecoveryConsent()
        // 还没配好手机操作:开关显示打开,下面说要先完成设置
        assertNotNull(RecoveryCopy.pending(a11y, fresh, paired = false, autoFailed = emptySet()))
        // 配好了、正在补上:不多说;补上没成功:照实说
        assertNull(RecoveryCopy.pending(a11y, fresh, paired = true, autoFailed = emptySet()))
        assertNotNull(RecoveryCopy.pending(a11y, fresh, paired = true, autoFailed = setOf(a11y)))
        // 已经生效,或用户亲手关掉:什么都不说
        assertNull(RecoveryCopy.pending(a11y, RecoveryConsent(applied = setOf(a11y)), paired = false, autoFailed = setOf(a11y)))
        assertNull(RecoveryCopy.pending(a11y, RecoveryConsent(declined = setOf(a11y)), paired = false, autoFailed = emptySet()))
    }

    /** 助手只用 USB 命令起过:靠无线调试重起助手的两项说清用不上;别的几项照旧。 */
    @Test
    fun wirelessOnlyCapabilitiesSayTheyNeedPairingWhenStartedOverUsb() {
        val usb = HelperRestart.UsbCommand
        listOf(RecoveryCapability.RestoreAfterReboot, RecoveryCapability.AutoWirelessDebugging).forEach { c ->
            val note = RecoveryCopy.pending(c, RecoveryConsent(applied = setOf(c)), paired = true, autoFailed = emptySet(), restart = usb)
            assertNotNull(c.name, note)
            // 用户关掉的不多说;配对过的照旧
            assertNull(RecoveryCopy.pending(c, RecoveryConsent(declined = setOf(c)), paired = true, autoFailed = emptySet(), restart = usb))
            assertNull(RecoveryCopy.pending(c, RecoveryConsent(applied = setOf(c)), paired = true, autoFailed = emptySet(), restart = HelperRestart.Wireless))
        }
        assertNull(RecoveryCopy.pending(RecoveryCapability.Accessibility, RecoveryConsent(), paired = true, autoFailed = emptySet(), restart = usb))
    }

    /** 用 Shizuku 启动过:靠无线调试重启的两项说明无需无线调试配对;无障碍和电池白名单照旧。 */
    @Test
    fun shizukuRestartExplainsNoWirelessPairingNeeded() {
        val shizuku = HelperRestart.Shizuku
        listOf(RecoveryCapability.RestoreAfterReboot, RecoveryCapability.AutoWirelessDebugging).forEach { c ->
            val note = RecoveryCopy.pending(c, RecoveryConsent(applied = setOf(c)), paired = false, autoFailed = emptySet(), restart = shizuku)
            assertNotNull(c.name, note)
            assertNull(RecoveryCopy.pending(c, RecoveryConsent(declined = setOf(c)), paired = false, autoFailed = emptySet(), restart = shizuku))
        }
        assertNull(RecoveryCopy.pending(RecoveryCapability.Accessibility, RecoveryConsent(applied = setOf(RecoveryCapability.Accessibility)), paired = false, autoFailed = emptySet(), restart = shizuku))
        assertNull(RecoveryCopy.pending(RecoveryCapability.BatteryWhitelist, RecoveryConsent(applied = setOf(RecoveryCapability.BatteryWhitelist)), paired = false, autoFailed = emptySet(), restart = shizuku))
    }

    @Test
    fun autoOnNoteOnlyWhenStopped() {
        assertNull(RecoveryCopy.autoOnBlocked(null))
        assertNotNull(RecoveryCopy.autoOnBlocked(AutoOnBlock.NotAllowedHere))
        assertEquals(AutoOnBlock.entries.size, AutoOnBlock.entries.mapNotNull(RecoveryCopy::autoOnBlocked).toSet().size)
    }
}
