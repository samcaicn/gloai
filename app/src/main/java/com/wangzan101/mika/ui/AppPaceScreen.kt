package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.core.cap.phone.OperatingPace
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设置 › 干活时 › 操作节奏 › 按应用设置:列表里每个 App 单独一档(快/标准/像人),可增可改可删;
 * 没列入的跟随全局档位。第一次打开时内置 careful App 已预置为「像人」(见 [FeatureFlags.appPaceOverrides])。
 */
@Composable
fun AppPaceScreen(global: OperatingPace, flags: FeatureFlags, onBack: () -> Unit) {
    val context = LocalContext.current
    var overrides by remember { mutableStateOf<Map<String, OperatingPace>?>(null) }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    // 打开页面就读一次(没初始化过会把内置 careful App 预置进去);本机应用清单另读,不用发给任何模型
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { flags.appPaceOverrides() }.getOrElse { emptyMap() }.let { overrides = it }
            apps = runCatching { launchableApps(context.packageManager, context.packageName) }.getOrElse { emptyList() }
        }
    }
    fun apply(next: Map<String, OperatingPace>) {
        overrides = next
        flags.setAppPaceOverrides(next)
    }

    PageScaffold(stringResource(R.string.settings_pace_per_app), onBack) {
        FooterNote(stringResource(R.string.settings_pace_per_app_intro, paceName(global)))
        val list = overrides.orEmpty()
        if (list.isEmpty()) {
            FooterNote(stringResource(R.string.settings_pace_per_app_empty))
        } else {
            GroupCard {
                list.entries.sortedBy { it.key }.forEachIndexed { i, (pkg, pace) ->
                    val label = apps.firstOrNull { it.first == pkg }?.second ?: pkg
                    ListRow(
                        null, label, pkg, first = i == 0,
                        detail = paceName(pace),
                        onClick = { editing = pkg },
                    )
                }
            }
        }
        TextButton({ adding = true }) { Text(stringResource(R.string.settings_pace_per_app_add)) }
    }
    if (adding) AppPacePickerDialog(
        installed = apps,
        already = overrides.orEmpty().keys,
        onDismiss = { adding = false },
        onPick = { pkg ->
            adding = false
            editing = pkg
        },
    )
    editing?.let { pkg ->
        val current = overrides.orEmpty()[pkg]
        MikaDialog(
            onDismissRequest = { editing = null },
            title = { Text(apps.firstOrNull { it.first == pkg }?.second ?: pkg) },
            text = {
                Column {
                    OperatingPace.entries.forEach { p ->
                        RadioRow(paceName(p), selected = p == current, onClick = {
                            apply(overrides.orEmpty() + (pkg to p))
                            editing = null
                        })
                    }
                    RadioRow(stringResource(R.string.settings_pace_per_app_remove), selected = false, onClick = {
                        apply(overrides.orEmpty() - pkg)
                        editing = null
                    })
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** 添加 App:本机装了的 App 里搜一个(包名或名字),已设置过的不再出现。 */
@Composable
private fun AppPacePickerDialog(
    installed: List<Pair<String, String>>,
    already: Set<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    val candidates = remember(installed, already, search) {
        installed.filter { (pkg, label) -> pkg !in already && (pkg.contains(search, true) || label.contains(search, true)) }
    }
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_pace_per_app_add)) },
        text = {
            Column {
                OutlinedTextField(
                    search, { search = it }, singleLine = true,
                    label = { Text(stringResource(R.string.settings_pace_per_app_search)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (candidates.isEmpty()) FooterNote(stringResource(R.string.settings_pace_per_app_none))
                    candidates.forEach { (pkg, label) ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) { Text(label); FooterNote(pkg) }
                            TextButton({ onPick(pkg) }) { Text(stringResource(R.string.settings_pace_per_app_pick)) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun paceName(p: OperatingPace): String = stringResource(
    when (p) {
        OperatingPace.FAST -> R.string.settings_pace_fast
        OperatingPace.STANDARD -> R.string.settings_pace_standard
        OperatingPace.HUMAN -> R.string.settings_pace_human
    },
)
