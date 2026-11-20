package com.wangzan101.mika.chat

import com.wangzan101.mika.cap.sandbox.JVM_GATE
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.core.workspace.SandboxTree
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** 分支工作区只复制 `inbox/` 里的普通文件;符号链接、inbox 外的文件都不进去。纯 JVM,不依赖 Android。 */
class ConversationBrancherTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun copiesInboxFilesAndStructureOnly() {
        val source = tmp.newFolder("source")
        val target = tmp.newFolder("target")
        File(source, "inbox/nested").mkdirs()
        File(source, "inbox/a.txt").writeText("A")
        File(source, "inbox/nested/b.txt").writeText("BB")
        File(source, "out").mkdirs()
        File(source, "out/result.txt").writeText("任务输出")
        File(source, "note.txt").writeText("inbox 外")

        assertEquals(BranchCopyResult(copied = 2), copyInboxTree(source, target, maxBytes = 1024))

        assertEquals("A", File(target, "inbox/a.txt").readText())
        assertEquals("BB", File(target, "inbox/nested/b.txt").readText())
        assertFalse(File(target, "out").exists())
        assertFalse(File(target, "note.txt").exists())
    }

    @Test fun skipsSymbolicLinks() {
        val source = tmp.newFolder("source")
        val target = tmp.newFolder("target")
        val outside = tmp.newFile("outside.txt").apply { writeText("outside") }
        File(source, "inbox").mkdirs()
        File(source, "inbox/real.txt").writeText("R")
        val link = File(source, "inbox/link.txt").toPath()
        val created = runCatching { Files.createSymbolicLink(link, outside.toPath()) }.isSuccess
        assumeTrue("platform cannot create symbolic links", created)

        val result = copyInboxTree(source, target, maxBytes = 1024)!!

        assertEquals(1, result.copied)
        assertTrue(result.skipped >= 1)
        assertFalse(File(target, "inbox/link.txt").exists())
        assertEquals("R", File(target, "inbox/real.txt").readText())
    }

    @Test fun overTheLimitLeavesNoHalfCopiedInbox() {
        val source = tmp.newFolder("source")
        val target = tmp.newFolder("target")
        File(source, "inbox").mkdirs()
        File(source, "inbox/a.txt").writeBytes(ByteArray(600) { 'a'.code.toByte() })
        File(source, "inbox/b.txt").writeBytes(ByteArray(600) { 'b'.code.toByte() })

        val result = copyInboxTree(source, target, maxBytes = 1000)!!

        assertEquals(BranchCopyResult(copied = 0, truncated = true), result)
        assertFalse(File(target, "inbox").exists())
        assertTrue(target.listFiles().orEmpty().none { it.name.startsWith(".inbox-tmp-") })
    }

    @Test fun missingSourceInboxReturnsNull() {
        val source = tmp.newFolder("source")
        val target = tmp.newFolder("target")
        assertNull(copyInboxTree(source, target, maxBytes = 1024))
        assertNull(copyInboxTree(tmp.newFolder("no-workspace"), target, maxBytes = 1024))
    }

    @Test fun keepsAnExistingTargetInbox() {
        val source = tmp.newFolder("source")
        val target = tmp.newFolder("target")
        File(source, "inbox").mkdirs()
        File(source, "inbox/a.txt").writeText("新的")
        File(target, "inbox").mkdirs()
        File(target, "inbox/a.txt").writeText("旧的")

        assertEquals(BranchCopyResult(copied = 0, skipped = 1), copyInboxTree(source, target, maxBytes = 1024))
        assertEquals("旧的", File(target, "inbox/a.txt").readText())
    }

    @Test fun retryCopiesRequestedFirstAndContinuesAfterLimitAndMissing() {
        assumeTrue("JVM gate requires Unix file attributes", File(tmp.root.path).toPath().fileSystem.supportedFileAttributeViews().contains("unix"))
        val source = tmp.newFolder("source")
        val target = tmp.newFolder("target")
        File(source, "inbox").mkdirs()
        File(source, "inbox/a-history").writeText("HH")
        File(source, "inbox/b-large").writeText("12345")
        File(source, "inbox/c-request").writeText("abc")
        val prefix = "/workspace/inbox/"
        val paths = listOf(prefix + "c-request", prefix + "b-large", prefix + "missing")
        assertEquals(listOf(
            RetryAttachmentResult(paths[0], RetryAttachmentStatus.COPIED, 3),
            RetryAttachmentResult(paths[1], RetryAttachmentStatus.OVER_LIMIT),
            RetryAttachmentResult(paths[2], RetryAttachmentStatus.MISSING),
        ), copyRetryAttachments(source, target, paths, 5, JVM_GATE))
        assertEquals("HH", File(target, "inbox/a-history").readText())
        assertFalse(File(target, "inbox/b-large").exists())
        assertEquals(listOf(
            RetryAttachmentResult(paths[0], RetryAttachmentStatus.COPIED, 3),
            RetryAttachmentResult(paths[1], RetryAttachmentStatus.MISSING),
        ), checkRetryAttachments(target, paths.take(2), JVM_GATE))
    }

    @Test fun retryDoesNotAcceptExistingSameNameOrUnsafePaths() {
        assumeTrue("JVM gate requires Unix file attributes", tmp.root.toPath().fileSystem.supportedFileAttributeViews().contains("unix"))
        val source = tmp.newFolder("source")
        val target = tmp.newFolder("target")
        File(source, "inbox").mkdirs()
        File(target, "inbox").mkdirs()
        File(source, "inbox/a").writeText("new")
        File(target, "inbox/a").writeText("old")
        val paths = listOf("/workspace/inbox/a", "/workspace/inbox/../secret")
        assertEquals(paths.map { RetryAttachmentResult(it, RetryAttachmentStatus.FAILED) },
            copyRetryAttachments(source, target, paths, 100, JVM_GATE))
        assertEquals("old", File(target, "inbox/a").readText())
        assertEquals(RetryAttachmentStatus.FAILED, checkRetryAttachments(target, paths, JVM_GATE)[1].status)
    }

    @Test fun retryRejectsSymlinksInSourceAndTarget() {
        assumeTrue("JVM gate requires Unix file attributes", tmp.root.toPath().fileSystem.supportedFileAttributeViews().contains("unix"))
        val source = tmp.newFolder("source")
        val target = tmp.newFolder("target")
        val outside = tmp.newFile("outside").apply { writeText("secret") }
        File(source, "inbox").mkdirs()
        File(target, "inbox").mkdirs()
        val sourceLink = File(source, "inbox/link").toPath()
        val targetLink = File(target, "inbox/link").toPath()
        assumeTrue(runCatching {
            Files.createSymbolicLink(sourceLink, outside.toPath())
            Files.createSymbolicLink(targetLink, outside.toPath())
        }.isSuccess)
        val paths = listOf("/workspace/inbox/link")
        assertTrue(copyRetryAttachments(source, target, paths, 100, JVM_GATE).single().status != RetryAttachmentStatus.COPIED)
        assertTrue(checkRetryAttachments(target, paths, JVM_GATE).single().status != RetryAttachmentStatus.COPIED)
        assertEquals("secret", outside.readText())
    }

    @Test fun createsAnEmptyInboxForAnEmptySourceInbox() {
        val source = tmp.newFolder("source")
        val target = tmp.newFolder("target")
        File(source, "inbox").mkdirs()
        assertEquals(BranchCopyResult(copied = 0), copyInboxTree(source, target, maxBytes = 1024))
        assertTrue(File(target, "inbox").isDirectory)
    }

    @Test fun sharedProjectBranchSkipsSelfCopyAndCopiesAcrossWorkspaces() = runBlocking {
        val dirs = SandboxDirs(tmp.newFolder("sandbox"), tmp.newFolder("tmp"), tmp.newFolder("lib"), JVM_GATE, SandboxTree())
        val sharedKey = WorkspaceKey.Project("proj-shared")
        val sharedDir = dirs.workspace(sharedKey)
        File(sharedDir, "inbox").mkdirs()
        File(sharedDir, "inbox/doc.txt").writeText("shared-content")

        val branchWorkspace = AndroidBranchWorkspace(dirs) { id ->
            if (id == "c1" || id == "c2") sharedKey else WorkspaceKey.Conversation(id)
        }

        // 同项目内分支共享同一目录:不做自拷贝
        assertNull(branchWorkspace.copyInbox("c1", "c2"))

        // 跨工作区分支按各自解析结果复制到独立目录
        assertEquals(BranchCopyResult(copied = 1), branchWorkspace.copyInbox("c1", "c-solo"))
        assertEquals("shared-content", File(dirs.workspace(WorkspaceKey.Conversation("c-solo")), "inbox/doc.txt").readText())
    }

    @Test fun sharedProjectRetryChecksExistingFilesWithoutCopying() = runBlocking {
        assumeTrue("JVM gate requires Unix file attributes", tmp.root.toPath().fileSystem.supportedFileAttributeViews().contains("unix"))
        val dirs = SandboxDirs(tmp.newFolder("sandbox-retry"), tmp.newFolder("tmp-retry"), tmp.newFolder("lib-retry"), JVM_GATE, SandboxTree())
        val sharedKey = WorkspaceKey.Project("proj-shared")
        val sharedDir = dirs.workspace(sharedKey)
        File(sharedDir, "inbox").mkdirs()
        File(sharedDir, "inbox/doc.txt").writeText("shared-content")

        val branchWorkspace = AndroidBranchWorkspace(dirs) { id ->
            if (id == "c1" || id == "c2") sharedKey else WorkspaceKey.Conversation(id)
        }

        assertEquals(
            listOf(
                RetryAttachmentResult("/workspace/inbox/doc.txt", RetryAttachmentStatus.COPIED, sizeBytes = 14L),
                RetryAttachmentResult("/workspace/inbox/missing.txt", RetryAttachmentStatus.MISSING),
            ),
            branchWorkspace.copyInboxForRetry("c1", "c2", listOf("/workspace/inbox/doc.txt", "/workspace/inbox/missing.txt")),
        )
    }
}
