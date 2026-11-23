package com.wangzan101.mika.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class SharedDraftStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun image(store: SharedDraftStore, fileName: String, bytes: ByteArray): SharedDraftAttachment {
        store.attachmentFile(fileName)!!.writeBytes(bytes)
        return SharedDraftAttachment(fileName, "photo.jpg", bytes.size.toLong())
    }

    @Test fun draftAndPrivateImageSurviveStoreRecreation() {
        val dir = tmp.newFolder()
        val store = SharedDraftStore(dir)
        val image = image(store, "one.jpg", byteArrayOf(1, 2, 3))
        val draft = SharedDraft("conversation-1", "https://example.com", image)
        assertTrue(store.save(draft))
        assertEquals(draft, SharedDraftStore(dir).read())

        store.removeAttachment(draft.conversationId)
        assertEquals(draft.copy(attachments = emptyList()), store.read())
        assertFalse(store.attachmentFile("one.jpg")!!.exists())
        store.clear(draft.conversationId)
        assertNull(store.read())
    }

    @Test fun conversationsKeepTheirImagesUntilTheirOwnDraftIsCleared() {
        val dir = tmp.newFolder()
        val store = SharedDraftStore(dir)
        val one = image(store, "one.jpg", byteArrayOf(1))
        val two = image(store, "two.jpg", byteArrayOf(2))
        assertTrue(store.save(SharedDraft("one", "first", one)))
        assertTrue(store.save(SharedDraft("two", "second", two)))
        assertTrue(store.attachmentFile("one.jpg")!!.exists())
        assertTrue(store.attachmentFile("two.jpg")!!.exists())

        store.attachmentFile("orphan.tmp")!!.writeText("partial")
        store.cleanup()
        assertEquals(setOf("draft.json", "one.jpg", "two.jpg"), dir.listFiles()?.map { it.name }?.toSet())
        assertEquals(listOf("one", "two"), SharedDraftStore(dir).readAll().map { it.conversationId })
        store.clear("one")
        assertFalse(store.attachmentFile("one.jpg")!!.exists())
        assertTrue(store.attachmentFile("two.jpg")!!.exists())
        store.clear("two")
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun editingAnOlderShareKeepsTheLatestShareAndOnlyReplacesItsOwnImage() {
        val store = SharedDraftStore(tmp.newFolder())
        val one = SharedDraft("one", "first", image(store, "one.jpg", byteArrayOf(1)))
        val two = SharedDraft("two", "second", image(store, "two.jpg", byteArrayOf(2)))
        assertTrue(store.save(one))
        assertTrue(store.save(two))
        val edited = one.copy(text = "edited", attachments = listOf(image(store, "replacement.jpg", byteArrayOf(3))))
        assertTrue(store.save(edited))
        assertEquals(listOf(edited, two), store.readAll())
        assertEquals(two, store.read())
        assertFalse(store.attachmentFile("one.jpg")!!.exists())
        assertTrue(store.attachmentFile("two.jpg")!!.exists())
        assertTrue(store.attachmentFile("replacement.jpg")!!.exists())
    }

    @Test fun legacySingleDraftFileIsPreservedWhenAnotherConversationIsAdded() {
        val dir = tmp.newFolder()
        File(dir, "draft.json").writeText("""{"conversation":"old","text":"keep me"}""")
        val store = SharedDraftStore(dir)
        assertEquals(SharedDraft("old", "keep me"), store.read())
        assertTrue(store.save(SharedDraft("new", "second")))
        assertEquals(listOf("keep me", "second"), SharedDraftStore(dir).readAll().map { it.text })
    }

    @Test fun legacyImageAndPendingDocumentSurviveRecreationAndReplacement() {
        val dir = tmp.newFolder()
        val store = SharedDraftStore(dir)
        val photo = image(store, "old.jpg", byteArrayOf(1))
        File(dir, "draft.json").writeText(
            """{"drafts":[{"conversation":"old","text":"","image":{"file":"old.jpg","name":"photo.jpg","size":1}}]}""",
        )
        val oldDraft = SharedDraft("old", "", photo)
        assertEquals(oldDraft, store.read())

        val document = SharedDraftAttachment("copy.pdf", "报告.pdf", 2, photo = false)
        val bytes = byteArrayOf(2, 3)
        store.attachmentFile(document.fileName)!!.writeBytes(bytes)
        val replacement = SharedDraft("old", "", document)
        assertTrue(store.savePending(replacement))

        val restored = SharedDraftStore(dir)
        restored.cleanup()
        assertEquals(oldDraft, restored.read())
        assertEquals(replacement, restored.readPending())
        assertTrue(restored.save(replacement))
        assertNull(restored.readPending())
        assertFalse(File(dir, photo.fileName).exists())
        assertEquals(replacement, SharedDraftStore(dir).read())
        assertArrayEquals(bytes, File(dir, document.fileName).readBytes())
        restored.removeAttachment("old")
        assertNull(restored.read())
        assertFalse(File(dir, document.fileName).exists())
    }

    @Test fun blankOversizeAndForgedPathsAreRejectedWithoutChangingCurrentDraft() {
        val store = SharedDraftStore(tmp.newFolder())
        val good = image(store, "good.jpg", byteArrayOf(4))
        val draft = SharedDraft("good", "keep", good)
        assertTrue(store.save(draft))
        assertFalse(store.save(SharedDraft("blank", " ")))
        assertFalse(store.save(SharedDraft("bad", "x", SharedDraftAttachment("../outside", "x", 1))))
        assertFalse(store.save(SharedDraft("bad", "x", SharedDraftAttachment("missing.jpg", "x", 1))))
        assertFalse(store.save(SharedDraft("bad", "x".repeat(SharedDraftStore.MAX_TEXT_CHARS + 1))))
        assertEquals(draft, store.read())
    }

    @Test fun pendingDraftAndImportingImageAreProtectedAcrossCleanupAndOverwrite() {
        val dir = tmp.newFolder()
        val store = SharedDraftStore(dir)
        val first = image(store, "first.jpg", byteArrayOf(1))
        val pendingImg = image(store, "pending.jpg", byteArrayOf(2))

        assertTrue(store.save(SharedDraft("c1", "first", first)))
        assertTrue(store.savePending(SharedDraft("c1", "pending", pendingImg)))
        store.cleanup()
        assertTrue(store.attachmentFile("first.jpg")!!.exists())
        assertTrue(store.attachmentFile("pending.jpg")!!.exists())
        assertEquals(SharedDraft("c1", "pending", pendingImg), SharedDraftStore(dir).readPending())

        // 正在导入中的图片在同会话覆盖保存时不会被提前删除,导入结束后才释放删除。
        val nextImg = image(store, "next.jpg", byteArrayOf(3))
        store.setImporting("first.jpg", true)
        assertTrue(store.save(SharedDraft("c1", "next", nextImg)))
        assertNull(store.readPending())
        assertFalse(store.attachmentFile("pending.jpg")!!.exists())
        assertTrue(store.attachmentFile("first.jpg")!!.exists())
        assertTrue(store.attachmentFile("next.jpg")!!.exists())

        store.setImporting("first.jpg", false)
        assertFalse(store.attachmentFile("first.jpg")!!.exists())
        assertTrue(store.attachmentFile("next.jpg")!!.exists())
    }

    @Test fun boundedCopyRejectsSizeAndLowStorageAndRemovesTemporaryFile() = runTest {
        val dir = tmp.newFolder()
        val tmpFile = File(dir, "image.tmp")
        val final = File(dir, "image.jpg")
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            ByteArrayInputStream(byteArrayOf(1, 2, 3)).let { input ->
                val size = SharedDraftCopy.copy(input, tmpFile, final, maxBytes = 3, minFree = 0) { 100 }
                assertEquals(3L, size)
                final.readBytes()
            },
        )
        assertFalse(tmpFile.exists())

        for (lowStorage in listOf(false, true)) {
            val result = runCatching {
                SharedDraftCopy.copy(
                    ByteArrayInputStream(byteArrayOf(1, 2, 3)), tmpFile, final,
                    maxBytes = if (lowStorage) 3 else 2,
                    minFree = if (lowStorage) 10 else 0,
                ) { if (lowStorage) 0 else 100 }
            }
            assertTrue(result.isFailure)
            assertFalse(tmpFile.exists())
        }

        val cancelled = runCatching {
            SharedDraftCopy.copy(
                object : java.io.InputStream() {
                    override fun read(): Int = throw CancellationException("cancelled")
                }, tmpFile, final, maxBytes = 3, minFree = 0,
            ) { 100 }
        }
        assertTrue(cancelled.exceptionOrNull() is CancellationException)
        assertFalse(tmpFile.exists())
    }

    @Test fun multipleAttachmentsPersistSupportSingleRemovalAndEnforceLimits() {
        val dir = tmp.newFolder()
        val store = SharedDraftStore(dir)
        val photo = image(store, "one.jpg", byteArrayOf(1, 2))
        val doc = SharedDraftAttachment("two.pdf", "合同.pdf", 3, photo = false).also {
            store.attachmentFile(it.fileName)!!.writeBytes(byteArrayOf(3, 4, 5))
        }
        val audio = SharedDraftAttachment("three.mp3", "录音.mp3", 1, photo = false).also {
            store.attachmentFile(it.fileName)!!.writeBytes(byteArrayOf(6))
        }
        val draft = SharedDraft("c1", "请一起整理", listOf(photo, doc, audio))
        assertTrue(store.save(draft))
        assertEquals(draft, SharedDraftStore(dir).read())

        // 只删除其中一个附件时,其余附件和文件保留。
        store.removeAttachment("c1", doc.fileName)
        assertEquals(SharedDraft("c1", "请一起整理", listOf(photo, audio)), SharedDraftStore(dir).read())
        assertFalse(store.attachmentFile(doc.fileName)!!.exists())
        assertTrue(store.attachmentFile(photo.fileName)!!.exists())
        assertTrue(store.attachmentFile(audio.fileName)!!.exists())

        // 导入保护覆盖所有附件。
        store.setImporting(listOf(photo.fileName, audio.fileName), true)
        store.clear("c1")
        assertTrue(store.attachmentFile(photo.fileName)!!.exists())
        assertTrue(store.attachmentFile(audio.fileName)!!.exists())
        store.setImporting(listOf(photo.fileName, audio.fileName), false)
        assertFalse(store.attachmentFile(photo.fileName)!!.exists())
        assertFalse(store.attachmentFile(audio.fileName)!!.exists())

        // 超过上限或重复 fileName 时拒绝保存。
        val ten = (1..10).map { i -> image(store, "f$i.jpg", byteArrayOf(i.toByte())) }
        assertFalse(store.save(SharedDraft("c2", "", ten)))
        assertFalse(store.save(SharedDraft("c2", "", listOf(ten[0], ten[0]))))
        assertTrue(store.save(SharedDraft("c2", "", ten.take(SharedDraftStore.MAX_ATTACHMENTS))))
        assertEquals(SharedDraftStore.MAX_ATTACHMENTS, SharedDraftStore(dir).read()?.attachments?.size)
    }

    @Test fun legacySingleAttachmentJsonStillDecodes() {
        val dir = tmp.newFolder()
        val store = SharedDraftStore(dir)
        val doc = SharedDraftAttachment("legacy.pdf", "旧文档.pdf", 2, photo = false)
        store.attachmentFile(doc.fileName)!!.writeBytes(byteArrayOf(9, 8))
        File(dir, "draft.json").writeText(
            """{"drafts":[{"conversation":"c1","text":"hi","attachment":{"file":"legacy.pdf","name":"旧文档.pdf","size":2,"photo":false}}]}""",
        )
        assertEquals(SharedDraft("c1", "hi", listOf(doc)), store.read())
    }
}
