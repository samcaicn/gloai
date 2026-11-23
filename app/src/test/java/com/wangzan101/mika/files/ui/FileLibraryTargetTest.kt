package com.wangzan101.mika.files.ui

import com.wangzan101.mika.chat.FileSource
import org.junit.Assert.assertEquals
import org.junit.Test
import com.wangzan101.mika.chat.ui.ChatTarget

class FileLibraryTargetTest {
    private val source = FileSource("a", turnIndex = 3, runId = "run", atMs = 10L)

    @Test fun inputFileJumpsToTheMessageThatAttachedIt() {
        assertEquals(ChatTarget.Message(3, null), fileLibraryTarget(source, input = true))
        assertEquals(ChatTarget.Message(3, 1), fileLibraryTarget(source.copy(steerIndex = 1), input = true))
    }

    @Test fun outputFileJumpsToTheTurnFileCard() {
        assertEquals(ChatTarget.Files(3), fileLibraryTarget(source, input = false))
        // 产物卡片属于整轮,不跟随某次插话
        assertEquals(ChatTarget.Files(3), fileLibraryTarget(source.copy(steerIndex = 1), input = false))
    }
}
