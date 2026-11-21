package com.wangzan101.mika.ui

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

/** The current call and receiving controls come first; the rest expands without changing pages. */
@Composable
fun CallSettingsScreen(runtime: CallRuntime, onInstructions: (() -> Unit)? = null, onBack: () -> Unit,
    standalone: Boolean = false, onHelperSetup: () -> Unit = {}, onAbout: () -> Unit = {},
    initialHistoryCallId: String? = null,
    updateAvailable: Boolean = false, focusRequest: Int = 0) {
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
    when {
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
    var deviceNavigation by rememberSaveable { mutableIntStateOf(0) }
    var handledDeviceNavigation by rememberSaveable { mutableIntStateOf(0) }
    var pendingReuse by remember { mutableStateOf<Pair<String, CallTaskBrief>?>(null) }
    var dialog by rememberSaveable { mutableStateOf<CallDialog?>(null) }
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
        tab = CallWorkspaceTab.Settings
        deviceNavigation++
    }

    PageBackground(Modifier.testTag("call-settings")) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            if (standalone) TopBar(stringResource(R.string.app_name), MikaIcons.Gear, ::openDeviceSettings,
                stringResource(R.string.calls_tab_settings), right = {
                    BarIconButton(MikaIcons.Info, stringResource(R.string.about_check), onAbout, badge = updateAvailable)
                })
            else TopBar(stringResource(R.string.calls_title), MikaIcons.Back, onBack, stringResource(R.string.back))
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
                LaunchedEffect(tab, liveNavigation, taskNavigation, deviceNavigation) {
                    when (tab) {
                        CallWorkspaceTab.Receive -> if (liveNavigation != handledLiveNavigation) {
                            scroll.scrollTo(0)
                            handledLiveNavigation = liveNavigation
                        }
                        CallWorkspaceTab.Task -> if (taskNavigation != handledTaskNavigation) {
                            scroll.scrollTo(0)
                            handledTaskNavigation = taskNavigation
                        }
                        CallWorkspaceTab.Settings -> if (deviceNavigation != handledDeviceNavigation) {
                            scroll.scrollTo(0)
                            handledDeviceNavigation = deviceNavigation
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
                            config, state, ready,
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
                            onPause = { minutes -> perform(priority = true) { runtime.pauseAutoAnswer(minutes) } },
                            onResume = { perform(priority = true) { runtime.resumeAutoAnswer() } },
                            onInstructions = onInstructions,
                        )
                        CallWorkspaceTab.Task -> CallTaskScreen(
                            taskDraft, config, state, ready,
                            editable = pendingLockedDial == null && !busy && !controlBusy,
                            canDial = idle && pendingLockedDial == null && !busy && !controlBusy,
                            feedback = visibleFeedback, onSetup = ::openDeviceSettings,
                            voiceProfile = runtime.activeVoiceProfile(), onVoiceProfiles = onVoiceProfiles,
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
                        CallWorkspaceTab.History -> {
                            TextButton(onClick = { tab = CallWorkspaceTab.Settings }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.calls_back_settings))
                            }
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
                        CallWorkspaceTab.Settings -> CallPreferencesPage(
                            config, state, ready, deviceNavigation,
                            profile = runtime.activeVoiceProfile(),
                            canConfigure = idle && pendingLockedDial == null && !busy && !controlBusy && !settingsBusy,
                            stopBusy = controlBusy || settingsBusy, device = device, canStartStandby = canStartStandby,
                            onPrepare = prepare, onStop = { perform(priority = true) { runtime.stop() } },
                            onHelperSetup = onHelperSetup, onStartStandby = startStandby,
                            onCredentials = onVoiceProfiles, onProfile = { dialog = CallDialog.Profile },
                            onVoiceProfiles = onVoiceProfiles,
                            onRecords = { tab = CallWorkspaceTab.History },
                            onVerifyAudio = { verified -> perform { runtime.setAudioVerified(verified) } },
                            onDefaultSim = { id -> scope.launch { save { it.copy(defaultOutgoingSimId = id) } } },
                            onSummaryEnabled = { enabled -> scope.launch { save { it.copy(summaryEnabled = enabled) } } },
                        )
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
        CallDialog.Credentials -> CallCredentialsDialog(runtime, onDismiss = { dialog = null }) {
            lastActionWasLockedDial = false
            feedback = CallActionResult(true, it)
        }
        CallDialog.Profile -> CallProfileDialog(config, onDismiss = { dialog = null }, onSave = { edits ->
            val saved = save { latest ->
                require(canEditCallSpeaker(edits.speaker, latest.speaker))
                latest.copy(greeting = edits.greeting, systemPrompt = edits.systemPrompt,
                    speaker = edits.speaker, audioMode = edits.audioMode, maxCallSeconds = edits.maxCallSeconds)
            }
            if (saved) runtime.saveVoiceProfile(runtime.activeVoiceProfile().copy(speaker = edits.speaker)).ok else false
        })
        null -> Unit
    }
}

private enum class CallDialog { Rules, Bypass, Credentials, Profile }

private enum class CallWorkspaceTab(val label: Int) {
    Receive(R.string.calls_nav_receive), Task(R.string.calls_nav_task),
    Settings(R.string.calls_tab_settings), History(R.string.calls_tab_history),
}

@Composable
private fun CallWorkspaceTabs(selected: CallWorkspaceTab, onSelect: (CallWorkspaceTab) -> Unit) {
    // 页面底有背景图片时标签栏透出它,不留一条纯色
    val tabBg = if (LocalSkinBackdrop.current != null) Color.Transparent else MikaTheme.colors.bg
    val visibleTabs = CallWorkspaceTab.entries.filterNot { it == CallWorkspaceTab.History }
    TabRow(selectedTabIndex = if (selected == CallWorkspaceTab.History) visibleTabs.indexOf(CallWorkspaceTab.Settings) else selected.ordinal,
        containerColor = tabBg, contentColor = MikaTheme.colors.accent) {
        visibleTabs.forEach { tab ->
            Tab(
                selected = tab == selected || selected == CallWorkspaceTab.History && tab == CallWorkspaceTab.Settings,
                onClick = { onSelect(tab) },
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
    config: CallConfig, state: CallRuntimeState, ready: Boolean, controlsEnabled: Boolean,
    canStartStandby: Boolean, standbyNeedsSetup: Boolean,
    onSetup: () -> Unit, onToggle: (Boolean) -> Unit, onRules: () -> Unit, onBypass: () -> Unit,
    onPause: (Int) -> Unit, onResume: () -> Unit, onInstructions: () -> Unit, onStartStandby: () -> Unit,
) {
    var pauseOpen by rememberSaveable { mutableStateOf(false) }
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
        if (!ready || !config.audioVerified) {
            HorizontalDivider()
            ListRow(MikaIcons.Gear, stringResource(R.string.calls_device_and_permissions),
                stringResource(if (!ready) R.string.calls_needs_setup else R.string.calls_verify_note),
                onClick = onSetup) { RowChevron() }
        }
        if (config.autoAnswer) {
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
        HorizontalDivider(Modifier.padding(start = 60.dp), color = MikaTheme.colors.hair)
        val paused = state.autoAnswerStage == CallAutoAnswerStage.PAUSED
        CallFoldRow(MikaIcons.Clock, stringResource(R.string.calls_rule_pause), pauseOpen,
            "call-rules-pause", subtitle = if (paused) autoAnswerSummary(config, state) else null) { pauseOpen = !pauseOpen }
        if (pauseOpen) {
            if (!config.autoAnswer && !paused) {
                FooterNote(stringResource(R.string.calls_pause_enable_first))
            } else Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (paused) {
                    TextButton(onClick = onResume, enabled = controlsEnabled,
                        modifier = Modifier.weight(1f).testTag("call-resume-auto")) {
                        Text(stringResource(R.string.calls_auto_resume))
                    }
                } else {
                    TextButton(onClick = { onPause(30) }, enabled = controlsEnabled,
                        modifier = Modifier.weight(1f).testTag("call-pause-30")) {
                        Text(stringResource(R.string.calls_auto_pause_30))
                    }
                    TextButton(onClick = { onPause(120) }, enabled = controlsEnabled,
                        modifier = Modifier.weight(1f).testTag("call-pause-120")) {
                        Text(stringResource(R.string.calls_auto_pause_120))
                    }
                }
            }
        }
    }
    GroupCard {
        ListRow(MikaIcons.Doc, stringResource(R.string.calls_instructions_entry), first = true, onClick = onInstructions) {
            RowChevron()
        }
    }
    FooterNote(stringResource(R.string.calls_privacy_note))
}

@Composable
private fun CallPreferencesPage(
    config: CallConfig, state: CallRuntimeState, ready: Boolean, openDeviceRequest: Int,
    profile: CallVoiceProfile,
    canConfigure: Boolean, stopBusy: Boolean, device: CallDeviceControls, canStartStandby: Boolean,
    onPrepare: () -> Unit, onStop: () -> Unit, onCredentials: () -> Unit, onProfile: () -> Unit,
    onVoiceProfiles: () -> Unit, onRecords: () -> Unit,
    onVerifyAudio: (Boolean) -> Unit, onDefaultSim: (String?) -> Unit,
    onSummaryEnabled: (Boolean) -> Unit,
    onHelperSetup: () -> Unit, onStartStandby: () -> Unit,
) {
    var deviceOpen by rememberSaveable { mutableStateOf(!ready || !config.audioVerified) }
    var backgroundOpen by rememberSaveable { mutableStateOf(false) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var simOpen by rememberSaveable { mutableStateOf(false) }
    var lastDeviceRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(openDeviceRequest) {
        if (openDeviceRequest != lastDeviceRequest) {
            deviceOpen = true
            lastDeviceRequest = openDeviceRequest
        }
    }
    GroupCard {
        ListRow(MikaIcons.Phone, stringResource(R.string.calls_enable),
            stringResource(if (config.enabled) R.string.calls_enabled_summary else R.string.calls_system_default), first = true) {
            MikaSwitch(config.enabled, { checked -> if (checked) onPrepare() else onStop() },
                enabled = canConfigure || config.enabled, modifier = Modifier.testTag("call-enabled"))
        }
    }

    GroupLabel(stringResource(R.string.calls_settings_voice_group))
    GroupCard {
        ListRow(MikaIcons.Mic, stringResource(R.string.calls_voice_service),
            "${profile.name} · " + stringResource(when {
                !profile.credentialsConfigured -> R.string.calls_key_missing
                !profile.connectionTested -> R.string.calls_voice_status_untested
                else -> R.string.calls_voice_status_tested
            }), first = true, onClick = onVoiceProfiles.takeIf { canConfigure }) { RowChevron() }
        ListRow(MikaIcons.Message, stringResource(R.string.calls_profile),
            stringResource(R.string.calls_model_label, profile.model), onClick = onProfile.takeIf { canConfigure }) { RowChevron() }
    }

    GroupLabel(stringResource(R.string.calls_settings_device_group))
    GroupCard {
        CallFoldRow(MikaIcons.Gear, stringResource(R.string.calls_device_and_permissions), deviceOpen, "call-settings-device",
            subtitle = stringResource(readinessSummary(config, ready))) { deviceOpen = !deviceOpen }
        if (deviceOpen) {
            CallPermissionRows(device, state.shellConnected, canConfigure, onHelperSetup)
            CallReadinessActions(config, state, ready, profile, canConfigure, stopBusy, device.readiness,
                onPrepare, onStop, onCredentials)
            val canVerify = canConfigure && (ready || config.audioVerified)
            Row(
                Modifier.fillMaxWidth().toggleable(config.audioVerified, enabled = canVerify, role = Role.Checkbox,
                    onValueChange = onVerifyAudio).padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(config.audioVerified, onCheckedChange = null, enabled = canVerify)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.calls_audio_verified), style = MaterialTheme.typography.bodyMedium)
                    if (!config.audioVerified) Text(stringResource(R.string.calls_verify_note),
                        style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3)
                }
            }
            state.audioDetail?.let { FooterNote(it) }
        }
    }

    GroupCard {
        CallFoldRow(MikaIcons.Bell, stringResource(R.string.calls_background_settings), backgroundOpen, "call-settings-background",
            subtitle = stringResource(backgroundStandbyStatus(config, state))) { backgroundOpen = !backgroundOpen }
        if (backgroundOpen) {
            if (config.autoAnswer) {
                CallBackgroundStandbyRow(config, state, canStartStandby, onStartStandby)
                if (!state.backgroundStandbyStarting) {
                    state.backgroundStandbyBlockedReason?.let { FooterNote(it) }
                }
            }
            CallBatterySettingsRow(device, canConfigure)
            FooterNote(stringResource(R.string.calls_background_lock_screen_note))
            if (device.readiness.callNotificationsAllowed) {
                TextButton(onClick = device.callChannelAction, enabled = canConfigure, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.calls_notification_settings))
                }
            }
        }
    }

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

    GroupCard {
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

    GroupCard {
        CallFoldRow(MikaIcons.Gear, stringResource(R.string.calls_advanced), advancedOpen, "call-settings-advanced",
            subtitle = stringResource(R.string.calls_advanced_summary)) { advancedOpen = !advancedOpen }
        if (advancedOpen) {
            ListRow(MikaIcons.Mic, stringResource(R.string.calls_audio_route),
                stringResource(if (config.audioMode == CallAudioMode.DIRECT) R.string.calls_audio_direct else R.string.calls_audio_redirect),
                first = true, onClick = onProfile.takeIf { canConfigure }) { RowChevron() }
            FooterNote(stringResource(R.string.calls_advanced_note))
        }
    }

    GroupLabel(stringResource(R.string.calls_settings_data_group))
    GroupCard {
        ListRow(MikaIcons.Clock, stringResource(R.string.calls_tab_history),
            stringResource(R.string.calls_history_entry_summary), first = true, onClick = onRecords) { RowChevron() }
    }
}

