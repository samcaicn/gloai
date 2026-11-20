package com.wangzan101.mika.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import com.wangzan101.mika.ui.theme.LightColors
import java.net.URLDecoder
import com.wangzan101.mika.ui.theme.MikaColors

/** 回答里的 Markdown 解析成的显示模型:块级结构 + 带样式的行内文字。纯数据,不依赖界面。 */
sealed interface MdBlock {
    data class Para(val text: AnnotatedString) : MdBlock
    data class Heading(val level: Int, val text: AnnotatedString) : MdBlock
    data class Code(val info: String, val code: String) : MdBlock {
        /** 顶栏显示的语言标签:CommonMark info 串首个空白分隔词(如 `kotlin {1}` 取 `kotlin`),无则为空。 */
        val language: String get() = info.trim().takeWhile { !it.isWhitespace() }
    }
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    /** [start] 为 null 是无序列表;每个列表项是一组块。 */
    data class ListBlock(val start: Int?, val tight: Boolean, val items: List<List<MdBlock>>) : MdBlock
    /** 每行的格数都补齐到表头的列数。 */
    data class Table(val header: List<AnnotatedString>, val rows: List<List<AnnotatedString>>) : MdBlock
    data object Rule : MdBlock
}

object MarkdownModel {
    /** 嵌套再深的引用、列表就按纯文字显示,免得递归太深。 */
    private const val MAX_DEPTH = 12
    private val LINK_SCHEMES = setOf("http", "https", "mailto", "tel")

    /** 行内代码和链接的样式;颜色跟着深浅色,由显示的地方按当前配色给。 */
    class Styles(val code: SpanStyle, val link: TextLinkStyles, val strong: FontWeight = FontWeight.SemiBold) {
        companion object {
            fun of(c: MikaColors, strong: FontWeight = FontWeight.SemiBold) = Styles(
                code = SpanStyle(fontFamily = FontFamily.Monospace, background = c.fill),
                link = TextLinkStyles(SpanStyle(color = c.accent)),
                strong = strong,
            )

            val Light = of(LightColors)
        }
    }

    private val parser: Parser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .build()

    /** 解析失败(包括写到一半的流式文字导致的任何意外)时,整段按纯文字一段显示。 */
    fun parse(markdown: String, styles: Styles = Styles.Light): List<MdBlock> = try {
        blocks(parser.parse(markdown), 0, styles)
    } catch (e: Exception) {
        plain(markdown)
    } catch (e: StackOverflowError) {
        plain(markdown)
    }

    /** 流式片段可停在语法中间；只在显示时补全尾部，不修改实际回答。 */
    fun parseStreaming(markdown: String, styles: Styles = Styles.Light): List<MdBlock> = parse(StreamingMarkdown.preview(markdown), styles)

    /**
     * 回答去掉 Markdown 符号后的纯文字(复制、引用用):段落、标题、代码照原文,各块之间空一行;
     * 列表每项一行,前面「• 」或「1. 」;引用每行前面「> 」;表格每行一行、格子之间用制表符;分隔线不要。
     */
    fun toPlainText(markdown: String): String = blocksText(parse(markdown)).trim()

    /**
     * 回复里 Markdown 图片 `![..]` 与链接 `[..]` 的地址中以 `/workspace` 开头者,按出现顺序去重返回。
     * 地址里的百分号转义解出来(带空格、非 ASCII 的文件名按 CommonMark 要写成 `%20`、`%E6` 这样的形式);
     * 解不了的非法序列原样保留,字面 `+` 也不是空格。
     */
    fun workspaceRefs(markdown: String): List<String> = try {
        val out = LinkedHashSet<String>()
        fun visit(n: Node) {
            val dest = when (n) {
                is Image -> n.destination
                is Link -> n.destination
                else -> null
            }
            if (dest != null && dest.startsWith("/workspace")) out.add(percentDecoded(dest))
            var c = n.firstChild
            while (c != null) { visit(c); c = c.next }
        }
        visit(parser.parse(markdown))
        out.toList()
    } catch (e: Exception) {
        emptyList()
    } catch (e: StackOverflowError) {
        emptyList()
    }

    /** 链接地址是按 URI 规则写的:合法 `%HH` 按 UTF-8 解;非法转义不动,`+` 是文件名里的字面加号,不当空格。 */
    private fun percentDecoded(dest: String): String =
        runCatching { URLDecoder.decode(dest.replace("+", "%2B"), "UTF-8") }.getOrDefault(dest)

