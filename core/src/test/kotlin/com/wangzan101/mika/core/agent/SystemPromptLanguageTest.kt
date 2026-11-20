package com.wangzan101.mika.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptLanguageTest {

    @Test
    fun testLanguageDirectiveEnglish() {
        val directive = SystemPrompt.languageDirective("en")
        assertTrue(directive.contains("Communication & Reasoning Language"))
        assertTrue(directive.contains("formulate all progress notes, tool call reasons, and final replies in English"))
        assertFalse(directive.contains("所有思考过程"))
    }

    @Test
    fun testLanguageDirectiveChinese() {
        val directive = SystemPrompt.languageDirective("zh-CN")
        assertTrue(directive.contains("语言设定"))
        assertTrue(directive.contains("所有思考过程、动作用意、执行进度与最终回复均使用中文"))
    }

    @Test
    fun testLanguageDirectiveJapanese() {
        val directive = SystemPrompt.languageDirective("ja")
        assertTrue(directive.contains("言語設定"))
        assertTrue(directive.contains("日本語"))
    }

    @Test
    fun testLanguageDirectiveSystemOrEmpty() {
        val dirNull = SystemPrompt.languageDirective(null)
        val dirSystem = SystemPrompt.languageDirective("system")
        assertTrue(dirNull.contains("Always mirror the language of the user's prompt"))
        assertTrue(dirSystem.contains("Always mirror the language of the user's prompt"))
    }

    @Test
    fun testWithLanguageAppendsToPrompt() {
        val base = "You are Mika."
        val withEn = SystemPrompt.withLanguage(base, "en")
        assertTrue(withEn.startsWith("You are Mika."))
        assertTrue(withEn.contains("Communication & Reasoning Language"))
    }
}
