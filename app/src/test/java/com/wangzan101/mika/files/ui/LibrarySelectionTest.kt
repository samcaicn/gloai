package com.wangzan101.mika.files.ui

import com.wangzan101.mika.chat.FileSource
import com.wangzan101.mika.chat.SharedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LibrarySelectionTest {
    @Test fun inputKeyIncludesConversationAndPath() {
        assertEquals("in:a:/workspace/inbox/x.pdf", LibrarySelection.inputKey("a", "/workspace/inbox/x.pdf"))
        assertNotEquals(
            LibrarySelection.inputKey("a", "/workspace/inbox/x.pdf"),
            LibrarySelection.inputKey("b", "/workspace/inbox/x.pdf"),
        )
    }

    @Test fun outputKeyUsesUriWhenPresentAndSourceWhenMissing() {
        val file = SharedFile("x.pdf", 3, "Download/Mika/x.pdf", "")
        val first = FileSource("a", 0, "run", 1L)
        val second = FileSource("a", 1, "run", 2L)
        assertNotEquals(LibrarySelection.outputKey(file, first), LibrarySelection.outputKey(file, second))
        assertEquals(
            "out:uri:content://media/1",
            LibrarySelection.outputKey(file.copy(uri = "content://media/1"), first),
        )
    }

    @Test fun toggleAddsAndRemoves() {
        assertEquals(setOf("a"), LibrarySelection.toggle(emptySet(), "a"))
        assertEquals(setOf("b"), LibrarySelection.toggle(setOf("a", "b"), "a"))
    }

    @Test fun toggleAllOnlyAffectsVisibleRows() {
        assertEquals(setOf("a", "b", "c"), LibrarySelection.toggleAll(setOf("a"), listOf("b", "c")))
        assertEquals(setOf("a"), LibrarySelection.toggleAll(setOf("a", "b", "c"), listOf("b", "c")))
    }

    @Test fun pruneDropsKeysThatDisappeared() {
        assertEquals(setOf("b"), LibrarySelection.prune(listOf("a", "b"), listOf("b", "c")))
    }
}
