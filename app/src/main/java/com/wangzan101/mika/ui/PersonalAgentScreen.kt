package com.wangzan101.mika.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.personal.PersonalTask
import com.wangzan101.mika.core.personal.PersonalSystemException
import com.wangzan101.mika.personal.displayText
import com.wangzan101.mika.personal.displayDetail
import com.wangzan101.mika.personal.displayResult
import com.wangzan101.mika.personal.personalSystemText
import com.wangzan101.mika.personal.personalStopErrorText
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.personal.PersonalPhonePreview
import com.wangzan101.mika.personal.PersonalRuntime
import com.wangzan101.mika.ui.components.ActivitySizedDialog
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaLogo
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.components.floatShadow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSpace
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.HoldToTalk
import com.wangzan101.mika.voice.VoiceSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun personalStatus(task: PersonalTask): String = stringResource(when (task.status) {
    "running" -> R.string.personal_status_running
    "queued" -> R.string.personal_status_queued
    "needs_you" -> R.string.personal_status_needs_you
    "paused" -> R.string.personal_status_paused
    "finishing" -> R.string.personal_status_finishing
    "completed" -> R.string.personal_status_completed
    "cancelled" -> R.string.personal_status_cancelled
    "partial" -> R.string.personal_status_partial
    else -> R.string.personal_status_incomplete
})

