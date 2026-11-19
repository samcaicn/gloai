package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.shell.BuiltinShellFailure
import com.wangzan101.mika.core.shell.RootGrant
import com.wangzan101.mika.core.shell.BuiltinShellFix
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.HelperRestart
import com.wangzan101.mika.core.shell.SetupStep
import com.wangzan101.mika.core.shell.SetupSuspect
import com.wangzan101.mika.ui.components.ValueTone

/**
 * 「手机操作」引导、设置页和通知上的文字(纯函数,便于测试)。
 * 用大白话:只出现手机设置里本来就有的词(开发者选项、USB 调试、无线调试、配对码),不说 adb、端口、shell 这类说法;
 * 例外是连不上时请用户照着「无线调试」页面填端口的那几句。
 */
object PhoneSetupCopy {
    val TITLE: String get() = tr(R.string.phone_title)
    val INTRO: String get() = tr(R.string.phone_intro)
    val DIAGNOSTICS: String get() = tr(R.string.diagnostics_copy)
    val LATER: String get() = tr(R.string.phone_later)
    /** 引导页顶栏上的短标题。 */
    val BAR_TITLE: String get() = tr(R.string.phone_bar_title)
    val SKIP_NOTE: String get() = tr(R.string.phone_skip_note)
    /** 这个环境设置不了手机操作(见 [com.wangzan101.mika.core.shell.PhoneSetupFlow.unavailable]):一句结论,一句原因。 */
    val UNAVAILABLE: String get() = tr(R.string.phone_unavailable)
    val UNAVAILABLE_REASON: String get() = tr(R.string.phone_unavailable_reason)

    /** [brief] 是清单里没轮到、或已做完时那一行的短说明;轮到时显示完整的 [body]。 */
    data class StepCopy(val title: String, val body: String, val action: String?, val done: String, val brief: String)

    fun step(s: SetupStep): StepCopy = when (s) {
        SetupStep.DeveloperOptions -> StepCopy(
            tr(R.string.phone_step_dev),
            tr(R.string.phone_step_dev_body),
            tr(R.string.phone_step_dev_action), tr(R.string.phone_done_on), tr(R.string.phone_step_dev_brief),
        )
        SetupStep.UsbDebugging -> StepCopy(
            tr(R.string.phone_step_usb),
            tr(R.string.phone_step_usb_body),
            tr(R.string.phone_open_dev_options), tr(R.string.phone_done_on), tr(R.string.phone_step_usb_brief),
        )
        SetupStep.Accessibility -> StepCopy(
            tr(R.string.phone_step_a11y),
            tr(R.string.phone_step_a11y_body),
            tr(R.string.phone_step_a11y_action), tr(R.string.phone_done_on), tr(R.string.phone_step_a11y_brief),
        )
        SetupStep.WirelessDebugging -> StepCopy(
            tr(R.string.phone_step_wireless),
            tr(R.string.phone_step_wireless_body),
            tr(R.string.phone_open_dev_options), tr(R.string.phone_done_on), tr(R.string.phone_step_wireless_brief),
        )
        SetupStep.PairingCode -> StepCopy(
            tr(R.string.phone_step_pair),
            tr(R.string.phone_step_pair_body),
            tr(R.string.phone_step_pair_action), tr(R.string.phone_paired), tr(R.string.phone_step_pair_brief),
        )
        SetupStep.Connect -> StepCopy(
            tr(R.string.phone_step_connect),
            tr(R.string.phone_step_connect_body),
            null, tr(R.string.phone_connected), tr(R.string.phone_step_connect_brief),
        )
    }

    val ACCESSIBILITY_SKIP: String get() = tr(R.string.phone_a11y_skip)
    val ACCESSIBILITY_SKIPPED: String get() = tr(R.string.phone_a11y_skipped)
    /** 系统拦着不让打开无障碍时(没从应用商店装的 App 常见)的去处。 */
    val APP_INFO: String get() = tr(R.string.phone_app_info)