@Composable
private fun CallReadinessActions(
    config: CallConfig, state: CallRuntimeState, ready: Boolean, profile: CallVoiceProfile, enabled: Boolean, stopBusy: Boolean,
    device: CallDeviceReadiness,
    onPrepare: () -> Unit, onStop: () -> Unit, onCredentials: () -> Unit,
) {
    val canPrepare = state.shellConnected && device.phoneAllowed && device.callNotificationsAllowed
    Column(Modifier.fillMaxWidth().testTag("call-readiness").padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when {
                !config.enabled -> stringResource(R.string.calls_system_default)
                ready -> if (config.maxCallSeconds > 0) stringResource(R.string.calls_limits, config.maxCallSeconds)
                    else stringResource(R.string.calls_limits_unlimited)
                else -> stringResource(R.string.calls_needs_setup)
            },
            style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3,
        )
        if (canPrepare && state.prepared && (!profile.credentialsConfigured || !profile.connectionTested)) {
            CallPrimaryButton(stringResource(R.string.calls_voice_setup), enabled, onCredentials)
        } else if (canPrepare && !ready) {
            CallPrimaryButton(stringResource(if (config.enabled) R.string.calls_prepare else R.string.calls_enable),
                enabled, onPrepare, Modifier.testTag("call-enable"))
        }
        if (config.enabled) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ready) TextButton(onClick = onPrepare, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.calls_prepare))
                }
                TextButton(onClick = onStop, enabled = !stopBusy, modifier = Modifier.weight(1f).testTag("call-disable")) {
                    Text(stringResource(R.string.calls_disable_short))
                }
            }
        }
    }
}

