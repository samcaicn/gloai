package com.wangzan101.mika.files.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import com.wangzan101.mika.chat.ui.ChatCopy
import com.wangzan101.mika.chat.ui.ChatTarget
import com.wangzan101.mika.chat.ui.ChatViewModel
import com.wangzan101.mika.chat.ui.sourceLabel
import com.wangzan101.mika.memory.ui.Row
import com.wangzan101.mika.settings.ui.models.listed

internal data class LibraryRow(
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