    /** 配对一步:无障碍开着时 Mika 自己读码。 */
    val AUTO_READ_HINT: String get() = tr(R.string.phone_auto_read)
    /** 读不到时的退路:填码通知一开始就静静放在通知栏里。 */
    val AUTO_READ_FALLBACK: String get() = tr(R.string.phone_auto_read_fallback, NOTIFY_ENTER_TITLE)
    /** 无障碍没开时的三步图示:点系统的配对按钮、看顶部弹出的通知、在通知里填码。 */
    val NOTIFY_GUIDE: List<String> get() = listOf(
        tr(R.string.phone_guide_1), tr(R.string.phone_guide_2), tr(R.string.phone_guide_3, NOTIFY_ACTION),
    )

    /** 读开关的几步的兜底:有的手机读不到或读不准,用户确认打开了就往下走。 */
    val TURNED_ON_NEXT: String get() = tr(R.string.phone_turned_on_next)
    /** 点回前面某一步重做时,不改了就回去。 */
    val REVISIT_BACK: String get() = tr(R.string.phone_revisit_back)
    val REVISIT_NOTE: String get() = tr(R.string.phone_revisit_note)

    /** 配对、连接没成功时,最可能要回去重做的那一步怎么说。 */
    fun suspect(s: SetupSuspect): String = when {
        s.step == SetupStep.DeveloperOptions -> tr(R.string.phone_suspect_dev)
        s.unreadable -> tr(R.string.phone_suspect_wireless_unreadable)
        else -> tr(R.string.phone_suspect_wireless_off)
    }

    fun goBackTo(s: SetupStep): String = tr(R.string.phone_suspect_go_back, step(s).title)
    val PORT_FOUND: String get() = tr(R.string.phone_port_found)
    val NEED_NOTIFICATIONS: String get() = tr(R.string.phone_need_notif)
    val ALLOW_NOTIFICATIONS: String get() = tr(R.string.explain_notif)
    /** 「配对」通知被关掉:配对码填不了,先打开它。 */
    val PAIRING_CHANNEL_OFF: String get() = tr(R.string.phone_pairing_channel_off, CHANNEL_NAME)
    val OPEN_PAIRING_CHANNEL: String get() = tr(R.string.phone_open_pairing_channel, CHANNEL_NAME)
    /** 「配对」通知被用户调成不弹出:还能下拉去填,只提醒一句并给入口。 */
    val PAIRING_LOWERED: String get() = tr(R.string.phone_pairing_lowered, CHANNEL_NAME)
    /** 通知允许了,但「需要你处理」「任务结果」被调成不弹出:不挡后面的步骤,但要说清楚。 */
    val HEADS_UP_LOWERED: String get() = tr(R.string.phone_heads_up_lowered)
    val OPEN_HEADS_UP: String get() = tr(R.string.heads_up_action)
    val MANUAL_TOGGLE: String get() = tr(R.string.phone_manual_toggle)
    val MANUAL_HINT: String get() = tr(R.string.phone_manual_hint)
    val CODE_LABEL: String get() = tr(R.string.phone_code_label)
    val PORT_LABEL: String get() = tr(R.string.phone_port_label)
    val PORT_HINT: String get() = tr(R.string.phone_port_hint)
    val SUBMIT: String get() = tr(R.string.phone_submit)
    val CODE_INVALID: String get() = tr(R.string.phone_code_invalid)
    val PORT_INVALID: String get() = tr(R.string.phone_port_invalid)

    val WORKING: String get() = tr(R.string.phone_working)
    /** 前面几种办法都没找到端口,正在扫本机。 */
    val SCANNING_PORTS: String get() = tr(R.string.phone_scanning_ports)
    /** 连接没成功时让用户自己填「无线调试」页面上的端口。 */
    val CONNECT_PORT_LABEL: String get() = tr(R.string.phone_connect_port_label)
    val CONNECT_PORT_HINT: String get() = tr(R.string.phone_connect_port_hint)
    val CONNECT_PORT_SUBMIT: String get() = tr(R.string.phone_connect_port_submit)
    val SUCCESS_LINE: String get() = tr(R.string.phone_success)
    /** 成功页底部的提醒:手机重启后怎么让助手回来,看它上次是怎么起的。 */
    fun successNote(restart: HelperRestart): String = when (restart) {
        HelperRestart.UsbCommand -> tr(R.string.phone_success_note_usb)
        HelperRestart.Shizuku -> tr(R.string.phone_success_note_shizuku)
        HelperRestart.Wireless, HelperRestart.None -> tr(R.string.phone_success_note)
    }
    /** 首次上手最后一步成功后的底部按钮;从设置进来的是 [SUCCESS_DONE]。 */
    val START_USING: String get() = tr(R.string.phone_start_using)
    val SUCCESS_DONE: String get() = tr(R.string.done)

