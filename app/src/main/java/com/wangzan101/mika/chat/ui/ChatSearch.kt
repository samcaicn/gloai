package com.wangzan101.mika.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import androidx.compose.ui.semantics.Role
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 搜索时按归档状态筛选:[All] 全部(归档的带上标记)、[Active] 只看未归档、[Archived] 只看已归档。 */
enum class SearchArchiveFilter { All, Active, Archived }

/** 侧栏搜索结果:标题和命中片段已经换成行要显示的样子;没搜完时 [searching] 为真。 */
data class DrawerSearch(
    val hits: List<DrawerConversation>,
    val searching: Boolean,
    val truncated: Boolean = false,
)

/** 侧栏顶部的搜索框:38 高的浅底圆角条,聚焦时一圈强调色;键盘上是「搜索」。 */
@Composable
internal fun DrawerSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val clear = stringResource(R.string.drawer_search_clear)
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .height(38.dp)
            .keepsKeyboard()
            .clip(NavShape)
            .background(c.fill)
            .then(if (focused) Modifier.border(1.dp, c.accent, NavShape) else Modifier)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(MikaIcons.Search, null, Modifier.size(16.dp), tint = c.ink3)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.drawer_search_hint), style = SearchText, color = c.ink3, maxLines = 1)
            }
            BasicTextField(
                query, onQueryChange,
                Modifier.fillMaxWidth().testTag("drawer-search")
                    .onFocusChanged { focused = it.isFocused },
                singleLine = true,
                textStyle = SearchText.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier.size(30.dp).clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = clear) { onQueryChange("") }
                    .testTag("drawer-search-clear"),
                contentAlignment = Alignment.Center,
            ) { Icon(MikaIcons.Close, clear, Modifier.size(14.dp), tint = c.ink3) }
        }
    }
}

/** 搜索框下面按归档状态筛选的三段;只在有搜索词时显示。 */
@Composable
internal fun SearchArchiveFilterRow(selected: SearchArchiveFilter, onSelect: (SearchArchiveFilter) -> Unit) {
    SegmentedControl(
        options = listOf(
            stringResource(R.string.drawer_search_filter_all),
            stringResource(R.string.drawer_search_filter_active),
            stringResource(R.string.drawer_search_filter_archived),
        ),
        selected = SearchArchiveFilter.entries.indexOf(selected),
        onSelect = { onSelect(SearchArchiveFilter.entries[it]) },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        tags = listOf("drawer-search-filter-all", "drawer-search-filter-active", "drawer-search-filter-archived"),
    )
}

/** 搜索结果里的一行提示:搜索中、结果太多只显示前若干条。 */
@Composable
internal fun SearchHint(text: String) {
    Text(
        text, Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 4.dp),
        style = MikaType.Hint, color = MikaTheme.colors.ink3,
    )
}

/** 一个对话都没搜到:说清楚搜了哪儿,别让人以为是功能坏了。 */
@Composable
internal fun SearchEmpty(query: String) {
    val c = MikaTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(MikaIcons.Search, null, Modifier.size(22.dp), tint = c.ink3)
        Text(
            stringResource(R.string.drawer_search_empty, query), style = MikaType.Body, color = c.ink2,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.drawer_search_empty_hint), style = MikaType.Hint, color = c.ink3,
            textAlign = TextAlign.Center,
        )
    }
}

/** 把 [text] 里出现在 [terms] 中的部分标成强调色;没有命中时原样返回。 */
internal fun highlighted(text: String, terms: List<String>, color: Color): AnnotatedString {
    val ranges = matchRanges(text, terms)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { addStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium), it.first, it.last + 1) }
    }
}

/** [terms] 在 [text] 里所有出现的位置(不区分大小写),重叠或相接的合并。 */
internal fun matchRanges(text: String, terms: List<String>): List<IntRange> {
    if (text.isEmpty() || terms.isEmpty()) return emptyList()
    val found = ArrayList<IntRange>()
    for (term in terms) {
        if (term.isEmpty()) continue
        var from = 0
        while (from <= text.length - term.length) {
            val i = text.indexOf(term, from, ignoreCase = true)
            if (i < 0) break
            found += i until i + term.length
            from = i + term.length
        }
    }
    if (found.isEmpty()) return emptyList()
    found.sortBy { it.first }
    val merged = ArrayList<IntRange>(found.size)
    for (range in found) {
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
            merged += range
        }
    }
    return merged
}

private val SearchText = MikaType.Body
