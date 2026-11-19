package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes

/** 按 Markdown 显示一段回答；流式草稿用临时闭合的语法预览。 */
private val LocalReportStyle = staticCompositionLocalOf { false }

/**
 * 页内查找要在 Markdown 里标出的词:命中的字都标 [soft]。
 * [focus] 指向的那一段文字(按对象本身认)里的第 [focusIndex] 处标 [strong];其余命中不受影响。
 * 为 null 时和平常一样,所以对话里显示回答不会多花一点力气。
 */
internal class FindHighlight(
    val query: String,
    val soft: Color,
    val strong: Color,
    val focus: CharSequence? = null,
    val focusIndex: Int = 0,
)

internal val LocalFindHighlight = compositionLocalOf<FindHighlight?> { null }

@Composable
fun MarkdownText(markdown: String, style: TextStyle, modifier: Modifier = Modifier, streaming: Boolean = false, reportStyle: Boolean = false) {
    MarkdownDocument(markdown, streaming, reportStyle) { blocks ->
        MarkdownBlocks(blocks, style, modifier, gap = if (reportStyle) 16 else 8)
    }
}

@Composable
internal fun MarkdownDocument(markdown: String, streaming: Boolean = false, reportStyle: Boolean = false, content: @Composable (List<MdBlock>) -> Unit) {
    // 流式片段更新频繁，在后台解析；上一帧保留到新片段准备好，避免主线程卡顿和文字闪空。
    val colors = MikaTheme.colors
    val styles = remember(colors, reportStyle) { MarkdownModel.Styles.of(colors, if (reportStyle) FontWeight.Bold else FontWeight.SemiBold) }
    val blocks = if (streaming) {
        val parsed by produceState<List<MdBlock>>(emptyList(), markdown, styles) {
            value = withContext(Dispatchers.Default) { MarkdownModel.parseStreaming(markdown, styles) }
        }
        parsed
    } else remember(markdown, styles) { MarkdownModel.parse(markdown, styles) }
    val context = LocalContext.current
    val uriHandler = remember(context) {
        object : UriHandler {
            override fun openUri(uri: String) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, tr(R.string.no_app_for_link), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides uriHandler, LocalReportStyle provides reportStyle) {
        content(blocks)
    }
}

@Composable
internal fun MarkdownBlocks(blocks: List<MdBlock>, style: TextStyle, modifier: Modifier = Modifier, depth: Int = 0, gap: Int = 8) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap.dp)) {
        blocks.forEach { Block(it, style, depth) }
    }
}

/** 单独一块 Markdown:给要按块懒加载的地方(文件查看页)用,块里的样式和 [MarkdownText] 一样。 */
@Composable
internal fun MarkdownBlock(b: MdBlock, style: TextStyle) {
    Block(b, style, depth = 0)
}

/** 命中处标底色;[FindHighlight.focus] 那一处用更深的颜色,好让人一眼看到「下一处」跳到了哪里。 */
private fun CharSequence.withFind(find: FindHighlight?): AnnotatedString {
    val base = this as? AnnotatedString ?: AnnotatedString(toString())
    if (find == null || find.query.isEmpty()) return base
    val ranges = findRanges(this, find.query, FIND_LIMIT)
    if (ranges.isEmpty()) return base
    val focus = if (this === find.focus) ranges.getOrNull(find.focusIndex) else null
    return buildAnnotatedString {
        append(base)
        ranges.forEach {
            addStyle(SpanStyle(background = if (it == focus) find.strong else find.soft), it.first, it.last + 1)
        }
    }
}

@Composable
private fun Block(b: MdBlock, style: TextStyle, depth: Int) {
    val find = LocalFindHighlight.current
    when (b) {
        is MdBlock.Para -> Text(remember(b.text, find) { b.text.withFind(find) }, style = style)
        is MdBlock.Heading -> Text(
            remember(b.text, find) { b.text.withFind(find) },
            Modifier.padding(top = if (LocalReportStyle.current) 12.dp else 2.dp),
            style = style.copy(
                fontSize = when (b.level) { 1 -> 19.sp; 2 -> 17.5.sp; 3 -> 16.sp; else -> style.fontSize },
                lineHeight = when (b.level) { 1 -> 25.sp; 2 -> 23.sp; else -> style.lineHeight },
                fontWeight = if (LocalReportStyle.current) FontWeight.Bold else FontWeight.SemiBold,
            ),
        )
        is MdBlock.Code -> CodeBlock(b, style)
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MikaTheme.colors.hair, MikaShapes.Micro))
            MarkdownBlocks(b.blocks, style.copy(color = MikaTheme.colors.ink2), Modifier.padding(start = 10.dp), depth + 1)
        }
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(if (b.tight) 3.dp else 8.dp)) {
            b.items.forEachIndexed { i, item ->
                val reportItem = LocalReportStyle.current && depth == 0
                Row(Modifier.then(if (reportItem) Modifier.fillMaxWidth().clip(MikaShapes.Button)
                    .background(MikaTheme.colors.bg).padding(14.dp) else Modifier)) {
                    val marker = if (b.start != null) "${b.start + i}." else if (depth % 2 == 0) "•" else "◦"
                    Text(marker, Modifier.widthIn(min = 18.dp).padding(end = 6.dp), style = style.copy(color = if (reportItem) MikaTheme.colors.accent else MikaTheme.colors.ink2))
                    MarkdownBlocks(item, style, depth = depth + 1, gap = if (b.tight) 3 else 8)
                }
            }
        }
        is MdBlock.Table -> TableBlock(b, style)
        MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(0.5.dp).background(MikaTheme.colors.hair))
    }
}

