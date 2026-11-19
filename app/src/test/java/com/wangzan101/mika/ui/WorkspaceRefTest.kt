package com.wangzan101.mika.ui

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
}