    /** 另一条路:用电脑经 USB 数据线起助手(命令本身不翻译)。只在需要时出现;[USB_ASK] 是无线调试一步里让用户自己叫出它的那一行。 */
    val USB_ASK: String get() = tr(R.string.phone_usb_ask)
    val USB_TITLE: String get() = tr(R.string.phone_usb_title)
    val USB_BODY: String get() = tr(R.string.phone_usb_body)
    val USB_COPY: String get() = tr(R.string.phone_usb_copy)
    val USB_COPIED: String get() = tr(R.string.phone_usb_copied)

    /** 「改用电脑启动」卡片的标题和说明:助手本来就是用这条命令起的,就说再运行一次,不说「打不开无线调试」。 */
    fun usbCard(restart: HelperRestart): Pair<String, String> =
        if (restart == HelperRestart.UsbCommand) tr(R.string.phone_usb_again_title) to tr(R.string.phone_usb_again_body)
        else USB_TITLE to USB_BODY

    data class FailureCopy(val title: String, val body: String)

    fun failure(f: BuiltinShellFailure): FailureCopy = when (f) {
        BuiltinShellFailure.WrongCode -> FailureCopy(
            tr(R.string.fail_wrong_code),
            tr(R.string.fail_wrong_code_body),
        )
        BuiltinShellFailure.PairingWindowClosed -> FailureCopy(
            tr(R.string.fail_window_closed),
            tr(R.string.fail_window_closed_body),
        )
        BuiltinShellFailure.NoWifi -> FailureCopy(
            tr(R.string.fail_no_wifi),
            tr(R.string.fail_no_wifi_body),
        )
        BuiltinShellFailure.WifiNotAllowed -> FailureCopy(
            tr(R.string.fail_wifi_not_allowed),
            tr(R.string.fail_wifi_not_allowed_body),
        )
        BuiltinShellFailure.ConnectFailed -> FailureCopy(
            tr(R.string.fail_connect),
            tr(R.string.fail_connect_body),
        )
        BuiltinShellFailure.NotLocal -> FailureCopy(
            tr(R.string.fail_not_local),
            tr(R.string.fail_not_local_body),
        )
        BuiltinShellFailure.AuthorizationRevoked -> FailureCopy(
            tr(R.string.fail_revoked),
            tr(R.string.fail_revoked_body),
        )
        BuiltinShellFailure.KeyUnreadable -> FailureCopy(
            tr(R.string.fail_key_unreadable),
            tr(R.string.fail_key_unreadable_body),
        )
        BuiltinShellFailure.HelperStartFailed -> FailureCopy(
            tr(R.string.fail_helper),
            tr(R.string.fail_helper_body),
        )
        BuiltinShellFailure.UsbHelperStopped -> FailureCopy(
            tr(R.string.fail_usb_stopped),
            tr(R.string.fail_usb_stopped_body),
        )
        BuiltinShellFailure.RootDenied -> FailureCopy(
            tr(R.string.fail_root_denied),
            tr(R.string.fail_root_denied_body),
        )
        BuiltinShellFailure.RootNoAnswer -> FailureCopy(
            tr(R.string.fail_root_no_answer),
            tr(R.string.fail_root_no_answer_body),
        )
        BuiltinShellFailure.RootUnavailable -> FailureCopy(
            tr(R.string.fail_root_gone),
            tr(R.string.fail_root_gone_body),
        )
        BuiltinShellFailure.RootNoShellUid -> FailureCopy(
            tr(R.string.fail_root_no_shell),
            tr(R.string.fail_root_no_shell_body),
        )
        BuiltinShellFailure.ShizukuNotInstalled -> FailureCopy(
            tr(R.string.fail_shizuku_not_installed),
            tr(R.string.fail_shizuku_not_installed_body),
        )
        BuiltinShellFailure.ShizukuNotRunning -> FailureCopy(
            tr(R.string.fail_shizuku_not_running),
            tr(R.string.fail_shizuku_not_running_body),
        )
        BuiltinShellFailure.ShizukuPermissionNeeded -> FailureCopy(
            tr(R.string.fail_shizuku_permission),
            tr(R.string.fail_shizuku_permission_body),
        )
        BuiltinShellFailure.ShizukuBindFailed -> FailureCopy(
            tr(R.string.fail_shizuku_bind_failed),
            tr(R.string.fail_shizuku_bind_failed_body),
        )
        BuiltinShellFailure.Unknown -> FailureCopy(
            tr(R.string.fail_unknown),
            tr(R.string.fail_unknown_body),
        )
    }