@Composable
internal fun personalDayFormat(): DateTimeFormatter {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalAgentScreen(
    runtime: PersonalRuntime,
    voice: VoiceSource,
    onOpenDrawer: () -> Unit,
    onOpenTask: (String) -> Unit,
    memoryRuntime: MemoryRuntime,
    onModels: () -> Unit,
    onPickSpeech: () -> Unit,
) {
    val state by runtime.state.collectAsState()
    val actionError by runtime.error.collectAsState()
    val c = MikaTheme.colors
    val reduceMotion = MikaTheme.reduceMotion
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var draft by rememberSaveable { mutableStateOf("") }
    var voiceHolding by remember { mutableStateOf(false) }
    var profile by rememberSaveable { mutableStateOf(false) }
    var selectedTask by rememberSaveable { mutableStateOf<String?>(null) }
    var replayTask by rememberSaveable { mutableStateOf<String?>(null) }
    var openingPreview by remember { mutableStateOf<PersonalPhonePreview?>(null) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var followLatest by rememberSaveable { mutableStateOf(true) }
    var composerHeight by remember { mutableIntStateOf(0) }
    var previousComposerHeight by remember { mutableIntStateOf(0) }
    val messages = remember(state.messages) {
        state.messages.mapNotNull { message ->
            if (message.role == "task") null else message.conversationText().takeIf { it.isNotBlank() }?.let { text ->
                if (text == message.text) message else message.copy(text = text)
            }
        }
    }
    // The spacer after the last message is the bottom anchor, at index messages.size.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = messages.size)
    var previousMessageCount by rememberSaveable { mutableIntStateOf(messages.size) }
    val active = state.tasks.filterNot { it.terminal }
    val activeIds = active.map { it.conversationId }
    val phonePreview = remember(runtime, activeIds) { runtime.phonePreview(activeIds) }
    val zone = remember { ZoneId.systemDefault() }
    val dayFormat = personalDayFormat()
    val context = LocalContext.current
    val sendFailed = stringResource(R.string.personal_send_failed)
    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        draft = ""
        keyboard?.hide()
        followLatest = true
        scope.launch {
            runCatching { runtime.send(text) }
                .onFailure {
                    draft = text
                    sendError = personalSystemText(context, (it as? PersonalSystemException)?.systemType, it.message ?: sendFailed)
                }
        }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.layoutInfo }.collect { (scrolling, layout) ->
            if (scrolling) followLatest = (layout.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layout.totalItemsCount - 3
        }
    }
    LaunchedEffect(messages.size) {
        val countChanged = previousMessageCount != messages.size
        previousMessageCount = messages.size
        if (countChanged && followLatest) list.scrollToItem(messages.size)
    }
    LaunchedEffect(composerHeight) {
        val growth = composerHeight - previousComposerHeight
        previousComposerHeight = composerHeight
        if (growth > 0 && followLatest && !list.isScrollInProgress) list.scrollBy(growth.toFloat())
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().testTag("personal-screen")) {
        PersonalHeader(
            active, phonePreview, onOpenDrawer, { profile = true }, { selectedTask = it.id },
            onOpenPreview = { preview ->
                openingPreview = preview
                replayTask = preview.conversationId
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth().voiceDim(voiceHolding, reduceMotion)) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("personal-timeline"),
                contentPadding = PaddingValues(horizontal = MikaSpace.Page, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (messages.isEmpty()) item("welcome") {
                    Column(Modifier.fillMaxWidth().padding(top = 60.dp, bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.personal_welcome_title), style = MikaType.LargeTitle, color = c.ink, textAlign = TextAlign.Center)
                        Text(stringResource(R.string.personal_welcome_body),
                            Modifier.padding(top = 12.dp), color = c.ink2, style = MikaType.Body, textAlign = TextAlign.Center)
                        TextButton(onClick = onModels, Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.personal_view_model)) }
                    }
                }
                itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                    val date = Instant.ofEpochMilli(message.at).atZone(zone).toLocalDate()
                    val priorDate = messages.getOrNull(index - 1)?.let {
                        Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate()
                    }
                    val task = message.taskId?.let { id -> state.tasks.find { it.id == id } }
                    val user = message.role == "user"
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                        if (date != priorDate) {
                            Text(dayFormat.format(date), Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp),
                                color = c.ink3, fontSize = 11.sp)
                            Spacer(Modifier.height(12.dp))
                        }
                        if (task != null) Text(task.title + " · " + personalStatus(task),
                            Modifier.padding(start = 4.dp, bottom = 6.dp), fontSize = 10.sp, color = c.ink2)
                        val bubble = Modifier.fillMaxWidth(if (user) 0.8f else 1f)
                            .wrapContentWidth(if (user) Alignment.End else Alignment.Start)
                            .background(if (user) c.accent else c.answer, if (user) MikaShapes.UserBubble else MikaShapes.AnswerBubble)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                        if (user) {
                            Text(message.text, bubble, style = MikaType.Body, color = c.onAccent)
                        } else {
                            MarkdownText(message.displayText(context), MikaType.Body.copy(color = if (message.error) c.bad else c.ink), bubble)
                        }
                        if (!message.handled) Text(stringResource(R.string.personal_received), Modifier.padding(top = 4.dp, end = 5.dp), fontSize = 10.sp, color = c.ink3)
                        if (task != null) TextButton(onClick = { selectedTask = task.id }) { Text(stringResource(R.string.personal_view_task), fontSize = 12.sp) }
                    }
                }
                item("bottom") { Spacer(Modifier.height(4.dp)) }
            }
            if (!followLatest && messages.isNotEmpty()) TextButton(
                onClick = { followLatest = true; scope.launch { list.scrollToItem(messages.size) } },
                modifier = Modifier.align(Alignment.BottomCenter).background(c.surface, CircleShape)) { Text(stringResource(R.string.personal_latest)) }
        }
        Composer(
            modifier = Modifier.onSizeChanged { composerHeight = it.height }.testTag("personal-composer"),
            text = draft,
            onText = { draft = it },
            mode = ComposerMode.Idle,
            hasHistory = messages.isNotEmpty(),
            onSend = ::send,
            onStop = {},
            reduceMotion = reduceMotion,
            voice = voice,
            onVoiceText = { draft = HoldToTalk.join(draft, it) },
            onPickSpeech = onPickSpeech,
            onVoiceHolding = { voiceHolding = it },
            sendFromIme = true,
        )
    }
    if (profile) {
        PersonalProfileDialog(
            state.tasks, memoryRuntime,
            onDismiss = { profile = false },
            onTask = { task -> profile = false; selectedTask = task.id },
        )
    }
    selectedTask?.let { id -> state.tasks.find { it.id == id } }?.let { task ->
        key(task.id) {
            val hasReplay by produceState(false, task.terminal) {
                do {
                    value = runtime.replayFrames(task.conversationId).isNotEmpty()
                    if (!task.terminal && !value) delay(1_000)
                } while (!task.terminal && !value)
            }
            PersonalTaskSheet(task, onDismiss = { selectedTask = null },
                onOpen = { selectedTask = null; onOpenTask(task.conversationId) },
                onStop = { runtime.stop(task.id) },
                onReplay = if (hasReplay) ({ selectedTask = null; replayTask = task.conversationId }) else null)
        }
    }
    replayTask?.let { id ->
        key(id) {
            PersonalReplayDialog(runtime, id, id in activeIds, openingPreview?.frame) {
                replayTask = null
                openingPreview = null
            }
        }
    }
    val actionErrorText = actionError?.let { personalStopErrorText(context, it) }
    (sendError ?: actionErrorText)?.let { error -> AlertDialog(onDismissRequest = { sendError = null; runtime.clearError() }, text = { Text(error) },
        confirmButton = { TextButton(onClick = { sendError = null; runtime.clearError() }) { Text(stringResource(R.string.personal_ok)) } }) }
}

