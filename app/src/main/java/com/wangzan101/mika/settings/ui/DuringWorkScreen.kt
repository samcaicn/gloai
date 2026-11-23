package com.wangzan101.mika.settings.ui

import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.wangzan101.mika.settings.DeviceAccessSettings
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.ui.components.MikaSwitch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.core.cap.phone.OperatingPace
import com.wangzan101.mika.core.model.usage.TokenBudgetLimits
import com.wangzan101.mika.device.ui.DeviceAccessSection

/**
 * 设置 › 任务运行:自主模式默认关;屏幕常亮、被操作的 App 静音默认开,可以关;[parallel] 不为空(实验开关打开了)时多一行同时进行的任务数。
 * 下面一组是操作节奏:快 / 标准(默认) / 像人,选了下一个任务起生效。
 * [deviceAccess] 非空时挂「锁屏时运行本机任务」一组:与「连接电脑」的远程开关互不影响,凭据编辑区共用。
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
    attachmentMaxMb: Int = 200, onAttachmentMaxMb: (Int) -> Unit = {},
    workspaceMaxGb: Int = 4, onWorkspaceMaxGb: (Int) -> Unit = {},
    tokenBudget: Long? = null, onTokenBudget: (Long?) -> Unit = {},
    showTaskCost: Boolean = false, onShowTaskCost: (Boolean) -> Unit = {},
    deviceAccess: DeviceAccessSettings? = null,
) {
    val context = LocalContext.current
    var editingLimit by remember { mutableStateOf(false) }
    var editingTriggerLimit by remember { mutableStateOf(false) }
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
            ListRow(MikaIcons.Sun, stringResource(R.string.settings_keep_on), first = true, onClick = { onKeepScreenOn(!keepScreenOn) }) {
                MikaSwitch(keepScreenOn, onKeepScreenOn, Modifier.testTag("work-keep-screen-on"))
            }
            val pocketAvailable = remember { com.wangzan101.mika.pocket.PocketGuardManager.sensorsAvailable(context) }
            ListRow(MikaIcons.Shield, stringResource(R.string.pocket_guard_title), stringResource(if (pocketAvailable) R.string.pocket_guard_summary else R.string.pocket_guard_unavailable), onClick = { if (pocketAvailable || pocketGuard) onPocketGuard(!pocketGuard) }) {
                MikaSwitch(pocketGuard, onPocketGuard, Modifier.testTag("work-pocket-guard"), enabled = pocketAvailable || pocketGuard)
            }
            ListRow(MikaIcons.Lock, stringResource(R.string.pocket_completion_lock_title), stringResource(if (pocketAvailable) R.string.pocket_completion_lock_summary else R.string.pocket_guard_unavailable), onClick = { if (pocketAvailable || pocketCompletionLock) onPocketCompletionLock(!pocketCompletionLock) }) {
                MikaSwitch(pocketCompletionLock, onPocketCompletionLock, Modifier.testTag("work-pocket-completion-lock"), enabled = pocketAvailable || pocketCompletionLock)
            }
            ListRow(MikaIcons.Mute, stringResource(R.string.settings_mute), onClick = { onMuteTarget(!muteTarget) }) {
                MikaSwitch(muteTarget, onMuteTarget, Modifier.testTag("work-mute-target"))
            }
            ListRow(
                MikaIcons.Clock, stringResource(R.string.settings_step_limit),
                onClick = { customLimit = stepLimit?.takeIf { it !in STEP_LIMIT_PRESETS }?.toString().orEmpty(); editingLimit = true },
            ) {
                RowValue(stepLimit?.let { trPlural(R.plurals.chat_steps_n, it) } ?: stringResource(R.string.settings_step_limit_off))
                RowChevron()
            }
            ListRow(
                MikaIcons.Clock, stringResource(R.string.settings_trigger_run_limit),
                onClick = { editingTriggerLimit = true },
            ) {
                RowValue(triggerRunMinutes?.let { trPlural(R.plurals.settings_trigger_run_minutes_n, it) } ?: stringResource(R.string.settings_step_limit_off))
                RowChevron()
            }
            TierSettingRow(R.string.settings_sandbox_budget, R.string.settings_sandbox_budget_sub, FeatureFlags.SANDBOX_BUDGET_MINUTES,
                R.plurals.settings_sandbox_budget_minutes_n, sandboxBudgetMinutes, onSandboxBudgetMinutes)
            TierSettingRow(R.string.settings_attachment_file_limit, R.string.settings_attachment_file_limit_sub, FeatureFlags.ATTACHMENT_FILE_LIMIT_MB,
                R.plurals.settings_attachment_file_limit_mb_n, attachmentMaxMb, onAttachmentMaxMb)
            TierSettingRow(R.string.settings_workspace_size_limit, R.string.settings_workspace_size_limit_sub, FeatureFlags.WORKSPACE_LIMIT_GB,
                R.plurals.settings_workspace_size_limit_gb_n, workspaceMaxGb, onWorkspaceMaxGb)
            ListRow(
                MikaIcons.Clock, stringResource(R.string.settings_task_token_budget),
                onClick = {
                    customTokenBudget = tokenBudget?.takeIf { it !in TokenBudgetLimits.PRESETS }?.toString().orEmpty()
                    editingTokenBudget = true
                },
            ) {
                RowValue(tokenBudget?.let(::tokenBudgetNumber) ?: stringResource(R.string.settings_token_budget_unlimited))
                RowChevron()
            }
            ListRow(
                null, stringResource(R.string.settings_show_task_cost),
                onClick = { onShowTaskCost(!showTaskCost) },
            ) {
                MikaSwitch(showTaskCost, onShowTaskCost, Modifier.testTag("work-show-task-cost"))
            }
            parallel?.let { ParallelRow(it, onParallelMax) }
        }
        // 锁屏时运行本机任务:独立开关 + 共用的锁屏凭据编辑区(组名/脚注说清与远程开关互不影响)
        deviceAccess?.let { DeviceAccessSection(it, localTasks = true) }
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
