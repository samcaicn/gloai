package com.wangzan101.mika.ui

import android.content.Context
import android.text.format.DateFormat as AndroidDateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.chat.FileLibrary
import com.wangzan101.mika.chat.FileLibrarySnapshot
import com.wangzan101.mika.chat.FileSource
import com.wangzan101.mika.chat.LibraryFile
import com.wangzan101.mika.task.DeviceImportStore
import kotlinx.coroutines.flow.catch
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class LibraryRow(
    val key: String,
    val name: String,
    val size: String,
    val at: Long?,
    val conversations: List<String>,
    val input: Boolean,
    val handle: LibraryFileHandle,
    /** 最近一次出现的位置,「在对话中查看」跳到这里。 */
    val source: FileSource?,
    val deviceImport: Boolean = false,
) {
    val type = LibraryFileType.of(name)
}

private data class LibraryLoaded(
    val rows: List<LibraryRow>,
    val titles: Map<String, String>,
    /** 归档了的对话;文件行要标出「来自已归档对话」。 */
    val archived: Set<String>,
    val partial: Boolean,
    val failed: Boolean = false,
    /** 对话所属的项目编号,用于把项目名一并标出。 */
    val projectIds: Map<String, String> = emptyMap(),
)

/** 拼接对话显示名:属于项目时带上项目名,没项目时只显示对话标题。 */
internal fun libraryConversationDisplay(
    id: String,
    titles: Map<String, String>,
    projectIds: Map<String, String>,
    projectNames: Map<String, String>,
): String? {
    val title = titles[id] ?: return null
    val project = projectIds[id]?.let(projectNames::get)?.takeIf { it.isNotBlank() }
    return if (project != null) "$project / $title" else title
}

/** 「在对话中查看」跳到哪一行:输入文件回到附上它的那条用户消息,产物回到这一轮的文件卡片。 */
internal fun fileLibraryTarget(source: FileSource, input: Boolean): ChatTarget =
    if (input) ChatTarget.Message(source.turnIndex, source.steerIndex) else ChatTarget.Files(source.turnIndex)

private data class LibraryBatchResult(val outcome: LibraryBatchOutcome, val targetId: String)

internal data class LibraryLoadResult(
    val snapshot: FileLibrarySnapshot?,
    val titles: Map<String, String>,
    val archived: Set<String>,
    val partial: Boolean,
    val projectIds: Map<String, String> = emptyMap(),
)