private fun readinessSummary(config: CallConfig, ready: Boolean): Int = when {
    !config.enabled -> R.string.calls_disabled
    !ready -> R.string.calls_needs_setup
    !config.audioVerified -> R.string.calls_device_ready_to_test
    else -> R.string.calls_ready
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
private fun CallCredentialsDialog(runtime: CallRuntime, onDismiss: () -> Unit, onResult: (String) -> Unit) {
    var legacy by remember { mutableStateOf(false) }
    // Sensitive fields never enter saved instance state.
    var key by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val saved = stringResource(R.string.calls_key_saved)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.calls_credentials)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.calls_credentials_note))
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(legacy, enabled = !busy, role = Role.Checkbox) { legacy = it; key = ""; appId = ""; error = false }
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(legacy, onCheckedChange = null, enabled = !busy)
                    Text(stringResource(R.string.calls_legacy_credentials))
                }
                if (legacy) OutlinedTextField(
                    appId, { appId = it; error = false }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("App ID") }, singleLine = true, enabled = !busy,
                )
                OutlinedTextField(
                    key, { key = it; error = false }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (legacy) "Access Key" else "API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true, enabled = !busy,
                )
                if (error) Text(stringResource(R.string.calls_action_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && key.isNotBlank() && (!legacy || appId.isNotBlank()), onClick = {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            runtime.settings.saveCredentials(if (legacy) appId else "", if (legacy) key else "", if (legacy) "" else key)
                        }
                        key = ""; appId = ""
                        onResult(saved)
                        onDismiss()
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = true }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.calls_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.calls_cancel)) } },
    )
}

