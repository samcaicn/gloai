package com.wangzan101.mika.wechat

import com.wangzan101.mika.cap.sandbox.JVM_GATE
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.wechat.WechatImage
import com.wangzan101.mika.core.wechat.WechatImages
import com.wangzan101.mika.core.workspace.SandboxTree
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

class WechatImageFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYPj/HwADAgH/5ncLrgAAAABJRU5ErkJggg==")
    private val peer = ChannelPeer("wechat", "owner", "thread")

    @Test fun orderedPicturesBecomeRealWorkspaceAttachmentsAndTemporaryFilesAreRemoved() = runBlocking {
        val workspace = tmp.newFolder()
        val staging = tmp.newFolder()
        val seen = mutableListOf<WechatImage>()
        val images = listOf(WechatImage(aesKey = "first"), WechatImage(aesKey = "second"))
        val files = StoredWechatImages(staging, { seen += it; WechatImages.validate(png) },
            { PreparedWechatImage(it.bytes, it.extension) }, { workspace }, JVM_GATE, SandboxTree(), { true }, { null })
        var stored = emptyList<SavedWechatImage>()
        assertTrue(files.receive(peer, images.map(::DownloadWechatImage)) { stored = it; true })
        assertEquals(listOf("/workspace/inbox/wechat-1.png", "/workspace/inbox/wechat-2.png"), stored.map { it.path })
        assertEquals(images, seen)
        stored.forEach { assertArrayEquals(png, File(workspace, it.path.removePrefix("/workspace/")).readBytes()) }
        assertEquals(0, staging.listFiles()!!.size)
    }

    @Test fun rejectedSessionAndPartialDownloadLeaveNoNewAttachments() = runBlocking {
        val workspace = tmp.newFolder()
        val inbox = File(workspace, "inbox").apply { mkdirs() }
        val existing = File(inbox, "wechat-1.png").apply { writeBytes(png) }
        val staging = tmp.newFolder()
        var downloads = 0
        val files = StoredWechatImages(staging, {
            downloads++
            if (it.aesKey == "bad") throw IOException("download failed")
            WechatImages.validate(png)
        }, { PreparedWechatImage(it.bytes, it.extension) }, { workspace }, JVM_GATE, SandboxTree(), { true }, { null })
        assertFalse(files.receive(peer, listOf(WechatImage()).map(::DownloadWechatImage)) { false })
        assertEquals(listOf(existing.name), inbox.list()!!.toList())
        val failure = runCatching {
            files.receive(peer, listOf(WechatImage(), WechatImage(aesKey = "bad")).map(::DownloadWechatImage)) { fail("Must not launch a partial batch"); true }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(3, downloads)
        assertEquals(listOf(existing.name), inbox.list()!!.toList())
        assertEquals(0, staging.listFiles()!!.size)
    }

    @Test fun journalFailureRollsBackTheImportAndTooManyImagesAreRejectedBeforeDownloading() = runBlocking {
        val workspace = tmp.newFolder()
        var downloads = 0
        val files = StoredWechatImages(tmp.newFolder(), { downloads++; WechatImages.validate(png) },
            { PreparedWechatImage(it.bytes, it.extension) }, { workspace }, JVM_GATE, SandboxTree(), { true }, { null })
        val failure = runCatching { files.receive(peer, listOf(WechatImage()).map(::DownloadWechatImage)) { throw IOException("disk full") } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(0, File(workspace, "inbox").listFiles()!!.size)
        assertTrue(runCatching { files.receive(peer, List(WechatImages.MAX_IMAGES + 1) { DownloadWechatImage(WechatImage()) }) { true } }.exceptionOrNull() is IOException)
        assertEquals(1, downloads)
    }

    @Test fun exportedImageMustStillMatchItsRecordedSizeAndDigest() = runBlocking {
        val digest = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
        val image = Delivery.Image("result:1:image:0", "thread", "content://media/external/downloads/12", "shot.png", png.size.toLong(), digest)
        assertArrayEquals(png, readWechatExport(image) { ByteArrayInputStream(png) })
        for (changed in listOf(image.copy(size = image.size - 1), image.copy(size = image.size + 1), image.copy(sha256 = "0".repeat(64)))) {
            assertTrue(runCatching { readWechatExport(changed) { ByteArrayInputStream(png) } }.exceptionOrNull() is IOException)
        }
        for (uri in listOf("file:///etc/passwd", "content://other.provider/image/1", "https://example.test/private.png")) {
            assertTrue(runCatching { readWechatExport(image.copy(uri = uri)) { fail("Must reject before opening"); null } }.exceptionOrNull() is IOException)
        }
        assertTrue(runCatching { readWechatExport(image) { null } }.exceptionOrNull() is IOException)
    }

    @Test fun cancellationDuringSecondDownloadRemovesStagingAndNeverImportsOrJournals() = runBlocking {
        val workspace = tmp.newFolder()
        val staging = tmp.newFolder()
        val downloadingSecond = CompletableDeferred<Unit>()
        var count = 0
        val files = StoredWechatImages(staging, {
            if (++count == 2) { downloadingSecond.complete(Unit); awaitCancellation() }
            WechatImages.validate(png)
        }, { PreparedWechatImage(it.bytes, it.extension) }, { workspace }, JVM_GATE, SandboxTree(), { true }, { null })
        val job = launch { files.receive(peer, listOf(WechatImage(), WechatImage()).map(::DownloadWechatImage)) { fail("Cancelled batch must not be accepted"); true } }
        withTimeout(2_000) { downloadingSecond.await(); job.cancelAndJoin() }
        assertEquals(0, staging.listFiles()!!.size)
        assertFalse(File(workspace, "inbox").exists())
    }

    @Test fun workspaceQuotaReachedByTheCopyRollsBackBeforeJournal() = runBlocking {
        val workspace = tmp.newFolder()
        val files = StoredWechatImages(tmp.newFolder(), { WechatImages.validate(png) },
            { PreparedWechatImage(it.bytes, it.extension) }, { workspace }, JVM_GATE, SandboxTree(),
            { File(workspace, "inbox").listFiles().orEmpty().sumOf { it.length() } < png.size }, { null })
        val failure = runCatching {
            files.receive(peer, listOf(WechatImage()).map(::DownloadWechatImage)) { fail("Over-quota batch must not be accepted"); true }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(0, File(workspace, "inbox").listFiles()!!.size)
    }
}