/** 索引和会话标题分别读取，一边出错不能阻止另一边显示。取消仍交给协程处理。 */
internal fun loadLibraryData(
    snapshot: () -> FileLibrarySnapshot,
    summaries: () -> List<ConversationSummary>,
    draftTitles: Map<String, String>,
    fallbackTitle: (String) -> String?,
): LibraryLoadResult {
    var partial = false
    fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        partial = true
        null
    }
    val files = attempt(snapshot)
    val listed = attempt(summaries)
    val names = listed?.associate { it.id to ChatCopy.plainTitle(it.title) }?.toMutableMap() ?: draftTitles.toMutableMap()
    // 归档状态和标题来自同一次读取,不额外扫一遍对话目录
    val archived = listed.orEmpty().filter { it.archivedAt != null }.map { it.id }.toSet()
    val projectIds = listed.orEmpty().mapNotNull { s -> s.projectId?.let { s.id to it } }.toMap()
    draftTitles.forEach { (id, title) -> names.putIfAbsent(id, title) }
    files?.files?.forEach { file ->
        val ids = when (file) {
            is LibraryFile.Input -> file.sources.map { it.source.conversationId }
            is LibraryFile.Output -> file.sources.map { it.source.conversationId }
        }
        ids.distinct().forEach { id ->
            if (id !in names) names[id] = attempt { fallbackTitle(id) } ?: id
        }
    }
    return LibraryLoadResult(files, names, archived, partial || files?.hasReadFailures == true, projectIds)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FileLibraryScreen(
    vm: ChatViewModel,
    initialConversation: String?,
    fromDrawer: Boolean,
    onBack: () -> Unit,
    onAdded: (String) -> Boolean,
    /** 跳到文件的来源消息;返回会话是否已打开。 */
    onOpenSource: (String, ChatTarget) -> Boolean = { _, _ -> false },
    conversationStore: ConversationStore? = null,
    addBatch: suspend (Context, ChatViewModel, String, List<LibraryFileHandle>, (Int, Int) -> Unit) -> LibraryBatchOutcome =
        { context, viewModel, target, sources, progress -> LibraryFileActions.addAllToDraft(context, viewModel, target, sources, progress) },
    shareBatch: suspend (Context, List<LibraryFileHandle>, (Int, Int) -> Unit) -> LibraryShareOutcome =
        { context, sources, progress -> LibraryFileActions.shareAll(context, sources, progress) },
) {
    val context = LocalContext.current
    val store = remember(conversationStore) { conversationStore ?: ConversationStore.shared(context) }
    val imports = remember(context) { DeviceImportStore.get(context) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(store) { store.changes.collect { revision++ } }
    LaunchedEffect(imports) { imports.recordsFlow().catch { revision++ }.collect { revision++ } }
    val drafts by vm.draftConversations.collectAsState()
    val loaded by produceState<LibraryLoaded?>(null, revision, drafts) {
        value = try { withContext(Dispatchers.IO) {
            val result = loadLibraryData(
                snapshot = { FileLibrary(store).snapshot() },
                summaries = { vm.withDrafts(store.list(includeArchived = true)) },
                draftTitles = drafts.associate { it.id to ChatCopy.plainTitle(it.title) },
                fallbackTitle = { id -> store.title(id)?.let(ChatCopy::plainTitle) },
            )
            var importReadFailed = false
            val importRecords = try { imports.allRecords() } catch (_: Exception) {
                importReadFailed = true
                emptyList()
            }
            val rows = result.snapshot?.files.orEmpty().mapNotNull { file ->
                when (file) {
                    is LibraryFile.Input -> file.sources.maxByOrNull { it.source.atMs ?: Long.MIN_VALUE }?.let { latest ->
                        LibraryRow(
                            LibrarySelection.inputKey(latest.source.conversationId, file.path),
                            file.name, latest.sizeLabel, file.latestAtMs,
                            file.sources.map { it.source.conversationId }.distinct(), true,
                            LibraryFileHandle.Workspace(latest.source.conversationId, file.path),
                            latest.source,
                        )
                    }
                    is LibraryFile.Output -> file.sources.maxByOrNull { it.source.atMs ?: Long.MIN_VALUE }?.let { latest ->
                        LibraryRow(
                            LibrarySelection.outputKey(latest.file, latest.source),
                            latest.file.name, ChatViewModel.sizeLabel(latest.file.size), file.latestAtMs,
                            file.sources.map { it.source.conversationId }.distinct(), false, LibraryFileHandle.Exported(latest.file),
                            latest.source,
                        )
                    }
                }
            } + importRecords.map { record ->
                LibraryRow(
                    "device-import:${record.id}", record.workspacePath.substringAfterLast('/'),
                    ChatViewModel.sizeLabel(record.size), record.importedAt, listOf(record.conversationId), true,
                    LibraryFileHandle.Workspace(record.conversationId, record.workspacePath), null, deviceImport = true,
                )
            }
            val titles = result.titles.toMutableMap()
            importRecords.forEach { record -> titles.putIfAbsent(record.conversationId, record.conversationId) }
            LibraryLoaded(rows.sortedByDescending { it.at ?: Long.MIN_VALUE }, titles, result.archived,
                result.partial || importReadFailed, failed = result.snapshot == null && importReadFailed, projectIds = result.projectIds)
        } } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LibraryLoaded(emptyList(), emptyMap(), emptySet(), partial = false, failed = true)
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var conversation by rememberSaveable { mutableStateOf(if (fromDrawer) null else initialConversation ?: "") }
    var source by rememberSaveable { mutableIntStateOf(0) }
    var type by rememberSaveable { mutableStateOf<LibraryFileType?>(null) }
    var conversationMenu by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<LibraryRow?>(null) }
    var actionsFor by remember { mutableStateOf<LibraryRow?>(null) }
    var actionsAvailable by remember { mutableStateOf<Boolean?>(null) }
    var choosingBatch by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var shareWorking by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }
    var error by remember { mutableStateOf<Int?>(null) }
    var savedElsewhere by remember { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedList by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var batchResult by remember { mutableStateOf<LibraryBatchResult?>(null) }
    var shareResult by remember { mutableStateOf<LibraryShareOutcome?>(null) }
    val selected = selectedList.toSet()
    val allRows = loaded?.rows.orEmpty()
    val rows = allRows.filter {
        it.name.contains(query.trim(), ignoreCase = true) &&
            (conversation == null || conversation in it.conversations) &&
            (source == 0 || (source == 1) == it.input) &&
            (type == null || type == it.type)
    }
    val titles = loaded?.titles.orEmpty()
    val projectIds = loaded?.projectIds.orEmpty()
    val projects by vm.projects.collectAsState()
    val projectNames = remember(projects) { projects.associate { it.id to it.name } }
    fun displayTitle(id: String): String? = libraryConversationDisplay(id, titles, projectIds, projectNames)
    val archivedIds = loaded?.archived.orEmpty()
    val rowKeys = allRows.map { it.key }
    LaunchedEffect(rowKeys) {
        if (selectedList.any { it !in rowKeys }) selectedList = selectedList.filter { it in rowKeys }
        if (selecting && rowKeys.isEmpty()) {
            selecting = false
            selectedList = emptyList()
        }
    }
    fun exitSelection() {
        selecting = false
        selectedList = emptyList()
    }
    BackHandler { if (selecting && !working) exitSelection() else onBack() }
    fun add(item: LibraryRow, id: String) {
        scope.launch {
            working = true
            val result = LibraryFileActions.addToDraft(context, vm, id, item.handle)
            working = false
            if (result == LibraryAttachmentResult.Added) savedElsewhere = !onAdded(id) else error = R.string.file_library_error
        }
    }
    /** 单行「加入待发栏」:从抽屉进来先选会话,从会话进来直接加回该会话。 */
    fun addOne(item: LibraryRow) {
        if (working) return
        when {
            fromDrawer -> choosing = item
            initialConversation != null -> add(item, initialConversation)
            vm.ui.value.conversationId == null -> add(item, vm.libraryTargetConversation())
            else -> error = R.string.file_library_error
        }
    }
    fun preview(item: LibraryRow) {
        if (working) return
        scope.launch {
            working = true
            error = if (!LibraryFileActions.preview(context, item.handle)) R.string.file_library_error else null
            working = false
        }
    }
    /** 跳到文件最近一次出现的消息:输入文件回到那条用户消息,产物回到这一轮的文件卡片。 */
    fun openInChat(item: LibraryRow) {
        if (working) return
        val source = item.source ?: return
        if (!onOpenSource(source.conversationId, fileLibraryTarget(source, item.input))) {
            error = R.string.file_library_open_failed
        }
    }
    fun shareOne(item: LibraryRow) {
        if (working) return
        scope.launch {
            working = true
            error = if (!LibraryFileActions.share(context, item.handle)) R.string.file_library_error else null
            working = false
        }
    }
    fun enterSelection(item: LibraryRow) {
        if (working) return
        selecting = true
        selectedList = listOf(item.key)
    }
    fun addSelected(targetId: String) {
        if (working) return
        val items = allRows.filter { it.key in selected }
        if (items.isEmpty()) return
        scope.launch {
            working = true
            shareWorking = false
            progress = 0 to items.size
            val outcome = addBatch(context, vm, targetId, items.map { it.handle }) { done, total -> progress = done to total }
            working = false
            if (outcome.unavailable) {
                error = R.string.file_library_target_unavailable
                return@launch
            }
            if (outcome.failed.isEmpty() && outcome.overLimit == 0 && outcome.accepted > 0) {
                exitSelection()
                if (!onAdded(targetId)) savedElsewhere = true
            } else {
                batchResult = LibraryBatchResult(outcome, targetId)
            }
        }
    }
    fun shareSelected() {
        if (working) return
        val items = allRows.filter { it.key in selected }
        if (items.isEmpty()) return
        scope.launch {
            working = true
            shareWorking = true
            progress = 0 to items.size
            val outcome = shareBatch(context, items.map { it.handle }) { done, total -> progress = done to total }
            working = false
            shareWorking = false
            if (outcome.failed.isNotEmpty()) shareResult = outcome
        }
    }
    fun chooseTarget() {
        if (working) return
        when {
            fromDrawer -> choosingBatch = true
            initialConversation != null -> addSelected(initialConversation)
            else -> addSelected(vm.libraryTargetConversation())
        }
    }
    val sourceLabel = stringResource(when (source) {
        1 -> R.string.file_library_input
        2 -> R.string.file_library_output
        else -> R.string.file_library_sources
    })
    val conversationLabel = conversation?.let { displayTitle(it) ?: stringResource(R.string.file_library_current) }
        ?: stringResource(R.string.file_library_all)
    val typeLabel = stringResource(type?.label ?: R.string.file_library_type_all)
    PageBackground {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            if (selecting) {
                LibrarySelectionTopBar(
                    count = selectedList.size,
                    allSelected = rows.isNotEmpty() && rows.all { it.key in selected },
                    canSelectAll = rows.isNotEmpty(),
                    working = working,
                    onCancel = { exitSelection() },
                    onSelectAll = { selectedList = LibrarySelection.toggleAll(selectedList.toSet(), rows.map { it.key }).toList() },
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                    Text(
                        "$conversationLabel · $sourceLabel · $typeLabel", Modifier.weight(1f).testTag("file-library-scope"),
                        style = MikaType.Hint, color = MikaTheme.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.file_library_file_count, rows.size), Modifier.padding(start = 12.dp),
                        style = MikaType.Hint, color = MikaTheme.colors.ink3, maxLines = 1,
                    )
                }
            } else {
                LibraryTopBar(
                    canSelect = rows.isNotEmpty(),
                    working = working,
                    onBack = onBack,
                    onSelect = { selecting = true },
                )
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SearchField(query, { query = it }, stringResource(R.string.file_library_search), Modifier.testTag("file-library-search"))
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val pillMaxWidth = ((LocalConfiguration.current.screenWidthDp.dp - 32.dp - 10.dp) / 2).coerceAtLeast(120.dp)
                        LibraryFilterPill(
                            label = conversationLabel,
                            active = conversation != null,
                            expanded = conversationMenu,
                            onToggle = { conversationMenu = true },
                            onDismiss = { conversationMenu = false },
                            modifier = Modifier.widthIn(max = pillMaxWidth),
                        ) {
                            FilterMenuItem(stringResource(R.string.file_library_all), conversation == null) {
                                conversation = null
                                conversationMenu = false
                            }
                            titles.forEach { (id, title) ->
                                FilterMenuItem(displayTitle(id) ?: title, conversation == id) {
                                    conversation = id
                                    conversationMenu = false
                                }
                            }
                            if (initialConversation != null && initialConversation !in titles) {
                                FilterMenuItem(stringResource(R.string.file_library_current), conversation == initialConversation) {
                                    conversation = initialConversation
                                    conversationMenu = false
                                }
                            }
                        }
                        LibraryFilterPill(
                            label = sourceLabel,
                            active = source != 0,
                            expanded = sourceMenu,
                            onToggle = { sourceMenu = true },
                            onDismiss = { sourceMenu = false },
                            modifier = Modifier.widthIn(max = pillMaxWidth),
                        ) {
                            listOf(R.string.file_library_sources, R.string.file_library_input, R.string.file_library_output).forEachIndexed { index, text ->
                                FilterMenuItem(stringResource(text), source == index) {
                                    source = index
                                    sourceMenu = false
                                }
                            }
                        }
                        LibraryFilterPill(
                            label = typeLabel,
                            active = type != null,
                            expanded = typeMenu,
                            onToggle = { typeMenu = true },
                            onDismiss = { typeMenu = false },
                            modifier = Modifier.widthIn(max = pillMaxWidth).testTag("file-library-type-filter"),
                        ) {
                            FilterMenuItem(stringResource(R.string.file_library_type_all), type == null) {
                                type = null
                                typeMenu = false
                            }
                            LibraryFileType.entries.forEach { option ->
                                FilterMenuItem(stringResource(option.label), type == option) {
                                    type = option
                                    typeMenu = false
                                }
                            }
                        }
                    }
                }
            }
            if (loaded?.partial == true && loaded?.failed == false) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.file_library_partial), Modifier.weight(1f).padding(vertical = 12.dp), style = MikaType.Hint, color = MikaTheme.colors.warn)
                    TextButton(onClick = { revision++ }) { Text(stringResource(R.string.file_library_retry)) }
                }
            }
            when {
                loaded == null -> EmptyNote(stringResource(R.string.file_library_loading))
                loaded?.failed == true -> {
                    Column {
                        EmptyNote(stringResource(R.string.file_library_load_failed))
                        TextButton(onClick = { revision++ }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.file_library_retry)) }
                    }
                }
                rows.isEmpty() -> EmptyNote(stringResource(R.string.file_library_empty))
                else -> {
                    val groups = rows.groupBy { row ->
                        row.at?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
                    }
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().testTag("file-library-list"),
                        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 8.dp),
                    ) {
                        groups.forEach { (day, members) ->
                            item(key = "day:${day ?: "unknown"}") {
                                LibraryDayHeader(dayLabel(members.first().at))
                            }
                            itemsIndexed(members, key = { _, item -> item.key }) { index, item ->
                                val available by produceState<Boolean?>(null, item.handle, revision) {
                                    value = null
                                    value = try {
                                        LibraryFileActions.isAvailable(context, item.handle)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        false
                                    }
                                }
                                LibraryFileRow(
                                    item = item,
                                    available = available,
                                    selected = item.key in selected,
                                    selecting = selecting,
                                    working = working,
                                    conversationLabel = item.conversations.joinToString(" · ") { displayTitle(it) ?: it },
                                    fromArchived = item.conversations.any { it in archivedIds },
                                    sourceLabel = stringResource(if (item.deviceImport) R.string.dev_device_files else if (item.input) R.string.file_library_input else R.string.file_library_output),
                                    divider = index > 0,
                                    onOpen = { preview(item) },
                                    onLongPress = { enterSelection(item) },
                                    onToggle = { selectedList = LibrarySelection.toggle(selectedList.toSet(), item.key).toList() },
                                    onMore = {
                                        actionsAvailable = available
                                        actionsFor = item
                                    },
                                )
                            }
                        }
                    }
                    if (!selecting) {
                        Text(
                            stringResource(R.string.file_library_file_count, rows.size),
                            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                            style = MikaType.Hint, color = MikaTheme.colors.ink3, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            if (selecting) {
                LibraryBatchBar(
                    count = selectedList.size,
                    working = working,
                    sharing = shareWorking,
                    done = progress.first,
                    total = progress.second,
                    onShare = { shareSelected() },
                    onAdd = { chooseTarget() },
                )
            }
        }
    }
    actionsFor?.let { item ->
        // 来源会话打不开(任务进行中且没开并行、又不是当前会话)时不给这一项
        val canOpenInChat = item.source?.let { it.conversationId == vm.ui.value.conversationId || vm.canSwitch() } == true
        val openInChatAction = SheetAction(
            stringResource(R.string.file_library_open_in_chat), icon = MikaIcons.Message,
        ) { openInChat(item) }
        val actions = if (actionsAvailable == false) buildList {
            add(SheetAction(stringResource(R.string.file_library_retry), icon = MikaIcons.Refresh) { revision++ })
            if (canOpenInChat) add(openInChatAction)
        } else buildList {
            add(SheetAction(stringResource(R.string.file_library_preview), icon = MikaIcons.Eye) { preview(item) })
            add(SheetAction(stringResource(R.string.file_library_add), icon = MikaIcons.ListPlus) { addOne(item) })
            add(SheetAction(stringResource(R.string.file_library_share), icon = MikaIcons.Share) { shareOne(item) })
            if (canOpenInChat) add(openInChatAction)
        }
        ActionSheet(title = item.name, actions = actions, onDismiss = { actionsFor = null })
    }
    choosing?.let { item ->
        AlertDialog(onDismissRequest = { choosing = null }, title = { Text(stringResource(R.string.file_library_choose)) }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                if (vm.canSwitch()) Text(stringResource(R.string.file_library_new), Modifier.fillMaxWidth().clickable {
                    choosing = null
                    add(item, store.newConversation())
                }.padding(vertical = 12.dp))
                titles.filter { (id, _) -> vm.canSwitch() || id == vm.ui.value.conversationId }.forEach { (id, title) -> Text(displayTitle(id) ?: title, Modifier.fillMaxWidth().clickable {
                    choosing = null
                    add(item, id)
                }.padding(vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (vm.ui.value.conversationId == null) Text(stringResource(R.string.file_library_current), Modifier.fillMaxWidth().clickable {
                    choosing = null
                    add(item, vm.libraryTargetConversation())
                }.padding(vertical = 12.dp))
            }
        }, confirmButton = { TextButton(onClick = { choosing = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (choosingBatch) {
        AlertDialog(onDismissRequest = { choosingBatch = false }, title = { Text(stringResource(R.string.file_library_choose)) }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                if (vm.canSwitch()) Text(stringResource(R.string.file_library_new), Modifier.fillMaxWidth().clickable {
                    choosingBatch = false
                    addSelected(store.newConversation())
                }.padding(vertical = 12.dp))
                titles.filter { (id, _) -> vm.canSwitch() || id == vm.ui.value.conversationId }.forEach { (id, title) -> Text(displayTitle(id) ?: title, Modifier.fillMaxWidth().clickable {
                    choosingBatch = false
                    addSelected(id)
                }.padding(vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (initialConversation != null && initialConversation !in titles) Text(stringResource(R.string.file_library_current), Modifier.fillMaxWidth().clickable {
                    choosingBatch = false
                    addSelected(initialConversation)
                }.padding(vertical = 12.dp))
                if (vm.ui.value.conversationId == null) Text(stringResource(R.string.file_library_current), Modifier.fillMaxWidth().clickable {
                    choosingBatch = false
                    addSelected(vm.libraryTargetConversation())
                }.padding(vertical = 12.dp))
            }
        }, confirmButton = { TextButton(onClick = { choosingBatch = false }) { Text(stringResource(R.string.cancel)) } })
    }
    batchResult?.let { result ->
        val outcome = result.outcome
        AlertDialog(
            onDismissRequest = { batchResult = null },
            title = { Text(stringResource(R.string.file_library_batch_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.file_library_batch_summary, outcome.added, outcome.duplicates, outcome.overLimit + outcome.failed.size))
                    if (outcome.failed.isNotEmpty()) {
                        val shown = outcome.failed.take(5).joinToString(stringResource(R.string.list_sep))
                        Text(
                            stringResource(R.string.file_library_batch_failed_names, if (outcome.failed.size > 5) "$shown…" else shown),
                            Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    batchResult = null
                    exitSelection()
                    if (outcome.accepted > 0 && !onAdded(result.targetId)) savedElsewhere = true
                }, modifier = Modifier.testTag("file-library-batch-done")) {
                    Text(stringResource(if (outcome.accepted > 0) R.string.file_library_view_draft else R.string.done))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    batchResult = null
                    selectedList = emptyList()
                    selecting = true
                }, modifier = Modifier.testTag("file-library-batch-continue")) {
                    Text(stringResource(R.string.file_library_keep_selecting))
                }
            },
        )
    }
    shareResult?.let { outcome ->
        AlertDialog(
            onDismissRequest = { shareResult = null },
            title = { Text(stringResource(R.string.file_library_share)) },
            text = {
                Column {
                    Text(stringResource(R.string.file_library_share_summary, outcome.shared, outcome.failed.size))
                    val shown = outcome.failed.take(5).joinToString(stringResource(R.string.list_sep))
                    Text(
                        stringResource(R.string.file_library_share_failed_names, if (outcome.failed.size > 5) "$shown…" else shown),
                        Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { shareResult = null }, modifier = Modifier.testTag("file-library-share-done")) {
                    Text(stringResource(R.string.done))
                }
            },
        )
    }
    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, text = { Text(stringResource(message)) }, confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.done)) } })
    }
    if (savedElsewhere) AlertDialog(onDismissRequest = { savedElsewhere = false }, text = { Text(stringResource(R.string.file_library_draft_saved)) }, confirmButton = { TextButton(onClick = { savedElsewhere = false }) { Text(stringResource(R.string.done)) } })
}