    fun fixLabel(fix: BuiltinShellFix): String = when (fix) {
        BuiltinShellFix.Retry -> tr(R.string.retry)
        BuiltinShellFix.RePair -> tr(R.string.phone_repair)
        BuiltinShellFix.OpenWifi -> tr(R.string.phone_open_wifi)
        BuiltinShellFix.OpenWirelessDebugging -> tr(R.string.phone_open_wireless)
        BuiltinShellFix.RunUsbCommand -> USB_COPY
    }

    // ---------- 设置页 ----------

    val RECONNECT: String get() = tr(R.string.phone_reconnect)

    /** 已配对、只是没连上或助手掉了:点一下用已配对的钥匙再连一次就行,不必回引导页。 */
    fun canReconnect(s: BuiltinShellStatus): Boolean =
        s is BuiltinShellStatus.Failed &&
            s.fix == BuiltinShellFix.Retry &&
            s.reason != BuiltinShellFailure.ShizukuNotInstalled &&
            s.reason != BuiltinShellFailure.ShizukuPermissionNeeded

    val ROW_TITLE: String get() = tr(R.string.settings_phone_ops)
    val ROW_SUBTITLE: String get() = tr(R.string.phone_row_sub)

    /** 设置 › 手机操作页底部的完整说明:只用 USB 启动命令起过助手时,说重启后要再运行命令,不说打开无线调试。 */
    fun usageNote(restart: HelperRestart): String = when (restart) {
        HelperRestart.UsbCommand -> tr(R.string.phone_usage_note_usb)
        HelperRestart.Shizuku -> tr(R.string.phone_usage_note_shizuku)
        HelperRestart.Wireless, HelperRestart.None -> tr(R.string.phone_usage_note)
    }

    /** 上面那条说明的摘要,常驻一行;全文收进「详情」。 */
    fun usageSummary(restart: HelperRestart): String =
        if (restart == HelperRestart.UsbCommand) tr(R.string.phone_usage_summary_usb) else tr(R.string.phone_usage_summary)

    fun statusValue(s: BuiltinShellStatus): Pair<String, ValueTone> = when (s) {
        BuiltinShellStatus.Running -> tr(R.string.sandbox_ready) to ValueTone.Ok
        BuiltinShellStatus.NotPaired -> tr(R.string.settings_not_set) to ValueTone.Bad
        BuiltinShellStatus.WirelessDebuggingOff -> tr(R.string.phone_wireless_off) to ValueTone.Bad
        BuiltinShellStatus.Connecting -> tr(R.string.phone_connecting) to ValueTone.Plain
        is BuiltinShellStatus.Failed -> tr(R.string.phone_error) to ValueTone.Bad
    }

