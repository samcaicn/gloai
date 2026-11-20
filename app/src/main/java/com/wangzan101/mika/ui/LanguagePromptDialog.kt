package com.wangzan101.mika.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * Filter matching a query against localized name, language tag, and common synonyms.
 */
internal fun filterLanguages(query: String): List<AppLanguage> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return AppLanguage.entries
    return AppLanguage.entries.filter { l ->
        val name = l.name.lowercase()
        val tag = l.tag?.lowercase().orEmpty()
        name.contains(q) || tag.contains(q) || when (l) {
            AppLanguage.System -> "system follow 跟随系统"
            AppLanguage.SimplifiedChinese -> "chinese 简体中文 中文"
            AppLanguage.TraditionalChinese -> "chinese 繁體中文 繁体中文 中文"
            AppLanguage.English -> "english 英文 英语"
            AppLanguage.Japanese -> "japanese 日本語 日文 日语"
            AppLanguage.Korean -> "korean 한국어 韩文 韩语"
            AppLanguage.Spanish -> "spanish español 西班牙语"
            AppLanguage.Portuguese -> "portuguese português 葡萄牙语"
            AppLanguage.Russian -> "russian русский 俄语"
            AppLanguage.Indonesian -> "indonesian bahasa 印尼语"
            AppLanguage.Arabic -> "arabic العربية 阿拉伯语"
            AppLanguage.Persian -> "persian فارسی 波斯语"
            AppLanguage.Turkish -> "turkish türkçe 土耳其语"
        }.lowercase().contains(q)
    }
}

/**
 * Prompt shown once on update or next launch to allow users to pick Mika's reasoning and UI language.
 * Dynamically scales its content height to the device screen height instead of using hardcoded dimensions.
 */
@Composable
internal fun LanguagePromptDialog(
    initial: AppLanguage,
    onConfirm: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxListHeight = if (imeBottom > 0.dp) {
        (screenHeight - imeBottom - 260.dp).coerceIn(120.dp, 240.dp)
    } else {
        (screenHeight * 0.45f).coerceIn(180.dp, 360.dp)
    }
    val focusManager = LocalFocusManager.current
    val colors = MikaTheme.colors
    val filtered = remember(query) { filterLanguages(query) }

    MikaDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text(stringResource(R.string.language_prompt_title)) },
        text = {
            Column(Modifier.animateContentSize()) {
                Text(
                    stringResource(R.string.language_prompt_hint),
                    Modifier.padding(bottom = 10.dp),
                )
                // Dynamic Search Field
                var searchFocused by remember { mutableStateOf(false) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(MikaShapes.Pill)
                        .background(colors.fill)
                        .then(if (searchFocused) Modifier.border(1.dp, colors.accent, MikaShapes.Pill) else Modifier)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(MikaIcons.Search, null, Modifier.size(16.dp), tint = colors.ink3)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(stringResource(R.string.language_search_hint), style = MikaType.Caption, color = colors.ink3, maxLines = 1)
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth().onFocusChanged { searchFocused = it.isFocused },
                            singleLine = true,
                            textStyle = MikaType.Caption.copy(color = colors.ink),
                            cursorBrush = SolidColor(colors.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        )
                    }
                    if (query.isNotEmpty()) {
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).clickable { query = "" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(MikaIcons.Close, null, Modifier.size(12.dp), tint = colors.ink3)
                        }
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .heightIn(max = maxListHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (filtered.isEmpty()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.file_library_empty),
                                style = MikaType.Caption,
                                color = colors.ink3,
                            )
                        }
                    } else {
                        filtered.forEachIndexed { index, l ->
                            RadioRow(
                                title = languageLabel(l),
                                selected = selected == l,
                                onClick = {
                                    selected = l
                                    focusManager.clearFocus()
                                },
                                first = index == 0,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                focusManager.clearFocus()
                onConfirm(selected)
            }) {
                Text(stringResource(R.string.language_prompt_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                focusManager.clearFocus()
                onDismiss()
            }) {
                Text(stringResource(R.string.language_prompt_dismiss))
            }
        },
    )
}
