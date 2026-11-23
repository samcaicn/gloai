package com.wangzan101.mika.core.learn.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontMatterTest {
    @Test fun parsesListsAndQuotes() {
        val (m, body) = FrontMatter.parse("---\nname: wechat\nupdated: \"2026-10-08\"\naliases: [WeChat, 'we, chat', \"x\"]\n---\n正文\n第二行\n")
        assertEquals("wechat", m["name"])
        assertEquals("2026-10-08", m["updated"])
        assertEquals(listOf("WeChat", "we, chat", "x"), m["aliases"])
        assertEquals("正文\n第二行", body)
    }

    @Test fun noFrontMatterIsAllBody() {
        val (m, body) = FrontMatter.parse("只有正文")
        assertEquals(emptyMap<String, Any>(), m); assertEquals("只有正文", body)
    }

    @Test fun handlesBomCrLfCommentsAndQuotedCommas() {
        val (m, body) = FrontMatter.parse("\ufeff---\r\nname: demo\r\n# 注释: 不进头部\r\naliases: [\"a, b\", 'c']\r\n---\r\n正文\r\n")
        assertEquals("demo", m["name"])
        assertEquals(listOf("a, b", "c"), m["aliases"])
        assertEquals("正文", body)
    }

    @Test fun quotesAreOnlyStrippedWhenTheyWrapTheWholeValue() {
        val (m, _) = FrontMatter.parse("---\nname: 'demo'\nnote: 前\"引号\"后\n---\n正文")
        assertEquals("demo", m["name"])
        assertEquals("前\"引号\"后", m["note"])
    }

    @Test fun unterminatedHeaderIsAllBodyAndBlankKeysAreSkipped() {
        val (m, body) = FrontMatter.parse("---\nname: demo\n正文没有结束标记")
        assertEquals(emptyMap<String, Any>(), m)
        assertTrue(body.startsWith("---"))
        val (m2, _) = FrontMatter.parse("---\n: 空键\nname: demo\n---\n正文")
        assertEquals("demo", m2["name"])
        assertEquals(1, m2.size)
    }
}
