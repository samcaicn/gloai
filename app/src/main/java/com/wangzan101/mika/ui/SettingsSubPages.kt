package com.wangzan101.mika.ui

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.R
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.ThemeAccent
import com.wangzan101.mika.settings.ThemeMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import com.wangzan101.mika.ui.theme.accentTone
import com.wangzan101.mika.ui.theme.SkinImage
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.settings.SkinBlur
import com.wangzan101.mika.settings.SkinSpec
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.wangzan101.mika.ui.components.MikaSwitch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.wangzan101.mika.core.shell.ShellTransport
import com.wangzan101.mika.ui.components.SelectChip
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.wangzan101.mika.i18n.AppLanguage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.capsule.CapsuleCardScale
import com.wangzan101.mika.capsule.CapsuleIdleOpacity
import com.wangzan101.mika.capsule.CapsuleMode
import com.wangzan101.mika.capsule.CapsuleTileService
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.settings.ChannelRow
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.Problem
import com.wangzan101.mika.settings.ScheduledQuietHours
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.settings.notificationOverview
import com.wangzan101.mika.task.ParallelLimit
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.Icon
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.DetailNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.core.cap.phone.OperatingPace
import com.wangzan101.mika.core.usage.TokenBudgetLimits
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// ---------- 手机操作 ----------

/** 设置 › 手机操作:权限与配对的状态,重新配对、解除配对。Root 能力集中在高级与其他的独立页面。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneOpsScreen(
    status: SystemStatus,
    androidRelease: String,
    onProblem: (Problem) -> Unit,
    onRepair: () -> Unit,
    onUnpair: () -> Unit,
    onBack: () -> Unit,
    onSelectTransport: (ShellTransport) -> Unit = {},
    onGrantShizuku: () -> Unit = {},
    onOpenShizukuGuide: () -> Unit = {},
    onRefreshShizuku: () -> Unit = {},
) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onRefreshShizuku() }
    val check = Prerequisites.check(status, hasMainModel = true)
    var confirmUnpair by remember { mutableStateOf(false) }
    PageScaffold(stringResource(R.string.settings_phone_ops), onBack) {
        GroupLabel(stringResource(R.string.settings_status))
        GroupCard {
            val (a11yText, a11yTone) = StatusCopy.accessibilityValue(status.accessibility)
            ListRow(
                MikaIcons.Hand, stringResource(R.string.settings_a11y), stringResource(R.string.settings_a11y_sub), first = true,
                onClick = check.problems.firstOrNull { it is Problem.Accessibility }?.let { { onProblem(it) } },
            ) { RowValue(a11yText, a11yTone) }
            // 安卓 12 以下手机功能不可用,不列这一行(系统版本那一行已说明)
            if (status.sdkInt >= Prerequisites.MIN_SDK) {
                val helper = status.builtinShell
                val (hText, hTone) = PhoneSetupCopy.statusValue(helper)
                val reconnect = PhoneSetupCopy.canReconnect(helper)
                ListRow(
                    MikaIcons.Link, PhoneSetupCopy.ROW_TITLE,
                    if (reconnect && helper is BuiltinShellStatus.Failed) PhoneSetupCopy.failure(helper.reason).title
                    else PhoneSetupCopy.statusLine(helper, status.usbDebugging, status.rootStart, status.shizukuStart),
                    onClick = { onProblem(Problem.PhoneHelper(helper)) },
                ) { if (reconnect) RowValue(PhoneSetupCopy.RECONNECT, ValueTone.Link) else RowValue(hText, hTone) }
                val shizuku = status.shizuku
                val shizukuValue = stringResource(
                    when {
                        !shizuku.installed -> R.string.phone_shizuku_not_installed
                        !shizuku.running -> R.string.phone_shizuku_not_running
                        !shizuku.permissionGranted -> R.string.phone_shizuku_perm_needed
                        else -> R.string.phone_shizuku_ready
                    }
                )
                val shizukuTone = when {
                    shizuku.ready -> ValueTone.Ok
                    !shizuku.permissionGranted && shizuku.running -> ValueTone.Bad
                    status.shellTransport == ShellTransport.Shizuku -> ValueTone.Bad
                    else -> ValueTone.Plain
                }
                val yes = stringResource(R.string.phone_shizuku_yes)
                val no = stringResource(R.string.phone_shizuku_no)
                val shizukuDetail = stringResource(
                    R.string.phone_shizuku_detail,
                    if (shizuku.installed) yes else no,
                    if (shizuku.running) yes else no,
                    if (shizuku.permissionGranted) stringResource(R.string.phone_shizuku_perm_granted)
                    else stringResource(R.string.phone_shizuku_perm_missing),
                )
                ListRow(
                    MikaIcons.Shield,
                    stringResource(R.string.phone_shizuku_group),
                    shizukuDetail,
                    onClick = if (shizuku.installed || shizuku.running) onGrantShizuku else onOpenShizukuGuide,
                    modifier = Modifier.testTag("phone-shizuku-status"),
                ) { RowValue(shizukuValue, shizukuTone) }
            }
            val tooOld = status.sdkInt < Prerequisites.MIN_SDK
            val focusWarning = Problem.FocusMayLeak in check.problems
            ListRow(
                MikaIcons.Phone, stringResource(R.string.settings_system_version),
                if (focusWarning) stringResource(R.string.settings_android_focus, androidRelease) else stringResource(R.string.settings_android, androidRelease),
                onClick = when {
                    tooOld -> ({ onProblem(Problem.SystemTooOld) })
                    focusWarning -> ({ onProblem(Problem.FocusMayLeak) })
                    else -> null
                },
            ) {
                when {
                    tooOld -> RowValue(stringResource(R.string.pill_unsupported), ValueTone.Bad)
                    focusWarning -> RowValue(stringResource(R.string.pill_available), ValueTone.Plain)
                    else -> RowValue(stringResource(R.string.settings_meets), ValueTone.Ok)
                }
            }
        }
        if (status.sdkInt >= Prerequisites.MIN_SDK) {
            GroupLabel(stringResource(R.string.phone_transport_title))
            GroupCard {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val options = listOf(
                            ShellTransport.Auto to stringResource(R.string.phone_transport_auto),
                            ShellTransport.WirelessDebugging to stringResource(R.string.phone_transport_wireless),
                            ShellTransport.Shizuku to stringResource(R.string.phone_transport_shizuku),
                        )
                        options.forEach { (transport, label) ->
                            SelectChip(
                                label,
                                status.shellTransport == transport,
                                { onSelectTransport(transport) },
                                Modifier.testTag("phone-transport-${transport.name}"),
                            )
                        }
                    }
                }
            }
            FooterNote(stringResource(R.string.phone_transport_sub))

            val shizuku = status.shizuku
            GroupLabel(stringResource(R.string.phone_shizuku_group))
            GroupCard {
                val actionTitle = stringResource(
                    if (shizuku.running && !shizuku.permissionGranted) R.string.phone_shizuku_grant
                    else R.string.phone_shizuku_open_app
                )
                val actionSub = stringResource(
                    when {
                        !shizuku.installed -> R.string.phone_shizuku_action_install_sub
                        !shizuku.running -> R.string.phone_shizuku_action_start_sub
                        !shizuku.permissionGranted -> R.string.phone_shizuku_action_grant_sub
                        else -> R.string.phone_shizuku_action_ready_sub
                    }
                )
                ListRow(
                    MikaIcons.Shield,
                    actionTitle,
                    actionSub,
                    first = true,
                    onClick = if (shizuku.installed || shizuku.running) onGrantShizuku else onOpenShizukuGuide,
                    modifier = Modifier.testTag("phone-shizuku-action"),
                ) { RowChevron() }
                if (!shizuku.installed || !shizuku.running) {
                    ListRow(
                        MikaIcons.Book,
                        stringResource(R.string.phone_shizuku_guide),
                        stringResource(R.string.phone_shizuku_guide_sub),
                        onClick = onOpenShizukuGuide,
                        modifier = Modifier.testTag("phone-shizuku-guide"),
                    ) { RowChevron() }
                }
            }
            FooterNote(stringResource(R.string.phone_shizuku_footer))

            GroupLabel(stringResource(R.string.phone_channel))
            GroupCard {
                ListRow(MikaIcons.Link, PhoneSetupCopy.REPAIR, PhoneSetupCopy.REPAIR_SUBTITLE, first = true, onClick = onRepair) {
                    RowChevron()
                }
                val paired = status.wirelessPaired ?: (status.builtinShell != BuiltinShellStatus.NotPaired)
                ListRow(
                    MikaIcons.Close, PhoneSetupCopy.UNPAIR, PhoneSetupCopy.UNPAIR_SUBTITLE,
                    titleColor = if (paired) MikaTheme.colors.bad else MikaTheme.colors.ink3,
                    tone = if (paired) TileTone.Bad else TileTone.Neutral,
                    onClick = if (paired) ({ confirmUnpair = true }) else null,
                )
            }
            DetailNote(
                PhoneSetupCopy.usageSummary(status.helperRestart),
                PhoneSetupCopy.usageNote(status.helperRestart),
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }
    if (confirmUnpair) {
        MikaDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text(PhoneSetupCopy.UNPAIR_TITLE) },
            text = { Text(PhoneSetupCopy.UNPAIR_BODY) },
            confirmButton = {
                TextButton({ confirmUnpair = false; onUnpair() }) { Text(PhoneSetupCopy.UNPAIR_CONFIRM, color = MikaTheme.colors.bad) }
            },
            dismissButton = { TextButton({ confirmUnpair = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ---------- 通知 ----------

/**
 * 设置 › 通知:显示系统通知频道状态，并设置定时任务的免打扰时段。
 */
