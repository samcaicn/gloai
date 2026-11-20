package com.wangzan101.mika.chat

import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.remote.RemoteCompanion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteConversationReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var store: ConversationStore
    private lateinit var reader: RemoteConversationReader

    @org.junit.Before
    fun setUp() {
        store = ConversationStore(tmp.newFolder("c"))
        reader = RemoteConversationReader(store) { emptyList() }
    }

    @Test fun listsConversationsNewestFirstWithCounts() {
        val a = store.newConversation()
        val b = store.newConversation()
        store.append(a, Turn("第一句 A", "回答 A", "succeeded"))
        Thread.sleep(5)
        store.append(b, Turn("第一句 B", "回答 B", "failed", note = "原因"))
        store.append(b, Turn("第二句 B", "回答 B2", "succeeded"))
        store.setPinned(a, true, at = 123)

        val list = reader.conversations(10)
        assertEquals(listOf(b, a), list.map { it.id })
        assertEquals("第一句 B", list[0].title)
        assertEquals(2, list[0].turnCount)
        assertEquals(123L, list[1].pinnedAtMs)
        assertEquals(1, list[1].turnCount)
        assertEquals(1, reader.conversations(1).size)
    }

    @Test fun pagesTurnsAndKeepsEmptyConversationDistinctFromMissing() {
        val id = store.newConversation()
        repeat(5) { i -> store.append(id, Turn("问题 $i", "回答 $i", "succeeded")) }
        val page = reader.conversation(id, from = 2, limit = 2)!!
        assertEquals(5, page.total)
        assertEquals(2, page.from)
        assertEquals(listOf(2, 3), page.turns.map { it.index })
        assertEquals("问题 2", page.turns[0].userText)
        assertNull(reader.conversation("missing-id", 0, 10))
    }

    @Test fun tailReadsTheNewestTurns() {
        val id = store.newConversation()
        repeat(5) { i -> store.append(id, Turn("问题 $i", "回答 $i", "succeeded")) }
        val page = reader.conversation(id, RemoteCompanion.TAIL, 2)!!
        assertEquals(3, page.from)
        assertEquals(listOf(3, 4), page.turns.map { it.index })
    }

    @Test fun clipsLongFieldsAndMarksTheTurn() {
        val id = store.newConversation()
        store.append(id, Turn("问".repeat(RemoteCompanion.MAX_USER_TEXT + 10), "答".repeat(RemoteCompanion.MAX_OUTCOME + 10), "succeeded", note = "注"))
        val turn = reader.conversation(id, 0, 10)!!.turns.single()
        assertTrue(turn.truncated)
        assertTrue(turn.userText.endsWith("…"))
        assertTrue(turn.outcome.endsWith("…"))
        assertEquals("注", turn.note)
    }

    @Test fun runningIsEmptyWithoutTasks() {
        assertTrue(reader.running().isEmpty())
    }
}
