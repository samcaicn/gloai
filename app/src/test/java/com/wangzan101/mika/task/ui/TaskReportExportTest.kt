package com.wangzan101.mika.task.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import com.wangzan101.mika.settings.ui.models.use

class TaskReportExportTest {
    private val report = TaskReport(TimelineHeader("查询 <车票> & 时间", emptyList(), "已完成", verdict = "", duration = "2 分钟", modelCalls = 1),
        "## 结果\n\n**最低票价： **302 元。**正常粗体**\n\n| 车次 | 票价 |\n| --- | --- |\n| G6042 | ¥302 |\n\n- 中文项目\n- 第二项\n\n[来源](https://example.com/?a=1&b=2)\n\n```\na < b && c\nline2\n```", emptyList())

    @Test fun markdownAndPlainTextKeepFullAnswerAndUnicode() {
        val md = ByteArrayOutputStream().also { TaskReportExport.write(report, TaskReportExport.Format.Markdown, it) }.toString("UTF-8")
        assertTrue(md.contains("| **G6042** | **¥302** |"))
        assertTrue(md.contains("**最低票价：** **302 元**"))
        assertTrue(md.contains("**正常粗体**"))
        val txt = ByteArrayOutputStream().also { TaskReportExport.write(report, TaskReportExport.Format.Text, it) }.toString("UTF-8")
        assertTrue(txt.contains("G6042\t¥302"))
        assertTrue(txt.contains("中文项目")); assertFalse(txt.contains("**"))
        assertTrue(txt.contains("a < b && c\nline2"))
    }

    @Test fun docxContainsValidPackageUnicodeStylesListsAndTables() {
        val bytes = ByteArrayOutputStream().also { TaskReportExport.write(report, TaskReportExport.Format.Word, it) }.toByteArray()
        val parts = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; parts[entry.name] = zip.readBytes() }
        }
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/_rels/document.xml.rels"), parts.keys)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        parts.forEach { (_, bytes) -> factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)) }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(parts.getValue("word/document.xml")))
        val text = document.documentElement.textContent
        assertTrue(text.contains("查询 <车票> & 时间"))
        assertTrue(text.contains("中文项目")); assertTrue(text.contains("https://example.com/?a=1&b=2"))
        val ns = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        assertEquals(1, document.getElementsByTagNameNS(ns, "tbl").length)
        assertTrue(document.getElementsByTagNameNS(ns, "b").length > 0)
        assertTrue(document.getElementsByTagNameNS(ns, "br").length > 0)
    }
    @Test fun copiedPlainTextKeepsHeaderAndAnswerWithoutMarkup() {
        val text = TaskReportExport.text(report)
        assertTrue(text.startsWith("查询 <车票> & 时间"))
        assertTrue(text.contains("已完成 · 2 分钟"))
        assertTrue(text.contains("G6042"))
        assertTrue(text.contains("中文项目"))
        assertFalse(text.contains("**"))
        assertFalse(text.contains("## "))
    }

    @Test fun literalMarkdownInsideCodeIsNeverRewritten() {
        val source = "`**原样： **`\n\n```text\n**原样： **\n```"
        assertEquals(source, TaskReportExport.displayMarkdown(source))
    }

    @Test fun requestedFactsAreEmphasizedWithoutChangingLinksOrExistingBold() {
        val source = "**结论：**优先 G6042，11月12日 18:18，¥302。[链接](https://example.com/18:18)"
        val display = TaskReportExport.displayMarkdown(source, "找最低价车票，告诉我出发时间")
        assertTrue(display.contains("**结论：** 优先 **G6042**"))
        assertTrue(display.contains("**11月12日** **18:18**，**¥302**"))
        assertTrue(display.contains("https://example.com/18:18"))
        assertFalse(display.contains("****"))
    }

}
