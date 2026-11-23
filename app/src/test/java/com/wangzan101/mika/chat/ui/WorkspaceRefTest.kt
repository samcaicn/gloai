package com.wangzan101.mika.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceRefTest {
    @Test
    fun outputRefsParsedSplitsPhotosAndFiles() {
        val p = outputRefsParsed("看图 ![图](/workspace/a.png) 和 [报告](/workspace/b.csv)")
        assertEquals(listOf("/workspace/a.png"), p.photos.map { it.path })
        assertEquals(listOf("/workspace/b.csv"), p.files.map { it.path })
    }

    @Test
    fun outputRefsParsedDropsEscape() {
        val p = outputRefsParsed("![x](/workspace/../x) ![y](/workspace/a.png)")
        assertEquals(listOf("/workspace/a.png"), p.photos.map { it.path })
    }

    @Test
    fun outputRefsParsedDecodesPercentEscapes() {
        val p = outputRefsParsed("[a](/workspace/my%20report.csv) ![图](/workspace/%E6%8A%A5%E5%91%8A.png)")
        assertEquals(listOf("/workspace/报告.png"), p.photos.map { it.path })
        assertEquals(listOf("/workspace/my report.csv"), p.files.map { it.path })
    }

    @Test
    fun outputRefsParsedDropsEncodedEscape() {
        val p = outputRefsParsed("[x](/workspace/%2E%2E/x) ![y](/workspace/a.png)")
        assertEquals(listOf("/workspace/a.png"), p.photos.map { it.path })
        assertEquals(emptyList<String>(), p.files.map { it.path })
    }
}