@Composable
private fun CallProfileDialog(config: CallConfig, onDismiss: () -> Unit, onSave: suspend (CallConfig) -> Boolean) {
    var greeting by rememberSaveable { mutableStateOf(config.greeting) }
    var prompt by rememberSaveable { mutableStateOf(config.systemPrompt) }
    var seconds by rememberSaveable { mutableStateOf(config.maxCallSeconds.toString()) }
    var speaker by rememberSaveable { mutableStateOf(config.speaker) }
    var mode by rememberSaveable { mutableStateOf(config.audioMode) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val duration = seconds.toIntOrNull()?.takeIf { it == 0 || it in CallPolicy.MIN_SECONDS..CallPolicy.MAX_SECONDS }
    val allowedVoice = canEditCallSpeaker(speaker, config.speaker)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.calls_profile)) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.calls_greeting), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(greeting, { greeting = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.calls_greeting)) }, maxLines = 3, enabled = !busy,
                    supportingText = { Text(stringResource(R.string.calls_greeting_hint)) })
                HorizontalDivider()
                Text(stringResource(R.string.calls_profile), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(prompt, { prompt = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.calls_instructions)) }, maxLines = 5, enabled = !busy)
                OutlinedTextField(seconds, { seconds = it.filter(Char::isDigit).take(3) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.calls_duration)) }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(speaker, { speaker = it; error = false }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.calls_speaker)) }, singleLine = true, enabled = !busy,
                    isError = !allowedVoice, supportingText = { Text(stringResource(R.string.calls_speaker_edit_note)) })
                Text(stringResource(R.string.calls_audio_route))
                CallAudioMode.entries.forEachIndexed { i, option ->
                    RadioRow(
                        stringResource(if (option == CallAudioMode.DIRECT) R.string.calls_audio_direct else R.string.calls_audio_redirect),
                        selected = mode == option, onClick = { mode = option }, first = i == 0, enabled = !busy,
                    )
                }
                if (error) Text(stringResource(R.string.calls_action_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = duration != null && allowedVoice && !busy, onClick = {
                val maxSeconds = duration ?: return@TextButton
                busy = true
                scope.launch {
                    try {
                        if (onSave(config.copy(greeting = greeting, systemPrompt = prompt, speaker = speaker,
                                audioMode = mode, maxCallSeconds = maxSeconds))) onDismiss() else error = true
                    } finally { busy = false }
                }
            }) { Text(stringResource(R.string.calls_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.calls_cancel)) } },
    )
}

private fun canEditCallSpeaker(candidate: String, current: String): Boolean {
    val speaker = candidate.trim()
    return !speaker.startsWith("S_", ignoreCase = true) || speaker == current
}