    /**
     * 「高级」里状态那一行的说明。[usbDebugging] 关着时,在运行也提醒一句:断了 Wi-Fi 就会停(经 root 或 Shizuku 起的不靠 Wi-Fi,不提醒)。
     * [rootStart]:看门狗经 su 起助手(用户打开了「用 root 启动」且能用),正在连接时说正在用 root 启动。
     * [shizukuStart]:此刻优先经 Shizuku 起助手。
     */
    fun statusLine(
        s: BuiltinShellStatus,
        usbDebugging: Boolean = true,
        rootStart: Boolean = false,
        shizukuStart: Boolean = false,
    ): String = when (s) {
        BuiltinShellStatus.Running -> when {
            shizukuStart -> SHIZUKU_RUNNING_LINE
            usbDebugging || rootStart -> tr(R.string.phone_line_running)
            else -> tr(R.string.phone_line_running_no_usb)
        }
        BuiltinShellStatus.NotPaired -> tr(R.string.phone_line_not_paired)
        BuiltinShellStatus.WirelessDebuggingOff -> tr(R.string.phone_line_wireless_off)
        BuiltinShellStatus.Connecting -> when {
            shizukuStart -> SHIZUKU_STARTING_LINE
            rootStart -> ROOT_STARTING_LINE
            else -> tr(R.string.phone_connecting)
        }
        is BuiltinShellStatus.Failed -> when (s.reason) {
            BuiltinShellFailure.NoWifi -> NO_WIFI_LINE
            BuiltinShellFailure.WifiNotAllowed -> tr(R.string.phone_line_joined, failure(s.reason).title, WIFI_NOT_ALLOWED_LINE)
            // 停了要用户动手:直接说怎么再起
            BuiltinShellFailure.UsbHelperStopped, in ROOT_FAILURES, in SHIZUKU_FAILURES -> failure(s.reason).body
            else -> failure(s.reason).title
        }
    }

    val NO_WIFI_LINE: String get() = tr(R.string.phone_no_wifi_line)
    val ROOT_STARTING_LINE: String get() = tr(R.string.phone_root_line_starting)
    val SHIZUKU_STARTING_LINE: String get() = tr(R.string.phone_shizuku_line_starting)
    val SHIZUKU_RUNNING_LINE: String get() = tr(R.string.phone_shizuku_line_running)

    /** 「用 root 启动」用不了的几种原因:状态行、横幅都直接说为什么、怎么办。 */
    val ROOT_FAILURES = setOf(
        BuiltinShellFailure.RootDenied, BuiltinShellFailure.RootNoAnswer, BuiltinShellFailure.RootUnavailable, BuiltinShellFailure.RootNoShellUid,
    )

    val SHIZUKU_FAILURES = setOf(
        BuiltinShellFailure.ShizukuNotInstalled,
        BuiltinShellFailure.ShizukuNotRunning,
        BuiltinShellFailure.ShizukuPermissionNeeded,
        BuiltinShellFailure.ShizukuBindFailed,
    )

    // ---------- root ----------

    val ROOT_GROUP: String get() = tr(R.string.phone_root_group)
    val ROOT_STATUS_TITLE: String get() = tr(R.string.phone_root_status)
    val ROOT_AVAILABLE: String get() = tr(R.string.phone_root_available)
    val ROOT_AUTO: String get() = tr(R.string.phone_root_auto)
    val ROOT_START: String get() = tr(R.string.phone_root_start)
    val ROOT_COMMANDS_GROUP: String get() = tr(R.string.phone_root_commands_group)
    val ROOT_COMMANDS: String get() = tr(R.string.phone_root_commands)
    val ROOT_COMMANDS_TITLE: String get() = tr(R.string.phone_root_commands_title)
    val ROOT_COMMANDS_WARNING: String get() = tr(R.string.phone_root_commands_warning)
    val ROOT_COMMANDS_CONFIRM: String get() = tr(R.string.phone_root_commands_confirm)
    val ROOT_NOTE: String get() = tr(R.string.phone_root_note)
    val ROOT_COMMANDS_NOTE: String get() = tr(R.string.phone_root_commands_note)
    val ROOT_PENDING: String get() = tr(R.string.phone_root_pending)

    /** 高级与其他里的 Root 入口:助手自动启动是一项能力,命令开关状态单独说明。 */
    fun rootEntryLine(commands: Boolean): String =
        tr(if (commands) R.string.phone_root_entry_on else R.string.phone_root_entry_off)