/** 表格:各列取本列最宽的格(有上限,超了换行),各行取本行最高的格;整张表可左右滑动。 */
@Composable
private fun TableBlock(t: MdBlock.Table, style: TextStyle) {
    val report = LocalReportStyle.current
    val find = LocalFindHighlight.current
    val cols = t.header.size
    if (cols == 0) return
    val cellStyle = style.copy(fontSize = 13.sp, lineHeight = 19.sp)
    val rows = listOf(t.header) + t.rows
    Box(Modifier.then(if (report) Modifier.clip(MikaShapes.Card).background(MikaTheme.colors.surface) else Modifier)
        .horizontalScroll(rememberScrollState())) {
        Layout(
            content = {
                rows.forEachIndexed { r, row ->
                    row.forEach { cell ->
                        Box(
                            Modifier.then(if (report) Modifier else Modifier.border(0.5.dp, MikaTheme.colors.hair))
                                .then(if (r == 0) Modifier.background(MikaTheme.colors.fill) else Modifier)
                                .padding(horizontal = if (report) 12.dp else 9.dp, vertical = if (report) 12.dp else 6.dp),
                        ) {
                            Text(
                                remember(cell, find) { cell.withFind(find) },
                                style = if (r == 0) cellStyle.copy(fontWeight = FontWeight.SemiBold) else cellStyle,
                            )
                        }
                    }
                }
            },
            modifier = if (report) Modifier else Modifier.border(0.5.dp, MikaTheme.colors.hair, MikaShapes.Micro),
        ) { measurables, _ ->
            val maxCol = 240.dp.roundToPx()
            val minCol = 48.dp.roundToPx()
            val widths = IntArray(cols)
            measurables.forEachIndexed { i, m ->
                val c = i % cols
                widths[c] = maxOf(widths[c], m.maxIntrinsicWidth(Constraints.Infinity).coerceIn(minCol, maxCol))
            }
            val heights = measurables.chunked(cols).map { row -> row.withIndex().maxOf { (c, m) -> m.minIntrinsicHeight(widths[c]) } }
            // 同一行的格一样高,边框和表头底色才对齐
            val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i % cols], heights[i / cols])) }
            layout(widths.sum(), heights.sum()) {
                var y = 0
                placeables.chunked(cols).forEachIndexed { r, row ->
                    var x = 0
                    row.forEachIndexed { c, p ->
                        p.place(x, y)
                        x += widths[c]
                    }
                    y += heights[r]
                }
            }
        }
    }
}

/** 代码块:顶部一条轻量顶栏(左显示语言标签,右放「复制 / 已复制」按钮),下面横向滚动显示代码正文。 */
@Composable
private fun CodeBlock(b: MdBlock.Code, style: TextStyle) {
    val c = MikaTheme.colors
    val find = LocalFindHighlight.current
    val context = LocalContext.current
    var copyVersion by remember(b.code) { mutableIntStateOf(0) }
    val copied = copyVersion > 0
    LaunchedEffect(copyVersion) {
        if (copyVersion > 0) {
            delay(2_000)
            copyVersion = 0
        }
    }
    val shape = MikaShapes.Tile
    val language = b.language
    Column(Modifier.fillMaxWidth().clip(shape).background(c.fill)) {
        // 包进 SelectionContainer(如「选择文字」模式或个人助理回答)时,顶栏标签和按钮不参与拖选。
        DisableSelection {
            Row(
                Modifier.fillMaxWidth().padding(start = 11.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (language.isNotEmpty()) {
                    Text(
                        text = language,
                        modifier = Modifier.weight(1f).padding(end = 8.dp).testTag("code-block-language"),
                        style = MikaType.Hint.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
                        color = c.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                val label = stringResource(if (copied) R.string.chat_copied else R.string.chat_copy)
                val tint = if (copied) c.ok else c.ink2
                Row(
                    Modifier
                        .clip(RoundedCornerShape(MikaRadius.BubbleTail))
                        .alpha(if (b.code.isNotEmpty()) 1f else 0.4f)
                        .clickable(
                            enabled = b.code.isNotEmpty(),
                            role = Role.Button,
                            onClick = {
                                context.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(ClipData.newPlainText(language.ifEmpty { "code" }, b.code))
                                copyVersion++
                            },
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .testTag("code-block-copy"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(if (copied) MikaIcons.Check else MikaIcons.Copy, contentDescription = null, modifier = Modifier.size(14.dp), tint = tint)
                    Text(label, style = MikaType.Hint.copy(fontWeight = FontWeight.Medium), color = tint, maxLines = 1)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.hair))
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 11.dp, vertical = 9.dp)) {
            Text(remember(b.code, find) { b.code.withFind(find) }, style = style.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp), softWrap = false)
        }
    }
}
