package com.wangzan101.mika.ui

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.FileLibrary
import com.wangzan101.mika.chat.LibraryFile
import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.agent.Turn
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileLibraryLoadTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun unreadableConversationDoesNotHideOtherFilesAndRetryRecovers() {
        val dir = tmp.newFolder()
        var failing = false
        val store = ConversationStore(dir, readConversation = {
            if (failing && it.name == "broken.json") throw IOException("unreadable")
            it.readText()
        })
        store.append("good", Turn(AttachmentLines.compose("look", listOf("/workspace/a.txt" to "1 B")), "", "succeeded"))
        store.append("broken", Turn("message", "", "succeeded"))
        failing = true
        fun load() = loadLibraryData(
            snapshot = { FileLibrary(store).snapshot() },
            summaries = { store.list() },
            draftTitles = mapOf("draft" to "Draft"),
            fallbackTitle = store::title,
        )

        val partial = load()
        assertTrue(partial.partial)
        assertEquals("a.txt", (partial.snapshot!!.files.single() as LibraryFile.Input).name)
        assertEquals("good", partial.titles["good"])
        assertEquals("Draft", partial.titles["draft"])

        failing = false
        val recovered = load()
        assertFalse(recovered.partial)
        assertEquals("a.txt", (recovered.snapshot!!.files.single() as LibraryFile.Input).name)
        assertTrue(recovered.titles.containsKey("broken"))
    }

    @Test fun indexFailureIsReportedAndCanBeRetried() {
        val store = ConversationStore(tmp.newFolder())
        var failing = true
        fun load() = loadLibraryData(
            snapshot = { if (failing) throw IOException("index") else FileLibrary(store).snapshot() },
            summaries = { emptyList() }, draftTitles = emptyMap(), fallbackTitle = { null },
        )
        val failed = load()
        assertNull(failed.snapshot)
        assertTrue(failed.partial)
        failing = false
        val recovered = load()
        assertNotNull(recovered.snapshot)
        assertFalse(recovered.partial)
    }

    /** 归档状态和标题一起读出来,文件行据此标出「来自已归档对话」。 */
    @Test fun archivedConversationsAreReportedAlongsideTitles() {
        val store = ConversationStore(tmp.newFolder())
        store.append("kept", Turn("message", "", "succeeded"))
        store.append("old", Turn("message", "", "succeeded"))
        store.setArchived("old", archived = true)
        val result = loadLibraryData(
            snapshot = { FileLibrary(store).snapshot() },
            summaries = { store.list(includeArchived = true) },
            draftTitles = emptyMap(),
            fallbackTitle = { null },
        )
        assertEquals(setOf("old"), result.archived)
        assertTrue(result.titles.containsKey("old"))
        assertFalse(result.partial)
    }

    @Test fun projectMembershipIsReturnedAndFormattedAlongsideTitles() {
        val store = ConversationStore(tmp.newFolder())
        store.append("c1", Turn("设计评审", "", "succeeded"))
        store.setProject("c1", "proj-1")
        val result = loadLibraryData(
            snapshot = { FileLibrary(store).snapshot() },
            summaries = { store.list(includeArchived = true) },
            draftTitles = emptyMap(),
            fallbackTitle = { null },
        )
        assertEquals(mapOf("c1" to "proj-1"), result.projectIds)
        assertEquals(
            "工作区项目 / 设计评审",
            libraryConversationDisplay("c1", result.titles, result.projectIds, mapOf("proj-1" to "工作区项目")),
        )
        assertEquals(
            "设计评审",
            libraryConversationDisplay("c1", result.titles, result.projectIds, emptyMap()),
        )
    }

    @Test fun cancellationIsNotConvertedIntoFailure() {
        try {
            loadLibraryData(snapshot = { throw CancellationException("cancel") }, summaries = { emptyList() },
                draftTitles = emptyMap(), fallbackTitle = { null })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // The owning Compose coroutine must be cancelled normally.
        }
    }
}
