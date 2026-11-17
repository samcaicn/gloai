package com.wangzan101.mika.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.knowledge.FrontMatter
import com.wangzan101.mika.skills.MarketEntry
import com.wangzan101.mika.skills.SkillMarket
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
internal fun SkillMarketScreen(onBack: () -> Unit, onImport: (String) -> String?) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("skill_market", Context.MODE_PRIVATE) }
    val market = remember { SkillMarket() }
    var repo by remember { mutableStateOf(prefs.getString("repo", null) ?: SkillMarket.DEFAULT_REPO) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable(repo) { mutableStateOf("") }
    var selected by remember(repo) { mutableStateOf<MarketEntry?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val entry = selected
    val back = { if (selected != null) selected = null else onBack() }
    BackHandler(onBack = back)

    // Keep the list and filter while reading a skill. Changing source discards both requests and previews.
    val index = key(repo, refresh) { marketLoad { market.list(repo) } }
    key(repo, entry) {
        SubPage(entry?.display ?: stringResource(R.string.market_title), back) {
            GroupLabel(stringResource(R.string.market_source))
            GroupCard {
                ListRow(null, repo, first = true, onClick = if (entry == null) ({ editing = true }) else null) {
                    if (entry == null) RowChevron()
                }
            }
            if (entry == null) {
                GroupLabel(stringResource(R.string.memory_skills))
                SearchField(query, { query = it }, stringResource(R.string.search))
                GroupCard {
                    MarketContent(index, { refresh++ }) { entries ->
                        val matches = entries.filter { it.display.contains(query.trim(), true) ||
                            it.description.contains(query.trim(), true) || it.name.contains(query.trim(), true) }
                        if (matches.isEmpty()) EmptyNote(stringResource(R.string.not_found))
                        matches.forEachIndexed { i, item ->
                            ListRow(null, item.display, item.description, first = i == 0, onClick = { selected = item }) { RowChevron() }
                        }
                    }
                }
            } else {
                var retry by remember { mutableIntStateOf(0) }
                val preview = key(retry) { marketLoad { market.preview(repo, entry) } }
                var problem by remember { mutableStateOf<String?>(null) }
                GroupLabel(entry.display)
                GroupCard {
                    MarketContent(preview, {
                        if (preview.error == R.string.market_invalid) { selected = null; refresh++ } else retry++
                    }) { text ->
                        val body = remember(text) { FrontMatter.parse(text).second }
                        Column(Modifier.padding(16.dp)) {
                            Text(entry.description, style = MikaType.Caption, color = MikaTheme.colors.ink2)
                            // Render section headings without making third-party links or images interactive.
                            for (line in body.lineSequence().filter { it.isNotBlank() }) {
                                val heading = line.startsWith("## ")
                                Text(if (heading) line.drop(3) else line,
                                    Modifier.padding(top = if (heading) 12.dp else 4.dp),
                                    style = if (heading) MikaType.BodyStrong else MikaType.Body, color = MikaTheme.colors.ink)
                            }
                        }
                        problem?.let { EmptyNote(it) }
                        RowActions {
                            MikaButton(stringResource(R.string.market_add), {
                                problem = onImport(text)
                                if (problem == null) onBack()
                            })
                        }
                    }
                }
                EmptyNote(stringResource(R.string.skills_import_hint))
            }
        }
    }
    if (editing) {
        var value by rememberSaveable { mutableStateOf(repo) }
        MikaDialog(onDismissRequest = { editing = false }, title = { Text(stringResource(R.string.market_source)) },
            text = { OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("GitHub") }, placeholder = { Text("owner/repository") }) },
            confirmButton = { TextButton({
                repo = value.trim()
                prefs.edit().putString("repo", repo).apply()
                editing = false
            }, enabled = SkillMarket.validRepo(value.trim())) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton({ editing = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

private data class MarketLoad<T>(val value: T? = null, val error: Int? = null)

@Composable
private fun <T> marketLoad(fetch: () -> T): MarketLoad<T> =
    produceState(MarketLoad<T>()) {
        value = try {
            MarketLoad(value = withContext(Dispatchers.IO) { fetch() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            MarketLoad(error = R.string.market_network_error)
        } catch (e: IllegalArgumentException) {
            MarketLoad(error = R.string.market_invalid)
        }
    }.value

@Composable
private fun <T> MarketContent(load: MarketLoad<T>, retry: () -> Unit, content: @Composable (T) -> Unit) {
    when {
        load.error != null -> {
            EmptyNote(stringResource(load.error))
            RowActions { MikaButton(stringResource(R.string.retry), retry) }
        }
        load.value != null -> content(load.value)
        else -> EmptyNote(stringResource(R.string.loading))
    }
}
