package com.wangzan101.mika.settings.ui

import com.wangzan101.mika.core.device.shell.HelperRestart
import com.wangzan101.mika.ChineseUi
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.capsule.CapsuleMode
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.core.device.shell.ShellTransport
import com.wangzan101.mika.core.device.shell.ShizukuPlatformState
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.Problem
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.settings.WebSearchConfig
import com.wangzan101.mika.task.ParallelCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import org.junit.runner.RunWith
import com.wangzan101.mika.core.cap.phone.OperatingPace
import com.wangzan101.mika.capsule.ui.CapsuleScreen
import com.wangzan101.mika.ui.awaitOne
import com.wangzan101.mika.ui.clickInDialog

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val ready = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true)

    private fun home(
        status: SystemStatus = ready,
        mainModel: String? = "DeepSeek",
        onProblem: (Problem) -> Unit = {},
        opened: MutableList<String> = mutableListOf(),
    ) {
        compose.setContent {
            SettingsContent(
                status = status, androidRelease = "14", mainModel = mainModel, onBack = {}, onProblem = onProblem,
                onOpenModels = { opened += "models" }, onOpenPhoneOps = { opened += "phone" }, onOpenDuringWork = { opened += "work" },
                onOpenWechat = { opened += "wechat" }, onOpenProactive = { opened += "proactive" },
                capsuleMode = CapsuleMode.DuringTasks, onOpenCapsule = { opened += "capsule" }, onOpenNotifications = { opened += "notifications" },
                onOpenLanguage = { opened += "language" }, onOpenAppAccess = { opened += "access" },
                onOpenMemory = { opened += "memory" }, onOpenAdvanced = { opened += "advanced" },
                versionName = "0.1.0", onOpenAbout = { opened += "about" },
            )
        }
    }

    /** 页面是一整列滚动内容:先把这一行滚进视口再断言或点击。 */
    private fun scrollTo(text: String) {
        compose.awaitOne(text).performScrollTo()
        compose.waitForIdle()
    }

    @Test
    fun homeShowsGroupsStatusAndValues() {
        home()
        compose.onNodeWithText("一切就绪").assertIsDisplayed()
        compose.onNodeWithText("常用").assertIsDisplayed()
        // 首页只显示模型、手机操作、通知和版本等常用状态。
        for (v in listOf("DeepSeek", "可用", "正常", "0.1.0")) {
            scrollTo(v)
            compose.onNodeWithText(v).assertIsDisplayed()
        }
    }

    @Test
    fun homeOmitsStaticEntrySubtitles() {
        compose.setContent {
            SettingsContent(
                status = ready, androidRelease = "14", mainModel = "DeepSeek", onBack = {}, onProblem = {}, onOpenModels = {},
                onOpenCalls = {}, onOpenWechat = {}, onOpenProactive = {}, onOpenAppAccess = {}, onOpenSmsLogin = {},
                onOpenDuringWork = {}, capsuleMode = CapsuleMode.Always, onOpenCapsule = {}, onOpenNotifications = {},
                onOpenMemory = {}, onOpenBackup = {}, onOpenRecommendations = {}, onOpenPersonas = {}, onOpenAdvanced = {},
                versionName = "0.1.0", onOpenAbout = {}, onPersonalStart = {}, autoArchiveDays = 7, onOpenAutoArchive = {},
            )
        }
        val staticCopy = listOf(
            "模型已配置 · 手机操作可用",
            "模型、服务商与 Token 用量",
            "实时语音 · 运营商电话",
            "在微信里给 Mika 安排任务",
            "读取所选 App 的通知",
            "自主模式、操作节奏、步数上限与声音",
            "用别的 App 时，在屏幕上看任务进展",
            "选择 Mika 和你说话的方式",
            "什么时候弹出、去系统里改",
            "把个人助手设为 Mika 首屏",
            "记忆、技能、操作路线与网站登录",
            "把全部数据导出成一个 ZIP 文件，或从 ZIP 恢复",
            "太久没有新消息的对话自动收进归档",
            "管理常用任务的名称和内容",
            "按应用设置禁用和屏幕分享许可",
            "默认关闭 · 本机可信配置",
            "扩展、网络、运行环境与维护",
        )
        for (text in staticCopy) compose.onAllNodesWithText(text).assertCountEquals(0)
        for (value in listOf("DeepSeek", "可用", "正常", "7 天", "0.1.0")) {
            scrollTo(value)
            compose.onNodeWithText(value).assertIsDisplayed()
        }
    }

    @Test
    fun homeRowsOpenTheirPages() {
        val opened = mutableListOf<String>()
        home(opened = opened)
        // 「主动能力」在「微信」和「任务运行」之间
        for (t in listOf("模型", "手机操作", "微信", "主动能力", "任务运行", "悬浮胶囊", "语言与外观", "通知", "记忆与数据", "应用访问", "高级与其他", "关于 Mika")) {
            scrollTo(t)
            compose.onNodeWithText(t).performClick()
        }
        assertEquals(listOf("models", "phone", "wechat", "proactive", "work", "capsule", "language", "notifications", "memory", "access", "advanced", "about"), opened)
    }

    /** 高级与其他集中放连接、维护和工程入口,各行仍打开原来的子页。 */
    @Test
    fun advancedRowsOpenTheirPages() {
        val opened = mutableListOf<String>()
        compose.setContent {
            AdvancedSettingsContent(
                onBack = {}, proxy = "", onOpenProxy = { opened += "proxy" },
                sandbox = SandboxStatus.Ready(network = false), onOpenSandbox = { opened += "sandbox" },
                onOpenRecovery = { opened += "recovery" },
                computerAccess = false, onOpenComputerAccess = { opened += "computer" },
                mcpServersCount = 0, onOpenMcpServers = { opened += "mcp" },
                showDeveloper = true, onOpenDeveloper = { opened += "dev" },
                onQuit = { opened += "quit" },
            )
        }
        for (t in listOf("网络代理", "文件与计算环境", "连接电脑", "MCP 服务器", "恢复与修复")) {
            scrollTo(t)
            compose.onNodeWithText(t).performClick()
        }
        compose.onNodeWithText("开发者选项").performScrollTo().performClick()
        scrollTo("完全退出 Mika")
        compose.onNodeWithText("完全退出 Mika").performClick()
        assertEquals(listOf("proxy", "sandbox", "computer", "mcp", "recovery", "dev", "quit"), opened)
    }

    /** 发布版的高级与其他里没有开发者选项。 */
    @Test
    fun developerRowHiddenInRelease() {
        compose.setContent { AdvancedSettingsContent(onBack = {}, showDeveloper = false) }
        compose.onAllNodesWithText("开发者选项").assertCountEquals(0)
    }

    /** 有问题时状态卡变成提示,按钮把问题交给 onProblem。 */
    @Test
    fun statusCardTurnsIntoFixPrompt() {
        val clicked = mutableListOf<Problem>()
        home(
            SystemStatus(34, BuiltinShellStatus.WirelessDebuggingOff, AccessibilityState.On, notificationsAllowed = true),
            onProblem = { clicked += it },
        )
        compose.onNodeWithText("无线调试关着").assertIsDisplayed()
        compose.onAllNodesWithText("一切就绪").assertCountEquals(0)
        compose.onNodeWithText("需要处理").assertIsDisplayed()
        compose.awaitOne("去打开").performClick()
        assertEquals(listOf<Problem>(Problem.PhoneHelper(BuiltinShellStatus.WirelessDebuggingOff)), clicked)
    }

    @Test
    fun noModelAndOldSystem() {
        home(SystemStatus(30, BuiltinShellStatus.Running, AccessibilityState.EnabledNotRunning, notificationsAllowed = false), mainModel = null)
        compose.onNodeWithText("手机操作不可用").assertIsDisplayed()
        compose.onNodeWithText("未设置").assertIsDisplayed()
        compose.onNodeWithText("不支持").assertIsDisplayed()
    }

    @Test
    fun phoneOpsPageShowsPermissionRowsAndPairing() {
        var repaired = 0
        compose.setContent {
            PhoneOpsScreen(
                SystemStatus(34, BuiltinShellStatus.WirelessDebuggingOff, AccessibilityState.On, notificationsAllowed = true),
                "14", onProblem = {}, onRepair = { repaired++ }, onUnpair = {}, onBack = {},
            )
        }
        compose.onNodeWithText("无障碍服务").assertIsDisplayed()
        compose.onNodeWithText("已开启").assertIsDisplayed()
        compose.onNodeWithText("无线调试已关").assertIsDisplayed()
        compose.onNodeWithText("安卓 14").assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.usageNote(HelperRestart.None)).performScrollTo().assertExists()
        compose.onNodeWithText(PhoneSetupCopy.REPAIR).performScrollTo().performClick()
        assertEquals(1, repaired)
    }

    /** 通知页:总开关允许、一个频道被调低、进度被关时,各行显示状态,点各行交给 onFix。 */
    @Test
    fun notificationsPageShowsChannelsAndOpensThem() {
        val fixes = mutableListOf<Fix>()
        compose.setContent {
            NotificationsScreen(
                ready.copy(attentionImportance = 4, resultImportance = 2, progressImportance = 0), onFix = { fixes += it }, onBack = {},
            )
        }
        compose.onNodeWithText("已允许").assertIsDisplayed()
        compose.onNodeWithText("需要你接管、确认、回答时").assertIsDisplayed()
        compose.onNodeWithText("会弹出").assertIsDisplayed()
        compose.onNodeWithText("只进通知栏").assertIsDisplayed()
        compose.onNodeWithText("已关闭").assertIsDisplayed()
        compose.onNodeWithText(StatusCopy.NOTIF_FOOTER).assertExists()
        compose.onNodeWithText("实时任务通知").assertDoesNotExist()
        compose.onNodeWithText("需要你处理").performClick()
        compose.onNodeWithText("任务结果").performClick()
        compose.onNodeWithText("任务进度").performClick()
        assertEquals(listOf(Fix.OpenAttentionChannel, Fix.OpenResultChannel, Fix.OpenProgressChannel), fixes)
    }

    @Test
    fun notificationsPageOpensLiveUpdatesOnAndroid16() {
        val fixes = mutableListOf<Fix>()
        compose.setContent { NotificationsScreen(ready.copy(sdkInt = 36), onFix = { fixes += it }, onBack = {}) }
        compose.onNodeWithText("实时任务通知").performScrollTo().performClick()
        assertEquals(listOf(Fix.OpenLiveTaskSettings), fixes)
    }

    /** 总开关关着:给「去打开」,三个频道都显示已关闭。 */
    @Test
    fun notificationsPageWhenAppOff() {
        val fixes = mutableListOf<Fix>()
        compose.setContent { NotificationsScreen(ready.copy(notificationsAllowed = false), onFix = { fixes += it }, onBack = {}) }
        compose.onAllNodesWithText("已关闭").assertCountEquals(3)
        compose.onNodeWithText("去打开").performClick()
        assertEquals(listOf(Fix.AllowNotifications), fixes)
    }

    @Test
    fun unpairAsksFirst() {
        var unpaired = 0
        compose.setContent { PhoneOpsScreen(ready, "14", onProblem = {}, onRepair = {}, onUnpair = { unpaired++ }, onBack = {}) }
        compose.onNodeWithText(PhoneSetupCopy.UNPAIR).performScrollTo().performClick()
        assertEquals(0, unpaired)
        compose.clickInDialog(PhoneSetupCopy.UNPAIR_CONFIRM)
        assertEquals(1, unpaired)
    }

    /** 并行开关打开、但设备不支持时:这一行灰掉,写明原因,不给加减。 */
    @Test
    fun parallelDisabledShowsReason() {
        compose.setContent {
            DuringWorkScreen(
                keepScreenOn = true, onKeepScreenOn = {}, muteTarget = true, onMuteTarget = {},
                parallel = ParallelSetting(2, ParallelCapability.detect(33, lowRam = false, userMax = 2, secondDisplayFailed = false)),
                onParallelMax = {}, onBack = {},
            )
        }
        compose.onNodeWithText("同时进行的任务").assertIsDisplayed()
        compose.onNodeWithText("安卓 14 以上才支持同时进行多个任务").assertIsDisplayed()
        compose.onNodeWithText("1 个").assertIsDisplayed()
        compose.onNodeWithText("+").assertDoesNotExist()
    }

    @Test
    fun parallelMaxAdjustable() {
        val picked = mutableListOf<Int>()
        var max by mutableStateOf(2)
        compose.setContent {
            DuringWorkScreen(
                keepScreenOn = true, onKeepScreenOn = {}, muteTarget = true, onMuteTarget = {},
                parallel = ParallelSetting(max, ParallelCapability.detect(34, lowRam = false, userMax = max, secondDisplayFailed = false)),
                onParallelMax = { max = it; picked += it }, onBack = {},
            )
        }
        compose.onNodeWithText("屏幕保持常亮").assertIsDisplayed()
        compose.onNodeWithText("2 个").assertIsDisplayed()
        compose.onNodeWithText("+").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("3 个").assertIsDisplayed()
        compose.onNodeWithText("−").performClick()
        assertEquals(listOf(3, 2), picked)
    }

    @Test
    fun duringWorkWithoutParallelHasNoParallelRow() {
        compose.setContent {
            DuringWorkScreen(true, {}, true, {}, parallel = null, onParallelMax = {}, onBack = {})
        }
        compose.onNodeWithText("被操作的 App 静音").assertIsDisplayed()
        compose.onAllNodesWithText("同时进行的任务").assertCountEquals(0)
    }

    /** 「干活时」两项默认开着,用户可以关:点了开关回调新值。 */
    @Test
    fun duringWorkTogglesCanBeTurnedOff() {
        val changes = mutableListOf<String>()
        compose.setContent {
            DuringWorkScreen(
                keepScreenOn = true, onKeepScreenOn = { changes += "screen:$it" },
                muteTarget = true, onMuteTarget = { changes += "mute:$it" },
                parallel = null, onParallelMax = {}, onBack = {},
            )
        }
        compose.onNodeWithTag("work-keep-screen-on").performClick()
        compose.onNodeWithTag("work-mute-target").performClick()
        assertEquals(listOf("screen:false", "mute:false"), changes)
    }

    /** 干活时 › 操作节奏:快、标准(默认)、像人三选一,点了回调新值。 */
    @Test
    fun operatingPaceIsOneChoiceOfThree() {
        val picked = mutableListOf<OperatingPace>()
        compose.setContent {
            DuringWorkScreen(true, {}, true, {}, parallel = null, onParallelMax = {}, onBack = {}, onOperatingPace = { picked += it })
        }
        compose.onNodeWithText("操作节奏").assertIsDisplayed()
        compose.onNodeWithText("快").assertIsDisplayed()
        compose.onNodeWithText("标准（默认）").assertIsDisplayed()
        compose.onNodeWithText("像人").performClick()
        compose.onNodeWithText("快").performClick()
        assertEquals(listOf(OperatingPace.HUMAN, OperatingPace.FAST), picked)
    }

    /** 首页「悬浮胶囊」一行写着当前的选择。 */
    @Test
    fun capsuleRowShowsCurrentChoice() {
        compose.setContent {
            SettingsContent(status = ready, androidRelease = "14", mainModel = "m", onBack = {}, onProblem = {}, onOpenModels = {}, capsuleMode = CapsuleMode.Always)
        }
        compose.awaitOne("一直显示").assertIsDisplayed()
    }

    /** 悬浮胶囊子页三选一,选了回调新值;页脚说明用的是无障碍服务。 */
    @Test
    fun capsulePageOffersThreeChoices() {
        var mode by mutableStateOf(CapsuleMode.DuringTasks)
        val picked = mutableListOf<CapsuleMode>()
        compose.setContent { CapsuleScreen(mode, onMode = { picked += it; mode = it }, onBack = {}) }
        compose.awaitOne("有任务时显示").assertIsDisplayed()
        compose.onNodeWithText("关闭").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("贴边自动隐藏").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("全屏时隐藏胶囊").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("允许添加到快捷设置").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("几秒没碰后的显示程度", substring = true).assertDoesNotExist()
        compose.onNodeWithText("前台应用铺满屏幕", substring = true).assertDoesNotExist()
        // 脚注在三个选项下面,矮屏或大字号时要滚一下才看得到
        compose.onNodeWithText("无障碍服务", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("一直显示").performScrollTo().performClick()
        compose.onNodeWithText("关闭").performScrollTo().performClick()
        assertEquals(listOf(CapsuleMode.Always, CapsuleMode.Off), picked)
    }

    /** 「全部清除」先问一句;确认后清空,条数变成 0、按钮消失;取消则不动。 */
    @Test
    fun clearRoutesAsksThenEmpties() {
        var routes by mutableStateOf(3)
        var cleared = 0
        compose.setContent { RoutesScreen(routes, onClear = { cleared++; routes = 0 }, onBack = {}) }
        compose.onNodeWithText("已记住的操作路线").assertIsDisplayed()
        compose.onNodeWithText("3 条", substring = true).assertIsDisplayed()
        compose.onNodeWithText("全部清除").performClick()
        compose.clickInDialog("取消")
        assertEquals(0, cleared)
        compose.onNodeWithText("全部清除").performClick()
        compose.awaitOne("清除全部已记住的操作路线?", inDialog = true).assertIsDisplayed()
        compose.clickInDialog("清除")
        assertEquals(1, cleared)
        compose.onNodeWithText("0 条", substring = true).assertIsDisplayed()
        compose.onNodeWithText("全部清除").assertDoesNotExist()
    }

    /** 开发者选项:八个开关分速度、实验两组,点一下打开对应那一项。 */
    @Test
    fun developerOptionsHasTwoGroupsAndToggles() {
        val changed = mutableListOf<SpeedFlags>()
        var flags by mutableStateOf(SpeedFlags.OFF)
        compose.setContent { DeveloperOptionsScreen(flags, onSpeed = { flags = it; changed += it }, onBack = {}) }
        compose.onNodeWithText("速度").assertIsDisplayed()
        for (t in listOf("先读元素，必要时看图", "操作后带回屏幕变化", "有把握时连做几步", "直接打开要去的页面", "简单步骤交给轻量模型")) {
            scrollTo(t)
            compose.onNodeWithText(t).assertIsDisplayed()
        }
        scrollTo("实验")
        compose.onNodeWithText("实验").assertIsDisplayed()
        scrollTo("照着上次的做法做")
        compose.onNodeWithText("照着上次的做法做").performClick()
        assertEquals(listOf(SpeedFlags(replay = true)), changed)
        scrollTo("不同对话同时进行")
        scrollTo("长任务的摘要分条写")
        compose.onNodeWithText("长任务的摘要分条写").assertIsDisplayed()
    }

    @Test
    fun deviceFilesExperimentalSwitchToggles() {
        val toggled = mutableListOf<Boolean>()
        var enabled by mutableStateOf(false)
        compose.setContent {
            DeveloperOptionsScreen(SpeedFlags.OFF, onSpeed = {}, onBack = {}, deviceFiles = enabled,
                onDeviceFiles = { enabled = it; toggled += it })
        }
        scrollTo("导入手机文件")
        compose.onNodeWithTag("dev-device-files").assertIsOff().performClick()
        compose.onNodeWithTag("dev-device-files").assertIsOn()
        compose.onNodeWithText("导入手机文件").performClick()
        compose.onNodeWithTag("dev-device-files").assertIsOff()
        assertEquals(listOf(true, false), toggled)
    }

    /** 「自主模式」在任务运行最前面,默认关;点开关或整行都切换。 */
    @Test
    fun autonomousSwitchComesFirstAndToggles() {
        val toggled = mutableListOf<Boolean>()
        var on by mutableStateOf(false)
        compose.setContent {
            DuringWorkScreen(
                keepScreenOn = true, onKeepScreenOn = {}, muteTarget = true, onMuteTarget = {},
                parallel = null, onParallelMax = {}, onBack = {}, autonomous = on, onAutonomous = { toggled += it; on = it },
            )
        }
        compose.onNodeWithText(AutonomyCopy.GROUP).assertIsDisplayed()
        compose.onNodeWithText(AutonomyCopy.TITLE).assertIsDisplayed()
        compose.onNodeWithTag("work-autonomous").assertIsOff().performClick()
        compose.onNodeWithTag("work-autonomous").assertIsOn()
        compose.onNodeWithText(AutonomyCopy.TITLE).performClick()
        compose.onNodeWithTag("work-autonomous").assertIsOff()
        assertEquals(listOf(true, false), toggled)
    }

    /** 「看图定位已验证」挪到开发者选项:有看图模型才出现,点了切换。 */
    @Test
    fun groundingSwitchShowsOnlyWithVisionModel() {
        val toggled = mutableListOf<Boolean>()
        var grounding by mutableStateOf<GroundingSetting?>(null)
        compose.setContent {
            DeveloperOptionsScreen(SpeedFlags.OFF, onSpeed = {}, onBack = {}, grounding = grounding, onGrounding = { toggled += it })
        }
        compose.onNodeWithText(GroundingCopy.TITLE).assertDoesNotExist()
        grounding = GroundingSetting("qwen-vl-max", verified = false)
        scrollTo(GroundingCopy.TITLE)
        compose.onNodeWithText(GroundingCopy.TITLE).performClick()
        assertEquals(listOf(true), toggled)
    }

    private class Calls { var reset = 0; val network = mutableListOf<Boolean>() }

    private fun advanced(status: SandboxStatus, network: Boolean = false, isolationIssue: Boolean = false): Calls {
        val c = Calls()
        compose.setContent {
            Column {
                AdvancedSection(
                    status = status, networkAllowed = network, isolationIssue = isolationIssue,
                    onNetworkChange = { c.network += it }, onReset = { c.reset++ },
                )
            }
        }
        return c
    }

    @Test
    fun unsupportedHidesAdvanced() {
        advanced(SandboxStatus.Unsupported("此设备不支持(需要 arm64)"))
        compose.onAllNodesWithText(SandboxCopy.NETWORK_TITLE).assertCountEquals(0)
        compose.onAllNodesWithText("重置运行环境").assertCountEquals(0)
    }

    @Test
    fun advancedHasNoInstallOrSandboxWording() {
        advanced(SandboxStatus.NotInstalled)
        compose.onNodeWithText(SandboxCopy.NETWORK_TITLE).assertIsDisplayed()
        compose.onNodeWithText("重置运行环境").assertIsDisplayed()
        for (t in listOf("沙箱", "安装沙箱", "软件源", "清空工作区")) compose.onAllNodesWithText(t, substring = true).assertCountEquals(0)
    }

    @Test
    fun resetAsksFirst() {
        val c = advanced(SandboxStatus.Ready(network = false))
        compose.onNodeWithText("重置运行环境").performClick()
        assertEquals(0, c.reset)
        compose.onNodeWithText(SandboxCopy.RESET_BODY).assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, c.reset)
        compose.onNodeWithText("重置运行环境").performClick()
        compose.onNodeWithText("重置").performClick()
        assertEquals(1, c.reset)
    }

    @Test
    fun isolationIssueShown() {
        advanced(SandboxStatus.Ready(network = true), network = true, isolationIssue = true)
        compose.onNodeWithText(SandboxCopy.NETWORK_EXPLAIN).assertIsDisplayed()
        compose.onNodeWithText(SandboxCopy.ISOLATION_UNAVAILABLE).assertIsDisplayed()
    }

    @Test
    fun networkToggleReportsChange() {
        val c = advanced(SandboxStatus.Ready(network = false))
        compose.onNodeWithTag("sandbox-network").performClick()
        assertTrue(c.network == listOf(true))
    }

    /** 已用空间:每个对话的工作区一行;有任务在跑的写明不能清、点不动;清理先确认,确认后才回调。 */
    @Test
    fun usageClearsAWorkspaceOnlyAfterConfirming() {
        val usage = com.wangzan101.mika.cap.sandbox.SandboxUsage(
            runtime = 300L shl 20, skills = 2L shl 20,
            workspaces = listOf(
                com.wangzan101.mika.cap.sandbox.WorkspaceUsage("c-a", "a", "查快递", 5L shl 20, busy = true),
                com.wangzan101.mika.cap.sandbox.WorkspaceUsage("c-b", "b", "整理表格", 1L shl 20, busy = false),
                com.wangzan101.mika.cap.sandbox.WorkspaceUsage("c-gone", null, null, 1024, busy = false),
            ),
        )
        val cleared = mutableListOf<String>()
        var all = 0
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AdvancedSection(
                    status = SandboxStatus.Ready(network = false), networkAllowed = false, isolationIssue = false,
                    onNetworkChange = {}, onReset = {}, usage = usage,
                    onClearWorkspace = { cleared += it.dirName }, onClearAll = { all++ },
                )
            }
        }
        compose.onNodeWithText(SandboxCopy.USAGE_TITLE).assertExists()
        compose.onNodeWithText("已删除的对话").assertExists()
        compose.onNodeWithText("这个对话有任务在跑，暂时不能清理").assertExists()
        compose.onNodeWithText("查快递").performScrollTo().performClick()
        compose.onAllNodesWithText(SandboxCopy.CLEAR_TITLE).assertCountEquals(0)
        compose.onNodeWithText("整理表格").performScrollTo().performClick()
        compose.onNodeWithText(SandboxCopy.CLEAR_TITLE).assertIsDisplayed()
        assertEquals(emptyList<String>(), cleared)
        compose.onNodeWithText(SandboxCopy.CLEAR).performClick()
        assertEquals(listOf("c-b"), cleared)
        compose.onNodeWithText(SandboxCopy.CLEAR_ALL).performScrollTo().performClick()
        compose.onNodeWithText(SandboxCopy.CLEAR_ALL_BODY).assertIsDisplayed()
        compose.onNodeWithText(SandboxCopy.CLEAR).performClick()
        assertEquals(1, all)
    }

    @Test
    fun phoneOpsPageShowsShizukuStatusTransportChipsAndGuide() {
        var statusState by mutableStateOf(
            ready.copy(
                shellTransport = ShellTransport.Auto,
                shizuku = ShizukuPlatformState(installed = false, running = false, permissionGranted = false),
                wirelessPaired = false,
            ),
        )
        var selectedTransport: ShellTransport? = null
        var grants = 0
        var guides = 0
        var refreshes = 0
        compose.setContent {
            PhoneOpsScreen(
                status = statusState,
                androidRelease = "14",
                onProblem = {},
                onRepair = {},
                onUnpair = {},
                onBack = {},
                onSelectTransport = { selectedTransport = it; statusState = statusState.copy(shellTransport = it) },
                onGrantShizuku = { grants++ },
                onOpenShizukuGuide = { guides++ },
                onRefreshShizuku = { refreshes++ },
            )
        }
        compose.onNodeWithTag("phone-shizuku-status").assertExists()
        compose.onNodeWithText("Not installed").assertExists()
        compose.onNodeWithTag("phone-shizuku-guide").performScrollTo().performClick()
        assertEquals(1, guides)

        compose.onNodeWithTag("phone-transport-Shizuku").performScrollTo().performClick()
        assertEquals(ShellTransport.Shizuku, selectedTransport)

        statusState = statusState.copy(
            shizuku = ShizukuPlatformState(installed = true, running = false, permissionGranted = false),
        )
        compose.onNodeWithText("Installed · Not running").assertExists()
        compose.onNodeWithTag("phone-shizuku-guide").performScrollTo().assertExists()

        statusState = statusState.copy(
            shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = false),
        )
        compose.onNodeWithText("Running · Permission needed").assertExists()
        compose.onNodeWithTag("phone-shizuku-guide").assertDoesNotExist()
        compose.onNodeWithTag("phone-shizuku-action").performScrollTo().performClick()
        assertEquals(1, grants)

        statusState = statusState.copy(
            shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = true),
        )
        compose.onNodeWithText("Running · Permission granted").assertExists()
        assertTrue(refreshes >= 1)
    }

    /** 手机操作:术语副标题、修复连接的目录说明删掉,系统版本用版本号替代「满足」,危险后果说明保留。 */
    @Test
    fun phoneOpsOmitsRedundantSubtitles() {
        compose.setContent { PhoneOpsScreen(ready, "14", onProblem = {}, onRepair = {}, onUnpair = {}, onBack = {}) }
        compose.onAllNodesWithText("读取和操作", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("换了手机设置", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("满足").assertCountEquals(0)
        compose.onNodeWithText("安卓 14").assertIsDisplayed()
    }

    /** Shizuku 就绪时诊断读盘隐藏,未就绪时仍出现。 */
    @Test
    fun phoneOpsShizukuReadyHidesDiagnostic() {
        var status by mutableStateOf(ready.copy(shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = true)))
        compose.setContent { PhoneOpsScreen(status, "14", onProblem = {}, onRepair = {}, onUnpair = {}, onBack = {}) }
        compose.onAllNodesWithText("已安装", substring = true).assertCountEquals(0)
        status = status.copy(shizuku = ShizukuPlatformState(installed = false, running = false, permissionGranted = false))
        compose.onAllNodesWithText("已安装", substring = true).assertCountEquals(1)
    }

    /** 任务运行:直白开关与费用说明的副标题删掉;4 个上限值仍显示(移到行尾)。 */
    @Test
    fun duringWorkOmitsRedundantSubtitles() {
        compose.setContent {
            DuringWorkScreen(true, {}, true, {}, parallel = null, onParallelMax = {}, onBack = {},
                stepLimit = null, triggerRunMinutes = null, sandboxBudgetMinutes = 30, tokenBudget = null)
        }
        for (t in listOf("屏幕不熄灭", "不出声", "按人民币估算", "在小窗里点")) {
            compose.onAllNodesWithText(t, substring = true).assertCountEquals(0)
        }
        // 4 个上限值仍显示:步数/定时/Token 是「不限制」,沙箱是「30 分钟」
        compose.onAllNodesWithText("不限制").assertCountEquals(3)
        compose.onNodeWithText("30 分钟").performScrollTo().assertIsDisplayed()
    }

    /** 通知:总开关与免打扰的复述说明删掉,频道触发时机保留。 */
    @Test
    fun notificationsOmitsRedundantSubtitles() {
        compose.setContent { NotificationsScreen(ready, onFix = {}, onBack = {}) }
        compose.onAllNodesWithText("允许 Mika 发通知").assertCountEquals(0)
        compose.onAllNodesWithText("静默显示在通知栏", substring = true).assertCountEquals(0)
        compose.onNodeWithText("需要你接管、确认、回答时").assertIsDisplayed()
    }

    /** 悬浮胶囊:两条滑杆说明删掉,三模式差异说明保留。 */
    @Test
    fun capsuleOmitsSliderDetails() {
        compose.setContent { CapsuleScreen(CapsuleMode.DuringTasks, onMode = {}, onBack = {}) }
        compose.onAllNodesWithText("几秒没碰后", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("整体缩放", substring = true).assertCountEquals(0)
        compose.onNodeWithText("有任务时显示").assertIsDisplayed()
    }

    /** 高级:网页搜索的目录说明删掉(行尾值仍显示)。 */
    @Test
    fun advancedOmitsWebSearchSubtitle() {
        compose.setContent { AdvancedSettingsContent(onBack = {}, webSearch = WebSearchConfig(), onOpenWebSearch = {}) }
        compose.onAllNodesWithText("可选搜索服务", substring = true).assertCountEquals(0)
        compose.onNodeWithText("网页搜索").assertExists()
    }
}

