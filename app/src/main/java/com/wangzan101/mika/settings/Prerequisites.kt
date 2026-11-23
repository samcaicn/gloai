package com.wangzan101.mika.settings

import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.core.device.shell.HelperRestart
import com.wangzan101.mika.core.device.shell.PairingChannelHealth
import com.wangzan101.mika.core.device.shell.ShellTransport
import com.wangzan101.mika.core.device.shell.ShizukuPlatformState

enum class AccessibilityState {
    Off,
    /** 系统设置里是开着的,服务却没在运行(App 被强行停止后常见),需要关掉再打开。 */
    EnabledNotRunning,
    On,
}

data class SystemStatus(
    val sdkInt: Int,
    /** 内置助手的状态。 */
    val builtinShell: BuiltinShellStatus,
    val accessibility: AccessibilityState,
    val notificationsAllowed: Boolean,
    /** 「需要你处理」通知频道被用户或系统调到了高优先级以下(不弹出、可能不响)。 */
    val attentionLowered: Boolean = false,
    /** 「任务结果」通知频道同理。 */
    val resultLowered: Boolean = false,
    /** 「配对」通知频道:配对码填在这里,被关掉或调成不弹出时引导页要说。 */
    val pairingChannel: PairingChannelHealth = PairingChannelHealth.Ready,
    /** 三个任务通知频道现在的重要程度;为空表示频道还没建。通知页据此显示。 */
    val attentionImportance: Int? = null,
    val resultImportance: Int? = null,
    val progressImportance: Int? = null,
    /** 系统「开发者选项」开着。 */
    val developerOptions: Boolean = false,
    /** 系统「无线调试」开着。 */
    val wirelessDebugging: Boolean = false,
    /** 系统里根本没有「无线调试」这一项(没带这个功能):引导页据此给「改用电脑启动」。 */
    val wirelessDebuggingMissing: Boolean = false,
    /** 系统里打不开「开发者选项」页:和 [wirelessDebuggingMissing] 一起成立时,这个环境设置不了手机操作。 */
    val developerSettingsMissing: Boolean = false,
    /** 系统「USB 调试」开着:开着时 Wi-Fi 断开助手也不停。 */
    val usbDebugging: Boolean = true,
    /** 用户打开了「用 root 启动手机助手」且此刻能用:看门狗经 su 起助手(不靠 Wi-Fi 和无线调试)。 */
    val rootStart: Boolean = false,
    /** 系统可能拦着不让打开无障碍(安卓 13 起,没从应用商店装的 App 要先允许受限制的设置)。 */
    val restrictedSettings: Boolean = false,
    /** 助手停了以后靠什么再起来:只用 USB 启动命令起过时,各处都不往无线调试引,说清要再运行命令。 */
    val helperRestart: HelperRestart = HelperRestart.None,
    /** 开机后用户还没解锁过一次([android.os.UserManager.isUserUnlocked] 为假):无障碍服务这时本来就绑不上。 */
    val userLocked: Boolean = false,
    /** 当前选择的助手启动通道。 */
    val shellTransport: ShellTransport = ShellTransport.Auto,
    /** Shizuku 的安装、运行和授权状态。 */
    val shizuku: ShizukuPlatformState = ShizukuPlatformState.Unavailable,
    /** 无线调试是否已配对;为空表示沿用 [builtinShell]。 */
    val wirelessPaired: Boolean? = null,
    /** 此刻是否优先经 Shizuku 启动助手。 */
    val shizukuStart: Boolean = false,
)

fun SystemStatus.notificationOverview() = NotificationOverview.of(
    notificationsAllowed, attentionImportance, resultImportance, progressImportance,
)

sealed interface Problem {
    /** 安卓 12 以下未启用 Mika 的手机操作能力。 */
    data object SystemTooOld : Problem
    /** 内置助手没在运行(没设置、无线调试关着、正在连、出错)。 */
    data class PhoneHelper(val status: BuiltinShellStatus) : Problem
    /** [userLocked] 为真时没在运行是因为开机后还没解锁过;[restrictedSettings] 为真时系统可能阻止绑定。 */
    data class Accessibility(val state: AccessibilityState, val userLocked: Boolean = false, val restrictedSettings: Boolean = false) : Problem
    data object NoModel : Problem
    /** 不挡任务,只是看不到进度和「需要你」提醒。 */
    data object NotificationsOff : Problem
    /** 「需要你处理」(接管、提问、确认)或「任务结果」的通知被调低了,不弹出,可能看不到;[attention]、[result] 说明是哪个。 */
    data class AttentionLowered(val attention: Boolean, val result: Boolean) : Problem
    /** 不挡任务:安卓 12、13 的虚拟屏没有独立焦点,后台操作可能让主屏的输入焦点短暂丢失。 */
    data object FocusMayLeak : Problem
}

/** 开始任务前的检查;界面据此提示,任务入口据此拦住。 */
object Prerequisites {
    /** 手机 App 操作的最低版本;聊天、网页和沙箱不需要这项能力。 */
    const val MIN_SDK = 31
    /** 安卓 14 起虚拟屏能有自己的焦点,不再影响主屏。 */
    const val OWN_FOCUS_SDK = 34
    private fun isWarning(p: Problem) = p == Problem.NotificationsOff || p == Problem.FocusMayLeak || p is Problem.AttentionLowered

    data class Check(val problems: List<Problem>) {
        /** 挡住任务的问题。手机操作的系统版本、内置助手、无障碍只在操作手机 App 时需要。 */
        val blockers: List<Problem> get() = problems.filter { !isWarning(it) && !phoneOnly(it) }
        val canStartTask: Boolean get() = blockers.isEmpty()
        /** 能操作手机 App。 */
        val phoneReady: Boolean get() = problems.none(::phoneOnly)
    }

    private fun phoneOnly(p: Problem) = p is Problem.SystemTooOld || p is Problem.PhoneHelper || p is Problem.Accessibility

    fun check(status: SystemStatus, hasMainModel: Boolean): Check = Check(
        buildList {
            if (status.sdkInt < MIN_SDK) add(Problem.SystemTooOld)
            // 安卓 12 以下手机功能不可用,已由 SystemTooOld 说明
            if (status.sdkInt >= MIN_SDK && status.builtinShell != BuiltinShellStatus.Running) {
                add(Problem.PhoneHelper(status.builtinShell))
            }
            if (status.sdkInt >= MIN_SDK && status.accessibility != AccessibilityState.On) {
                add(Problem.Accessibility(status.accessibility, status.userLocked, status.restrictedSettings))
            }
            if (!hasMainModel) add(Problem.NoModel)
            if (!status.notificationsAllowed) add(Problem.NotificationsOff)
            else if (status.attentionLowered || status.resultLowered) add(Problem.AttentionLowered(status.attentionLowered, status.resultLowered))
            if (status.sdkInt in MIN_SDK until OWN_FOCUS_SDK) add(Problem.FocusMayLeak)
        },
    )
}