    /** Root 状态卡只说能力和助手授权;失败后的重试动作放在下面可点的助手行,不在这里写「点这里」。 */
    fun rootStatusLine(grant: RootGrant): String =
        tr(if (grant == RootGrant.Granted) R.string.phone_root_authorized else R.string.phone_root_unauthorized)

    /** 自动启动那一行:未授权时说明点这里授权,授权后说明自动恢复,失败时说明如何重试。 */
    fun rootStartLine(grant: RootGrant): String = when (grant) {
        RootGrant.Pending -> ROOT_PENDING
        RootGrant.Denied -> tr(R.string.phone_root_denied_sub)
        RootGrant.NoAnswer -> tr(R.string.phone_root_no_answer_sub)
        RootGrant.NoShellUid -> tr(R.string.phone_root_no_shell_sub)
        RootGrant.Unknown -> tr(R.string.phone_root_authorize)
        RootGrant.Granted -> tr(R.string.phone_root_start_sub)
    }

    /** 「允许 Mika 执行 root 命令」那一行下面的说明。 */
    fun rootCommandsLine(on: Boolean, grant: RootGrant): String = when {
        grant == RootGrant.Pending -> ROOT_PENDING
        !on && (grant == RootGrant.Denied || grant == RootGrant.NoAnswer) -> tr(R.string.phone_root_commands_denied)
        on -> tr(R.string.phone_root_commands_on)
        else -> tr(R.string.phone_root_commands_sub)
    }
    val WIFI_NOT_ALLOWED_LINE: String get() = tr(R.string.phone_wifi_not_allowed_line)

    val REPAIR: String get() = tr(R.string.phone_repair)
    val REPAIR_SUBTITLE: String get() = tr(R.string.phone_repair_sub)
    val UNPAIR: String get() = tr(R.string.phone_unpair)
    val UNPAIR_SUBTITLE: String get() = tr(R.string.phone_unpair_sub)
    val UNPAIR_TITLE: String get() = tr(R.string.phone_unpair_title)
    val UNPAIR_BODY: String get() = tr(R.string.phone_unpair_body)
    val UNPAIR_CONFIRM: String get() = tr(R.string.phone_unpair_confirm)

    // ---------- 对话页(手机操作不可用时) ----------

    val DEGRADED_LINE: String get() = tr(R.string.phone_degraded)
    val DEGRADED_ACTION: String get() = tr(R.string.chat_go_settings)

    // ---------- 通知 ----------

    val CHANNEL_NAME: String get() = tr(R.string.phone_channel)
    val NOTIFY_ENTER_TITLE: String get() = tr(R.string.phone_notify_enter_title)
    val NOTIFY_ENTER_TEXT: String get() = tr(R.string.phone_notify_enter_text)
    val NOTIFY_WINDOW_FOUND: String get() = tr(R.string.phone_notify_window_found)
    /** 填的不是 6 位数字:当场在通知里说,不去试。 */
    val NOTIFY_CODE_INVALID: String get() = tr(R.string.phone_notify_code_invalid)

    /** 配对阶段没成功时通知里怎么说:码不对、过期、窗口关了都要去拿新码再填。 */
    fun notifyRetry(f: BuiltinShellFailure): String = when (f) {
        BuiltinShellFailure.WrongCode -> tr(R.string.phone_notify_retry_code)
        BuiltinShellFailure.PairingWindowClosed -> tr(R.string.phone_notify_retry_window)
        else -> failure(f).title
    }
    val NOTIFY_ACTION: String get() = tr(R.string.phone_notify_action)
    val NOTIFY_INPUT_LABEL: String get() = tr(R.string.phone_notify_input)
    val NOTIFY_WORKING: String get() = tr(R.string.phone_notify_working)
    val NOTIFY_DONE_TITLE: String get() = tr(R.string.phone_notify_done_title)
    val NOTIFY_DONE_TEXT: String get() = tr(R.string.phone_notify_done_text)
    val NOTIFY_PROBLEM_TEXT: String get() = tr(R.string.phone_notify_problem)
}