internal val LibraryTitleStyle = MikaType.LargeTitle.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)

/** 文件库顶栏:返回、左对齐大标题、右边「多选」胶囊。 */
@Composable
private fun LibraryTopBar(canSelect: Boolean, working: Boolean, onBack: () -> Unit, onSelect: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 64.dp).padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIconButton(MikaIcons.Back, stringResource(R.string.back), onBack)
        Text(
            stringResource(R.string.file_library_title), Modifier.weight(1f).padding(start = 4.dp),
            style = LibraryTitleStyle, color = c.ink, maxLines = 1,
        )
        Row(
            Modifier.clip(MikaShapes.Chip).background(c.fill)
                .clickable(enabled = canSelect && !working, role = Role.Button, onClick = onSelect)
                .testTag("file-library-select")
                .alpha(if (canSelect && !working) 1f else 0.4f)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(MikaIcons.ListChecks, null, Modifier.size(17.dp), tint = c.ink2)
            Text(stringResource(R.string.file_library_select), style = MikaType.Label, color = c.ink2)
        }
    }
}

/** 多选顶栏:左边取消、中间已选数量、右边全选 / 取消全选。 */
@Composable
private fun LibrarySelectionTopBar(
    count: Int,
    allSelected: Boolean,
    canSelectAll: Boolean,
    working: Boolean,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
) {
    val c = MikaTheme.colors
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(60.dp)) {
        Text(
            if (count == 0) stringResource(R.string.file_library_select_title) else stringResource(R.string.file_library_selected_count, count),
            Modifier.align(Alignment.Center).padding(horizontal = 92.dp).testTag("file-library-selected-count"),
            style = MikaType.Title, color = c.ink, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.fillMaxSize().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                onClick = onCancel, enabled = !working,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.testTag("file-library-select-cancel"),
            ) {
                Text(stringResource(R.string.cancel), style = MikaType.Body, color = if (working) c.ink3 else c.accent)
            }
            TextButton(
                onClick = onSelectAll, enabled = !working && canSelectAll,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.testTag("file-library-select-all"),
            ) {
                Text(
                    stringResource(if (allSelected) R.string.file_library_select_none else R.string.file_library_select_all),
                    style = MikaType.Body, color = if (working || !canSelectAll) c.ink3 else c.accent, maxLines = 1,
                )
            }
        }
    }
}

