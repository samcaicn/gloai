package com.wangzan101.mika.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.mika.R
import com.wangzan101.mika.about.UpdateMirror
import com.wangzan101.mika.about.UpdateMirrorError
import com.wangzan101.mika.about.UpdateMirrorStore
import com.wangzan101.mika.about.UpdateMirrors
import com.wangzan101.mika.about.UpdateProbe
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.ReorderableRow
import com.wangzan101.mika.ui.components.rememberReorderableRowsState
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 › 关于 › 更新镜像:总开关、可拖动排序的镜像列表、增删改和「测试」。
 * GitHub 官方固定排在最后,不在此列表里,也不能删。检查更新和下载都按这里的顺序尝试。
 */
@Composable
fun UpdateMirrorsScreen(store: UpdateMirrorStore, onBack: () -> Unit) {
    var ready by remember(store) { mutableStateOf(false) }
    LaunchedEffect(store) {
        withContext(Dispatchers.IO) { store.ensureLoaded() }
        ready = true
    }
    if (!ready) {
        PageScaffold(stringResource(R.string.about_mirror_sources), onBack) {
            CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp), color = MikaTheme.colors.accent)
        }
        return
    }
    val mirrors by store.mirrors.collectAsState()
    val enabled by store.enabled.collectAsState()
    val unreadable by store.unreadable.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<UpdateMirror?>(null) }
    var deleting by remember { mutableStateOf<UpdateMirror?>(null) }
    var resetting by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<UpdateMirrorError?>(null) }
    val scope = rememberCoroutineScope()
    val c = MikaTheme.colors
    val scroll = rememberScrollState()
    val reorder = rememberReorderableRowsState(
        ids = mirrors.map { it.id }, scroll = scroll,
        onStart = { actionError = null },
        onSave = { order ->
            val error = withContext(Dispatchers.IO) { store.reorder(order) }
            actionError = error
            error == null
        },
    )

    PageScaffold(
        stringResource(R.string.about_mirror_sources), onBack, scrollState = scroll,
        viewportModifier = Modifier.onGloballyPositioned { reorder.viewport = it.boundsInWindow() },
    ) {
        GroupCard {
            ListRow(
                MikaIcons.Refresh,
                stringResource(R.string.about_mirror_download),
                stringResource(R.string.about_mirror_download_detail),
                first = true,
            ) {
                // 列表读不出来时不能改开关,不显示一个点了没反应的控件。
                if (!unreadable) {
                    MikaSwitch(enabled, { on ->
                        scope.launch {
                            actionError = withContext(Dispatchers.IO) { store.setEnabled(on) }
                        }
                    }, Modifier.testTag("mirrors-switch"))
                }
            }
        }

        if (unreadable) {
            FooterNote(stringResource(R.string.about_mirrors_unreadable), color = c.bad)
            MikaButton(
                stringResource(R.string.about_mirrors_reset), { resetting = true },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp), kind = ButtonKind.Tonal,
            )
        } else {
            GroupCard {
                if (mirrors.isEmpty()) {
                    ListRow(
                        MikaIcons.Link,
                        stringResource(R.string.about_mirrors_empty),
                        stringResource(R.string.about_mirrors_empty_sub),
                        first = true,
                    )
                } else {
                    mirrors.forEachIndexed { index, mirror ->
                        key(mirror.id) {
                            ReorderableRow(reorder, mirror.id, index, "mirror-drag-${mirror.id}") { first, dragHandle ->
                                ListRow(
                                    MikaIcons.Link, mirror.name, mirror.prefix, first = first,
                                    onClick = { if (!reorder.busy) editing = mirror },
                                ) {
                                    DeleteMirrorButton { if (!reorder.busy) deleting = mirror }
                                    dragHandle()
                                }
                            }
                        }
                    }
                }
                ListRow(
                    MikaIcons.Plus, stringResource(R.string.about_mirrors_add),
                    first = false, onClick = { if (!reorder.busy) adding = true },
                )
            }

            GroupCard {
                ListRow(
                    MikaIcons.Shield,
                    stringResource(R.string.about_mirrors_official),
                    stringResource(R.string.about_mirrors_official_sub),
                    first = true,
                )
            }

            actionError?.let { FooterNote(mirrorError(it), color = c.bad) }
            FooterNote(stringResource(R.string.about_mirrors_intro))
            if (mirrors.size > 1) FooterNote(stringResource(R.string.recommendations_drag_hint))
            MikaButton(
                stringResource(R.string.about_mirrors_reset), { resetting = true },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), kind = ButtonKind.Tonal,
            )
        }
    }

    if (adding || editing != null) {
        MirrorDialog(store, editing, onDismiss = { adding = false; editing = null })
    }
    deleting?.let { mirror ->
        MikaDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.about_mirrors_delete_title)) },
            text = { Text(stringResource(R.string.about_mirrors_delete_body)) },
            confirmButton = {
                TextButton({
                    scope.launch {
                        val error = withContext(Dispatchers.IO) { store.delete(mirror.id) }
                        if (error == null) deleting = null else actionError = error
                    }
                }, Modifier.testTag("mirror-delete")) { Text(stringResource(R.string.about_mirrors_delete)) }
            },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (resetting) {
        MikaDialog(
            onDismissRequest = { resetting = false },
            title = { Text(stringResource(R.string.about_mirrors_reset_title)) },
            text = { Text(stringResource(R.string.about_mirrors_reset_body)) },
            confirmButton = {
                TextButton({
                    scope.launch {
                        val error = withContext(Dispatchers.IO) { store.resetDefaults() }
                        if (error == null) { resetting = false; actionError = null } else actionError = error
                    }
                }, Modifier.testTag("mirrors-reset")) { Text(stringResource(R.string.about_mirrors_reset)) }
            },
            dismissButton = { TextButton({ resetting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun MirrorDialog(store: UpdateMirrorStore, initial: UpdateMirror?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var prefix by remember { mutableStateOf(initial?.prefix.orEmpty()) }
    var error by remember { mutableStateOf<UpdateMirrorError?>(null) }
    var saving by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    val c = MikaTheme.colors
    val prefixOk = UpdateMirrors.normalizePrefix(prefix) != null

    fun save() {
        if (saving || !prefixOk) return
        saving = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (initial == null) store.add(name, prefix) else store.update(initial.id, name, prefix)
            }
            saving = false
            if (result == null) onDismiss() else error = result
        }
    }

    fun test() {
        if (testing) return
        val normalized = UpdateMirrors.normalizePrefix(prefix) ?: return
        testing = true
        testResult = null
        scope.launch {
            val found = withContext(Dispatchers.IO) { UpdateProbe.test(normalized) }
            if (UpdateMirrors.normalizePrefix(prefix) == normalized) testResult = found != null
            testing = false
        }
    }

    MikaDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(if (initial == null) R.string.about_mirrors_add else R.string.about_mirrors_edit)) },
        text = {
            Column {
                OutlinedTextField(
                    name, { name = it; error = null },
                    label = { Text(stringResource(R.string.about_mirrors_name)) },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    prefix, { prefix = it; error = null; testResult = null },
                    label = { Text(stringResource(R.string.about_mirrors_prefix)) },
                    placeholder = { Text("https://") },
                    singleLine = true,
                    isError = prefix.isNotBlank() && !prefixOk,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (prefix.isNotBlank() && !prefixOk) {
                    Text(
                        stringResource(R.string.about_mirrors_invalid_prefix),
                        Modifier.padding(top = 4.dp), style = MikaType.Hint, color = c.bad,
                    )
                }
                error?.let {
                    Text(mirrorError(it), Modifier.padding(top = 8.dp), style = MikaType.Hint, color = c.bad)
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ test() }, enabled = prefixOk && !testing && !saving, modifier = Modifier.testTag("mirror-test")) {
                        Text(stringResource(if (testing) R.string.about_mirrors_testing else R.string.about_mirrors_test))
                    }
                    testResult?.let { ok ->
                        Text(
                            stringResource(if (ok) R.string.about_mirrors_test_ok else R.string.about_mirrors_test_fail),
                            Modifier.padding(start = 4.dp), style = MikaType.Hint,
                            color = if (ok) c.ok else c.ink3,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ save() }, Modifier.testTag("mirror-save"), enabled = !saving && prefixOk) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton({ if (!saving) onDismiss() }) { Text(stringResource(R.string.cancel)) } },
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}

/** 行尾的删除。单独可点,不会连着打开编辑。 */
@Composable
private fun DeleteMirrorButton(onClick: () -> Unit) {
    val label = stringResource(R.string.about_mirrors_delete)
    Box(
        Modifier.size(MikaSize.IconButton).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.Trash, label, Modifier.size(MikaSize.Icon), tint = MikaTheme.colors.ink3)
    }
}

@Composable
private fun mirrorError(error: UpdateMirrorError): String = when (error) {
    UpdateMirrorError.InvalidName -> stringResource(R.string.about_mirrors_invalid_name)
    UpdateMirrorError.InvalidPrefix -> stringResource(R.string.about_mirrors_invalid_prefix)
    UpdateMirrorError.Duplicate -> stringResource(R.string.about_mirrors_duplicate)
    UpdateMirrorError.TooMany -> stringResource(R.string.about_mirrors_too_many, UpdateMirrors.MAX)
    UpdateMirrorError.SaveFailed -> stringResource(R.string.about_mirrors_save_failed)
    UpdateMirrorError.Unreadable -> stringResource(R.string.about_mirrors_unreadable)
}
