package com.wangzan101.mika.calls.ui

import androidx.activity.compose.BackHandler
import com.wangzan101.mika.ui.theme.LocalSkinBackdrop
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.*
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSpace
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import com.wangzan101.mika.chat.ui.readiness
import com.wangzan101.mika.memory.ui.Row
import com.wangzan101.mika.settings.ui.models.Page

/** The current call and receiving controls come first; the rest expands without changing pages. */
@Composable
fun CallSettingsScreen(runtime: CallRuntime, onInstructions: (() -> Unit)? = null, onBack: () -> Unit,
    standalone: Boolean = false, onHelperSetup: () -> Unit = {}, onAbout: () -> Unit = {},
    initialHistoryCallId: String? = null,
    updateAvailable: Boolean = false, focusRequest: Int = 0, onOpenVoiceCatalog: () -> Unit = {}) {
    var instructionsOpen by rememberSaveable { mutableStateOf(false) }
    var voiceProfilesOpen by rememberSaveable { mutableStateOf(false) }
    var handledFocusRequest by rememberSaveable { mutableIntStateOf(0) }
    val workspaceState = rememberSaveableStateHolder()
    val taskDraft = rememberSaveable(saver = CallTaskDraft.Saver) { CallTaskDraft() }
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0 && focusRequest != handledFocusRequest) {
            instructionsOpen = false
            voiceProfilesOpen = false
            handledFocusRequest = focusRequest
        }
    }
    // 这里的子页都不是导航路由,系统返回键要先关子页,不能直接退出整个电话页
    BackHandler(enabled = voiceProfilesOpen || instructionsOpen) {
        voiceProfilesOpen = false
        instructionsOpen = false
    }
    when {
        // 迁移后旧的配置页不再用:在这里从语音目录里选通话用的实时模型,新增/编辑去目录
        voiceProfilesOpen && runtime.realtimeMigrated -> CallVoiceScreen(
            runtime, onBack = { voiceProfilesOpen = false }, onOpenServices = onOpenVoiceCatalog,
        )
        voiceProfilesOpen -> CallVoiceProfilesScreen(runtime) { voiceProfilesOpen = false }
        instructionsOpen -> CallInstructionsScreen(runtime) { instructionsOpen = false }
        else -> workspaceState.SaveableStateProvider("call-workspace") {
            CallSettingsWorkspace(runtime, taskDraft, onBack, onInstructions ?: { instructionsOpen = true },
                { voiceProfilesOpen = true }, standalone, onHelperSetup, onAbout, updateAvailable, focusRequest, initialHistoryCallId)
        }
    }
}