@Composable
fun NotificationsScreen(status: SystemStatus, onFix: (Fix) -> Unit, onBack: () -> Unit) {
    val o = status.notificationOverview()
    val context = LocalContext.current
    val flags = remember(context) { FeatureFlags.get(context) }
    var quietHours by remember(flags) { mutableStateOf(flags.scheduledQuietHours()) }
    fun updateQuietHours(hours: ScheduledQuietHours) {
        flags.setScheduledQuietHours(hours)
        quietHours = hours
    }
    PageScaffold(StatusCopy.NOTIFICATIONS, onBack) {
        GroupCard {
            ListRow(
                MikaIcons.Bell, stringResource(R.string.settings_notif_master),
                if (o.appAllowed) StatusCopy.NOTIF_APP_SUBTITLE_ON else StatusCopy.NOTIF_APP_SUBTITLE_OFF,
                first = true, tone = if (o.appAllowed) TileTone.Neutral else TileTone.Bad,
                onClick = if (o.appAllowed) null else ({ onFix(Fix.AllowNotifications) }),
            ) {
                if (o.appAllowed) RowValue(stringResource(R.string.settings_allowed), ValueTone.Ok) else RowValue(StatusCopy.HEADS_UP_ACTION, ValueTone.Link)
            }
        }
        GroupLabel(stringResource(R.string.settings_task_notifs))
        GroupCard {
            ChannelRowItem(StatusCopy.ATTENTION_CHANNEL, StatusCopy.NOTIF_ATTENTION_WHEN, o.attention, Fix.OpenAttentionChannel, onFix, first = true)
            ChannelRowItem(StatusCopy.RESULT_CHANNEL, StatusCopy.NOTIF_RESULT_WHEN, o.result, Fix.OpenResultChannel, onFix)
            ChannelRowItem(StatusCopy.PROGRESS_CHANNEL, StatusCopy.NOTIF_PROGRESS_WHEN, o.progress, Fix.OpenProgressChannel, onFix)
            if (status.sdkInt >= 36) {
                ListRow(MikaIcons.Bell, stringResource(R.string.settings_live_task_updates),
                    stringResource(R.string.settings_live_task_updates_detail), onClick = { onFix(Fix.OpenLiveTaskSettings) }) {
                    RowChevron()
                }
            }
        }
        FooterNote(StatusCopy.NOTIF_FOOTER)
        GroupCard {
            ListRow(
                MikaIcons.Mute, stringResource(R.string.settings_scheduled_quiet),
                stringResource(R.string.settings_scheduled_quiet_detail), first = true,
                onClick = { updateQuietHours(quietHours.copy(enabled = !quietHours.enabled)) },
            ) {
                MikaSwitch(quietHours.enabled, { updateQuietHours(quietHours.copy(enabled = it)) },
                    Modifier.testTag("scheduled-quiet-switch"))
            }
            if (quietHours.enabled) {
                QuietHoursTimeRow(R.string.settings_scheduled_quiet_start, quietHours.start) {
                    updateQuietHours(quietHours.copy(start = it))
                }
                QuietHoursTimeRow(R.string.settings_scheduled_quiet_end, quietHours.end) {
                    updateQuietHours(quietHours.copy(end = it))
                }
            }
        }
        if (quietHours.enabled && quietHours.start == quietHours.end) {
            FooterNote(stringResource(R.string.settings_scheduled_quiet_equal))
        }
    }
}