/** 筛选胶囊:描边圆角、内容宽度;展开时浮出一张圆角菜单,选中项用强调色和勾标出。 */
@Composable
private fun LibraryFilterPill(
    label: String,
    active: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = MikaTheme.colors
    val shape = RoundedCornerShape(MikaRadius.Bubble)
    Box(modifier) {
        Row(
            Modifier.heightIn(min = 40.dp).clip(shape)
                .border(1.dp, if (active || expanded) c.accent.copy(alpha = 0.55f) else c.edge, shape)
                .background(if (expanded) c.fill else Color.Transparent)
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                label, Modifier.weight(1f, fill = false),
                style = MikaType.Caption, color = if (active) c.accent else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Icon(MikaIcons.Chevron, null, Modifier.size(13.dp).rotate(90f), tint = if (active) c.accent else c.ink3)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            offset = DpOffset(0.dp, 6.dp),
            shape = MikaShapes.Card,
            containerColor = c.surface,
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(0.5.dp, c.edge),
        ) {
            Column(Modifier.padding(6.dp).widthIn(min = 200.dp, max = 280.dp), content = content)
        }
    }
}

/** 筛选菜单里的一行:选中时浅强调底、文字强调色、右端一个勾。 */
@Composable
private fun FilterMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(MikaShapes.Chip)
            .background(if (selected) c.accentSoft else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label, Modifier.weight(1f), style = MikaType.Body,
            color = if (selected) c.accent else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (selected) Icon(MikaIcons.Check, null, Modifier.size(16.dp), tint = c.accent)
        else Spacer(Modifier.size(16.dp))
    }
}