internal enum class PersonalProfileTab(val label: Int, val icon: ImageVector) {
    Tasks(R.string.personal_tab_tasks, MikaIcons.Lines),
    Memory(R.string.personal_tab_memory, MikaIcons.Book),
    Skills(R.string.personal_tab_skills, MikaIcons.Spark),
}

@Composable
internal fun PersonalProfileDialog(
    tasks: List<PersonalTask>,
    memoryRuntime: MemoryRuntime,
    onDismiss: () -> Unit,
    onTask: (PersonalTask) -> Unit,
) {
    val c = MikaTheme.colors
    var tab by rememberSaveable { mutableStateOf(PersonalProfileTab.Tasks) }
    val dismiss by rememberUpdatedState(onDismiss)
    var panelBounds by remember { mutableStateOf(Rect.Zero) }
    val corner = 28.dp
    val shape = RoundedCornerShape(corner)
    val density = LocalDensity.current
    val radius = CornerRadius(with(density) { corner.toPx() })
    val maxContentHeight = if (tab == PersonalProfileTab.Tasks) 300.dp else 520.dp
    ActivitySizedDialog(onDismiss, dismissOnClickOutside = false) { safeArea ->
        BoxWithConstraints(
            Modifier.fillMaxSize().pointerInput(radius) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val bounds = panelBounds
                    val panel = RoundRect(bounds, radius)
                    if (bounds == Rect.Zero || panel.contains(down.position)) return@awaitEachGesture
                    down.consume()
                    val up = waitForUpOrCancellation()
                    if (up != null && !panel.contains(up.position)) {
                        up.consume()
                        dismiss()
                    }
                }
            }.then(safeArea)
                .padding(MikaSpace.S12),
            contentAlignment = Alignment.Center,
        ) {
            val compact = maxHeight < 360.dp
            val showTabIcons = maxWidth >= 284.dp && density.fontScale <= 1.6f
            Column(
                Modifier.widthIn(max = 460.dp).fillMaxWidth()
                    .onGloballyPositioned { panelBounds = it.boundsInRoot() }
                    .cardSurface(c, shape).clip(shape).testTag("personal-profile-dialog")
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = MikaSpace.S12, vertical = if (compact) MikaSpace.S12 else MikaSpace.S16),
                verticalArrangement = Arrangement.spacedBy(if (compact) MikaSpace.S8 else MikaSpace.S12),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    if (compact) {
                        Row(
                            Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
                            horizontalArrangement = Arrangement.spacedBy(MikaSpace.S8),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MikaLogo(36.dp, corner = 18.dp)
                            Text("Mika", style = MikaType.Title, color = c.ink, maxLines = 1)
                        }
                    } else {
                        Column(Modifier.align(Alignment.TopCenter).padding(top = MikaSpace.S4),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            MikaLogo(52.dp, corner = 26.dp)
                            Text("Mika", Modifier.padding(top = MikaSpace.S8), style = MikaType.Title, color = c.ink)
                        }
                    }
                    Box(Modifier.align(Alignment.TopEnd)) {
                        BarIconButton(MikaIcons.Close, stringResource(R.string.personal_close), onDismiss)
                    }
                }
                PersonalProfileTabs(tab, showIcons = showTabIcons, onSelect = { tab = it })
                Box(Modifier.fillMaxWidth().heightIn(min = 136.dp, max = maxContentHeight)) {
                    if (tab == PersonalProfileTab.Tasks) {
                        if (tasks.isEmpty()) {
                            Text(stringResource(R.string.personal_no_tasks), Modifier.align(Alignment.Center), style = MikaType.Body, color = c.ink2)
                        } else {
                            LazyColumn(
                                Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = MikaSpace.S8),
                                verticalArrangement = Arrangement.spacedBy(MikaSpace.S12),
                            ) {
                                items(tasks.asReversed(), key = { it.id }) { task -> PersonalTaskCard(task) { onTask(task) } }
                            }
                        }
                    } else {
                        val model by rememberMemoryViewModel(memoryRuntime)
                        val vm = model
                        if (vm == null) {
                            CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp))
                        } else {
                            val ui by vm.ui.collectAsState()
                            key(tab) {
                                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                                    .padding(horizontal = 6.dp, vertical = MikaSpace.S8)) {
                                    if (tab == PersonalProfileTab.Memory) {
                                        MemoryEntries(vm, ui, allowAdd = false)
                                    } else {
                                        SkillsSection(vm, ui.skills, pending = false)
                                        SkillsSection(vm, ui.skills, pending = true)
                                    }
                                }
                            }
                            MemoryMessage(vm, ui.message)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PersonalProfileTabs(selected: PersonalProfileTab, showIcons: Boolean, onSelect: (PersonalProfileTab) -> Unit) {
    val c = MikaTheme.colors
    val labels = PersonalProfileTab.entries.map { stringResource(it.label) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Long translations at large font scales don't fit three equal slots; stack them instead of clipping or shrinking text.
        val widest = with(density) {
            labels.maxOf { measurer.measure(it, MikaType.Label, maxLines = 1).size.width }.toDp()
        }
        val slot = (maxWidth - 6.dp - 4.dp) / 3
        val stacked = widest + MikaSpace.S4 * 2 + (if (showIcons) 20.dp else 0.dp) > slot
        val item: @Composable (PersonalProfileTab, String, Modifier) -> Unit = { tab, label, modifier ->
            val active = tab == selected
            val ink = if (active) c.ink else c.ink2
            Row(
                modifier.heightIn(min = 42.dp)
                    .then(if (active) Modifier.cardSurface(c, CircleShape) else Modifier)
                    .clip(CircleShape).selectable(selected = active, role = Role.Tab) { onSelect(tab) }
                    .padding(horizontal = MikaSpace.S4),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showIcons) Icon(tab.icon, null, Modifier.size(16.dp), tint = ink)
                Text(label, style = MikaType.Label, color = ink, maxLines = 1)
            }
        }
        if (stacked) {
            Column(
                Modifier.fillMaxWidth().selectableGroup().clip(RoundedCornerShape(24.dp)).background(c.fill).padding(3.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PersonalProfileTab.entries.forEachIndexed { i, tab -> item(tab, labels[i], Modifier.fillMaxWidth()) }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().selectableGroup().clip(CircleShape).background(c.fill).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PersonalProfileTab.entries.forEachIndexed { i, tab -> item(tab, labels[i], Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun PersonalHeader(
    active: List<PersonalTask>,
    phonePreview: Flow<PersonalPhonePreview?>,
    onOpenDrawer: () -> Unit,
    onActivity: () -> Unit,
    onTask: (PersonalTask) -> Unit,
    onOpenPreview: (PersonalPhonePreview) -> Unit,
) {
    val c = MikaTheme.colors
    val latestPreview by phonePreview.collectAsState(initial = null)
    val context = LocalContext.current
    val preview = latestPreview
    val previewTask = active.firstOrNull { it.conversationId == preview?.conversationId }
    val frame = preview?.frame?.takeUnless { previewTask == null || it.isRecycled }
    val previewWidth = frame?.let { minOf(76.dp, 104.dp * (it.width.toFloat() / it.height)) } ?: 0.dp
    Box(Modifier.fillMaxWidth().padding(horizontal = MikaSpace.Page, vertical = 6.dp)) {
        Box(Modifier.align(Alignment.TopStart).padding(top = 4.dp)) {
            BarIconButton(MikaIcons.Menu, stringResource(R.string.personal_open_sidebar), onOpenDrawer)
        }
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = 250.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MikaSpace.S12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (frame != null) Spacer(Modifier.width(previewWidth))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(58.dp).floatShadow(c, CircleShape).background(c.surface, CircleShape)
                            .border(0.5.dp, c.edge, CircleShape).clip(CircleShape).testTag("personal-profile")
                            .clickable(onClickLabel = stringResource(R.string.personal_view_activity), role = Role.Button, onClick = onActivity),
                        contentAlignment = Alignment.Center,
                    ) {
                        MikaLogo(52.dp, corner = 26.dp)
                    }
                    Text("Mika", Modifier.padding(top = 7.dp), style = MikaType.Title, color = c.ink)
                }
                if (frame != null) {
                    Image(
                        remember(frame) { frame.asImageBitmap() },
                        contentDescription = stringResource(R.string.personal_expand_phone),
                        modifier = Modifier.size(previewWidth, previewWidth * (frame.height.toFloat() / frame.width))
                            .border(1.dp, c.ink.copy(alpha = 0.18f), MikaShapes.Tile)
                            .clip(MikaShapes.Tile).testTag("personal-phone-preview")
                            .clickable(role = Role.Button) { onOpenPreview(preview) },
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            val task = previewTask ?: active.firstOrNull()
            if (task != null) {
                val tone = personalTaskTone(task)
                val showAll = active.size > 1 && frame == null
                Row(
                    Modifier.padding(top = MikaSpace.S4).heightIn(min = 32.dp).clip(MikaShapes.Pill)
                        .background(tone.fill).testTag("personal-task-progress")
                        .clickable(role = Role.Button) { if (showAll) onActivity() else onTask(task) }
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(tone.icon, null, Modifier.size(14.dp), tint = tone.ink)
                    Text(
                        if (showAll) pluralStringResource(R.plurals.task_active, active.size, active.size) else task.displayDetail(context).ifBlank { task.title },
                        Modifier.weight(1f, fill = false), style = MikaType.Hint, color = tone.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Icon(MikaIcons.Chevron, null, Modifier.size(12.dp), tint = tone.ink)
                }
            }
        }
    }
}

private data class PersonalTaskTone(val icon: ImageVector, val ink: Color, val fill: Color)

private val PersonalTask.iconOnlyStatus: Boolean get() = status == "completed" || status == "failed"

@Composable
private fun personalTaskTone(task: PersonalTask): PersonalTaskTone {
    val c = MikaTheme.colors
    return when (task.status) {
        "completed" -> PersonalTaskTone(MikaIcons.Check, c.ok, c.okSoft)
        "needs_you", "paused", "partial" -> PersonalTaskTone(MikaIcons.Info, c.warn, c.warnSoft)
        "failed" -> PersonalTaskTone(MikaIcons.Info, c.bad, c.badSoft)
        "cancelled" -> PersonalTaskTone(MikaIcons.Stop, c.ink2, c.fill)
        "queued" -> PersonalTaskTone(MikaIcons.Clock, c.ink2, c.fill)
        else -> PersonalTaskTone(MikaIcons.Spark, c.accent, c.accentSoft)
    }
}

@Composable
private fun PersonalTaskIcon(tone: PersonalTaskTone, label: String?) {
    Box(Modifier.size(40.dp).background(tone.fill, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Icon(tone.icon, label, Modifier.size(19.dp), tint = tone.ink)
    }
}

@Composable
private fun PersonalTaskCard(task: PersonalTask, onClick: () -> Unit) {
    val c = MikaTheme.colors
    val tone = personalTaskTone(task)
    val context = LocalContext.current
    val summary = task.displayResult(context)?.takeIf { it.isNotBlank() } ?: task.displayDetail(context).ifBlank { task.goal }
    val description = remember(summary) { MarkdownModel.toPlainText(summary).lineSequence().firstOrNull().orEmpty() }
    Row(
        Modifier.fillMaxWidth().testTag("personal-task-" + task.id).cardSurface(c).clip(MikaShapes.Card)
            .clickable(onClickLabel = stringResource(R.string.personal_task_details), role = Role.Button, onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PersonalTaskIcon(tone, personalStatus(task).takeIf { task.iconOnlyStatus })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(task.title, style = MikaType.BodyStrong, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (description.isNotBlank() && description != task.title) {
                Text(description, style = MikaType.Caption, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!task.iconOnlyStatus) Text(personalStatus(task), style = MikaType.Label, color = tone.ink)
        }
        Icon(MikaIcons.Chevron, null, Modifier.size(16.dp), tint = c.ink3)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalTaskSheet(
    task: PersonalTask,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    onReplay: (() -> Unit)?,
) {
    val c = MikaTheme.colors
    val tone = personalTaskTone(task)
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 580.dp).padding(horizontal = MikaSpace.S20)
                .testTag("personal-task-detail"),
        ) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = MikaSpace.S16),
                verticalArrangement = Arrangement.spacedBy(MikaSpace.S16),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PersonalTaskIcon(tone, personalStatus(task).takeIf { task.iconOnlyStatus })
                    if (!task.iconOnlyStatus) Text(personalStatus(task), style = MikaType.Label, color = tone.ink)
                }
                Text(task.title, style = MikaType.LargeTitle, color = c.ink)
                if (task.goal.isNotBlank() && task.goal != task.title) PersonalTaskSection(stringResource(R.string.personal_goal), task.goal)
                if (task.detail.isNotBlank() && task.detail != task.goal && task.detail != task.result) {
                    PersonalTaskSection(stringResource(R.string.personal_progress), task.displayDetail(context))
                }
                task.displayResult(context)?.takeIf { it.isNotBlank() }?.let { PersonalTaskSection(stringResource(R.string.personal_result), it) }
            }
            HorizontalDivider(color = c.hair)
            MikaButton(
                if (task.status == "needs_you") stringResource(R.string.personal_handle_task) else stringResource(R.string.personal_view_execution),
                onOpen, Modifier.fillMaxWidth().padding(top = MikaSpace.S12, bottom = MikaSpace.S12),
            )
            onReplay?.let {
                TextButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.personal_view_replay)) }
            }
            if (!task.terminal) {
                TextButton(onClick = onStop, Modifier.fillMaxWidth().padding(bottom = MikaSpace.S8)) {
                    Text(stringResource(R.string.personal_stop_task), color = c.bad)
                }
            }
        }
    }
}

@Composable
private fun PersonalTaskSection(label: String, body: String) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().background(c.fill, MikaShapes.Card).padding(MikaSpace.S16),
        verticalArrangement = Arrangement.spacedBy(MikaSpace.S8)) {
        Text(label, style = MikaType.Label, color = c.ink2)
        MarkdownText(body, style = MikaType.Body.copy(color = c.ink))
    }
}
