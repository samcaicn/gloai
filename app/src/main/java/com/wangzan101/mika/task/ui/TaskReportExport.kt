package com.wangzan101.mika.task.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.wangzan101.mika.settings.ui.models.use
import com.wangzan101.mika.ui.MarkdownModel
import com.wangzan101.mika.ui.MdBlock

/** 纯本地导出；复用 Markdown 模型，DOCX 使用标准 OOXML ZIP，不把 HTML 改后缀。 */
object TaskReportExport {
    enum class Format(val extension: String, val mime: String) {
        Markdown("md", "text/markdown"), Word("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"), Text("txt", "text/plain")
    }

    // 模型偶尔在粗体结束符前留下空格。只修正显示标记，不更改回答文字或原始记录。
    fun displayMarkdown(answer: String, goal: String = ""): String {
        var fence: String? = null
        val important = buildList {
            if (Regex("票|价|预算|费用|酒店|price|cost|budget|fare|hotel", RegexOption.IGNORE_CASE).containsMatchIn(goal))
                add("[¥￥$€]\\s?\\d[\\d,.]*(?:元)?|\\d[\\d,.]*\\s?(?:元|人民币)")
            if (Regex("时间|日期|车|票|酒店|行程|出发|到达|预订|几点|周末|旅行|攻略|time|date|train|hotel|schedule", RegexOption.IGNORE_CASE).containsMatchIn(goal))
                add("(?:\\d{4}年)?\\d{1,2}月(?:\\d{1,2}日)?|\\d{1,2}[:：]\\d{2}|[GDKTZC]\\d{2,5}")
        }.takeIf { it.isNotEmpty() }?.let { Regex(it.joinToString("|")) }
        fun normalize(text: String): String {
            val repaired = Regex("\\*\\*([^*\\n]+)\\*\\*").replace(text) { match ->
                val body = match.groupValues[1]
                val trimmed = body.trimEnd()
                val next = text.getOrNull(match.range.last + 1)
                val gap = body.takeLast(body.length - trimmed.length).ifEmpty {
                    if (trimmed.lastOrNull()?.let { it in "：:，,。！？!?；;" } == true && next != null && !next.isWhitespace()) " " else ""
                }
                "**$trimmed**$gap"
            }
            if (important == null) return repaired
            return buildString {
                var end = 0
                // 已加粗的内容和链接地址不再嵌套 Markdown 标记。
                Regex("\\*\\*[^*]+\\*\\*|\\]\\([^)]*\\)").findAll(repaired).forEach {
                    append(important.replace(repaired.substring(end, it.range.first)) { m -> "**${m.value}**" })
                    append(it.value); end = it.range.last + 1
                }
                append(important.replace(repaired.substring(end)) { m -> "**${m.value}**" })
            }
        }
        return answer.lines().joinToString("\n") { line ->
            val marker = Regex("^ {0,3}(`{3,}|~{3,})").find(line)?.groupValues?.get(1)
            if (marker != null) {
                val active = fence
                if (active == null) fence = marker
                else if (marker.first() == active.first() && marker.length >= active.length) fence = null
                line
            } else if (fence != null) line else buildString {
                var end = 0
                Regex("(`+).*?\\1").findAll(line).forEach {
                    append(normalize(line.substring(end, it.range.first))); append(it.value); end = it.range.last + 1
                }
                append(normalize(line.substring(end)))
            }
        }
    }

    fun markdown(report: TaskReport): String = buildString {
        append("# ").append(report.header.goal.replace('\n', ' ')).append("\n\n")
        append(report.header.outcome)
        report.header.duration?.let { append(" · ").append(it) }
        append("\n\n")
        if (report.header.criteria.isNotEmpty()) {
            report.header.criteria.forEach { append("- ").append(it).append('\n') }
            append('\n')
        }
        append(displayMarkdown(report.answer, report.header.goal)).append('\n')
    }

    /** 纯文本正文:去掉 Markdown 标记,供复制和分享直接粘贴到聊天、邮件和笔记。 */
    fun text(report: TaskReport): String = MarkdownModel.toPlainText(markdown(report))

    fun write(report: TaskReport, format: Format, output: OutputStream) {
        val md = markdown(report)
        when (format) {
            Format.Markdown -> output.write(md.toByteArray(Charsets.UTF_8))
            Format.Text -> output.write(text(report).toByteArray(Charsets.UTF_8))
            Format.Word -> writeDocx(MarkdownModel.parse(md), output)
        }
    }

