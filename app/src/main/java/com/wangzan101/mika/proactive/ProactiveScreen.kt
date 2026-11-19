package com.wangzan101.mika.proactive

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.core.proactive.GrantResult
import com.wangzan101.mika.core.proactive.LearnLevel
import com.wangzan101.mika.core.proactive.NoticeFilter
import com.wangzan101.mika.core.proactive.SourceTier
import com.wangzan101.mika.core.proactive.NoticeRecord
import com.wangzan101.mika.core.proactive.TriageDecision
import com.wangzan101.mika.core.proactive.TriageGate
import com.wangzan101.mika.core.proactive.TriageMark
import com.wangzan101.mika.core.proactive.TriageNote
import com.wangzan101.mika.core.proactive.TriageTarget
import com.wangzan101.mika.core.proactive.TriageVerdict
import com.wangzan101.mika.core.usage.ProactiveBudget
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.ui.TokenBudgetDialog
import com.wangzan101.mika.ui.tokenBudgetNumber
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.DetailNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.core.proactive.ObservedContact
import com.wangzan101.mika.core.proactive.NoticeInbox
import com.wangzan101.mika.ui.components.SearchField
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 › 主动能力。只放要用户做决定的:
 * 状态卡(总开关、一句现在的状态、要处理时给一个按钮)→「Mika 知道了什么」的入口
 * → 设置(激进程度、敏感类推断、当日消息归纳、每日上限)→ 读哪些 App(每个 App 不读 / 低 / 中 / 高;列表可能很长,放在设置后面)
 * → 诊断(默认折起:监听状态、计数、失败重试、最近的通知)→ 页尾说明
 * (发什么、存什么、留多久)。归纳出的内容和它们的删除在下一页([ProactiveLearnScreen])。
 * 通知内容和模型写的判定理由都来自别处,一律当普通文字显示:不识别链接,不渲染格式。
 */
