package com.wangzan101.mika.settings.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.device.shell.BuiltinShellFailure
import com.wangzan101.mika.core.device.shell.BuiltinShellFix
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.core.device.shell.HelperRestart
import com.wangzan101.mika.core.device.shell.RootGrant
import com.wangzan101.mika.core.device.shell.SetupStep
import com.wangzan101.mika.core.device.shell.SetupSuspect
import com.wangzan101.mika.settings.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSetupCopyTest {
    @get:Rule val texts = ChineseTexts()

    /** 给用户看的字里不出现这些技术说法(系统设置里本来就有的选项名除外,见下)。 */
    private val jargon = listOf("shell", "端口", "mDNS", "TLS", "Binder", "uid", "进程")

    private fun allText(): List<String> = buildList {
        add(PhoneSetupCopy.INTRO)
        SetupStep.entries.forEach { val c = PhoneSetupCopy.step(it); add(c.title); add(c.body); c.action?.let(::add); add(c.done); add(c.brief) }
        BuiltinShellFailure.entries.forEach { val f = PhoneSetupCopy.failure(it); add(f.title); add(f.body) }
        BuiltinShellFix.entries.forEach { add(PhoneSetupCopy.fixLabel(it)) }
        statuses.forEach { add(PhoneSetupCopy.statusLine(it)); add(PhoneSetupCopy.statusLine(it, usbDebugging = false)); add(PhoneSetupCopy.statusValue(it).first) }
        statuses.forEach { add(PhoneSetupCopy.statusLine(it, rootStart = true)); add(PhoneSetupCopy.statusLine(it, shizukuStart = true)) }
        RootGrant.entries.forEach {
            add(PhoneSetupCopy.rootStartLine(it)); add(PhoneSetupCopy.rootStatusLine(it))
            add(PhoneSetupCopy.rootCommandsLine(true, it)); add(PhoneSetupCopy.rootCommandsLine(false, it))
        }
        addAll(listOf(
            PhoneSetupCopy.ROOT_START, PhoneSetupCopy.ROOT_COMMANDS, PhoneSetupCopy.ROOT_COMMANDS_TITLE, PhoneSetupCopy.ROOT_COMMANDS_WARNING,
            PhoneSetupCopy.ROOT_NOTE, PhoneSetupCopy.ROOT_COMMANDS_NOTE, PhoneSetupCopy.ROOT_AVAILABLE, PhoneSetupCopy.ROOT_AUTO,
            PhoneSetupCopy.ROOT_STATUS_TITLE, PhoneSetupCopy.ROOT_COMMANDS_GROUP,
            PhoneSetupCopy.rootEntryLine(false), PhoneSetupCopy.rootEntryLine(true),
        ))
        addAll(
            listOf(
                PhoneSetupCopy.SUCCESS_LINE, PhoneSetupCopy.UNPAIR_BODY, PhoneSetupCopy.SKIP_NOTE, PhoneSetupCopy.DEGRADED_LINE, PhoneSetupCopy.PORT_FOUND,
                PhoneSetupCopy.NEED_NOTIFICATIONS, PhoneSetupCopy.MANUAL_HINT, PhoneSetupCopy.NOTIFY_ENTER_TEXT,
                PhoneSetupCopy.NOTIFY_ENTER_TITLE, PhoneSetupCopy.NOTIFY_ACTION, PhoneSetupCopy.NOTIFY_INPUT_LABEL,
                PhoneSetupCopy.AUTO_READ_HINT, PhoneSetupCopy.ACCESSIBILITY_SKIP, PhoneSetupCopy.NOTIFY_WINDOW_FOUND,
                PhoneSetupCopy.NOTIFY_CODE_INVALID, PhoneSetupCopy.PAIRING_CHANNEL_OFF, PhoneSetupCopy.OPEN_PAIRING_CHANNEL,
                PhoneSetupCopy.PAIRING_LOWERED, PhoneSetupCopy.NOTIFY_DONE_TITLE, PhoneSetupCopy.NOTIFY_DONE_TEXT,
                PhoneSetupCopy.ACCESSIBILITY_SKIPPED, PhoneSetupCopy.APP_INFO, PhoneSetupCopy.TURNED_ON_NEXT,
                PhoneSetupCopy.REVISIT_BACK, PhoneSetupCopy.REVISIT_NOTE, PhoneSetupCopy.CONNECT_PORT_SUBMIT,
            ),
        )
        HelperRestart.entries.forEach { add(PhoneSetupCopy.successNote(it)); add(PhoneSetupCopy.usageNote(it)) }
        suspects.forEach { add(PhoneSetupCopy.suspect(it)); add(PhoneSetupCopy.goBackTo(it.step)) }
        addAll(PhoneSetupCopy.NOTIFY_GUIDE)
        BuiltinShellFailure.entries.forEach { add(PhoneSetupCopy.notifyRetry(it)) }
    }

    private val suspects = listOf(
        SetupSuspect(SetupStep.DeveloperOptions, unreadable = false),
        SetupSuspect(SetupStep.WirelessDebugging, unreadable = false),
        SetupSuspect(SetupStep.WirelessDebugging, unreadable = true),
    )

    private val statuses = listOf(
        BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff, BuiltinShellStatus.Connecting, BuiltinShellStatus.Running,
    ) + BuiltinShellFailure.entries.map { BuiltinShellStatus.Failed(it) }

    @Test
    fun connectPortInputAcceptsOnlyValidPorts() {
        assertEquals(41235, ConnectPortInput.parse("41235"))
        assertEquals(41235, ConnectPortInput.parse(" 41 235 "))
        assertEquals(41235, ConnectPortInput.parse("４１２３５"))
        // 整段粘了「IP 地址和端口」也行
        assertEquals(41235, ConnectPortInput.parse("192.0.2.10:41235"))
        assertEquals(41235, ConnectPortInput.parse("192.0.2.10：41235"))
        assertEquals(1, ConnectPortInput.parse("1"))
        assertEquals(65535, ConnectPortInput.parse("65535"))
        for (bad in listOf("", " ", "0", "65536", "70000", "abc", "41a35", "-1", "192.0.2.10:")) assertNull(bad, ConnectPortInput.parse(bad))
    }

    @Test
    fun reconnectOnlyWhenRetryingIsTheFix() {
        val reconnectable = statuses.filter { PhoneSetupCopy.canReconnect(it) }
        assertEquals(
            // root 被拒绝、没回应:点重试再问一次 su；Shizuku 服务未跑/绑定失败同样直接点重试；非本机地址修正后也可重试。
            listOf(
                BuiltinShellFailure.ConnectFailed, BuiltinShellFailure.NotLocal, BuiltinShellFailure.HelperStartFailed,
                BuiltinShellFailure.RootDenied, BuiltinShellFailure.RootNoAnswer,
                BuiltinShellFailure.ShizukuNotRunning, BuiltinShellFailure.ShizukuBindFailed, BuiltinShellFailure.Unknown,
            ),
            reconnectable.map { (it as BuiltinShellStatus.Failed).reason },
        )
        reconnectable.forEach { s ->
            assertEquals(Fix.RetryPhoneHelper, StatusCopy.directFix(Problem.PhoneHelper(s)))
        }
        // 没配对要走引导;无线调试关着要先去打开;没 Wi-Fi 会自己恢复;配对失效要重新配对;Shizuku 未安装/未授权要去手机操作页处理
        listOf(
            BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff, BuiltinShellStatus.Connecting,
            BuiltinShellStatus.Running, BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi),
            BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked),
            BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuNotInstalled),
            BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuPermissionNeeded),
        ).forEach { assertFalse(PhoneSetupCopy.canReconnect(it)) }
    }

    @Test
    fun bannerAndFixForPhoneHelperProblem() {
        statuses.filter { it != BuiltinShellStatus.Running }.forEach { s ->
            val p = Problem.PhoneHelper(s)
            val fix = when {
                PhoneSetupCopy.canReconnect(s) -> Fix.RetryPhoneHelper
                // 这个 Wi-Fi 还没允许无线调试:直接去亲手打开一次
                s == BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed) -> Fix.OpenWirelessDebugging
                s == BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuNotInstalled) ||
                    s == BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuPermissionNeeded) -> Fix.OpenPhoneOps
                else -> Fix.OpenPhoneSetup
            }
            assertEquals(fix, StatusCopy.directFix(p))
            assertEquals(Fix.OpenPhoneSetup, StatusCopy.explain(p, "14").fix)
        }
    }

    @Test
    fun copyIsPlainChinese() {
        // 连不上时要用户照着「无线调试」页面填端口:只有这一句直说「端口」
        val portAllowed = PhoneSetupCopy.failure(BuiltinShellFailure.ConnectFailed).body
        allText().forEach { t ->
            assertTrue("empty copy", t.isNotBlank())
            jargon.forEach { j -> if (!(j == "端口" && t == portAllowed)) assertFalse("「$j」 in: $t", t.contains(j, ignoreCase = true)) }
            // 只有系统里那个选项名本身带 adb
            if (t.contains("adb")) assertTrue(t, t.contains("「停用 adb 授权超时功能」"))
        }
    }

    @Test
    fun failuresAreDistinctAndActionable() {
        val titles = BuiltinShellFailure.entries.map { PhoneSetupCopy.failure(it).title }
        assertEquals(titles.size, titles.toSet().size)
        assertEquals("重新配对", PhoneSetupCopy.fixLabel(BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode).fix))
        assertEquals("重试", PhoneSetupCopy.fixLabel(BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed).fix))
    }

    /** USB 命令起的助手停了:设置页、横幅都直说停了、再运行命令;不往无线调试引(有的手机根本没有)。 */
    @Test
    fun usbHelperStoppedSaysHowToStartItAgain() {
        val stopped = BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped)
        assertEquals("手机助手已停止", PhoneSetupCopy.failure(BuiltinShellFailure.UsbHelperStopped).title)
        val line = PhoneSetupCopy.statusLine(stopped)
        assertTrue(line, line.contains("USB 启动命令") && line.contains("再运行一次"))
        assertFalse(line, line.contains("无线调试"))
        assertEquals(BuiltinShellFix.RunUsbCommand, stopped.fix)
        assertEquals("复制", PhoneSetupCopy.fixLabel(stopped.fix))
        // 本来就是用命令起的:卡片说「再运行一次」,不说「打不开无线调试」
        val (againTitle, againBody) = PhoneSetupCopy.usbCard(HelperRestart.UsbCommand)
        assertEquals("再运行一次启动命令", againTitle)
        assertTrue(againBody.contains("重启后"))
        assertEquals(PhoneSetupCopy.USB_TITLE to PhoneSetupCopy.USB_BODY, PhoneSetupCopy.usbCard(HelperRestart.None))
        val banner = StatusCopy.banner(Problem.PhoneHelper(stopped))
        assertEquals("手机助手已停止", banner.title)
        assertEquals(line, banner.detail)
        assertEquals("这台手机打不开无线调试？改用电脑启动", PhoneSetupCopy.USB_TITLE)
        assertEquals("找不到无线调试？", PhoneSetupCopy.USB_ASK)
        assertTrue(PhoneSetupCopy.USB_BODY.contains("数据线"))
        assertTrue(PhoneSetupCopy.USB_BODY.contains("重启后"))
    }

    /** 「用 root 启动」用不了时,设置页、横幅都直说原因和怎么办;拒绝、没回应的给重试。正在用 root 启动时照实说。 */
    @Test
    fun rootStartStatusSaysWhyAndWhatToDo() {
        val denied = BuiltinShellStatus.Failed(BuiltinShellFailure.RootDenied)
        assertEquals("root 授权被拒绝了", PhoneSetupCopy.failure(BuiltinShellFailure.RootDenied).title)
        assertTrue(PhoneSetupCopy.statusLine(denied).contains("重试"))
        assertTrue(PhoneSetupCopy.canReconnect(denied))
        val banner = StatusCopy.banner(Problem.PhoneHelper(denied))
        assertEquals("root 授权被拒绝了", banner.title)
        assertEquals(PhoneSetupCopy.RECONNECT, banner.link)
        val gone = BuiltinShellStatus.Failed(BuiltinShellFailure.RootUnavailable)
        assertTrue(PhoneSetupCopy.statusLine(gone).contains("找不到 su"))
        assertFalse(PhoneSetupCopy.canReconnect(gone))
        assertEquals("手机助手没在运行,正在用 root 启动…", PhoneSetupCopy.statusLine(BuiltinShellStatus.Connecting, rootStart = true))
        assertEquals(PhoneSetupCopy.statusLine(BuiltinShellStatus.Connecting), PhoneSetupCopy.statusLine(BuiltinShellStatus.Connecting, rootStart = false))
        // 经 root 起的不靠 Wi-Fi:USB 调试关着也不提醒断 Wi-Fi 会停
        assertEquals(PhoneSetupCopy.statusLine(BuiltinShellStatus.Running), PhoneSetupCopy.statusLine(BuiltinShellStatus.Running, usbDebugging = false, rootStart = true))
    }

    /** 自动启动没有开关:首次明确授权,之后自动;拒绝和没回应都能亲手重试。Root 命令仍默认关闭。 */
    @Test
    fun rootLinesFollowAuthorization() {
        assertEquals(PhoneSetupCopy.ROOT_PENDING, PhoneSetupCopy.rootStartLine(RootGrant.Pending))
        assertTrue(PhoneSetupCopy.rootStartLine(RootGrant.Unknown).contains("点这里授权"))
        assertTrue(PhoneSetupCopy.rootStartLine(RootGrant.Granted).contains("已授权"))
        assertTrue(PhoneSetupCopy.rootStartLine(RootGrant.Denied).contains("再问一次"))
        assertTrue(PhoneSetupCopy.rootStartLine(RootGrant.NoAnswer).contains("再问一次"))
        assertTrue(PhoneSetupCopy.rootStatusLine(RootGrant.Unknown).contains("检测到 su"))
        assertTrue(PhoneSetupCopy.rootStatusLine(RootGrant.Granted).contains("已授权"))
        assertFalse(PhoneSetupCopy.rootStatusLine(RootGrant.Denied).contains("再问一次"))
        assertTrue(PhoneSetupCopy.rootCommandsLine(false, RootGrant.Unknown).contains("默认关闭"))
        assertTrue(PhoneSetupCopy.rootCommandsLine(true, RootGrant.Granted).contains("已开启"))
        assertTrue(PhoneSetupCopy.rootCommandsLine(false, RootGrant.Denied).contains("保持关闭"))
        assertTrue(PhoneSetupCopy.rootEntryLine(false).contains("已关闭"))
        assertTrue(PhoneSetupCopy.rootEntryLine(true).contains("已开启"))
        assertTrue(PhoneSetupCopy.ROOT_COMMANDS_WARNING.contains("其他 App 的数据"))
        assertEquals("允许 Mika 执行 root 命令", PhoneSetupCopy.ROOT_COMMANDS)
        assertEquals("用 root 启动手机助手", PhoneSetupCopy.ROOT_START)
    }

    /** USB 启动不承诺拔线后一定能用:成功页、设置页和重跑卡片都给出通用恢复办法。 */
    @Test
    fun usbCopyDoesNotPromiseUnplugSurvival() {
        listOf(
            PhoneSetupCopy.successNote(HelperRestart.UsbCommand),
            PhoneSetupCopy.usageNote(HelperRestart.UsbCommand),
            PhoneSetupCopy.usbCard(HelperRestart.UsbCommand).second,
        ).forEach { text ->
            assertTrue(text, text.contains("多数手机"))
            assertTrue(text, text.contains("拔掉数据线"))
            assertTrue(text, text.contains("再运行一次"))
            assertFalse(text, text.contains("拔掉数据线也会继续运行"))
        }
        // 无线调试路径的提示不受 USB 恢复说明影响
        assertFalse(PhoneSetupCopy.successNote(HelperRestart.Wireless).contains("拔掉数据线"))
        assertFalse(PhoneSetupCopy.usageNote(HelperRestart.Wireless).contains("拔掉数据线"))
    }

    /** Shizuku 失败文案、重试/去设置分支、启动中/运行中状态行和重启后提示。 */
    @Test
    fun shizukuFailuresHaveClearCopyAndActions() {
        listOf(
            BuiltinShellFailure.ShizukuNotInstalled,
            BuiltinShellFailure.ShizukuPermissionNeeded,
        ).forEach { reason ->
            val st = BuiltinShellStatus.Failed(reason)
            assertFalse(reason.name, PhoneSetupCopy.canReconnect(st))
            assertEquals(Fix.OpenPhoneOps, StatusCopy.directFix(Problem.PhoneHelper(st)))
            val banner = StatusCopy.banner(Problem.PhoneHelper(st))
            assertEquals(PhoneSetupCopy.failure(reason).title, banner.title)
            assertEquals(PhoneSetupCopy.failure(reason).body, banner.detail)
            assertEquals("去处理", banner.link)
        }

        listOf(
            BuiltinShellFailure.ShizukuNotRunning,
            BuiltinShellFailure.ShizukuBindFailed,
        ).forEach { reason ->
            val st = BuiltinShellStatus.Failed(reason)
            assertTrue(reason.name, PhoneSetupCopy.canReconnect(st))
            assertEquals(Fix.RetryPhoneHelper, StatusCopy.directFix(Problem.PhoneHelper(st)))
            val banner = StatusCopy.banner(Problem.PhoneHelper(st))
            assertEquals(PhoneSetupCopy.failure(reason).title, banner.title)
            assertEquals(PhoneSetupCopy.failure(reason).body, banner.detail)
            assertEquals(PhoneSetupCopy.RECONNECT, banner.link)
        }

        val notRunning = PhoneSetupCopy.failure(BuiltinShellFailure.ShizukuNotRunning)
        assertTrue(notRunning.title, notRunning.title.contains("未运行"))
        assertTrue(notRunning.body, notRunning.body.contains("手机重启后"))

        assertTrue(PhoneSetupCopy.statusLine(BuiltinShellStatus.Connecting, shizukuStart = true).contains("Shizuku"))
        assertEquals(
            PhoneSetupCopy.statusLine(BuiltinShellStatus.Running, shizukuStart = true),
            PhoneSetupCopy.statusLine(BuiltinShellStatus.Running, usbDebugging = false, shizukuStart = true),
        )
        assertTrue(PhoneSetupCopy.usageNote(HelperRestart.Shizuku).contains("不会自行启动"))
        assertTrue(PhoneSetupCopy.successNote(HelperRestart.Shizuku).contains("手机重启后"))
    }
}
