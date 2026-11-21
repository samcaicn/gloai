package com.wangzan101.mika.ui

import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.i18n.ResFileTexts
import com.wangzan101.mika.i18n.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ToolLabelsTest {
    @get:Rule val texts = ChineseTexts()

    /** 每个声明了 label 的工具都有界面文字,中文和声明一字不差;英文另有一份。 */
    @Test
    fun everyDeclaredLabelIsLocalized() {
        val tools = knownTools()
        val labels = ToolLabels(tools)
        val declared = tools.names.mapNotNull { n -> tools[n]?.label?.let { n to it } }
        assertTrue(declared.size > 40)
        for ((name, label) in declared) {
            assertTrue("$name: $label", label in ToolLabels.TOOL_LABELS)
            assertEquals(name, label, labels.label(name))
        }
        UiText.source = ResFileTexts.english
        assertEquals("Tap", labels.label("tap"))
        assertEquals("Open web page", labels.label("browser_navigate"))
        assertEquals("Browse device files", labels.label("list_device_files"))
        assertEquals("Copy device file", labels.label("copy_device_file"))
    }

    /** 没声明 label 的工具按工具名查界面文字;查不到才显示工具名本身。 */
    @Test
    fun toolWithoutDeclaredLabelIsLocalizedByName() {
        val labels = ToolLabels(knownTools())
        assertEquals("发送到我的微信", labels.label("wechat_send_owner"))
        assertEquals("unknown_tool_name", labels.label("unknown_tool_name"))
        UiText.source = ResFileTexts.english
        assertEquals("Send to my WeChat", labels.label("wechat_send_owner"))
    }
}
