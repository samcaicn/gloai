package com.wangzan101.mika.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.core.device.access.*
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.modelFor
import com.wangzan101.mika.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wangzan101.mika.settings.ui.models.refresh

/** 分类名只在界面上出现,core 里不存中文;分类本身是 AI 判断的用途,不改变权限。 */
@Composable
private fun AppCategory.title(): String = stringResource(
    when (this) {
        AppCategory.Finance -> R.string.appaccess_category_finance
        AppCategory.Social -> R.string.appaccess_category_social
        AppCategory.Game -> R.string.appaccess_category_game
        AppCategory.Shopping -> R.string.appaccess_category_shopping
        AppCategory.Productivity -> R.string.appaccess_category_productivity
        AppCategory.Media -> R.string.appaccess_category_media
        AppCategory.Other -> R.string.appaccess_category_other
        AppCategory.Unknown -> R.string.appaccess_category_unknown
    },
)

/** 要显示给用户的失败:界面语言的一句话,后面可以带上原始说明方便排查。 */
private class Failure(val message: Int, val detail: String? = null)

/**
 * 分类时让模型用界面的语言写理由:同一个应用换语言后旧理由仍是旧语言,这没关系,下次分类会覆盖。
 * 中文按文字体系分,和资源目录(values-zh / values-b+zh+Hant)一致。
 */
internal fun classifierLanguage(locale: java.util.Locale): String = when {
    locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO") -> "Traditional Chinese"
    locale.language == "zh" -> "Simplified Chinese"
    else -> locale.getDisplayLanguage(java.util.Locale.ENGLISH)
}

