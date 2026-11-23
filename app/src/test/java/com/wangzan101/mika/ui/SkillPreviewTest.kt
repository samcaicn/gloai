package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import com.wangzan101.mika.skills.ui.readingSections
import com.wangzan101.mika.skills.ui.skillPreviewBody

class SkillPreviewTest {
    @Test fun railSkillDisplaysItsMarkdownBodyWithoutTheFileHeader() {
        val body = "## 入口\n- 用 `phone_launch_app` 打开铁路 <参数>。\n\n## 步骤\n1. 选择**出发日期**。"
        val document = "\uFEFF---\nname: train-fare\ndisplay: 查车次\n---\n$body".replace("\n", "\r\n")
        assertEquals(body, skillPreviewBody(document))
        val blocks = MarkdownModel.parse(skillPreviewBody(document))
        assertEquals(listOf("入口", "步骤"), blocks.filterIsInstance<MdBlock.Heading>().map { it.text.text })
        assertEquals(listOf(null, 1), blocks.filterIsInstance<MdBlock.ListBlock>().map { it.start })
    }

    @Test fun sectionLayoutKeepsPreambleNestedHeadingsAndFencedText() {
        val blocks = MarkdownModel.parse("前言\n\n## 步骤\n3. 从第三步开始\n\n### 补充\n\n```md\n## 示例标题\n```\n\n## 结束")
        val sections = readingSections(blocks)
        assertEquals(listOf(null, "步骤", "结束"), sections.map { it.title?.text?.text })
        assertEquals(3, (sections[1].blocks.first() as MdBlock.ListBlock).start)
        assertEquals(blocks, sections.flatMap { listOfNotNull(it.title) + it.blocks })
    }

    @Test fun plainMarkdownAndIncompleteFilesDoNotLoseText() {
        for (text in listOf("---\n普通段落\n---\n结尾", "---\nname: train-fare", "普通段落", "---\nname: train-fare\n---")) {
            assertEquals(text, skillPreviewBody(text))
        }
    }
}