@Composable
private fun QuietHoursTimeRow(label: Int, time: LocalTime, onChange: (LocalTime) -> Unit) {
    val context = LocalContext.current
    val title = stringResource(label)
    val use24HourClock = DateFormat.is24HourFormat(context)
    val locale = context.resources.configuration.locales[0]
    val pattern = DateFormat.getBestDateTimePattern(locale, if (use24HourClock) "Hm" else "hm")
    ListRow(MikaIcons.Clock, title, onClick = {
        TimePickerDialog(context, { _, hour, minute -> onChange(LocalTime.of(hour, minute)) },
            time.hour, time.minute, use24HourClock).apply {
            setTitle(title)
            show()
        }
    }) {
        RowValue(DateTimeFormatter.ofPattern(pattern, locale).format(time))
        RowChevron()
    }
}

@Composable
private fun ChannelRowItem(title: String, whenText: String, row: ChannelRow, fix: Fix, onFix: (Fix) -> Unit, first: Boolean = false) {
    val (text, tone) = StatusCopy.channelLevelValue(row)
    ListRow(
        MikaIcons.Bell, title, whenText, first = first,
        tone = if (row.warn) TileTone.Warn else TileTone.Neutral,
        onClick = { onFix(fix) },
    ) {
        RowValue(text, tone)
        RowChevron()
    }
}

// ---------- 任务运行 ----------

/** 并行任务的设置:用户设的上限 [max](1–3),和设备此刻允许的 [limit]。 */
data class ParallelSetting(val max: Int, val limit: ParallelLimit)

/**
 * 设置 › 任务运行:自主模式默认关;屏幕常亮、被操作的 App 静音默认开,可以关;[parallel] 不为空(实验开关打开了)时多一行同时进行的任务数。
 * 下面一组是操作节奏:快 / 标准(默认) / 像人,选了下一个任务起生效。
 */
