package com.wangzan101.mika.files.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFileActionsTest {
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    /** 副本连同 `.tmp` 要放得进文件名的 255 字节,扩展名留着(放进草稿、预览都按它认类型),字不劈开。 */
    @Test fun copyNameKeepsTheExtensionAndFitsTheFileSystem() {
        val ascii = LibraryFileActions.copyName("r".repeat(230) + ".xlsx")
        assertTrue(ascii, ascii.endsWith(".xlsx"))
        assertTrue(bytes("$ascii.tmp") <= 255)

        // 按字节截:中文 83 个字加 .pdf 是 253 字节,接上 .tmp 就超了
        val cjk = LibraryFileActions.copyName("报".repeat(83) + ".pdf")
        assertTrue(cjk, cjk.endsWith(".pdf"))
        assertTrue(bytes("$cjk.tmp") <= 255)

        // 截到 240 字节时那个 emoji 放不下,整个丢掉,不留半个
        assertEquals("a".repeat(233) + ".txt", LibraryFileActions.copyName("a".repeat(233) + "\uD83D\uDE00" + ".txt"))

        assertEquals("合同.pdf", LibraryFileActions.copyName("合同.pdf"))
        assertEquals("a_b.txt", LibraryFileActions.copyName("a/b.txt"))
        assertEquals("attachment", LibraryFileActions.copyName(".."))
    }
}