@Composable
private fun CallSettingsWorkspace(
    runtime: CallRuntime, taskDraft: CallTaskDraft,
    onBack: () -> Unit, onInstructions: () -> Unit, onVoiceProfiles: () -> Unit,
    standalone: Boolean, onHelperSetup: () -> Unit, onAbout: () -> Unit, updateAvailable: Boolean,
    focusRequest: Int,
    initialHistoryCallId: String?,
) {
    val config by runtime.settings.state.collectAsState()
    val state by runtime.state.collectAsState()
    val history by runtime.history.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var controlBusy by remember { mutableStateOf(false) }
    var settingsBusy by remember { mutableStateOf(false) }
    var lastAction by remember { mutableIntStateOf(0) }
    var lastActionWasLockedDial by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<CallActionResult?>(null) }
    var tab by rememberSaveable { mutableStateOf(if (initialHistoryCallId != null) CallWorkspaceTab.History else CallWorkspaceTab.Receive) }
    LaunchedEffect(initialHistoryCallId) {
        if (initialHistoryCallId != null) tab = CallWorkspaceTab.History
    }
    val tabStates = rememberSaveableStateHolder()
    var liveNavigation by remember { mutableIntStateOf(0) }
    var handledLiveNavigation by remember { mutableIntStateOf(0) }
    var handledFocusRequest by rememberSaveable { mutableIntStateOf(0) }
    var taskNavigation by rememberSaveable { mutableIntStateOf(0) }
    var handledTaskNavigation by rememberSaveable { mutableIntStateOf(0) }
    var pendingReuse by remember { mutableStateOf<Pair<String, CallTaskBrief>?>(null) }
    var dialog by rememberSaveable { mutableStateOf<CallDialog?>(null) }
    var audioOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var checklistOpen by rememberSaveable { mutableStateOf(false) }
    val idle = state.phase == CallPhase.IDLE
    val pendingLockedDial = state.lockedDialRequestId
    val failureText = stringResource(R.string.calls_action_failed)
    val settingsErrorText = stringResource(R.string.calls_device_system_settings_failed)
    val device = rememberCallDeviceControls(runtime) { feedback = CallActionResult(false, settingsErrorText) }
    val ready = state.manualReady && device.readiness.phoneAllowed && device.readiness.callNotificationsAllowed
    val canStartStandby = state.autoAnswerReady && config.autoAnswer && idle && pendingLockedDial == null &&
        device.readiness.standbyNotificationsAllowed && !busy && !controlBusy && !settingsBusy

    LaunchedEffect(focusRequest) {
        if (focusRequest > 0 && focusRequest != handledFocusRequest) {
            tab = CallWorkspaceTab.Receive
            liveNavigation++
            handledFocusRequest = focusRequest
        }
    }

    fun perform(priority: Boolean = false, lockedDial: Boolean = false, action: suspend () -> CallActionResult) {
        if (if (priority) controlBusy else busy || controlBusy) return
        if (priority) controlBusy = true else busy = true
        val sequence = ++lastAction
        lastActionWasLockedDial = lockedDial
        feedback = null
        scope.launch {
            try {
                val result = action()
                if (sequence == lastAction) feedback = result
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (sequence == lastAction) feedback = CallActionResult(false, failureText)
            } finally {
                if (priority) controlBusy = false else busy = false
            }
        }
    }

    suspend fun save(change: (CallConfig) -> CallConfig): Boolean {
        if (settingsBusy) return false
        settingsBusy = true
        return try {
            withContext(Dispatchers.IO) {
                synchronized(runtime.settings) { runtime.settings.save(change(runtime.settings.state.value)) }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            feedback = CallActionResult(false, failureText)
            false
        } finally {
            settingsBusy = false
        }
    }

    val prepare: () -> Unit = {
        perform {
            if (!runtime.settings.state.value.enabled && !save { it.copy(enabled = true) }) {
                CallActionResult(false, failureText)
            } else runtime.prepare()
        }
    }
    val startStandby: () -> Unit = { perform { runtime.startBackgroundStandby() } }
    fun reuse(number: String, brief: CallTaskBrief) {
        taskDraft.fill(number, brief)
        tab = CallWorkspaceTab.Task
        taskNavigation++
    }

    fun openDeviceSettings() {
        checklistOpen = true
    }

    val canConfigure = idle && pendingLockedDial == null && !busy && !controlBusy && !settingsBusy
    val voiceSummary = callVoiceSummary(runtime)
    val missing = callSetupMissing(config, state, device.readiness, runtime.activeVoiceProfile().credentialsConfigured)
    if (audioOpen) {
        BackHandler { audioOpen = false }
        CallAudioScreen(
            config, state.audioDetail, canConfigure, canVerify = canConfigure && (ready || config.audioVerified),
            onMode = { mode -> scope.launch { save { it.copy(audioMode = mode) } } },
            onVerify = { verified -> perform { runtime.setAudioVerified(verified) } },
            onBack = { audioOpen = false },
        )
        return
    }
    if (checklistOpen) {
        BackHandler { checklistOpen = false }
        CallChecklistPage(
            missing, config, state, device, voiceSummary, canConfigure, canStartStandby,
            onBack = { checklistOpen = false }, onVoice = onVoiceProfiles, onPrepare = prepare,
            onHelper = onHelperSetup, onAudio = { audioOpen = true }, onStartStandby = startStandby,
        )
        return
    }
    if (settingsOpen) {
        BackHandler { settingsOpen = false }
        Page(stringResource(R.string.calls_settings_title), onBack = { settingsOpen = false }) {
            feedback?.takeIf { idle }?.let { CallFeedback(it) }
            CallPreferencesPage(
                config, state, missing, voiceNeedsChange = CallSetupStep.Voice in missing,
                canConfigure = canConfigure, voiceSummary = voiceSummary,
                stopBusy = controlBusy || settingsBusy,
                onStop = { perform(priority = true) { runtime.stop() } },
                onVoiceProfiles = onVoiceProfiles, onChecklist = { checklistOpen = true }, onAudio = { audioOpen = true },
                onDefaultSim = { id -> scope.launch { save { it.copy(defaultOutgoingSimId = id) } } },
                onSummaryEnabled = { enabled -> scope.launch { save { it.copy(summaryEnabled = enabled) } } },
            )
        }
        return
    }

    PageBackground(Modifier.testTag("call-settings")) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            if (standalone) TopBar(stringResource(R.string.app_name), MikaIcons.Gear, { settingsOpen = true },
                stringResource(R.string.calls_settings_title), right = {
                    BarIconButton(MikaIcons.Info, stringResource(R.string.about_check), onAbout, badge = updateAvailable)
                })
            else TopBar(stringResource(R.string.calls_title), MikaIcons.Back, onBack, stringResource(R.string.back), right = {
                BarIconButton(MikaIcons.Gear, stringResource(R.string.calls_settings_title), { settingsOpen = true })
            })
            if (!idle) {
                val callId = state.callId
                CurrentCallCard(
                    state = state, feedback = feedback,
                    canAnswer = ready && !busy && !controlBusy && callId != null && state.canAnswer,
                    canControl = !controlBusy && callId != null,
                    modifier = Modifier.padding(horizontal = MikaSpace.Page),
                    onAnswer = { callId?.let { perform { runtime.answer(it) } } },
                    onHangup = { callId?.let { perform(priority = true) { runtime.hangup(it) } } },
                    onTakeover = {
                        if (callId != null) perform(priority = true) {
                            withContext(Dispatchers.Main.immediate) {
                                val current = runtime.state.value
                                if (current.callId == callId && current.canTakeOver) runtime.takeover()
                                else CallActionResult(false, failureText)
                            }
                        }
                    },
                    onCancelAuto = { callId?.let { perform(priority = true) { runtime.cancelAutoAnswer(it) } } },
                    onSendDtmf = { expectedCallId, digits -> runtime.sendDtmf(expectedCallId, digits) },
                    onTranscript = {
                        tab = CallWorkspaceTab.Receive
                        liveNavigation++
                    },
                )
            }
            if (pendingLockedDial != null) {
                CallLockedDialPendingRow(state, enabled = !controlBusy) {
                    perform(priority = true, lockedDial = true) { runtime.cancelLockedDial(pendingLockedDial) }
                }
            }
            CallWorkspaceTabs(tab) { tab = it }
            tabStates.SaveableStateProvider(tab.name) {
                val scroll = rememberScrollState()
                LaunchedEffect(tab, liveNavigation, taskNavigation) {
                    when (tab) {
                        CallWorkspaceTab.Receive -> if (liveNavigation != handledLiveNavigation) {
                            scroll.scrollTo(0)
                            handledLiveNavigation = liveNavigation
                        }
                        CallWorkspaceTab.Task -> if (taskNavigation != handledTaskNavigation) {
                            scroll.scrollTo(0)
                            handledTaskNavigation = taskNavigation
                        }
                        CallWorkspaceTab.History -> Unit
                    }
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)
                        .padding(horizontal = MikaSpace.Page).padding(top = 8.dp, bottom = 24.dp)
                        .testTag("call-page-" + tab.name.lowercase()),
                ) {
                    val visibleFeedback = feedback?.takeIf {
                        idle && !(lastActionWasLockedDial && it.ok && state.lockedDialDetail != null)
                    }
                    if (tab == CallWorkspaceTab.Task && idle && pendingLockedDial == null && visibleFeedback == null) {
                        state.lockedDialDetail?.let { FooterNote(it) }
                    }
                    if (tab != CallWorkspaceTab.Task) visibleFeedback?.let { CallFeedback(it) }
                    when (tab) {
                        CallWorkspaceTab.Receive -> CallReceivePage(
                            config, state, ready, missing,
                            controlsEnabled = !settingsBusy && !controlBusy && (!busy || config.autoAnswer),
                            canStartStandby = canStartStandby,
                            standbyNeedsSetup = ready && !device.readiness.standbyNotificationsAllowed,
                            onSetup = ::openDeviceSettings,
                            onToggle = { checked ->
                                scope.launch {
                                    if (save { it.copy(autoAnswer = checked) } && checked &&
                                        device.readiness.standbyNotificationsAllowed) startStandby()
                                }
                            },
                            onStartStandby = startStandby,
                            onRules = { dialog = CallDialog.Rules },
                            onBypass = { dialog = CallDialog.Bypass },
                            onProfile = { dialog = CallDialog.Profile }.takeIf { canConfigure },
                            onPause = { minutes -> perform(priority = true) { runtime.pauseAutoAnswer(minutes) } },
                            onResume = { perform(priority = true) { runtime.resumeAutoAnswer() } },
                            onInstructions = onInstructions,
                        )
                        CallWorkspaceTab.Task -> {
                            if (missing.isNotEmpty()) CallReadyCard(missing, config, false, ::openDeviceSettings)
                            CallTaskScreen(
                            taskDraft, config, state, ready,
                            editable = pendingLockedDial == null && !busy && !controlBusy,
                            canDial = idle && pendingLockedDial == null && !busy && !controlBusy,
                            feedback = visibleFeedback, onSetup = ::openDeviceSettings,
                            voiceSummary = voiceSummary, onVoiceProfiles = onVoiceProfiles,
                            voiceBriefing = rememberCallVoiceBriefing(
                                runtime, taskDraft,
                                allowed = ready && idle && pendingLockedDial == null && !busy && !controlBusy,
                                profile = runtime.activeVoiceProfile(),
                            ) { submission ->
                                perform { runtime.dial(submission.number, simId = submission.simId, task = submission.brief) }
                            },
                            onConfirm = { submission ->
                                perform {
                                    runtime.dial(submission.number, simId = submission.simId, task = submission.brief)
                                }
                            },
                            onConfirmLocked = { submission ->
                                perform(lockedDial = true) {
                                    runtime.queueLockedDial(submission.number, simId = submission.simId, task = submission.brief)
                                }
                            },
                            )
                        }
                        CallWorkspaceTab.History -> {
                            CallHistorySection(
                                history, state.historyWriteFailed, onMark = runtime::setHistoryHandled, onDelete = runtime::deleteHistory,
                                onRetrySummary = runtime::retrySummary,
                                onSaveDraft = runtime::saveFollowUpDraft,
                                focusRecordId = initialHistoryCallId,
                                onClear = runtime::clearHistory,
                                onReuseTask = if (pendingLockedDial != null || busy || controlBusy) null else { number, brief ->
                                    if (taskDraft.hasContent) pendingReuse = number to brief else reuse(number, brief)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingReuse?.let { (number, brief) ->
        AlertDialog(
            onDismissRequest = { pendingReuse = null },
            title = { Text(stringResource(R.string.calls_task_replace_title)) },
            text = { Text(stringResource(R.string.calls_task_replace_note)) },
            confirmButton = {
                TextButton(enabled = !busy && !controlBusy, onClick = { pendingReuse = null; reuse(number, brief) }) {
                    Text(stringResource(R.string.calls_task_replace_action))
                }
            },
            dismissButton = { TextButton(onClick = { pendingReuse = null }) { Text(stringResource(R.string.calls_cancel)) } },
        )
    }

    when (dialog) {
        CallDialog.Rules, CallDialog.Bypass -> {
            val personal = dialog == CallDialog.Bypass
            CallNumberRuleEditor(config, personal, onDismiss = { dialog = null }) { mode, numbers, types, delay ->
                save { latest ->
                    if (personal) latest.copy(bypassIncomingNumbers = numbers)
                    else latest.copy(incomingScope = mode, allowedCallTypes = types, allowedIncomingNumbers = numbers, autoAnswerDelaySeconds = delay)
                }
            }
        }
        CallDialog.Profile -> CallProfileDialog(config, onDismiss = { dialog = null }, onSave = { edits ->
            save { latest ->
                latest.copy(greeting = edits.greeting, systemPrompt = edits.systemPrompt, maxCallSeconds = edits.maxCallSeconds)
            }
        })
        null -> Unit
    }
}

private enum class CallDialog { Rules, Bypass, Profile }

private enum class CallWorkspaceTab(val label: Int) {
    Receive(R.string.calls_nav_receive), Task(R.string.calls_nav_task), History(R.string.calls_tab_history),
}

@Composable
private fun CallWorkspaceTabs(selected: CallWorkspaceTab, onSelect: (CallWorkspaceTab) -> Unit) {
    // 页面底有背景图片时标签栏透出它,不留一条纯色
    val tabBg = if (LocalSkinBackdrop.current != null) Color.Transparent else MikaTheme.colors.bg
    TabRow(selectedTabIndex = selected.ordinal, containerColor = tabBg, contentColor = MikaTheme.colors.accent) {
        CallWorkspaceTab.entries.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                selectedContentColor = MikaTheme.colors.accent, unselectedContentColor = MikaTheme.colors.ink3,
                modifier = Modifier.heightIn(min = 48.dp).testTag("call-tab-" + tab.name.lowercase()),
            ) {
                Text(stringResource(tab.label), Modifier.padding(horizontal = 3.dp, vertical = 6.dp),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun CallFeedback(result: CallActionResult) {
    FooterNote(result.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = if (result.ok) MikaTheme.colors.ink3 else MaterialTheme.colorScheme.error)
}

@Composable
internal fun CurrentCallCard(
    state: CallRuntimeState,
    feedback: CallActionResult?,
    canAnswer: Boolean,
    canControl: Boolean,
    modifier: Modifier = Modifier,
    onAnswer: () -> Unit,
    onHangup: () -> Unit,
    onTakeover: () -> Unit,
    onCancelAuto: () -> Unit,
    onTranscript: () -> Unit,
    onSendDtmf: (suspend (String, String) -> CallActionResult)? = null,
) {
    var showActiveTask by rememberSaveable(state.callId) { mutableStateOf(false) }
    if (showActiveTask && state.activeTask != null) {
        val task = state.activeTask
        AlertDialog(
            onDismissRequest = { showActiveTask = false },
            title = { Text(stringResource(R.string.calls_record_brief)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(task.goal)
                    task.questions.forEach { Text("• $it") }
                    if (task.context.isNotBlank()) Text(task.context)
                    if (task.constraints.isNotBlank()) Text(task.constraints)
                }
            },
            confirmButton = { TextButton(onClick = { showActiveTask = false }) { Text(stringResource(R.string.calls_collapse)) } },
            modifier = Modifier.testTag("call-active-task-dialog"),
        )
    }
    var keypadCallId by remember { mutableStateOf<String?>(null) }
    val canUseKeypad = state.phase == CallPhase.ACTIVE && state.canSendDtmf && state.callId != null && onSendDtmf != null
    LaunchedEffect(state.callId, state.phase, canUseKeypad) {
        if (!canUseKeypad || keypadCallId != state.callId) keypadCallId = null
    }
    if (canUseKeypad && keypadCallId == state.callId) key(keypadCallId) {
        CallDtmfKeypadDialog(checkNotNull(keypadCallId), { keypadCallId = null }, checkNotNull(onSendDtmf), state.dtmfBusy)
    }

    val cancelAuto = state.autoAnswerStage == CallAutoAnswerStage.COUNTDOWN ||
        state.autoAnswerStage == CallAutoAnswerStage.PREPARING
    val ringing = state.phase == CallPhase.RINGING && state.autoAnswerStage != CallAutoAnswerStage.ANSWERING
    val cancelManual = ringing && state.canTakeOver && !cancelAuto
    val showTakeover = state.canTakeOver && !cancelAuto && !ringing
    val phase = when (state.autoAnswerStage) {
        CallAutoAnswerStage.COUNTDOWN -> state.autoAnswerRemainingSeconds?.let {
            stringResource(R.string.calls_auto_countdown, it)
        } ?: stringResource(R.string.calls_answer_pending)
        CallAutoAnswerStage.PREPARING -> stringResource(R.string.calls_auto_preparing)
        CallAutoAnswerStage.ANSWERING -> stringResource(R.string.calls_auto_answering)
        else -> if (state.aiActive) stringResource(R.string.calls_ai_active) else phaseLabel(state.phase)
    }
    MikaCard(modifier.padding(bottom = 6.dp).testTag("call-current")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconTile(MikaIcons.Phone, if (state.aiActive || cancelAuto) TileTone.Accent else TileTone.Neutral)
                Column(Modifier.weight(1f)) {
                    Text(phase, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val party = state.number?.let { "\u2066$it\u2069" }
                        ?: stringResource(R.string.calls_unknown_number).takeIf { state.direction == CallDirection.INCOMING }
                    val direction = state.direction?.let { directionLabel(it) }
                    val subtitle = listOfNotNull(direction, party).joinToString(" · ")
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MikaTheme.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (state.aiActive || state.liveTranscript.isNotEmpty()) {
                    IconButton(onClick = onTranscript, modifier = Modifier.testTag("call-current-transcript")) {
                        Icon(MikaIcons.Message, contentDescription = stringResource(R.string.calls_live_transcript),
                            tint = MikaTheme.colors.accent)
                    }
                }
            }
            if (state.aiActive) state.voiceStage?.let { CallVoiceStageRow(it, Modifier.padding(start = 46.dp)) }
            state.liveTranscript.lastOrNull { it.text.isNotBlank() }?.let { latest ->
                Row(Modifier.fillMaxWidth().testTag("call-current-latest"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(callTranscriptSpeakerLabel(latest.role)),
                        style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 80.dp))
                    Text(latest.text, style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
            if (ringing || showTakeover || cancelAuto || state.canHangUp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (ringing || showTakeover) {
                        val action = when {
                            cancelManual -> Triple(R.string.calls_cancel_ai_answer, onTakeover, "call-cancel-ai-answer")
                            showTakeover -> Triple(R.string.calls_takeover, onTakeover, "call-takeover")
                            else -> Triple(R.string.calls_answer, onAnswer, "call-answer")
                        }
                        Button(onClick = action.second, enabled = if (cancelManual || showTakeover) canControl else canAnswer,
                            modifier = Modifier.weight(1.25f).heightIn(min = 48.dp).testTag(action.third),
                            shape = MikaShapes.Button, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MikaTheme.colors.accent, contentColor = MikaTheme.colors.onAccent),
                        ) { Text(stringResource(action.first), textAlign = TextAlign.Center) }
                    }
                    if (cancelAuto) OutlinedButton(onClick = onCancelAuto, enabled = canControl,
                        modifier = Modifier.weight(1.25f).heightIn(min = 48.dp).testTag("call-cancel-auto"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    ) { Text(stringResource(R.string.calls_auto_cancel), textAlign = TextAlign.Center) }
                    if (state.canHangUp) TextButton(onClick = onHangup, enabled = canControl,
                        modifier = Modifier.weight(.85f).heightIn(min = 48.dp).testTag("call-hangup"),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    ) { Text(stringResource(R.string.calls_hangup), textAlign = TextAlign.Center,
                        color = if (canControl) MaterialTheme.colorScheme.error else MikaTheme.colors.ink3) }
                }
            }
            if (canUseKeypad) TextButton(
                onClick = { keypadCallId = state.callId }, enabled = canControl && !state.dtmfBusy,
                modifier = Modifier.testTag("call-dtmf-open"),
            ) { Text(stringResource(R.string.calls_dtmf_title)) }
            state.activeTask?.let { task ->
                TextButton(onClick = { showActiveTask = true }, modifier = Modifier.testTag("call-active-task")) {
                    Text(stringResource(R.string.calls_record_brief) + ": " + task.goal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            feedback?.let {
                Text(it.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (it.ok) MikaTheme.colors.ink3 else MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
internal fun LiveCallTranscript(callId: String?, lines: List<CallTranscriptLine>) {
    var expanded by rememberSaveable(callId) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.calls_live_transcript), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        if (lines.size > 3) TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.calls_collapse else R.string.calls_expand))
        }
    }
    if (lines.isEmpty()) {
        Text(stringResource(R.string.calls_live_waiting), style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3)
    } else Column(Modifier.testTag("call-live-transcript"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (if (expanded) lines else lines.takeLast(3)).forEach { line ->
            CallTranscriptText(line)
        }
    }
}

@Composable
internal fun CallReceivePage(
    config: CallConfig, state: CallRuntimeState, ready: Boolean, missing: List<CallSetupStep>, controlsEnabled: Boolean,
    canStartStandby: Boolean, standbyNeedsSetup: Boolean,
    onSetup: () -> Unit, onToggle: (Boolean) -> Unit, onRules: () -> Unit, onBypass: () -> Unit,
    onProfile: (() -> Unit)?,
    onPause: (Int) -> Unit, onResume: () -> Unit, onInstructions: () -> Unit, onStartStandby: () -> Unit,
) {
    CallReadyCard(missing, config, standby = state.autoAnswerStage == CallAutoAnswerStage.STANDBY, onOpen = onSetup)
    if (state.aiActive || state.liveTranscript.isNotEmpty()) {
        GroupCard {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LiveCallTranscript(state.callId, state.liveTranscript)
            }
        }
    }
    val autoLabel = stringResource(R.string.calls_auto_answer)
    val canTurnOn = ready && config.audioVerified && state.phase == CallPhase.IDLE &&
        config.hasIncomingSelection
    val paused = state.autoAnswerStage == CallAutoAnswerStage.PAUSED
    GroupCard {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(MikaIcons.Bell, when (state.autoAnswerStage) {
                CallAutoAnswerStage.STANDBY -> TileTone.Ok
                CallAutoAnswerStage.PAUSED, CallAutoAnswerStage.BLOCKED -> TileTone.Warn
                else -> TileTone.Neutral
            })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(autoLabel, style = MaterialTheme.typography.titleMedium)
                Text(autoAnswerSummary(config, state), style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3)
            }
            MikaSwitch(
                config.autoAnswer, onToggle,
                modifier = Modifier.testTag("call-auto-answer").semantics { contentDescription = autoLabel },
                enabled = controlsEnabled && (config.autoAnswer || canTurnOn),
            )
        }
        // 开着或暂停中才有暂停/恢复;按钮直接放在开关下面,不再藏进折叠行
        if (config.autoAnswer || paused) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (paused) {
                    TextButton(onClick = onResume, enabled = controlsEnabled, modifier = Modifier.testTag("call-resume-auto")) {
                        Text(stringResource(R.string.calls_auto_resume))
                    }
                } else {
                    TextButton(onClick = { onPause(30) }, enabled = controlsEnabled, modifier = Modifier.testTag("call-pause-30")) {
                        Text(stringResource(R.string.calls_auto_pause_30))
                    }
                    TextButton(onClick = { onPause(120) }, enabled = controlsEnabled, modifier = Modifier.testTag("call-pause-120")) {
                        Text(stringResource(R.string.calls_auto_pause_120))
                    }
                }
            }
        }
        if (config.autoAnswer && (state.backgroundStandbyRunning || state.backgroundStandbyStarting || canStartStandby || standbyNeedsSetup)) {
            CallBackgroundStandbyRow(config, state, canStartStandby, onStartStandby, onSetup.takeIf { standbyNeedsSetup })
        }
    }

    GroupLabel(stringResource(R.string.calls_rules))
    GroupCard(Modifier.testTag("call-incoming-rules")) {
        ListRow(MikaIcons.Phone, stringResource(R.string.calls_rule_scope),
            subtitle = rulesSummary(config) + " · " + stringResource(R.string.calls_rule_delay_summary, config.autoAnswerDelaySeconds),
            first = true, onClick = onRules, modifier = Modifier.testTag("call-rules-scope")) { RowChevron() }
        ListRow(MikaIcons.Heart, stringResource(R.string.calls_bypass_title),
            subtitle = if (config.bypassIncomingNumbers.isEmpty()) stringResource(R.string.calls_bypass_empty)
                else stringResource(R.string.calls_bypass_count, config.bypassIncomingNumbers.size),
            onClick = onBypass, modifier = Modifier.testTag("call-rules-important")) { RowChevron() }
    }

    GroupLabel(stringResource(R.string.calls_how_to_talk))
    GroupCard {
        ListRow(MikaIcons.Message, stringResource(R.string.calls_greeting),
            config.greeting.ifBlank { stringResource(R.string.calls_greeting_empty) }, first = true,
            onClick = onProfile, modifier = Modifier.testTag("call-settings-profile")) { RowChevron() }
        ListRow(MikaIcons.Doc, stringResource(R.string.calls_instructions_entry),
            stringResource(R.string.calls_talk_settings_sub), onClick = onInstructions,
            modifier = Modifier.testTag("call-instructions")) { RowChevron() }
    }
    FooterNote(stringResource(R.string.calls_privacy_note))
}

/** ⑭: the settings that rarely change — voice, this phone's setup, records, and turning AI calls off. */
@Composable
private fun CallPreferencesPage(
    config: CallConfig, state: CallRuntimeState, missing: List<CallSetupStep>, voiceNeedsChange: Boolean,
    canConfigure: Boolean, voiceSummary: String, stopBusy: Boolean,
    onStop: () -> Unit, onVoiceProfiles: () -> Unit, onChecklist: () -> Unit, onAudio: () -> Unit,
    onDefaultSim: (String?) -> Unit,
    onSummaryEnabled: (Boolean) -> Unit,
) {
    var simOpen by rememberSaveable { mutableStateOf(false) }
    GroupLabel(stringResource(R.string.calls_settings_sound))
    GroupCard {
        ListRow(MikaIcons.Mic, stringResource(R.string.calls_voice_title), voiceSummary, first = true,
            onClick = onVoiceProfiles.takeIf { canConfigure }, modifier = Modifier.testTag("call-settings-voice")) {
            if (voiceNeedsChange) StatusPill(stringResource(R.string.calls_voice_needs_change), PillTone.Warn)
            RowChevron()
        }
    }

    GroupLabel(stringResource(R.string.calls_settings_this_phone))
    GroupCard {
        ListRow(MikaIcons.Check, stringResource(R.string.calls_checklist_title),
            if (missing.isEmpty()) stringResource(R.string.calls_checklist_done)
            else stringResource(R.string.calls_checklist_left, missing.size),
            first = true, tone = if (missing.isEmpty()) TileTone.Ok else TileTone.Warn,
            onClick = onChecklist, modifier = Modifier.testTag("call-settings-checklist")) { RowChevron() }
        ListRow(MikaIcons.Sound, stringResource(R.string.calls_audio_title), callAudioSummary(config),
            onClick = onAudio, modifier = Modifier.testTag("call-settings-audio")) { RowChevron() }
        val preferred = config.defaultOutgoingSimId
        val selected = state.simOptions.firstOrNull { it.id == preferred }
        val summary = when {
            selected != null -> stringResource(R.string.calls_sim_preferred, selected.label)
            preferred != null -> stringResource(R.string.calls_sim_unavailable)
            else -> stringResource(R.string.calls_sim_ask_each_time)
        }
        CallFoldRow(MikaIcons.Phone, stringResource(R.string.calls_default_sim_settings), simOpen, "call-settings-sim",
            subtitle = summary) { simOpen = !simOpen }
        if (simOpen) {
            HorizontalDivider()
            RadioRow(stringResource(R.string.calls_sim_ask_each_time), preferred == null,
                onClick = { onDefaultSim(null) }, first = true, enabled = canConfigure)
            state.simOptions.forEach { sim ->
                RadioRow(sim.label, preferred == sim.id, onClick = { onDefaultSim(sim.id) }, enabled = canConfigure,
                    subtitle = if (sim.isDefault) stringResource(R.string.calls_default) else null)
            }
        }
    }

    GroupLabel(stringResource(R.string.calls_settings_records))
    GroupCard {
        Row(
            Modifier.fillMaxWidth().toggleable(config.summaryEnabled, enabled = canConfigure, role = Role.Switch,
                onValueChange = onSummaryEnabled).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.calls_summary_enable), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.calls_summary_cloud_consent), style = MaterialTheme.typography.bodySmall,
                    color = MikaTheme.colors.ink3)
            }
            Switch(config.summaryEnabled, onCheckedChange = null, enabled = canConfigure,
                modifier = Modifier.testTag("call-summary-enabled"))
        }
    }

    // 不再有「启用」大开关:做完准备清单就是启用,不想用了在这里停用
    if (config.enabled) {
        Spacer(Modifier.height(16.dp))
        GroupCard {
            ListRow(null, stringResource(R.string.calls_disable_short), stringResource(R.string.calls_disable_sub), first = true,
                titleColor = MaterialTheme.colorScheme.error, onClick = onStop.takeIf { !stopBusy },
                modifier = Modifier.testTag("call-disable"))
        }
    }
}


@Composable
private fun autoAnswerSummary(config: CallConfig, state: CallRuntimeState): String = when (state.autoAnswerStage) {
    CallAutoAnswerStage.OFF -> stringResource(when {
        !config.enabled -> R.string.calls_system_default
        !state.manualReady -> R.string.calls_needs_setup
        else -> R.string.calls_auto_off
    })
    CallAutoAnswerStage.BLOCKED -> state.autoAnswerBlockedReason ?: stringResource(R.string.calls_needs_setup)
    CallAutoAnswerStage.STANDBY -> stringResource(R.string.calls_auto_standby, config.autoAnswerDelaySeconds)
    CallAutoAnswerStage.BUSY -> stringResource(R.string.calls_auto_busy)
    CallAutoAnswerStage.PAUSED -> {
        val until = config.autoAnswerPausedUntilMs
        if (until == null) stringResource(R.string.calls_auto_paused)
        else {
            val locale = LocalContext.current.resources.configuration.locales[0]
            val formatter = remember(locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale) }
            stringResource(R.string.calls_auto_paused_until, formatter.format(Date(until)))
        }
    }
    CallAutoAnswerStage.COUNTDOWN, CallAutoAnswerStage.PREPARING, CallAutoAnswerStage.ANSWERING ->
        stringResource(R.string.calls_answer_pending)
}

@Composable
private fun rulesSummary(config: CallConfig): String = when {
    config.incomingScope == IncomingScope.STRANGERS -> stringResource(R.string.calls_only_strangers)
    config.incomingScope == IncomingScope.ALL -> stringResource(R.string.calls_all_note)
    config.incomingScope == IncomingScope.TYPES -> {
        val resources = LocalContext.current.resources
        config.allowedCallTypes.sortedBy { it.ordinal }.joinToString(", ") { resources.getString(it.title) }
            .ifEmpty { resources.getString(R.string.calls_pick_types) }
    }
    config.allowedIncomingNumbers.isEmpty() -> stringResource(R.string.calls_allowlist_empty)
    else -> stringResource(R.string.calls_list_count, config.allowedIncomingNumbers.size)
}

@Composable
internal fun CallFoldRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, expanded: Boolean,
    testTag: String, subtitle: String? = null, onClick: () -> Unit,
) {
    val expansion = stringResource(if (expanded) R.string.calls_collapse else R.string.calls_expand)
    Row(
        Modifier.fillMaxWidth().testTag(testTag).clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = expansion }.heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3) }
        }
        CallExpandIcon(expanded)
    }
}

