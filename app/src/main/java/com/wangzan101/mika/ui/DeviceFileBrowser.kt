package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.chat.WorkspaceResolver
import com.wangzan101.mika.core.cap.devicefiles.DeviceFileEntry
import com.wangzan101.mika.core.cap.devicefiles.DeviceFileSearchQuery
import com.wangzan101.mika.core.cap.devicefiles.DeviceFilesPort
import com.wangzan101.mika.settings.DeviceDirectoryStore
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.AndroidDeviceFiles
import com.wangzan101.mika.task.DeviceImportRecord
import com.wangzan101.mika.task.DeviceImportStore
import com.wangzan101.mika.ui.components.ActivitySizedDialog
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.DirectionalIcon
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.FieldClearButton
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

internal data class DeviceBreadcrumb(val name: String, val path: String)

internal fun crumbsIntoDirectory(
    currentCrumbs: List<DeviceBreadcrumb>,
    currentPath: String?,
    entry: DeviceFileEntry,
): List<DeviceBreadcrumb> {
    if (currentPath == null) return currentCrumbs + DeviceBreadcrumb(entry.name, entry.path)
    val trimmedRoot = currentPath.trimEnd('/')
    if (!entry.path.startsWith("$trimmedRoot/")) return currentCrumbs + DeviceBreadcrumb(entry.name, entry.path)
    val segments = entry.path.removePrefix(trimmedRoot).trimStart('/').split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return currentCrumbs
    var acc = trimmedRoot
    return currentCrumbs + segments.map { seg ->
        acc = "$acc/$seg"
        DeviceBreadcrumb(seg, acc)
    }
}

internal fun relativeSubdir(entryPath: String, rootPath: String?): String? {
    if (rootPath == null) return null
    val trimmedRoot = rootPath.trimEnd('/')
    val parent = entryPath.substringBeforeLast('/', "")
    if (parent != trimmedRoot && !parent.startsWith("$trimmedRoot/")) return null
    return parent.removePrefix(trimmedRoot).trimStart('/').takeIf { it.isNotEmpty() }
}

@Composable
internal fun DeviceFileBrowser(vm: ChatViewModel, conversationId: String, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val directories = remember(context) { DeviceDirectoryStore.get(context) }
    val importStore = remember(context) { DeviceImportStore.get(context) }
    val files = remember(context) {
        AndroidDeviceFiles(
            PhoneServices.of(context).hub,
            SandboxDirs(context),
            AndroidSandbox(context, SandboxState.get(context)),
            directories,
            importStore,
            resolveWorkspace = WorkspaceResolver.resolver(context),
        )
    }
    val records by remember(importStore) {
        importStore.recordsFlow().catch { emit(emptyList()) }
    }.collectAsState(initial = emptyList())
    val workspaceId = remember(context, conversationId) { WorkspaceResolver.resolveFor(context, conversationId).wire }
    DeviceFileBrowserDialog(
        files = files,
        loadRootDescriptions = { authorized ->
            withContext(Dispatchers.IO) {
                directories.directories().filter { it.path in authorized }.associate { it.path to it.description }
            }
        },
        records = records.filter { it.workspaceId == workspaceId }.asReversed(),
        onAddSelected = { selectedPaths ->
            if (FeatureFlags.get(context).deviceFiles()) {
                vm.importDeviceAttachments(conversationId, selectedPaths, files::importFile)
            }
            onClose()
        },
        onClose = onClose,
    )
}

@Composable
internal fun DeviceFileBrowserDialog(
    files: DeviceFilesPort,
    loadRootDescriptions: suspend (Set<String>) -> Map<String, String> = { emptyMap() },
    records: List<DeviceImportRecord> = emptyList(),
    onAddSelected: (List<String>) -> Unit,
    onClose: () -> Unit,
) {
    var backSignal by remember { mutableIntStateOf(0) }
    ActivitySizedDialog(onDismiss = { backSignal++ }, dismissOnClickOutside = false) { safeArea ->
        DeviceFileBrowserScreen(
            files = files,
            loadRootDescriptions = loadRootDescriptions,
            records = records,
            backSignal = backSignal,
            safeAreaModifier = safeArea,
            onAddSelected = onAddSelected,
            onClose = onClose,
        )
    }
}