    private fun blocksText(blocks: List<MdBlock>, sep: String = "\n\n"): String = blocks.mapNotNull { b ->
        when (b) {
            is MdBlock.Para -> b.text.text
            is MdBlock.Heading -> b.text.text
            is MdBlock.Code -> b.code
            is MdBlock.Quote -> blocksText(b.blocks).lines().joinToString("\n") { "> $it".trimEnd() }
            is MdBlock.ListBlock -> b.items.mapIndexed { i, item ->
                val mark = if (b.start == null) "• " else "${b.start + i}. "
                mark + blocksText(item, "\n").lines().joinToString("\n" + " ".repeat(mark.length))
            }.joinToString("\n")
            is MdBlock.Table -> (listOf(b.header) + b.rows).joinToString("\n") { r -> r.joinToString("\t") { it.text } }
            MdBlock.Rule -> null
        }?.takeIf { it.isNotBlank() }
    }.joinToString(sep)

    private fun plain(s: String): List<MdBlock> = if (s.isBlank()) emptyList() else listOf(MdBlock.Para(AnnotatedString(s.trim())))

    private fun children(n: Node): Sequence<Node> = generateSequence(n.firstChild) { it.next }

    private fun blocks(parent: Node, depth: Int, s: Styles): List<MdBlock> = children(parent).mapNotNull { block(it, depth, s) }.toList()

    private fun block(n: Node, depth: Int, s: Styles): MdBlock? {
        if (depth > MAX_DEPTH) return MdBlock.Para(AnnotatedString(plainText(n)))
        return when (n) {
            is Paragraph -> MdBlock.Para(inlines(n, s))
            is org.commonmark.node.Heading -> MdBlock.Heading(n.level, inlines(n, s))
            is FencedCodeBlock -> MdBlock.Code(n.info.orEmpty().trim(), n.literal.orEmpty().trimEnd('\n'))
            is IndentedCodeBlock -> MdBlock.Code("", n.literal.orEmpty().trimEnd('\n'))
            is HtmlBlock -> MdBlock.Para(AnnotatedString(n.literal.orEmpty().trimEnd('\n')))
            is BlockQuote -> MdBlock.Quote(blocks(n, depth + 1, s))
            is ListBlock -> MdBlock.ListBlock(
                start = (n as? OrderedList)?.let { it.markerStartNumber ?: 1 },
                tight = n.isTight,
                items = children(n).filterIsInstance<ListItem>().map { blocks(it, depth + 1, s) }.toList(),
            )
            is TableBlock -> table(n, s)
            is ThematicBreak -> MdBlock.Rule
            is LinkReferenceDefinition -> null
            else -> plainText(n).takeIf { it.isNotBlank() }?.let { MdBlock.Para(AnnotatedString(it)) }
        }
    }

    private fun table(t: TableBlock, s: Styles): MdBlock.Table {
        val rows = mutableListOf<List<AnnotatedString>>()
        var header: List<AnnotatedString> = emptyList()
        for (section in children(t)) for (row in children(section).filterIsInstance<TableRow>()) {
            val cells = children(row).filterIsInstance<TableCell>().map { inlines(it, s) }.toList()
            if (section is TableHead && header.isEmpty()) header = cells else rows += cells
        }
        val cols = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
        fun pad(r: List<AnnotatedString>) = r + List(cols - r.size) { AnnotatedString("") }
        return MdBlock.Table(pad(header), rows.map(::pad))
    }

    private fun inlines(parent: Node, s: Styles): AnnotatedString = buildAnnotatedString { appendInlines(parent, s) }

    private fun AnnotatedString.Builder.appendInlines(parent: Node, s: Styles) {
        for (n in children(parent)) when (n) {
            is Text -> append(n.literal)
            is SoftLineBreak, is HardLineBreak -> append('\n')
            is Code -> withStyle(s.code) { append(n.literal) }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(n, s) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = s.strong)) { appendInlines(n, s) }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInlines(n, s) }
            is Link -> link(n.destination, s) { appendInlines(n, s) }
            // 聊天里不显示图片,只留替代文字(有可打开的地址就做成链接)
            is Image -> link(n.destination, s) { if (n.firstChild != null) appendInlines(n, s) else append(n.destination.orEmpty()) }
            is HtmlInline -> append(n.literal)
            else -> appendInlines(n, s)
        }
    }

    private inline fun AnnotatedString.Builder.link(dest: String?, s: Styles, content: AnnotatedString.Builder.() -> Unit) {
        val url = dest?.trim().orEmpty()
        if (url.substringBefore(':', "").lowercase() in LINK_SCHEMES) withLink(LinkAnnotation.Url(url, s.link)) { content() } else content()
    }

    private fun plainText(n: Node): String = buildString {
        fun walk(x: Node) {
            when (x) {
                is Text -> append(x.literal)
                is Code -> append(x.literal)
                is FencedCodeBlock -> append(x.literal)
                is IndentedCodeBlock -> append(x.literal)
                is HtmlBlock -> append(x.literal)
                is HtmlInline -> append(x.literal)
                is SoftLineBreak, is HardLineBreak -> append('\n')
                else -> {
                    children(x).forEach(::walk)
                    if (x is Paragraph || x is org.commonmark.node.Heading) append('\n')
                }
            }
        }
        walk(n)
    }.trim()
}