@Composable
private fun LibraryDayHeader(label: String) {
    Text(
        label, Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 13.dp, bottom = 5.dp),
        style = MikaType.Label, color = MikaTheme.colors.ink3,
    )
}

/** 日期分组标题,按系统语言显示月日。 */
@Composable
private fun dayLabel(at: Long?): String {
    if (at == null) return stringResource(R.string.file_library_unknown_time)
    val locale = Locale.getDefault()
    val formatter = remember(locale) {
        DateTimeFormatter.ofPattern(AndroidDateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
    }
    return formatter.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
}

/**
 * 文件行:左边类型块(多选时换成勾选框),中间名称、类型/大小/来源、会话,
 * 右边「…」更多操作(多选时是「预览」)。长按进入多选。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryFileRow(
    item: LibraryRow,
    available: Boolean?,
    selected: Boolean,
    selecting: Boolean,
    working: Boolean,
    conversationLabel: String,
    /** 这个文件至少有一个来源对话已经归档:会话名旁边标出来。 */
    fromArchived: Boolean,
    sourceLabel: String,
    divider: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggle: () -> Unit,
    onMore: () -> Unit,
) {
    val c = MikaTheme.colors
    val shape = MikaShapes.Chip
    Column(Modifier.fillMaxWidth()) {
        if (divider && !selected) {
            Box(Modifier.fillMaxWidth().padding(start = 70.dp, end = 4.dp).height(0.5.dp).background(c.hair))
        }
        Row(
            Modifier.fillMaxWidth().clip(shape)
                .then(if (selected) Modifier.background(c.accentSoft) else Modifier)
                .then(
                    if (selecting) Modifier.toggleable(
                        value = selected, enabled = !working, role = Role.Checkbox, onValueChange = { onToggle() },
                    ) else Modifier.combinedClickable(
                        enabled = !working, onClick = onOpen, onLongClick = onLongPress,
                    )
                )
                .testTag("file-library-row")
                .padding(start = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Box(Modifier.size(width = 48.dp, height = 54.dp), contentAlignment = Alignment.Center) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = null,
                        modifier = Modifier.size(23.dp).testTag("file-library-check"),
                        colors = CheckboxDefaults.colors(
                            checkedColor = c.accent,
                            uncheckedColor = c.ink3,
                            checkmarkColor = c.onAccent,
                        ),
                    )
                }
            } else {
                FileTypeTile(item.name)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                Text(item.name, style = MikaType.BodyStrong, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        fileBadge(item.name).second,
                        item.size.takeIf { it.isNotBlank() },
                        sourceLabel,
                        if (available == false) stringResource(R.string.file_library_missing) else null,
                    ).joinToString(" · "),
                    Modifier.padding(top = 3.dp),
                    style = MikaType.Hint, color = if (available == false) c.bad else c.ink3,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (conversationLabel.isNotBlank()) {
                    Row(
                        Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            conversationLabel, Modifier.weight(1f, fill = false),
                            style = MikaType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (fromArchived) ArchivedTag()
                    }
                }
            }
            if (selecting) {
                Box(
                    Modifier.width(56.dp).height(48.dp).clip(MikaShapes.Tile).clickable(enabled = !working, onClick = onOpen),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.file_library_preview), style = MikaType.Label, color = c.accent)
                }
            } else {
                Box(
                    Modifier.width(56.dp).height(48.dp).clip(MikaShapes.Tile).clickable(enabled = !working, onClick = onMore),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MikaIcons.More, stringResource(R.string.more), Modifier.size(20.dp), tint = c.ink3)
                }
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