@Composable
internal fun DeviceFileBrowserScreen(
    files: DeviceFilesPort,
    loadRootDescriptions: suspend (Set<String>) -> Map<String, String> = { emptyMap() },
    records: List<DeviceImportRecord> = emptyList(),
    backSignal: Int = 0,
    safeAreaModifier: Modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars).imePadding(),
    onAddSelected: (List<String>) -> Unit,
    onClose: () -> Unit,
) {
    val c = MikaTheme.colors
    var crumbs by remember { mutableStateOf<List<DeviceBreadcrumb>>(emptyList()) }
    val path = crumbs.lastOrNull()?.path
    var entries by remember { mutableStateOf<List<DeviceFileEntry>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var rootDescriptions by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf(false) }
    var request by remember { mutableIntStateOf(0) }
    var append by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showHistory by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var activeQuery by remember { mutableStateOf<String?>(null) }
    var truncated by remember { mutableStateOf(false) }
    val dateFormatter = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    fun navigateTo(nextCrumbs: List<DeviceBreadcrumb>) {
        loading = true
        activeQuery = null
        query = ""
        crumbs = nextCrumbs
        entries = emptyList()
        cursor = null
        append = false
        truncated = false
    }

    fun clearSearch() {
        query = ""
        if (activeQuery != null) {
            loading = true
            activeQuery = null
            entries = emptyList()
            cursor = null
            append = false
            truncated = false
            request++
        }
    }

    fun triggerSearch() {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || path == null || loading) return
        loading = true
        activeQuery = trimmed
        entries = emptyList()
        cursor = null
        append = false
        truncated = false
        request++
    }

    fun handleBack() {
        when {
            showHistory -> showHistory = false
            activeQuery != null -> clearSearch()
            crumbs.isNotEmpty() -> navigateTo(crumbs.dropLast(1))
            else -> onClose()
        }
    }

    LaunchedEffect(backSignal) {
        if (backSignal > 0) handleBack()
    }

    LaunchedEffect(path, request) {
        loading = true
        error = false
        try {
            val search = activeQuery
            if (search != null && path != null) {
                val result = files.search(
                    DeviceFileSearchQuery(
                        path = path,
                        name = search,
                        extension = null,
                        modifiedAfter = null,
                        modifiedBefore = null,
                        maxDepth = 5,
                        maxEntries = 5000,
                        timeoutMs = 5000,
                        includeDirectories = true,
                    ),
                )
                entries = result.entries
                cursor = null
                truncated = result.truncated
            } else {
                val page = files.list(path, if (append) cursor else null, 128)
                if (path == null) {
                    val authorized = page.entries.map { it.path }.toSet()
                    rootDescriptions = loadRootDescriptions(authorized)
                }
                entries = if (append) entries + page.entries else page.entries
                cursor = page.nextCursor
                truncated = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = true
        }
        loading = false
    }

    PageBackground {
        Column(
            Modifier.fillMaxSize().then(safeAreaModifier).testTag("device-browser-dialog"),
        ) {
            DeviceBrowserTopBar(
                showHistory = showHistory,
                onBack = ::handleBack,
                onToggleHistory = { showHistory = !showHistory },
            )
            if (showHistory) {
                DeviceImportHistoryBody(
                    records = records,
                    dateFormatter = dateFormatter,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                DeviceBreadcrumbBar(
                    crumbs = crumbs,
                    enabled = !loading,
                    onSelectRoot = { if (crumbs.isNotEmpty()) navigateTo(emptyList()) },
                    onSelectIndex = { index ->
                        if (index < crumbs.lastIndex || activeQuery != null) {
                            navigateTo(crumbs.take(index + 1))
                        }
                    },
                )
                if (path != null) {
                    DeviceSearchBar(
                        query = query,
                        activeQuery = activeQuery,
                        loading = loading,
                        onQueryChange = { query = it.take(255) },
                        onSearch = ::triggerSearch,
                        onClear = ::clearSearch,
                    )
                }
                if (truncated) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(MikaShapes.Tile).background(c.warnSoft)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.device_browser_truncated),
                            style = MikaType.Hint, color = c.warn,
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(0.5.dp).background(c.hair))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        error -> {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    stringResource(R.string.device_browser_retry),
                                    style = MikaType.Body, color = c.bad, textAlign = TextAlign.Center,
                                )
                                MikaButton(
                                    text = stringResource(R.string.file_library_retry),
                                    onClick = { loading = true; request++ },
                                    kind = ButtonKind.Secondary,
                                    size = ButtonSize.Medium,
                                    enabled = !loading,
                                    modifier = Modifier.testTag("device-browser-retry"),
                                )
                            }
                        }
                        loading && entries.isEmpty() -> {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(26.dp), color = c.accent, strokeWidth = 2.5.dp)
                                EmptyNote(stringResource(R.string.file_library_loading))
                            }
                        }
                        !loading && entries.isEmpty() -> {
                            EmptyNote(stringResource(R.string.device_browser_empty))
                        }
                        else -> {
                            LazyColumn(
                                Modifier.fillMaxSize().testTag("device-browser-list"),
                                contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 8.dp),
                            ) {
                                itemsIndexed(entries, key = { _, item -> item.path }) { index, entry ->
                                    val isSelected = !entry.isDirectory && entry.path in selected
                                    val subtitle = when {
                                        entry.isDirectory && path == null -> rootDescriptions[entry.path]?.takeIf { it.isNotBlank() }
                                        else -> {
                                            val typeTag = if (!entry.isDirectory) fileBadge(entry.name).second else null
                                            val sizeLabel = entry.size?.takeIf { !entry.isDirectory }?.let { ChatViewModel.sizeLabel(it) }
                                            val timeLabel = entry.modifiedAt?.let { dateFormatter.format(Date(it)) }
                                            listOfNotNull(typeTag, sizeLabel, timeLabel).joinToString(" · ").takeIf { it.isNotEmpty() }
                                        }
                                    }
                                    val locationHint = if (activeQuery != null) relativeSubdir(entry.path, path) else null
                                    DeviceEntryRow(
                                        entry = entry,
                                        subtitle = subtitle,
                                        locationHint = locationHint,
                                        selected = isSelected,
                                        enabled = !loading && !error,
                                        divider = index > 0,
                                        onClick = {
                                            if (entry.isDirectory) {
                                                navigateTo(crumbsIntoDirectory(crumbs, path, entry))
                                            } else {
                                                selected = if (isSelected) selected - entry.path else selected + entry.path
                                            }
                                        },
                                    )
                                }
                                if (cursor != null && !loading) {
                                    item(key = "load-more") {
                                        Box(
                                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            MikaButton(
                                                text = stringResource(R.string.device_browser_more),
                                                onClick = { loading = true; append = true; request++ },
                                                kind = ButtonKind.Tonal,
                                                size = ButtonSize.Medium,
                                                modifier = Modifier.testTag("device-browser-more"),
                                            )
                                        }
                                    }
                                }
                                if (loading) {
                                    item(key = "loading-more") {
                                        Box(
                                            Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator(Modifier.size(24.dp), color = c.accent, strokeWidth = 2.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                DeviceBrowserBottomBar(
                    selectedCount = selected.size,
                    loading = loading,
                    onCancel = onClose,
                    onAdd = { onAddSelected(selected.toList()) },
                )
            }
        }
    }
}

@Composable
private fun DeviceBrowserTopBar(
    showHistory: Boolean,
    onBack: () -> Unit,
    onToggleHistory: () -> Unit,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 60.dp).padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIconButton(MikaIcons.Back, stringResource(R.string.back), onBack)
        Text(
            stringResource(R.string.device_browser_title),
            Modifier.weight(1f).padding(start = 4.dp, end = 8.dp),
            style = LibraryTitleStyle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.widthIn(max = 180.dp)
                .clip(MikaShapes.Chip)
                .background(if (showHistory) c.accentSoft else c.fill)
                .clickable(role = Role.Button, onClick = onToggleHistory)
                .testTag("device-browser-history-toggle")
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (showHistory) MikaIcons.Folder else MikaIcons.Clock,
                null,
                Modifier.size(17.dp),
                tint = if (showHistory) c.accent else c.ink2,
            )
            Text(
                stringResource(if (showHistory) R.string.device_browser_show_files else R.string.device_browser_history),
                style = MikaType.Label,
                color = if (showHistory) c.accent else c.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DeviceBreadcrumbBar(
    crumbs: List<DeviceBreadcrumb>,
    enabled: Boolean,
    onSelectRoot: () -> Unit,
    onSelectIndex: (Int) -> Unit,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(crumbs.size, scrollState.maxValue) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    Row(
        Modifier.fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("device-browser-breadcrumbs"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BreadcrumbChip(
            label = stringResource(R.string.device_browser_roots),
            current = crumbs.isEmpty(),
            enabled = enabled && crumbs.isNotEmpty(),
            onClick = onSelectRoot,
        )
        crumbs.forEachIndexed { index, crumb ->
            BreadcrumbSeparator()
            val isCurrent = index == crumbs.lastIndex
            BreadcrumbChip(
                label = crumb.name,
                current = isCurrent,
                enabled = enabled && !isCurrent,
                onClick = { onSelectIndex(index) },
            )
        }
    }
}

@Composable
private fun BreadcrumbChip(
    label: String,
    current: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = MikaTheme.colors
    val shape = MikaShapes.Tile
    Row(
        Modifier.clip(shape)
            .then(if (current) Modifier.background(c.accentSoft) else Modifier)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = if (current) 10.dp else 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (current) {
            Icon(MikaIcons.Folder, null, Modifier.size(14.dp), tint = c.accent)
        }
        Text(
            text = label,
            style = if (current) MikaType.Label else MikaType.Caption,
            color = if (current) c.accent else c.ink2,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun BreadcrumbSeparator() {
    DirectionalIcon(
        MikaIcons.Chevron,
        null,
        Modifier.size(12.dp).padding(horizontal = 1.dp),
        tint = MikaTheme.colors.ink3,
    )
}

@Composable
private fun DeviceSearchBar(
    query: String,
    activeQuery: String?,
    loading: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.weight(1f).height(40.dp).keepsKeyboard()
                .background(c.fill, MikaShapes.Button).padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(MikaIcons.Search, null, Modifier.size(18.dp), tint = c.ink3)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        stringResource(R.string.device_browser_search),
                        style = MikaType.Body, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().testTag("device-browser-search"),
                    singleLine = true,
                    textStyle = MikaType.Body.copy(color = c.ink, textDirection = TextDirection.Content),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                )
            }
            if (query.isNotEmpty() || activeQuery != null) {
                FieldClearButton(
                    description = stringResource(R.string.clear),
                    onClick = onClear,
                    modifier = Modifier.testTag("device-browser-search-clear"),
                )
            }
        }
        if (query.isNotBlank() && query.trim() != activeQuery) {
            MikaButton(
                text = stringResource(R.string.device_browser_search),
                onClick = onSearch,
                kind = ButtonKind.Primary,
                size = ButtonSize.Small,
                enabled = !loading,
                modifier = Modifier.testTag("device-browser-search-submit"),
            )
        } else if (activeQuery != null) {
            MikaButton(
                text = stringResource(R.string.device_browser_show_files),
                onClick = onClear,
                kind = ButtonKind.Tonal,
                size = ButtonSize.Small,
                enabled = !loading,
                modifier = Modifier.testTag("device-browser-search-reset"),
            )
        }
    }
}

@Composable
private fun DeviceEntryRow(
    entry: DeviceFileEntry,
    subtitle: String?,
    locationHint: String?,
    selected: Boolean,
    enabled: Boolean,
    divider: Boolean,
    onClick: () -> Unit,
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
                    if (entry.isDirectory) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    else Modifier.toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() }),
                )
                .testTag("device-browser-row")
                .padding(start = 10.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.isDirectory) {
                FolderTile()
            } else {
                FileTypeTile(entry.name)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                Text(
                    entry.name,
                    style = MikaType.BodyStrong, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        subtitle,
                        Modifier.padding(top = 3.dp),
                        style = MikaType.Hint, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!locationHint.isNullOrEmpty()) {
                    Text(
                        locationHint,
                        Modifier.padding(top = 2.dp),
                        style = MikaType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (entry.isDirectory) {
                Box(Modifier.width(40.dp).height(48.dp), contentAlignment = Alignment.Center) {
                    RowChevron()
                }
            } else {
                Box(Modifier.size(width = 48.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = null,
                        enabled = enabled,
                        modifier = Modifier.size(23.dp).testTag("device-browser-check"),
                        colors = CheckboxDefaults.colors(
                            checkedColor = c.accent,
                            uncheckedColor = c.ink3,
                            checkmarkColor = c.onAccent,
                            disabledCheckedColor = c.accent.copy(alpha = 0.4f),
                            disabledUncheckedColor = c.ink3.copy(alpha = 0.4f),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderTile() {
    val c = MikaTheme.colors
    Box(
        Modifier.size(width = 48.dp, height = 54.dp).clip(MikaShapes.Chip).background(c.fill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.Folder, null, Modifier.size(24.dp), tint = c.ink)
    }
}

@Composable
private fun DeviceImportHistoryBody(
    records: List<DeviceImportRecord>,
    dateFormatter: DateFormat,
    modifier: Modifier = Modifier,
) {
    val c = MikaTheme.colors
    Column(modifier) {
        Text(
            stringResource(R.string.device_browser_history_hint),
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            style = MikaType.Caption, color = c.ink3,
        )
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.hair))
        if (records.isEmpty()) {
            EmptyNote(stringResource(R.string.file_library_empty))
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().testTag("device-browser-history-list"),
                contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 12.dp),
            ) {
                itemsIndexed(records, key = { _, record -> record.id }) { index, record ->
                    val name = record.sourcePath.substringAfterLast('/')
                    val meta = listOf(
                        ChatViewModel.sizeLabel(record.size),
                        dateFormatter.format(Date(record.importedAt)),
                    ).joinToString(" · ")
                    Column(Modifier.fillMaxWidth()) {
                        if (index > 0) {
                            Box(Modifier.fillMaxWidth().padding(start = 70.dp, end = 4.dp).height(0.5.dp).background(c.hair))
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FileTypeTile(name)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(name, style = MikaType.BodyStrong, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(meta, Modifier.padding(top = 3.dp), style = MikaType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    record.workspacePath,
                                    Modifier.padding(top = 2.dp),
                                    style = MikaType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceBrowserBottomBar(
    selectedCount: Int,
    loading: Boolean,
    onCancel: () -> Unit,
    onAdd: () -> Unit,
) {
    val c = MikaTheme.colors
    val canAdd = selectedCount > 0 && !loading
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.hair))
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.height(48.dp).clip(MikaShapes.Button).background(c.fill)
                    .clickable(role = Role.Button, onClick = onCancel)
                    .testTag("device-browser-cancel")
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.device_browser_cancel), style = MikaType.BodyStrong, color = c.ink)
            }
            Row(
                Modifier.weight(1f).height(48.dp).clip(MikaShapes.Button)
                    .background(if (canAdd) c.accent else c.accent.copy(alpha = 0.35f))
                    .clickable(enabled = canAdd, role = Role.Button, onClick = onAdd)
                    .testTag("device-browser-add")
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    MikaIcons.ListPlus, null, Modifier.size(18.dp),
                    tint = if (canAdd) c.onAccent else c.onAccent.copy(alpha = 0.6f),
                )
                Text(
                    stringResource(R.string.device_browser_add, selectedCount),
                    Modifier.padding(start = 7.dp),
                    style = MikaType.BodyStrong,
                    color = if (canAdd) c.onAccent else c.onAccent.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