@Composable
fun DuringWorkScreen(
    keepScreenOn: Boolean, onKeepScreenOn: (Boolean) -> Unit,
    muteTarget: Boolean, onMuteTarget: (Boolean) -> Unit,
    parallel: ParallelSetting?, onParallelMax: (Int) -> Unit, onBack: () -> Unit,
    pocketGuard: Boolean = false, onPocketGuard: (Boolean) -> Unit = {},
    pocketCompletionLock: Boolean = false, onPocketCompletionLock: (Boolean) -> Unit = {},
    autonomous: Boolean = false, onAutonomous: (Boolean) -> Unit = {},
    operatingPace: OperatingPace = OperatingPace.DEFAULT, onOperatingPace: (OperatingPace) -> Unit = {},
    onOpenAppPace: () -> Unit = {},
    selfConfig: Boolean = false, onSelfConfig: (Boolean) -> Unit = {},
    selfOperate: Boolean = false, onSelfOperate: (Boolean) -> Unit = {},
    pipOperate: Boolean = false, onPipOperate: (Boolean) -> Unit = {},
    externalSchedule: Boolean = false, onExternalSchedule: (Boolean) -> Unit = {},
    stepLimit: Int? = null, onStepLimit: (Int?) -> Unit = {},
    triggerRunMinutes: Int? = null, onTriggerRunMinutes: (Int?) -> Unit = {},
    sandboxBudgetMinutes: Int = 30, onSandboxBudgetMinutes: (Int) -> Unit = {},
    tokenBudget: Long? = null, onTokenBudget: (Long?) -> Unit = {},
    showTaskCost: Boolean = false, onShowTaskCost: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    var editingLimit by remember { mutableStateOf(false) }
    var editingTriggerLimit by remember { mutableStateOf(false) }
    var editingSandboxBudget by remember { mutableStateOf(false) }
    var customLimit by remember { mutableStateOf("") }
    var editingTokenBudget by remember { mutableStateOf(false) }
    var customTokenBudget by remember { mutableStateOf("") }
    PageScaffold(stringResource(R.string.settings_during_work), onBack) {
        GroupLabel(AutonomyCopy.GROUP)
        GroupCard {
            ListRow(null, AutonomyCopy.TITLE, AutonomyCopy.SUBTITLE, first = true, onClick = { onAutonomous(!autonomous) }) {
                MikaSwitch(autonomous, onAutonomous, Modifier.testTag("work-autonomous"))
            }
        }
        GroupCard {
            ListRow(MikaIcons.Sun, stringResource(R.string.settings_keep_on), stringResource(R.string.settings_keep_on_sub), first = true, onClick = { onKeepScreenOn(!keepScreenOn) }) {
                MikaSwitch(keepScreenOn, onKeepScreenOn, Modifier.testTag("work-keep-screen-on"))
            }
            val pocketAvailable = remember { com.wangzan101.mika.pocket.PocketGuardManager.sensorsAvailable(context) }
            ListRow(MikaIcons.Shield, stringResource(R.string.pocket_guard_title), stringResource(if (pocketAvailable) R.string.pocket_guard_summary else R.string.pocket_guard_unavailable), onClick = { if (pocketAvailable || pocketGuard) onPocketGuard(!pocketGuard) }) {
                MikaSwitch(pocketGuard, onPocketGuard, Modifier.testTag("work-pocket-guard"), enabled = pocketAvailable || pocketGuard)
            }
            ListRow(MikaIcons.Lock, stringResource(R.string.pocket_completion_lock_title), stringResource(if (pocketAvailable) R.string.pocket_completion_lock_summary else R.string.pocket_guard_unavailable), onClick = { if (pocketAvailable || pocketCompletionLock) onPocketCompletionLock(!pocketCompletionLock) }) {
                MikaSwitch(pocketCompletionLock, onPocketCompletionLock, Modifier.testTag("work-pocket-completion-lock"), enabled = pocketAvailable || pocketCompletionLock)
            }
            ListRow(MikaIcons.Mute, stringResource(R.string.settings_mute), stringResource(R.string.settings_mute_sub), onClick = { onMuteTarget(!muteTarget) }) {
                MikaSwitch(muteTarget, onMuteTarget, Modifier.testTag("work-mute-target"))
            }
            ListRow(
                MikaIcons.Clock, stringResource(R.string.settings_step_limit),
                stepLimit?.let { trPlural(R.plurals.chat_steps_n, it) }
                    ?: stringResource(R.string.settings_step_limit_off),
                // 当前是自己填的数时,输入框里带着它
                onClick = { customLimit = stepLimit?.takeIf { it !in STEP_LIMIT_PRESETS }?.toString().orEmpty(); editingLimit = true },
            )
            ListRow(
                MikaIcons.Clock, stringResource(R.string.settings_trigger_run_limit),
                triggerRunMinutes?.let { trPlural(R.plurals.settings_trigger_run_minutes_n, it) }
                    ?: stringResource(R.string.settings_step_limit_off),
                onClick = { editingTriggerLimit = true },
            )
            ListRow(
                MikaIcons.Clock, stringResource(R.string.settings_sandbox_budget),
                trPlural(R.plurals.settings_sandbox_budget_minutes_n, sandboxBudgetMinutes),
                onClick = { editingSandboxBudget = true },
            )
            ListRow(
                MikaIcons.Clock, stringResource(R.string.settings_task_token_budget),
                tokenBudget?.let(::tokenBudgetNumber) ?: stringResource(R.string.settings_token_budget_unlimited),
                onClick = {
                    customTokenBudget = tokenBudget?.takeIf { it !in TokenBudgetLimits.PRESETS }?.toString().orEmpty()
                    editingTokenBudget = true
                },
            )
            ListRow(
                null, stringResource(R.string.settings_show_task_cost), stringResource(R.string.settings_show_task_cost_sub),
                onClick = { onShowTaskCost(!showTaskCost) },
            ) {
                MikaSwitch(showTaskCost, onShowTaskCost, Modifier.testTag("work-show-task-cost"))
            }
            parallel?.let { ParallelRow(it, onParallelMax) }
        }
        if (editingLimit) StepLimitDialog(
            current = stepLimit,
            custom = customLimit,
            onCustom = { customLimit = it.filter(Char::isDigit).take(3) },
            onPick = { onStepLimit(it); editingLimit = false },
            onDismiss = { editingLimit = false },
        )
        if (editingTriggerLimit) TriggerRunLimitDialog(
            current = triggerRunMinutes,
            onPick = { onTriggerRunMinutes(it); editingTriggerLimit = false },
            onDismiss = { editingTriggerLimit = false },
        )
        if (editingSandboxBudget) SandboxBudgetDialog(
            current = sandboxBudgetMinutes,
            onPick = { onSandboxBudgetMinutes(it); editingSandboxBudget = false },
            onDismiss = { editingSandboxBudget = false },
        )
        if (editingTokenBudget) TokenBudgetDialog(
            title = stringResource(R.string.settings_task_token_budget),
            description = stringResource(R.string.settings_task_token_budget_sub),
            current = tokenBudget,
            custom = customTokenBudget,
            onCustom = { customTokenBudget = it.filter(Char::isDigit).take(9) },
            onPick = { onTokenBudget(it); editingTokenBudget = false },
            onDismiss = { editingTokenBudget = false },
        )
        // 允许 Mika 改自己的设置:默认关,打开后任务里才有 settings_list、settings_set
        GroupCard {
            ListRow(
                MikaIcons.Spark, stringResource(R.string.settings_self_config),
                stringResource(R.string.settings_self_config_sub),
                first = true,
                onClick = { onSelfConfig(!selfConfig) },
            ) {
                MikaSwitch(selfConfig, onSelfConfig, Modifier.testTag("work-self-config"))
            }
        }
        // 允许 Mika 操作自己的界面:默认关;打开后任务里才有 mika_open_screen/open_file/notify/copy
        GroupCard {
            ListRow(
                MikaIcons.Screens, stringResource(R.string.settings_self_operate),
                stringResource(R.string.settings_self_operate_sub),
                first = true,
                onClick = { onSelfOperate(!selfOperate) },
            ) {
                MikaSwitch(selfOperate, onSelfOperate, Modifier.testTag("work-self-operate"))
            }
        }
        // 允许你在对话里的小窗直接操作虚拟屏(点/滑/返回);默认关,开着时展开小窗就能直接点
        GroupCard {
            ListRow(
                MikaIcons.Back, stringResource(R.string.settings_pip_operate),
                stringResource(R.string.settings_pip_operate_sub),
                first = true,
                onClick = { onPipOperate(!pipOperate) },
            ) {
                MikaSwitch(pipOperate, onPipOperate, Modifier.testTag("work-pip-operate"))
            }
        }
        // 允许外部脚本经广播建定时任务;默认关,开着时仍受每分钟限流并标记来源
        GroupCard {
            ListRow(
                MikaIcons.Code, stringResource(R.string.settings_external_schedule),
                stringResource(R.string.settings_external_schedule_sub),
                first = true,
                onClick = { onExternalSchedule(!externalSchedule) },
            ) {
                MikaSwitch(externalSchedule, onExternalSchedule, Modifier.testTag("work-external-schedule"))
            }
        }
        GroupLabel(stringResource(R.string.settings_pace))
        GroupCard {
            OperatingPace.entries.forEachIndexed { i, p ->
                RadioRow(paceLabel(p), selected = p == operatingPace, onClick = { onOperatingPace(p) }, subtitle = paceDetail(p), first = i == 0)
            }
            // 按应用覆盖:单个 App 单独设快慢,没设的跟着上面选的全局档
            ListRow(
                null, stringResource(R.string.settings_pace_per_app), stringResource(R.string.settings_pace_per_app_sub),
                onClick = onOpenAppPace,
            )
        }
        FooterNote(stringResource(R.string.settings_pace_foot))
    }
}