/** 文件来自已归档的对话:会话名旁边的小标签,和侧栏里的归档图标一个意思。 */
@Composable
private fun ArchivedTag() {
    val c = MikaTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(MikaRadius.BubbleTail)).background(c.accentSoft).padding(horizontal = 5.dp, vertical = 1.dp)
            .testTag("file-library-archived"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(MikaIcons.Archive, null, Modifier.size(10.dp), tint = c.ink3)
        Text(stringResource(R.string.drawer_archived), style = MikaType.Hint, color = c.ink3, maxLines = 1)
    }
}

/** 文件类型块:上面一个图形,下面扩展名。 */
@Composable
internal fun FileTypeTile(name: String) {
    val c = MikaTheme.colors
    val (icon, label) = fileBadge(name)
    Column(
        Modifier.size(width = 48.dp, height = 54.dp).clip(MikaShapes.Chip).background(c.fill),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = c.ink2)
        Text(
            label, Modifier.padding(top = 2.dp),
            style = MikaType.Micro.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
            color = c.ink2, maxLines = 1,
        )
    }
}

/** 文件块的图形和扩展名;图片、代码、文档各一种图形。 */
internal fun fileBadge(name: String): Pair<ImageVector, String> {
    val ext = name.substringAfterLast('.', "").uppercase(Locale.ROOT)
    val icon = if (LibraryFileType.of(name) == LibraryFileType.Image) MikaIcons.Photo else when (ext) {
        "HTML", "HTM", "CSS", "JS", "JSX", "TS", "TSX", "JSON", "KT", "KTS", "JAVA", "PY", "SH",
        "XML", "YAML", "YML", "TOML", "GO", "RS", "RB", "C", "CPP", "H" -> MikaIcons.Code
        else -> MikaIcons.Doc
    }
    return icon to ext.take(4).ifBlank { "FILE" }
}