@Composable
internal fun CallExpandIcon(expanded: Boolean) {
    Icon(
        MikaIcons.Chevron, null, Modifier.size(16.dp).rotate(if (expanded) -90f else 90f), tint = MikaTheme.colors.ink3,
    )
}

@Composable
internal fun CallPrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = MikaShapes.Button,
        colors = ButtonDefaults.buttonColors(containerColor = MikaTheme.colors.accent, contentColor = MikaTheme.colors.onAccent),
    ) { Text(text) }
}

@Composable
internal fun directionLabel(direction: CallDirection): String = stringResource(
    if (direction == CallDirection.INCOMING) R.string.calls_direction_in else R.string.calls_direction_out,
)

@Composable
private fun phaseLabel(phase: CallPhase): String = stringResource(when (phase) {
    CallPhase.DIALING -> R.string.calls_dialing
    CallPhase.RINGING -> R.string.calls_ringing
    CallPhase.CONNECTING -> R.string.calls_connecting
    CallPhase.ACTIVE -> R.string.calls_connected
    CallPhase.HOLDING -> R.string.calls_holding
    CallPhase.DISCONNECTING -> R.string.calls_disconnecting
    CallPhase.UNKNOWN -> R.string.calls_system_active
    CallPhase.IDLE -> R.string.calls_no_current
})