    private fun writeDocx(blocks: List<MdBlock>, output: OutputStream) {
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${blocksXml(blocks)}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="567" w:footer="567" w:gutter="0"/></w:sectPr></w:body></w:document>"""
        // 释放压缩器，但不接管调用方的输出流。
        ZipOutputStream(object : java.io.FilterOutputStream(output) { override fun close() { flush() } }).use { zip ->
        fun part(name: String, xml: String) {
            zip.putNextEntry(ZipEntry(name)); zip.write(xml.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
        part("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>""")
        part("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
        part("word/document.xml", document)
        part("word/_rels/document.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="styles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
        part("word/styles.xml", """<?xml version="1.0" encoding="UTF-8"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Microsoft YaHei"/><w:color w:val="242936"/><w:sz w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="300" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>${(1..6).joinToString("") { level -> """<w:style w:type="paragraph" w:styleId="Heading$level"><w:name w:val="heading $level"/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="160"/><w:outlineLvl w:val="${level - 1}"/></w:pPr><w:rPr><w:b/><w:color w:val="243653"/><w:sz w:val="${if (level == 1) 36 else 28}"/></w:rPr></w:style>""" }}</w:styles>""")
        zip.finish(); zip.flush()
        }
    }

    private fun blocksXml(blocks: List<MdBlock>, depth: Int = 0): String = blocks.joinToString("") { b ->
        when (b) {
            is MdBlock.Para -> paragraph(b.text, depth = depth)
            is MdBlock.Heading -> paragraph(b.text, "Heading${b.level}", depth)
            is MdBlock.Code -> paragraph(AnnotatedString(b.code), depth = depth, code = true)
            is MdBlock.Quote -> blocksXml(b.blocks, depth + 1)
            is MdBlock.ListBlock -> b.items.mapIndexed { i, item ->
                val marker = if (b.start == null) "• " else "${b.start + i}. "
                val first = item.firstOrNull()
                if (first is MdBlock.Para) paragraph(AnnotatedString(marker) + first.text, depth = depth + 1) + blocksXml(item.drop(1), depth + 1)
                else paragraph(AnnotatedString(marker), depth = depth + 1) + blocksXml(item, depth + 1)
            }.joinToString("")
            is MdBlock.Table -> {
                val cols = b.header.size.coerceAtLeast(1)
                "<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>" +
                    listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { "<w:$it w:val=\"single\" w:sz=\"4\" w:color=\"DCE2EB\"/>" } +
                    "</w:tblBorders></w:tblPr><w:tblGrid>" + "<w:gridCol w:w=\"${9600 / cols}\"/>".repeat(cols) + "</w:tblGrid>" +
                    (listOf(b.header) + b.rows).mapIndexed { i, row ->
                        "<w:tr>" + (if (i == 0) "<w:trPr><w:tblHeader/></w:trPr>" else "") + row.joinToString("") {
                            "<w:tc><w:tcPr><w:tcW w:w=\"${9600 / cols}\" w:type=\"dxa\"/>" + (if (i == 0) "<w:shd w:fill=\"F2F5FA\"/>" else "") +
                                "</w:tcPr>" + paragraph(it, bold = i == 0) + "</w:tc>"
                        } + "</w:tr>"
                    }.joinToString("") + "</w:tbl><w:p/>"
            }
            MdBlock.Rule -> "<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"DCE2EB\"/></w:pBdr></w:pPr></w:p>"
        }
    }

    private fun paragraph(text: AnnotatedString, style: String? = null, depth: Int = 0, bold: Boolean = false, code: Boolean = false): String {
        val cuts = (listOf(0, text.length) + text.spanStyles.flatMap { listOf(it.start, it.end) }).distinct().sorted()
        val runs = cuts.zipWithNext().joinToString("") { (start, end) ->
            val spans = text.spanStyles.filter { it.start <= start && it.end >= end }.map { it.item }
            val props = (if (bold || spans.any { (it.fontWeight?.weight ?: 0) >= FontWeight.SemiBold.weight }) "<w:b/>" else "") +
                (if (spans.any { it.fontStyle == FontStyle.Italic }) "<w:i/>" else "") +
                (if (code) "<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/>" else "")
            "<w:r><w:rPr>$props</w:rPr>" + text.text.substring(start, end).split('\n').joinToString("<w:br/>") { "<w:t xml:space=\"preserve\">${xml(it)}</w:t>" } + "</w:r>"
        }
        val links = text.getLinkAnnotations(0, text.length).mapNotNull { (it.item as? LinkAnnotation.Url)?.url }.distinct()
            .filter { it !in text.text }.joinToString("") { "<w:r><w:t xml:space=\"preserve\"> (${xml(it)})</w:t></w:r>" }
        return "<w:p><w:pPr>" + (style?.let { "<w:pStyle w:val=\"$it\"/>" } ?: "") +
            (if (depth > 0) "<w:ind w:left=\"${depth * 300}\"/>" else "") + "</w:pPr>$runs$links</w:p>"
    }

    private fun xml(value: String) = value.filter { it >= ' ' || it == '\n' || it == '\t' }.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