/** 步数上限的几个现成选项;别的数在输入框里填。 */
private val STEP_LIMIT_PRESETS = listOf(30, 60, 120, 240)

/** 定时任务最长运行时间:不限制或几档分钟数,选了就生效。 */
@Composable
private fun TriggerRunLimitDialog(current: Int?, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_trigger_run_limit)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_trigger_run_limit_sub), Modifier.padding(bottom = 8.dp))
                RadioRow(stringResource(R.string.settings_step_limit_off), selected = current == null, onClick = { onPick(null) }, first = true)
                FeatureFlags.TRIGGER_RUN_MINUTES.forEach { m ->
                    RadioRow(trPlural(R.plurals.settings_trigger_run_minutes_n, m), selected = current == m, onClick = { onPick(m) })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 每任务沙箱命令累计时长:只认几档,默认 30 分钟,没有「不限制」。 */
@Composable
private fun SandboxBudgetDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_sandbox_budget)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_sandbox_budget_sub), Modifier.padding(bottom = 8.dp))
                FeatureFlags.SANDBOX_BUDGET_MINUTES.forEachIndexed { i, m ->
                    RadioRow(trPlural(R.plurals.settings_sandbox_budget_minutes_n, m), selected = current == m, onClick = { onPick(m) }, first = i == 0)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun StepLimitDialog(
    current: Int?,
    custom: String,
    onCustom: (String) -> Unit,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val customValue = custom.toIntOrNull()?.takeIf { it in 1..FeatureFlags.MAX_TASK_STEP_LIMIT }
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_step_limit)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_step_limit_sub), Modifier.padding(bottom = 8.dp))
                RadioRow(stringResource(R.string.settings_step_limit_off), selected = current == null, onClick = { onPick(null) }, first = true)
                STEP_LIMIT_PRESETS.forEach { n ->
                    RadioRow(trPlural(R.plurals.chat_steps_n, n), selected = current == n, onClick = { onPick(n) })
                }
                OutlinedTextField(
                    value = custom, onValueChange = onCustom, singleLine = true,
                    label = { Text(stringResource(R.string.settings_step_limit_custom)) },
                    isError = custom.isNotEmpty() && customValue == null,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (custom.isNotEmpty() && customValue == null) {
                    Text(stringResource(R.string.settings_step_limit_invalid), Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { customValue?.let(onPick) }, enabled = customValue != null) {
                Text(stringResource(R.string.settings_step_limit_custom_apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun paceLabel(p: OperatingPace): String = stringResource(
    when (p) {
        OperatingPace.FAST -> R.string.settings_pace_fast
        OperatingPace.STANDARD -> R.string.settings_pace_standard
        OperatingPace.HUMAN -> R.string.settings_pace_human
    },
)

@Composable
private fun paceDetail(p: OperatingPace): String = stringResource(
    when (p) {
        OperatingPace.FAST -> R.string.settings_pace_fast_detail
        OperatingPace.STANDARD -> R.string.settings_pace_standard_detail
        OperatingPace.HUMAN -> R.string.settings_pace_human_detail
    },
)

/** 同时进行几个任务:设备不支持时灰掉、写明原因,上限固定为 1。 */
@Composable
private fun ParallelRow(p: ParallelSetting, onMax: (Int) -> Unit) {
    val supported = p.limit.reason == null
    ListRow(
        MikaIcons.Phone, stringResource(R.string.settings_parallel),
        subtitle = p.limit.reason ?: stringResource(R.string.settings_parallel_sub),
        titleColor = if (supported) MikaTheme.colors.ink else MikaTheme.colors.ink3,
    ) {
        if (!supported) {
            RowValue(trPlural(R.plurals.settings_count_tasks, 1))
            return@ListRow
        }
        Text(
            "−", Modifier.clickable(enabled = p.max > 1) { onMax(p.max - 1) }.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MikaType.Title, color = if (p.max > 1) MikaTheme.colors.accent else MikaTheme.colors.ink3,
        )
        RowValue(trPlural(R.plurals.settings_count_tasks, p.max))
        Text(
            "+", Modifier.clickable(enabled = p.max < 3) { onMax(p.max + 1) }.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MikaType.Title, color = if (p.max < 3) MikaTheme.colors.accent else MikaTheme.colors.ink3,
        )
    }
}

// ---------- 悬浮胶囊 ----------

/** 设置 › 悬浮胶囊的文字。 */
object CapsuleSettingsCopy {
    val TITLE: String get() = tr(R.string.capsule_settings_title)
    val SUBTITLE: String get() = tr(R.string.capsule_settings_sub)
    val FOOTNOTE: String get() = tr(R.string.capsule_settings_foot)
    /** 系统可能关掉后台的 App,胶囊跟着不见:请用户在系统设置里放行。 */
    val BACKGROUND: String get() = tr(R.string.capsule_settings_background)
    val IDLE_OPACITY: String get() = tr(R.string.capsule_idle_opacity)
    val IDLE_OPACITY_DETAIL: String get() = tr(R.string.capsule_idle_opacity_detail)
    val CARD_SIZE: String get() = tr(R.string.capsule_card_size)
    val CARD_SIZE_DETAIL: String get() = tr(R.string.capsule_card_size_detail)

    fun scaleLabel(scale: CapsuleCardScale): String = when (scale) {
        CapsuleCardScale.Small -> tr(R.string.capsule_card_size_small)
        CapsuleCardScale.Normal -> tr(R.string.capsule_card_size_normal)
        CapsuleCardScale.Large -> tr(R.string.capsule_card_size_large)
        CapsuleCardScale.XLarge -> tr(R.string.capsule_card_size_xlarge)
    }

    fun label(mode: CapsuleMode): String = when (mode) {
        CapsuleMode.Off -> tr(R.string.capsule_mode_off)
        CapsuleMode.DuringTasks -> tr(R.string.capsule_mode_tasks)
        CapsuleMode.Always -> tr(R.string.capsule_mode_always)
    }

    fun detail(mode: CapsuleMode): String = when (mode) {
        CapsuleMode.Off -> tr(R.string.capsule_mode_off_detail)
        CapsuleMode.DuringTasks -> tr(R.string.capsule_mode_tasks_detail)
        CapsuleMode.Always -> tr(R.string.capsule_mode_always_detail)
    }
}

/** 设置 › 悬浮胶囊:关闭 / 有任务时显示(默认) / 一直显示,选了当场生效;静置不透明度与展开卡片的大小。 */
@Composable
fun CapsuleScreen(
    mode: CapsuleMode,
    onMode: (CapsuleMode) -> Unit,
    onBack: () -> Unit,
    idleOpacity: CapsuleIdleOpacity = CapsuleIdleOpacity.DEFAULT,
    onIdleOpacity: (CapsuleIdleOpacity) -> Unit = {},
    cardScale: CapsuleCardScale = CapsuleCardScale.DEFAULT,
    onCardScale: (CapsuleCardScale) -> Unit = {},
) {
    val context = LocalContext.current
    val flags = remember { FeatureFlags.get(context) }
    val hideAtEdge by remember(flags) { flags.capsuleHideAtEdgeChanges() }.collectAsState(flags.capsuleHideAtEdge())
    val hideFullscreen by remember(flags) { flags.capsuleHideFullscreenChanges() }.collectAsState(flags.capsuleHideFullscreen())
    var quickSettings by remember { mutableStateOf(CapsuleTileService.isAvailable(context)) }
    fun setQuickSettings(on: Boolean) {
        CapsuleTileService.setAvailable(context, on)
        quickSettings = CapsuleTileService.isAvailable(context)
    }
    PageScaffold(CapsuleSettingsCopy.TITLE, onBack) {
        GroupCard {
            CapsuleMode.entries.forEachIndexed { i, m ->
                RadioRow(
                    CapsuleSettingsCopy.label(m), selected = m == mode, onClick = { onMode(m) },
                    subtitle = CapsuleSettingsCopy.detail(m), first = i == 0,
                )
            }
        }
        GroupLabel(CapsuleSettingsCopy.IDLE_OPACITY)
        GroupCard {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                ListRow(
                    MikaIcons.Photo, CapsuleSettingsCopy.IDLE_OPACITY, CapsuleSettingsCopy.IDLE_OPACITY_DETAIL,
                    first = true, titleColor = if (mode == CapsuleMode.Off) MikaTheme.colors.ink3 else androidx.compose.ui.graphics.Color.Unspecified,
                ) { RowValue("${idleOpacity.percent}%") }
                Slider(
                    value = CapsuleIdleOpacity.entries.indexOf(idleOpacity).toFloat(),
                    onValueChange = {
                        val index = it.roundToInt().coerceIn(CapsuleIdleOpacity.entries.indices)
                        onIdleOpacity(CapsuleIdleOpacity.entries[index])
                    },
                    valueRange = 0f..3f,
                    steps = 2,
                    enabled = mode != CapsuleMode.Off,
                    modifier = Modifier.testTag("capsule-idle-opacity"),
                )
            }
        }
        GroupLabel(CapsuleSettingsCopy.CARD_SIZE)
        GroupCard {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                ListRow(
                    MikaIcons.Photo, CapsuleSettingsCopy.CARD_SIZE, CapsuleSettingsCopy.CARD_SIZE_DETAIL,
                    first = true, titleColor = if (mode == CapsuleMode.Off) MikaTheme.colors.ink3 else androidx.compose.ui.graphics.Color.Unspecified,
                ) { RowValue(CapsuleSettingsCopy.scaleLabel(cardScale)) }
                Slider(
                    value = CapsuleCardScale.entries.indexOf(cardScale).toFloat(),
                    onValueChange = {
                        val index = it.roundToInt().coerceIn(CapsuleCardScale.entries.indices)
                        onCardScale(CapsuleCardScale.entries[index])
                    },
                    valueRange = 0f..3f,
                    steps = 2,
                    enabled = mode != CapsuleMode.Off,
                    modifier = Modifier.testTag("capsule-card-size"),
                )
            }
        }
        GroupCard {
            ListRow(MikaIcons.EyeOff, stringResource(R.string.capsule_hide_at_edge), first = true,
                onClick = { flags.setCapsuleHideAtEdge(!hideAtEdge) }) {
                MikaSwitch(hideAtEdge, flags::setCapsuleHideAtEdge, Modifier.testTag("capsule-hide-at-edge"))
            }
            ListRow(MikaIcons.Screens, stringResource(R.string.capsule_hide_fullscreen),
                onClick = { flags.setCapsuleHideFullscreen(!hideFullscreen) }) {
                MikaSwitch(hideFullscreen, flags::setCapsuleHideFullscreen, Modifier.testTag("capsule-hide-fullscreen"))
            }
            ListRow(MikaIcons.Apps, stringResource(R.string.capsule_tile_enabled),
                onClick = { setQuickSettings(!quickSettings) }) {
                MikaSwitch(quickSettings, ::setQuickSettings, Modifier.testTag("capsule-quick-settings"))
            }
        }
        FooterNote(CapsuleSettingsCopy.FOOTNOTE)
        FooterNote(CapsuleSettingsCopy.BACKGROUND)
    }
}

/** 设置 › 语言:跟随系统、简体中文、繁體中文、English;选了马上换,界面跟着重建。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LanguageScreen(language: AppLanguage, onLanguage: (AppLanguage) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val flags = remember { FeatureFlags.get(context) }
    // 跟着设置走:任务里改了外观,这页也对得上
    val themeMode by remember(flags) { flags.themeModeChanges() }.collectAsState(flags.themeMode())
    val themeAccent by remember(flags) { flags.themeAccentChanges() }.collectAsState(flags.themeAccent())
    val skin by remember(flags) { flags.skinChanges() }.collectAsState(flags.skin())
    val scope = rememberCoroutineScope()
    val pickSkin = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            if (!SkinImage.import(context, uri)) Toast.makeText(context, R.string.skin_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val focusManager = LocalFocusManager.current
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    PageScaffold(stringResource(R.string.appearance_title), onBack, modifier = Modifier.imePadding()) {
        // 语言和外观放在同一页:都是「界面长什么样」
        GroupLabel(stringResource(R.string.appearance_group))
        GroupCard {
            ThemeMode.entries.forEachIndexed { idx, m ->
                RadioRow(
                    appearanceLabel(m), selected = m == themeMode, first = idx == 0,
                    subtitle = if (m == ThemeMode.System) stringResource(R.string.appearance_system_detail) else null,
                    onClick = {
                        // 界面跟着设置当场换颜色,不重建,页面和滚动位置都不丢
                        if (m != themeMode) flags.setThemeMode(m)
                    },
                )
            }
        }
        GroupLabel(stringResource(R.string.accent_group))
        GroupCard {
            val dark = MikaTheme.colors.isDark
            // 背景图片的颜色在当强调色时,这里选的暂时不用,行变灰
            val followingImage = skin.accentHue != null
            ThemeAccent.entries.forEachIndexed { idx, a ->
                RadioRow(
                    accentLabel(a), selected = a == themeAccent, first = idx == 0, enabled = !followingImage,
                    subtitle = if (a == ThemeAccent.Blue) stringResource(R.string.accent_default) else null,
                    onClick = { if (a != themeAccent) flags.setThemeAccent(a) },
                ) {
                    // 色块按此刻的深浅色画,和选上以后看到的一样
                    Box(Modifier.size(18.dp).background(accentTone(a, dark).first, CircleShape))
                }
            }
        }
        GroupLabel(stringResource(R.string.skin_group))
        GroupCard {
            ListRow(
                null, stringResource(if (skin.active) R.string.skin_change else R.string.skin_pick), first = true,
                onClick = { pickSkin.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
            if (skin.active) SkinControls(skin, flags::setSkin, onClear = { SkinImage.clear(context) })
        }
        GroupLabel(stringResource(R.string.language_title))
        var langQuery by remember { mutableStateOf("") }
        var langSearchFocused by remember { mutableStateOf(false) }
        val c = MikaTheme.colors
        val filteredLanguages = remember(langQuery) { filterLanguages(langQuery) }

        LaunchedEffect(langSearchFocused, langQuery) {
            if (langSearchFocused || langQuery.isNotEmpty()) {
                bringIntoViewRequester.bringIntoView()
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .height(36.dp)
                .clip(MikaShapes.Pill)
                .background(c.fill)
                .then(if (langSearchFocused) Modifier.border(1.dp, c.accent, MikaShapes.Pill) else Modifier)
                .padding(horizontal = 10.dp)
                .bringIntoViewRequester(bringIntoViewRequester),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(MikaIcons.Search, null, Modifier.size(16.dp), tint = c.ink3)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (langQuery.isEmpty()) {
                    Text(stringResource(R.string.language_search_hint), style = MikaType.Caption, color = c.ink3, maxLines = 1)
                }
                BasicTextField(
                    value = langQuery,
                    onValueChange = { langQuery = it },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { langSearchFocused = it.isFocused },
                    singleLine = true,
                    textStyle = MikaType.Caption.copy(color = c.ink),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                )
            }
            if (langQuery.isNotEmpty()) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape).clickable {
                        langQuery = ""
                        focusManager.clearFocus()
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MikaIcons.Close, null, Modifier.size(12.dp), tint = c.ink3)
                }
            }
        }
        GroupCard(Modifier.animateContentSize()) {
            if (filteredLanguages.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.file_library_empty),
                        style = MikaType.Caption,
                        color = c.ink3,
                    )
                }
            } else {
                filteredLanguages.forEachIndexed { i, l ->
                    RadioRow(
                        languageLabel(l), selected = l == language, onClick = {
                            if (l != language) onLanguage(l)
                            focusManager.clearFocus()
                        },
                        first = i == 0,
                    )
                }
            }
        }
    }
}

/** 语言的名字:每种语言总用它自己的写法(日本語、Español…),「跟随系统」按当前语言写。 */
@Composable
fun languageLabel(l: AppLanguage): String = stringResource(
    when (l) {
        AppLanguage.System -> R.string.language_system
        AppLanguage.SimplifiedChinese -> R.string.language_simplified_chinese
        AppLanguage.TraditionalChinese -> R.string.language_traditional_chinese
        AppLanguage.Arabic -> R.string.language_arabic
        AppLanguage.Persian -> R.string.language_persian
        AppLanguage.English -> R.string.language_english
        AppLanguage.Japanese -> R.string.language_japanese
        AppLanguage.Korean -> R.string.language_korean
        AppLanguage.Spanish -> R.string.language_spanish
        AppLanguage.Portuguese -> R.string.language_portuguese
        AppLanguage.Russian -> R.string.language_russian
        AppLanguage.Indonesian -> R.string.language_indonesian
        AppLanguage.Turkish -> R.string.language_turkish
    },
)

// ---------- 隐私与数据 ----------

/** 设置 › 网站登录。 */
@Composable
fun WebSitesScreen(state: WebSitesState, onBack: () -> Unit) {
    PageScaffold(stringResource(R.string.settings_web_logins), onBack) { WebSitesSection(state, labeled = false) }
}

// ---------- 高级 ----------

/** 设置 › 文件与计算环境:允许脚本联网、网络代理、已用空间(按对话清理工作区)、重置运行环境。 */
@Composable
fun SandboxScreen(onBack: () -> Unit) {
    PageScaffold(stringResource(R.string.settings_sandbox), onBack) { AdvancedSettings() }
}

/** 设置 › 记住的操作路线:[count] 条,可以全部清除(先确认);[count] 为空表示还没读出来。 */
@Composable
fun RoutesScreen(count: Int?, onClear: () -> Unit, onBack: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    PageScaffold(stringResource(R.string.settings_routes), onBack) {
        GroupCard {
            ListRow(
                MikaIcons.Route, stringResource(R.string.routes_saved),
                if (count == null) stringResource(R.string.loading) else trPlural(R.plurals.routes_count, count), first = true,
            ) {
                if (count != null && count > 0) Text(
                    stringResource(R.string.routes_clear_all), Modifier.clickable { confirmClear = true }.padding(4.dp),
                    style = MikaType.Value, color = MikaTheme.colors.bad,
                )
            }
        }
    }
    if (confirmClear) {
        MikaDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.routes_clear_title)) },
            text = { Text(stringResource(R.string.routes_clear_body)) },
            confirmButton = { TextButton({ confirmClear = false; onClear() }) { Text(stringResource(R.string.clear)) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** 设置 › 恢复与修复:手机操作的几个授权开关,下面是兼容性自检的入口。 */
@Composable
fun RecoveryScreen(onBack: () -> Unit, onOpenSelfCheck: () -> Unit = {}) {
    PageScaffold(stringResource(R.string.settings_recovery), onBack) {
        RecoverySwitches()
        GroupCard {
            ListRow(
                MikaIcons.Bug, stringResource(R.string.selfcheck_title), stringResource(R.string.selfcheck_row_sub), first = true,
                onClick = onOpenSelfCheck,
            ) { RowChevron() }
        }
    }
}

/** 外观的当前说法(设置页和这一行都用)。 */
fun appearanceLabel(mode: ThemeMode): String = tr(
    when (mode) {
        ThemeMode.System -> R.string.appearance_system
        ThemeMode.Light -> R.string.appearance_light
        ThemeMode.Dark -> R.string.appearance_dark
    },
)

/**
 * 背景图片的调节:遮罩浓度、图片位置、模糊、强调色跟不跟图片、换回纯色。
 * 页面底下就是这张图,拖的时候当场看到效果;值变了才写设置(整数百分比、位置按 5% 一档),不是每一帧都写。
 */
@Composable
private fun SkinControls(skin: SkinSpec, onChange: (SkinSpec) -> Unit, onClear: () -> Unit) {
    val scrimLabel = stringResource(R.string.skin_scrim)
    ListRow(null, scrimLabel) { RowValue("${skin.scrim}%") }
    Slider(
        value = skin.scrim.toFloat(),
        onValueChange = { v -> v.roundToInt().let { if (it != skin.scrim) onChange(skin.copy(scrim = it)) } },
        valueRange = SkinSpec.MIN_SCRIM.toFloat()..SkinSpec.MAX_SCRIM.toFloat(),
        modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = scrimLabel }.testTag("skin-scrim"),
    )
    val positionLabel = stringResource(R.string.skin_position)
    ListRow(null, positionLabel)
    Slider(
        value = skin.focus,
        onValueChange = { v -> ((v * 20).roundToInt() / 20f).let { if (it != skin.focus) onChange(skin.copy(focus = it)) } },
        valueRange = -1f..1f,
        modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = positionLabel }.testTag("skin-position"),
    )
    ListRow(null, stringResource(R.string.skin_blur))
    SegmentedControl(
        listOf(stringResource(R.string.skin_blur_off), stringResource(R.string.skin_blur_light), stringResource(R.string.skin_blur_strong)),
        selected = skin.blur.ordinal,
        onSelect = { onChange(skin.copy(blur = SkinBlur.entries[it])) },
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    )
    ListRow(
        null, stringResource(R.string.skin_accent_follow), stringResource(R.string.skin_accent_follow_sub),
        onClick = { onChange(skin.copy(accentFromImage = !skin.accentFromImage)) },
    ) {
        MikaSwitch(skin.accentFromImage, { onChange(skin.copy(accentFromImage = it)) }, Modifier.testTag("skin-accent-follow"))
    }
    ListRow(null, stringResource(R.string.skin_clear), onClick = onClear)
}

fun accentLabel(accent: ThemeAccent): String = tr(
    when (accent) {
        ThemeAccent.Blue -> R.string.accent_blue
        ThemeAccent.Indigo -> R.string.accent_indigo
        ThemeAccent.Purple -> R.string.accent_purple
        ThemeAccent.Pink -> R.string.accent_pink
        ThemeAccent.Teal -> R.string.accent_teal
        ThemeAccent.Graphite -> R.string.accent_graphite
    },
)