@Composable
private fun CallProfileDialog(config: CallConfig, onDismiss: () -> Unit, onSave: suspend (CallConfig) -> Boolean) {
    var greeting by rememberSaveable { mutableStateOf(config.greeting) }
    var prompt by rememberSaveable { mutableStateOf(config.systemPrompt) }
    var seconds by rememberSaveable { mutableStateOf(config.maxCallSeconds.toString()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val duration = seconds.toIntOrNull()?.takeIf { it == 0 || it in CallPolicy.MIN_SECONDS..CallPolicy.MAX_SECONDS }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.calls_profile)) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(greeting, { greeting = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.calls_greeting)) }, maxLines = 3, enabled = !busy,
                    supportingText = { Text(stringResource(R.string.calls_greeting_hint)) })
                OutlinedTextField(prompt, { prompt = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.calls_instructions)) }, maxLines = 5, enabled = !busy)
                OutlinedTextField(seconds, { seconds = it.filter(Char::isDigit).take(3) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.calls_duration)) }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (error) Text(stringResource(R.string.calls_action_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = duration != null && !busy, onClick = {
                val maxSeconds = duration ?: return@TextButton
                busy = true
                scope.launch {
                    try {
                        if (onSave(config.copy(greeting = greeting, systemPrompt = prompt, maxCallSeconds = maxSeconds))) onDismiss()
                        else error = true
                    } finally { busy = false }
                }
            }) { Text(stringResource(R.string.calls_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.calls_cancel)) } },
    )
}