@Composable
fun ProactiveScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MikaApp
    val runtime = app.proactive
    val enabled by runtime.enabled.collectAsState()
    val connected by runtime.isConnected.collectAsState()
    val allowed by runtime.allowed.state.collectAsState()
    val inbox by runtime.inbox.state.collectAsState()
    val consent by runtime.consent.collectAsState()
    val triage by runtime.triageState.collectAsState()
    val learnState by runtime.learnState.collectAsState()
    val level by runtime.learnLevel.collectAsState()
    val sensitiveOn by runtime.learnSensitive.collectAsState()
    val digestOn by runtime.learnDigest.collectAsState()
    val optOut by runtime.learnOptOut.collectAsState()
    val high by runtime.learnHigh.collectAsState()
    val topics by runtime.learnTopics.collectAsState()
    val contactsView by runtime.learnContactsView.collectAsState()
    val limit by runtime.triageLimit.collectAsState()
    val accessKnown by runtime.accessKnown.collectAsState(runtime.accessKnownNow())
    // 判定和归纳实际会用的服务商和模型:功能开着时才去取(关着不碰模型配置;打开开关那一下另取);回到本页时重读
    var target by remember { mutableStateOf<TriageTarget?>(null) }
    var targetRead by remember { mutableStateOf(0) }
    LaunchedEffect(enabled, consent, targetRead) { target = if (enabled) runtime.triageTarget() else null }
    // 确认框里给用户看的那一家那个模型;[confirmEnables] 为 true 是从关着打开(同意了才开),否则是开着时(重新)确认
    var confirmModel by remember { mutableStateOf<TriageTarget?>(null) }
    var confirmEnables by remember { mutableStateOf(false) }
    // 打开总开关:配了模型就先弹确认框(同意了才开);没配模型先开着收,不发任何东西。关掉直接关
    val toggle: (Boolean) -> Unit = { on ->
        if (!on) runtime.setEnabled(false)
        else {
            val t = runtime.triageTarget()
            if (t == null) runtime.setEnabled(true) else { confirmEnables = true; confirmModel = t }
        }
    }
    var editingBudget by remember { mutableStateOf(false) }
    var customBudget by remember { mutableStateOf("") }
    var authorized by remember { mutableStateOf(runtime.authorized()) }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var addingApps by rememberSaveable { mutableStateOf(false) }
    var pickApp by remember { mutableStateOf<String?>(null) }
    // 改「读不读、哪一档」没存下来(存储满了之类):提示再选一次。只要名单还有没写成的「不读」就一直提示
    // (上次运行留下的也算),不只看最近那一次——中间改别的 App 的档位是成的,不能把提示带走
    var unsaved by remember { mutableStateOf(runtime.allowed.dirty) }
    var pickLevel by remember { mutableStateOf(false) }
    var confirmDigestOn by remember { mutableStateOf(false) }
    var confirmSensitiveOn by remember { mutableStateOf(false) }
    var confirmSensitiveOff by remember { mutableStateOf(false) }
    var diagOpen by rememberSaveable { mutableStateOf(false) }
    // 「由 Mika 助手授权」:要用助手或已配对的连接(和恢复开关看的是同一个条件);跑的过程中按钮不能再点
    val phone = remember { PhoneServices.of(context) }
    val shellStatus by phone.shellControl.status.collectAsState()
    val helperShell by phone.hub.shell.collectAsState()
    val helperUsable = helperShell != null || shellStatus != BuiltinShellStatus.NotPaired
    var confirmGrant by remember { mutableStateOf(false) }
    var granting by remember { mutableStateOf(false) }
    var grantMessage by remember { mutableStateOf<Int?>(null) }
    val uiScope = rememberCoroutineScope()
    // 超时后命令仍可能生效:监听连上时也重读授权
    LaunchedEffect(connected) { authorized = runtime.authorized() }
    // 从系统设置回来时重读授权;话题和联系人的个数要读出池子才有(顺带整理一遍)
    LifecycleResumeEffect(Unit) {
        authorized = runtime.authorized()
        targetRead++
        runtime.onLearn { open() }
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) {
        // Mika 自己的各个安装包(正式版、.dev 等)不给选
        apps = withContext(Dispatchers.IO) {
            runCatching { launchableApps(app.packageManager, app.packageName) }.getOrDefault(emptyList())
                .filterNot { NoticeFilter.isSelf(it.first) }
        }
    }
    val labels = apps.toMap()
    fun label(pkg: String) = labels[pkg] ?: pkg
    fun tierOf(pkg: String): SourceTier = when (pkg) { in optOut -> SourceTier.Low; in high -> SourceTier.High; else -> SourceTier.Mid }
    // 模型配置和用量账本只在主动能力开着时才碰
    var usageTick by remember { mutableStateOf(0) }
    if (enabled) {
        val version by remember { runtime.usageVersion() }.collectAsState()
        LaunchedEffect(version) { usageTick++ }
    }
    val (tokens, calls) = remember(enabled, usageTick, triage, learnState) { if (enabled) runtime.triageUsage() else 0L to 0L }
    val budgetOut = enabled && ProactiveBudget.exhausted(tokens, calls, limit) != null
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    val dateFormat = remember { android.text.format.DateFormat.getMediumDateFormat(context) }
    // 「Mika 知道了什么」是本页下面的一页;放在所有 remember 之后,回来时本页的状态还在
    var knownOpen by rememberSaveable { mutableStateOf(false) }
    if (knownOpen) return ProactiveLearnScreen(onBack = { knownOpen = false })
    val gate = TriageGate.of(true, consent, target)

    PageScaffold(stringResource(R.string.proactive_title), onBack) {
        // ---- 状态卡:开关,和一句现在的状态;要用户处理时给一个按钮 ----
        GroupCard {
            ListRow(null, stringResource(R.string.proactive_switch), first = true, onClick = { toggle(!enabled) }) {
                MikaSwitch(enabled, toggle, Modifier.testTag("proactive-switch"))
            }
            if (enabled) when {
                // 「应用访问」读不出来:整个停着,别的状态这时都不作数
                !accessKnown -> ListRow(null, stringResource(R.string.proactive_status_access))
                !authorized -> {
                    ListRow(null, stringResource(R.string.proactive_listener, stringResource(R.string.proactive_state_unauthorized))) {
                        TextButton({
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }) { Text(stringResource(R.string.proactive_open_access)) }
                    }
                    // 第二条路:不去系统页,由手机助手以 shell 身份打开;先确认,跑的时候转圈、不能再点
                    ListRow(
                        null, stringResource(R.string.proactive_helper_grant),
                        (grantMessage ?: R.string.proactive_helper_unavailable.takeIf { !helperUsable })?.let { stringResource(it) },
                        onClick = if (helperUsable && !granting) ({ confirmGrant = true }) else null,
                    ) {
                        if (granting) CircularProgressIndicator(Modifier.size(20.dp).testTag("proactive-helper-grant-busy"), strokeWidth = 2.dp)
                    }
                }
                gate == TriageGate.NoModel -> ListRow(null, stringResource(R.string.proactive_triage_no_model))
                gate == TriageGate.NeedsConsent -> {
                    // 还没同意过(没配模型时打开的),或者同意过但服务商、模型变了
                    val ask = { target?.let { confirmEnables = false; confirmModel = it } }
                    ListRow(
                        null, stringResource(if (consent == null) R.string.proactive_status_consent else R.string.proactive_triage_reconfirm),
                        onClick = { ask() },
                    ) {
                        TextButton({ ask() }, Modifier.testTag("proactive-reconfirm")) { Text(stringResource(R.string.proactive_triage_reconfirm_action)) }
                    }
                }
                budgetOut -> ListRow(null, stringResource(R.string.proactive_learn_state_budget))
                // 连不上判定用的模型、正停着:不写「正在工作」
                triage.status == TriageStatus.Unreachable -> ListRow(null, stringResource(R.string.proactive_triage_state_unreachable))
                !connected -> ListRow(null, stringResource(R.string.proactive_listener, stringResource(R.string.proactive_state_unbound)))
                else -> ListRow(null, stringResource(R.string.proactive_status_working, inbox.received))
            }
        }
        // 值得提醒的入口(胶囊显示不了时从这里进)和两个反馈数
        if (enabled) NoticeHintEntry(runtime.hints.collectAsState().value)

        // ---- 归纳出的内容在下一页 ----
        GroupCard {
            ListRow(
                null, stringResource(R.string.proactive_learn_title),
                stringResource(R.string.proactive_known_sub, topics.size, contactsView.contacts.size),
                first = true, onClick = { knownOpen = true },
            ) { RowChevron() }
        }

        // ---- 设置 ----
        GroupLabel(stringResource(R.string.settings_title))
        GroupCard {
            // 激进程度:一次调一组门槛(话题几天算数、每天提醒多少、归纳出的东西带不带进任务)
            ListRow(null, stringResource(R.string.proactive_learn_level), stringResource(levelDesc(level)), first = true, onClick = { pickLevel = true }) {
                RowValue(stringResource(levelName(level)))
            }
            // 敏感类推断:默认关;打开先确认,关掉时问已存的敏感话题删不删
            val toggleSensitive: (Boolean) -> Unit = { on -> if (on) confirmSensitiveOn = true else confirmSensitiveOff = true }
            ListRow(
                null, stringResource(R.string.proactive_learn_sensitive), stringResource(R.string.proactive_learn_sensitive_sub),
                onClick = { toggleSensitive(!sensitiveOn) },
            ) {
                MikaSwitch(sensitiveOn, toggleSensitive, Modifier.testTag("proactive-learn-sensitive"))
            }
            // 当日消息归纳:要把通知原文存在本机,所以打开前单独说明、单独确认;关掉就把存着的原文删掉
            val toggleDigest: (Boolean) -> Unit = { on -> if (on) confirmDigestOn = true else runtime.setLearnDigest(false) }
            ListRow(
                null, stringResource(R.string.proactive_learn_digest_switch),
                if (digestOn) stringResource(R.string.proactive_learn_digest_on, learnState.journal) else stringResource(R.string.proactive_learn_digest_sub),
                onClick = { toggleDigest(!digestOn) },
            ) {
                MikaSwitch(digestOn, toggleDigest, Modifier.testTag("proactive-learn-digest"))
            }
            ListRow(
                null,
                stringResource(
                    R.string.proactive_triage_budget, tokenBudgetNumber(tokens), tokenBudgetNumber(limit),
                    calls.toInt(), ProactiveBudget.callLimit(limit).toInt(),
                ),
                onClick = {
                    customBudget = ""
                    editingBudget = true
                },
            )
        }
        // 不是最保守的一档时,把「带进任务是什么意思、发给谁」那句常驻在这里(弹窗里也有)
        if (level != LearnLevel.Conservative) DetailNote(
            stringResource(R.string.proactive_learn_level_summary),
            stringResource(R.string.proactive_learn_level_note),
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )

        // ---- 读哪些 App:选了的列在这里,每个是低 / 中 / 高;「选择更多」展开全部 App ----
        GroupLabel(stringResource(R.string.proactive_apps, allowed.size))
        // 名单读不出来时按空的读:说清楚,免得用户以为自己一个都没选过
        if (!runtime.allowed.readable) FooterNote(stringResource(R.string.proactive_apps_unreadable))
        if (unsaved) FooterNote(stringResource(R.string.proactive_apps_unsaved))
        GroupCard {
            val chosen = allowed.filterNot(NoticeFilter::isSelf).sortedBy { label(it).lowercase() }
            chosen.forEachIndexed { i, pkg ->
                ListRow(null, label(pkg), pkg.takeIf { it != label(pkg) }, first = i == 0, onClick = { pickApp = pkg }) {
                    RowValue(stringResource(tierName(tierOf(pkg))))
                }
            }
            ListRow(null, stringResource(R.string.proactive_apps_more), first = chosen.isEmpty(), onClick = { addingApps = !addingApps }) { RowChevron() }
        }
        if (addingApps) {
            SearchField(search, { search = it }, stringResource(R.string.appaccess_search), Modifier.testTag("proactive-search"))
            GroupCard(Modifier.padding(top = 8.dp)) {
                apps.filter { (pkg, name) -> pkg !in allowed && (pkg.contains(search, true) || name.contains(search, true)) }
                    .forEachIndexed { i, (pkg, name) -> ListRow(null, name, pkg, first = i == 0, onClick = { pickApp = pkg }) }
            }
        }
        FooterNote(stringResource(R.string.proactive_apps_sub))

        // ---- 诊断:排查用的,默认折起 ----
        GroupCard {
            ListRow(null, stringResource(R.string.proactive_diag), first = true, onClick = { diagOpen = !diagOpen }) { RowChevron() }
        }
        if (diagOpen) Diagnostics(
            runtime, enabled, connected, authorized, gate, inbox, triage, learnState, budgetOut, allowed, contactsView.contacts,
            ::label, timeFormat, dateFormat,
        )

        // ---- 页尾:发什么、存什么、留多久;关掉开关会怎样 ----
        FooterNote(stringResource(R.string.proactive_footer_send))
        FooterNote(stringResource(R.string.proactive_footer_store))
        FooterNote(stringResource(if (digestOn) R.string.proactive_footer_digest else R.string.proactive_footer_no_text))
        FooterNote(stringResource(R.string.proactive_off_note))
    }

    confirmModel?.let { t ->
        MikaDialog(
            onDismissRequest = { confirmModel = null },
            title = { Text(stringResource(R.string.proactive_triage_consent_title)) },
            text = { Text(consentText(t, digestOn, level)) },
            confirmButton = {
                // 记下的是框里给用户看过的这一家这个模型
                TextButton({
                    if (confirmEnables) runtime.setEnabled(true, t) else runtime.confirmModel(t)
                    confirmModel = null
                }, Modifier.testTag("proactive-consent")) { Text(stringResource(R.string.proactive_triage_reconfirm_action)) }
            },
            dismissButton = { TextButton({ confirmModel = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (editingBudget) TokenBudgetDialog(
        title = stringResource(R.string.proactive_triage_budget_title),
        description = stringResource(
            R.string.proactive_triage_budget_sub,
            ProactiveBudget.callLimit(limit).toInt(), ProactiveBudget.TRIAGE_SHARE_PERCENT.toInt(),
        ),
        current = limit,
        custom = customBudget,
        onCustom = { customBudget = it.filter(Char::isDigit).take(9) },
        onPick = { picked ->
            picked?.let(runtime::setTriageBudget)
            editingBudget = false
        },
        onDismiss = { editingBudget = false },
        presets = BUDGET_PRESETS,
        unlimited = false,
    )
    if (confirmGrant) {
        MikaDialog(
            onDismissRequest = { confirmGrant = false },
            title = { Text(stringResource(R.string.proactive_helper_grant)) },
            text = { Text(stringResource(R.string.proactive_helper_grant_body)) },
            confirmButton = {
                TextButton({
                    confirmGrant = false
                    granting = true
                    grantMessage = null
                    uiScope.launch {
                        val r = runtime.grantViaHelper()
                        authorized = runtime.authorized()
                        grantMessage = when (r) {
                            GrantResult.Granted -> null
                            GrantResult.HelperUnavailable -> R.string.proactive_helper_unavailable
                            is GrantResult.Refused, GrantResult.TimedOut -> R.string.proactive_helper_failed
                        }
                        granting = false
                    }
                }) { Text(stringResource(R.string.proactive_helper_grant_confirm)) }
            },
            dismissButton = { TextButton({ confirmGrant = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // 一个 App 读不读、读的话哪一档。点一项就设好并关掉,所以只有「取消」一个按钮(放在 confirmButton 上:
    // MikaDialog 总会画出那一格)。不读和低会把它存着的原文和句子删掉,各项下面的说明里写着
    pickApp?.let { pkg ->
        val current = if (pkg in allowed) tierOf(pkg) else null
        MikaDialog(
            onDismissRequest = { pickApp = null },
            title = { Text(stringResource(R.string.proactive_learn_tier_title, label(pkg))) },
            text = {
                Column {
                    RadioRow(
                        stringResource(R.string.proactive_app_off), selected = current == null,
                        onClick = { unsaved = !runtime.setAppReading(pkg, null) || runtime.allowed.dirty; pickApp = null },
                        subtitle = stringResource(R.string.proactive_app_off_sub), first = true,
                    )
                    SourceTier.entries.forEach { t ->
                        RadioRow(
                            stringResource(tierName(t)), selected = t == current,
                            onClick = { unsaved = !runtime.setAppReading(pkg, t) || runtime.allowed.dirty; pickApp = null },
                            subtitle = stringResource(tierDesc(t)),
                        )
                    }
                }
            },
            confirmButton = { TextButton({ pickApp = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (pickLevel) {
        MikaDialog(
            onDismissRequest = { pickLevel = false },
            title = { Text(stringResource(R.string.proactive_learn_level)) },
            text = {
                Column {
                    LearnLevel.entries.forEach { l ->
                        RadioRow(
                            stringResource(levelName(l)), selected = l == level,
                            onClick = { runtime.setLearnLevel(l); pickLevel = false },
                            subtitle = stringResource(levelDesc(l)), first = l == LearnLevel.entries.first(),
                        )
                    }
                    Text(stringResource(R.string.proactive_learn_level_note), Modifier.padding(top = 10.dp))
                }
            },
            confirmButton = { TextButton({ pickLevel = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmDigestOn) {
        MikaDialog(
            onDismissRequest = { confirmDigestOn = false },
            title = { Text(stringResource(R.string.proactive_learn_digest_switch)) },
            text = { Text(stringResource(R.string.proactive_learn_digest_body)) },
            confirmButton = {
                TextButton({
                    runtime.setLearnDigest(true)
                    confirmDigestOn = false
                }, Modifier.testTag("proactive-learn-digest-confirm")) { Text(stringResource(R.string.proactive_learn_sensitive_on_confirm)) }
            },
            dismissButton = { TextButton({ confirmDigestOn = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmSensitiveOn) {
        MikaDialog(
            onDismissRequest = { confirmSensitiveOn = false },
            title = { Text(stringResource(R.string.proactive_learn_sensitive)) },
            text = { Text(stringResource(R.string.proactive_learn_sensitive_on_body)) },
            confirmButton = {
                TextButton({
                    runtime.setLearnSensitive(true)
                    confirmSensitiveOn = false
                }, Modifier.testTag("proactive-learn-sensitive-confirm")) { Text(stringResource(R.string.proactive_learn_sensitive_on_confirm)) }
            },
            dismissButton = { TextButton({ confirmSensitiveOn = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmSensitiveOff) {
        // 默认的(主按钮)是删除;另一个是保留但不再增加。点框外不关开关
        MikaDialog(
            onDismissRequest = { confirmSensitiveOff = false },
            title = { Text(stringResource(R.string.proactive_learn_sensitive_off_title)) },
            text = { Text(stringResource(R.string.proactive_learn_sensitive_off_body)) },
            confirmButton = {
                TextButton({
                    runtime.setLearnSensitive(false, deleteExisting = true)
                    confirmSensitiveOff = false
                }, Modifier.testTag("proactive-learn-sensitive-delete")) { Text(stringResource(R.string.proactive_learn_delete)) }
            },
            dismissButton = {
                TextButton({
                    runtime.setLearnSensitive(false, deleteExisting = false)
                    confirmSensitiveOff = false
                }) { Text(stringResource(R.string.proactive_learn_sensitive_keep)) }
            },
        )
    }
}

/**
 * 确认框里的话:发给谁、归纳出什么、存在哪;再按此刻的设置补上——「当日消息归纳」开着时连原文也存,
 * 激进程度不是最保守的一档时归纳出的东西还会发给执行任务的模型。这些设置关了总开关也还留着,所以每次确认都按当时的说。
 */
@Composable
internal fun consentText(target: TriageTarget, digestOn: Boolean, level: LearnLevel): String = listOfNotNull(
    stringResource(R.string.proactive_consent_body, target.providerName, target.model),
    stringResource(R.string.proactive_footer_digest).takeIf { digestOn },
    stringResource(R.string.proactive_learn_level_note).takeIf { level != LearnLevel.Conservative },
).joinToString("\n\n")

internal fun levelName(l: LearnLevel): Int = when (l) {
    LearnLevel.Conservative -> R.string.proactive_learn_level_conservative
    LearnLevel.Balanced -> R.string.proactive_learn_level_balanced
    LearnLevel.Aggressive -> R.string.proactive_learn_level_aggressive
}

private fun levelDesc(l: LearnLevel): Int = when (l) {
    LearnLevel.Conservative -> R.string.proactive_learn_level_conservative_sub
    LearnLevel.Balanced -> R.string.proactive_learn_level_balanced_sub
    LearnLevel.Aggressive -> R.string.proactive_learn_level_aggressive_sub
}

private fun tierName(t: SourceTier): Int = when (t) {
    SourceTier.Low -> R.string.proactive_learn_tier_low
    SourceTier.Mid -> R.string.proactive_learn_tier_mid
    SourceTier.High -> R.string.proactive_learn_tier_high
}

private fun tierDesc(t: SourceTier): Int = when (t) {
    SourceTier.Low -> R.string.proactive_learn_tier_low_sub
    SourceTier.Mid -> R.string.proactive_learn_tier_mid_sub
    SourceTier.High -> R.string.proactive_learn_tier_high_sub
}

/** 收件箱预览里每条通知下最多显示几段之前的文字。 */
private const val HISTORY_SHOWN = 5

/** 主动能力每日预算的几个预设;范围照 TokenBudgetLimits。 */
private val BUDGET_PRESETS = listOf(100_000L, 200_000L, 500_000L, 1_000_000L, 2_000_000L)

/**
 * 诊断(默认折起):监听状态、今天的计数、判定和归纳的状态、失败的再试一次、每个 App 的归纳计数、最近收到的通知和判定结果。
 * 排查问题时用,平时不用看。计数里不显示任何通知文字;「最近的通知」显示的是还在内存里的那些。
 */
@Composable
private fun Diagnostics(
    runtime: ProactiveRuntime,
    enabled: Boolean,
    connected: Boolean,
    authorized: Boolean,
    gate: TriageGate,
    inbox: NoticeInbox.Snapshot,
    triage: TriageState,
    learn: LearnState,
    budgetOut: Boolean,
    allowed: Set<String>,
    contacts: List<ObservedContact>,
    label: (String) -> String,
    timeFormat: java.text.DateFormat,
    dateFormat: java.text.DateFormat,
) {
    GroupCard {
        val state = when {
            connected -> R.string.proactive_state_connected
            !authorized -> R.string.proactive_state_unauthorized
            else -> R.string.proactive_state_unbound
        }
        ListRow(null, stringResource(R.string.proactive_listener, stringResource(state)), first = true)
        ListRow(null, stringResource(R.string.proactive_counts, inbox.received, inbox.kept))
        if (enabled && gate == TriageGate.Ready) {
            val c = triage.counts
            val counts = stringResource(R.string.proactive_triage_counts, c.pending + c.sending, c.judged, c.failed, c.cooling)
            val line = when {
                triage.status == TriageStatus.TokensUsedUp -> stringResource(R.string.proactive_triage_state_tokens)
                triage.status == TriageStatus.CallsUsedUp -> stringResource(R.string.proactive_triage_state_calls)
                triage.status == TriageStatus.Busy -> stringResource(R.string.proactive_triage_state_busy)
                triage.status == TriageStatus.Unreachable -> stringResource(R.string.proactive_triage_state_unreachable)
                triage.status == TriageStatus.Sending -> counts + " · " + stringResource(R.string.proactive_triage_sending)
                else -> counts
            }
            ListRow(null, line, counts.takeIf { it != line && triage.status != TriageStatus.Sending })
            // 连不上而停着时也给这个入口:那一批没有记成失败(失败数是 0),不给的话用户只能干等
            if (c.failed > 0 || triage.status == TriageStatus.Unreachable) {
                ListRow(null, stringResource(R.string.proactive_triage_retry), onClick = { runtime.retryTriage() })
            }
            val learnCounts = stringResource(R.string.proactive_learn_state, learn.pending, learn.buffered)
            val learnLine = when {
                budgetOut || learn.status == LearnStatus.BudgetUsedUp -> stringResource(R.string.proactive_learn_state_budget)
                learn.status == LearnStatus.Busy -> stringResource(R.string.proactive_triage_state_busy)
                learn.status == LearnStatus.StoreUnavailable -> stringResource(R.string.proactive_learn_state_store)
                learn.status == LearnStatus.Sending -> learnCounts + " · " + stringResource(R.string.proactive_learn_state_sending)
                else -> learnCounts
            }
            val last = learn.lastDistilledAtMs?.let {
                stringResource(R.string.proactive_learn_last, dateFormat.format(java.util.Date(it)) + " " + timeFormat.format(java.util.Date(it)))
            } ?: stringResource(R.string.proactive_learn_never)
            ListRow(null, learnLine, if (learnLine == learnCounts) last else "$learnCounts · $last")
        }
    }

    // 每个 App 归纳了多少(不显示名字):联系人比名字多很多,说明通知键不是一个会话一个
    val counted = allowed.filterNot(NoticeFilter::isSelf).sortedBy { label(it).lowercase() }
    if (counted.isNotEmpty()) {
        GroupLabel(stringResource(R.string.proactive_learn_apps))
        GroupCard {
            counted.forEachIndexed { i, pkg ->
                val f = learn.feed[pkg] ?: LearnFeed()
                val mine = contacts.filter { it.packageName == pkg }
                val reasons = f.contactDropped.entries.sortedBy { it.key.ordinal }.joinToString(", ") { "${it.key.name} ${it.value}" }
                ListRow(
                    null, label(pkg),
                    stringResource(R.string.proactive_learn_feed, f.captured, f.distilled, f.batches, f.failed, f.accepted, f.droppedTotal),
                    first = i == 0,
                    detail = stringResource(
                        R.string.proactive_learn_contacts_feed, mine.size, mine.map { it.name }.distinct().size, f.contactsAccepted, f.contactDroppedTotal,
                    ) + if (reasons.isEmpty()) "" else " ($reasons)",
                )
            }
        }
    }

    GroupLabel(stringResource(R.string.proactive_inbox))
    GroupCard {
        val latest = inbox.records.asReversed().take(20)
        if (latest.isEmpty()) EmptyNote(stringResource(R.string.proactive_inbox_empty))
        latest.forEach { r ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    "${label(r.packageName)} · ${timeFormat.format(java.util.Date(r.postedAtMs))}",
                    style = MikaType.Hint, color = MikaTheme.colors.ink3,
                )
                if (r.title.isNotBlank()) Text(r.title, style = MikaType.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // 同一条通知重发前显示过的话(最后一段就是下面的正文,不重复);只显示最近 HISTORY_SHOWN 段,更早的给个数
                val earlier = inbox.history[r.key].orEmpty().dropLast(1)
                if (earlier.size > HISTORY_SHOWN) {
                    Text(stringResource(R.string.proactive_history_more, earlier.size - HISTORY_SHOWN), style = MikaType.Hint, color = MikaTheme.colors.ink3)
                }
                earlier.takeLast(HISTORY_SHOWN).forEach { line ->
                    Text(line.text, style = MikaType.Hint, color = MikaTheme.colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (r.said.isNotBlank()) Text(r.said, style = MikaType.Body, color = MikaTheme.colors.ink2, maxLines = 6, overflow = TextOverflow.Ellipsis)
                if (enabled) triageLabel(triage.marks, r)?.let { tag ->
                    Text(tag, style = MikaType.Hint, color = MikaTheme.colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("proactive-triage-label"))
                }
            }
        }
    }
}

/**
 * 一条通知的判定标签:判定 · 理由(或内置说明);失败的说失败;还没判的没有标签。理由按纯文字显示。
 * 判过(或判失败)之后又有新内容的(在冷却、或冷却过了在等再判),照旧显示上次的结果,紧跟一句「有新内容,稍后再判」
 * (放在理由前面,理由长了被截掉的也不是它)。
 */
@Composable
private fun triageLabel(marks: Map<String, TriageMark>, r: NoticeRecord): String? {
    val any = marks[r.key] ?: return null
    if (any.postedAtMs != r.postedAtMs) {
        val newer = stringResource(R.string.proactive_triage_newer)
        return when (any) {
            is TriageMark.Done -> verdictLabel(any.verdict, newer)
            is TriageMark.Failed -> stringResource(R.string.proactive_triage_failed) + " · " + newer
        }
    }
    if (any is TriageMark.Failed) return stringResource(R.string.proactive_triage_failed)
    return verdictLabel((any as TriageMark.Done).verdict, null)
}

/** 判定 · [hint](有的话)· 理由(或内置说明)。 */
@Composable
private fun verdictLabel(v: TriageVerdict, hint: String?): String {
    val decision = stringResource(when (v.decision) {
        TriageDecision.Ignore -> R.string.proactive_triage_ignore
        TriageDecision.Remember -> R.string.proactive_triage_remember
        TriageDecision.Surface -> R.string.proactive_triage_surface
    })
    val why = when (v.note) {
        TriageNote.NoVerdict -> stringResource(R.string.proactive_triage_no_verdict)
        TriageNote.SurfaceCapped -> stringResource(R.string.proactive_triage_capped)
        null -> v.reason
    }
    return listOfNotNull(decision, hint, why.takeIf { it.isNotBlank() }).joinToString(" · ")
}