/** 多选底部操作条:次按钮分享,主按钮把所选加入待发栏;进行中显示进度。 */
@Composable
private fun LibraryBatchBar(
    count: Int,
    working: Boolean,
    sharing: Boolean,
    done: Int,
    total: Int,
    onShare: () -> Unit,
    onAdd: () -> Unit,
) {
    val c = MikaTheme.colors
    val enabled = count > 0 && !working
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.hair))
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)
                .testTag("file-library-select-bar"),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.height(48.dp).clip(MikaShapes.Button).background(c.fill)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onShare)
                    .testTag("file-library-share-selected")
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(MikaIcons.Share, null, Modifier.size(18.dp), tint = if (enabled) c.ink else c.ink3)
                Text(
                    stringResource(R.string.file_library_share), style = MikaType.BodyStrong,
                    color = if (enabled) c.ink else c.ink3,
                )
            }
            Row(
                Modifier.weight(1f).height(48.dp).clip(MikaShapes.Button)
                    .background(if (enabled) c.accent else c.accent.copy(alpha = 0.35f))
                    .clickable(enabled = enabled, role = Role.Button, onClick = onAdd)
                    .testTag("file-library-add-selected")
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (working) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.onAccent)
                    Text(
                        stringResource(if (sharing) R.string.file_library_sharing else R.string.file_library_adding, done, total),
                        Modifier.padding(start = 8.dp),
                        style = MikaType.Label, color = c.onAccent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Icon(
                        MikaIcons.ListPlus, null, Modifier.size(18.dp),
                        tint = if (enabled) c.onAccent else c.onAccent.copy(alpha = 0.6f),
                    )
                    Text(
                        if (count > 0) stringResource(R.string.file_library_add_selected, count) else stringResource(R.string.file_library_add),
                        Modifier.padding(start = 7.dp),
                        style = MikaType.BodyStrong,
                        color = if (enabled) c.onAccent else c.onAccent.copy(alpha = 0.6f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