@Composable
fun AppAccessScreen(onBack: () -> Unit, classifyApps: Boolean = true) {
    val app = LocalContext.current.applicationContext as MikaApp
    val store = app.appAccess
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val language = classifierLanguage(LocalConfiguration.current.locales[0])
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Failure?>(null) }
    var confirmingClassify by remember { mutableStateOf(false) }
    var recommending by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    DisposableEffect(store) { val protection = store.protectUi(); onDispose { protection.close() } }

    // 推荐只含还在允许范围内的金融应用:已经禁用的不再重复出现。
    val recommended = apps.filter { (pkg, _) ->
        state.classifications[pkg]?.category == AppCategory.Finance && pkg !in state.blocked
    }

    fun save(action: () -> Unit) {
        runCatching(action).onFailure { failure = Failure(R.string.appaccess_error_save, it.message) }
    }
    suspend fun loadApps() {
        apps = withContext(Dispatchers.IO) { launchableApps(app.packageManager, app.packageName) }
    }
    suspend fun refresh(classifyNow: Boolean) {
        busy = true
        failure = null
        try {
            loadApps()
            if (!classifyNow || !classifyApps) return
            val latest = store.current()
            if (!latest.available) return
            val pending = apps.filter { latest.classifications[it.first] == null }
            if (pending.isEmpty()) return
            val model = app.providers.modelFor(ModelRole.Light) ?: app.providers.modelFor(ModelRole.Main)
            if (model == null) {
                failure = Failure(R.string.appaccess_error_no_model)
                return
            }
            val classifier = AppClassifier(model, app.providers.modelFor(ModelRole.Main), language)
            for (batch in pending.chunked(30)) {
                val classified = withContext(Dispatchers.IO) { classifier.classify(batch) }
                save {
                    store.classify(batch.associate { it.first to (classified[it.first] ?: AppClassification(AppCategory.Unknown)) })
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = Failure(R.string.appaccess_error_refresh, e.message)
        } finally {
            busy = false
        }
    }
    // 打开页面只读取本机应用清单,不把任何内容发给模型。分类要用户点刷新并确认后才做。
    LaunchedEffect(Unit) {
        try {
            loadApps()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = Failure(R.string.appaccess_error_load, e.message)
        }
    }

    PageScaffold(stringResource(R.string.appaccess_title), onBack) {
        Text(stringResource(R.string.appaccess_intro))
        Text(stringResource(R.string.appaccess_privacy))
        failure?.let { f ->
            val detail = f.detail?.takeIf { it.isNotBlank() }
            Text(
                if (detail == null) stringResource(f.message) else "${stringResource(f.message)} — $detail",
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!state.available) {
            Text(stringResource(R.string.appaccess_unavailable))
            TextButton({ save { store.reset() } }) { Text(stringResource(R.string.appaccess_reset)) }
        } else {
            OutlinedTextField(
                search, { search = it },
                label = { Text(stringResource(R.string.appaccess_search)) }, singleLine = true,
            )
            TextButton({
                if (busy) return@TextButton
                val pending = apps.filter { state.classifications[it.first] == null }
                if (classifyApps && pending.isNotEmpty()) confirmingClassify = true else scope.launch { refresh(false) }
            }, enabled = !busy) { Text(stringResource(if (busy) R.string.appaccess_refreshing else R.string.appaccess_refresh)) }
            TextButton({ recommending = true }) {
                Text(
                    if (recommended.isEmpty()) stringResource(R.string.appaccess_recommend)
                    else stringResource(R.string.appaccess_recommend_count, recommended.size),
                )
            }
            TextButton({ save { store.allowAll() } }) { Text(stringResource(R.string.appaccess_allow_all)) }
            AppCategory.entries.forEach { category ->
                val rows = apps.filter { (pkg, label) ->
                    (state.classifications[pkg]?.category ?: AppCategory.Unknown) == category &&
                        (pkg.contains(search, true) || label.contains(search, true))
                }
                if (rows.isNotEmpty()) {
                    GroupLabel("${category.title()} · ${rows.size}")
                    if (category == AppCategory.Game || category == AppCategory.Social) Text(stringResource(R.string.appaccess_risk_note))
                    GroupCard {
                        rows.forEachIndexed { index, (pkg, label) ->
                            val classification = state.classifications[pkg]
                            val detail = when {
                                classification == null -> null
                                classification.manual -> stringResource(R.string.appaccess_category_manual)
                                else -> classification.reason.ifBlank { null }
                            }
                            ListRow(null, label, pkg, first = index == 0, detail = detail, onClick = { editing = pkg }) {
                                Text(stringResource(if (pkg in state.blocked) R.string.appaccess_blocked else R.string.appaccess_allowed))
                                Switch(pkg !in state.blocked, { allowed -> save { store.setBlocked(pkg, !allowed) } })
                            }
                            if (pkg in state.shareAllowed) {
                                TextButton({ save { store.setShareAllowed(pkg, false) } }) {
                                    Text(stringResource(R.string.appaccess_revoke_share, label))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmingClassify) {
        MikaDialog(
            onDismissRequest = { confirmingClassify = false },
            title = { Text(stringResource(R.string.appaccess_send_title)) },
            text = { Text(stringResource(R.string.appaccess_send_body)) },
            confirmButton = { TextButton({
                confirmingClassify = false
                scope.launch { refresh(true) }
            }) { Text(stringResource(R.string.appaccess_send_confirm)) } },
            dismissButton = { TextButton({ confirmingClassify = false }) { Text(stringResource(R.string.appaccess_cancel)) } },
        )
    }
    if (recommending) {
        MikaDialog(
            onDismissRequest = { recommending = false },
            title = { Text(stringResource(R.string.appaccess_recommend_title)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.appaccess_recommend_body))
                    if (recommended.isEmpty()) {
                        Text(stringResource(R.string.appaccess_recommend_empty), Modifier.padding(top = 12.dp))
                    }
                    recommended.forEach { (pkg, label) ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) { Text(label) }
                            TextButton({ save { store.blockPackages(setOf(pkg)) } }) { Text(stringResource(R.string.appaccess_block)) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    { save { store.blockPackages(recommended.map { it.first }.toSet()) }; recommending = false },
                    enabled = recommended.isNotEmpty(),
                ) { Text(stringResource(R.string.appaccess_block_all)) }
            },
            dismissButton = { TextButton({ recommending = false }) { Text(stringResource(R.string.appaccess_cancel)) } },
        )
    }
    editing?.let { pkg ->
        MikaDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.appaccess_edit_category)) }, text = {
            Column { AppCategory.entries.forEach { category ->
                TextButton({
                    save { store.classify(mapOf(pkg to AppClassification(category, "", manual = true))) }
                    editing = null
                }) { Text(category.title()) }
            } }
        }, confirmButton = { TextButton({ editing = null }) { Text(stringResource(R.string.appaccess_cancel)) } })
    }
}
