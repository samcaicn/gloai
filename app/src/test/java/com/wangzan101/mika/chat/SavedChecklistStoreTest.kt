package com.wangzan101.mika.chat

import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.KeyCipher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class SavedChecklistStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val file get() = File(tmp.root, "lists.bin")
    private val cipher = AesGcmKeyCipher { key }
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun store(c: KeyCipher = cipher) = SavedChecklistStore(file, c) { 1000L }

    @Test fun encryptedRevisionsAndSourcesSurviveReloadAndDeletion() {
        val store = store()
        val source = ChecklistSource("chat", "answer-1", "数量：20\n交期：待确认")
        assertNull(store.save("id", 0, "采购需求", source.text, source))
        assertNull(store.save("id", 1, "采购需求", "数量：30\n交期：周五"))
        val reopened = store()
        assertTrue(reopened.load())
        assertEquals(store.items.value, reopened.items.value)
        val item = reopened.items.value.single()
        assertEquals(source, item.revisions[1].source)
        assertEquals(2, item.current.version)
        assertNull(item.current.source)
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("采购需求"))
        assertNull(reopened.delete("id", 2))
        assertTrue(store().also { assertTrue(it.load()) }.items.value.isEmpty())
    }

    @Test fun failedEncryptionLeavesMemoryAndDiskUntouchedThenCanRetry() {
        var fail = false
        val storage = store(object : KeyCipher {
            override fun encrypt(plain: ByteArray) = if (fail) error("unavailable") else cipher.encrypt(plain)
            override fun decrypt(data: ByteArray) = cipher.decrypt(data)
        })
        assertNull(storage.save("id", 0, "原清单", "原内容"))
        val before = file.readBytes()
        fail = true
        assertEquals(ChecklistError.SaveFailed, storage.save("id", 1, "更新", "新内容"))
        assertEquals(ChecklistError.SaveFailed, storage.delete("id", 1))
        assertArrayEquals(before, file.readBytes())
        assertEquals("原内容", storage.items.value.single().current.body)
        fail = false
        assertNull(storage.save("id", 1, "更新", "新内容"))
        assertEquals(2, storage.items.value.single().current.version)
    }

    @Test fun corruptStorageCannotBeOverwrittenAndCanBeReadAfterRecovery() {
        assertNull(store().save("id", 0, "原清单", "原内容"))
        val good = file.readBytes()
        file.writeBytes(byteArrayOf(1, 2, 3))
        val store = store()
        assertFalse(store.load())
        assertEquals(ChecklistError.ReadFailed, store.save("new", 0, "新清单", "新内容"))
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        file.writeBytes(good)
        assertTrue(store.load())
        assertEquals("原清单", store.items.value.single().current.title)
    }

    @Test fun staleEditorsAndDeletesCannotOverwriteNewerRevisions() {
        val store = store()
        assertNull(store.save("id", 0, "清单", "第一版"))
        assertNull(store.save("id", 1, "清单", "第二版"))
        assertEquals(ChecklistError.Changed, store.save("id", 1, "过期编辑", "旧内容"))
        assertEquals(ChecklistError.Changed, store.delete("id", 1))
        assertEquals(ChecklistError.Changed, store.save("id", 0, "重名ID", "旧内容"))
        assertEquals("第二版", store.items.value.single().current.body)
        assertNull(store.delete("id", 2))
        assertEquals(ChecklistError.Missing, store.save("id", 2, "删除后", "旧内容"))
    }

    @Test fun boundsAndHistoryLimitKeepTheNewestVersions() {
        val store = store()
        assertEquals(ChecklistError.Blank, store.save("id", 0, " ", "内容"))
        assertEquals(ChecklistError.TooLong, store.save("id", 0, "标题", "字".repeat(SavedChecklistStore.CONTENT_MAX + 1)))
        repeat(12) { assertNull(store.save("id", it, "清单", "第${it + 1}版")) }
        assertEquals((12 downTo 3).toList(), store.items.value.single().revisions.map { it.version })
        assertNull(store.save("id", 12, "清单", "第12版"))
        assertEquals(12, store.items.value.single().current.version)
        repeat(SavedChecklistStore.MAX_LISTS - 1) { assertNull(store.save("other-$it", 0, "其他", "内容")) }
        assertEquals(ChecklistError.Full, store.save("extra", 0, "其他", "内容"))
    }

    @Test fun lineChangesPreserveDuplicatesAndDistinguishReordering() {
        assertEquals(ChecklistChanges(listOf("数量：30"), listOf("数量：20"), false),
            checklistChanges("数量：20\n交期：待确认", "数量：30\n交期：待确认"))
        assertEquals(listOf("相同"), checklistChanges("相同\n相同", "相同").removed)
        assertTrue(checklistChanges("A\nB", "B\nA").reordered)
        assertEquals(ChecklistChanges(emptyList(), emptyList(), false), checklistChanges("A", "A"))
    }
}
