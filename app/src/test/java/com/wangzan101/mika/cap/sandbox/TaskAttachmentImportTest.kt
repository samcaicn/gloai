package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.SandboxTree
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException

class TaskAttachmentImportTest {
    @get:Rule val temp = TemporaryFolder()

    private fun bytes(count: Long): InputStream = object : InputStream() {
        var remaining = count
        override fun read(): Int = if (remaining-- > 0) 0 else -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val n = minOf(remaining, length.toLong()).toInt()
            buffer.fill(0, offset, offset + n)
            remaining -= n
            return n
        }
    }

    @Test fun legacyResultDefaultsFailuresToIoError() {
        val result = ImportResult(emptyList(), listOf("a", "b"))
        assertEquals(listOf(AttachmentFailure("a", AttachmentFailureReason.IO_ERROR),
            AttachmentFailure("b", AttachmentFailureReason.IO_ERROR)), result.failures)
    }

    @Test fun classifiesLimitsBeforeAndDuringCopyAndCleansPartialFiles() = runBlocking {
        val ws = temp.newFolder()
        val inbox = File(ws, "inbox").apply { mkdir() }
        val existing = File(inbox, "file").apply { writeText("keep") }
        suspend fun check(reason: AttachmentFailureReason, free: () -> Long, used: () -> Long, size: Long) {
            val result = Attachments.importOne(ws, JVM_GATE, "file", free, used, { bytes(size) })
            assertEquals(listOf("file"), result.rejected)
            assertEquals(listOf(AttachmentFailure("file", reason)), result.failures)
            assertTrue(result.added.isEmpty())
            assertEquals(setOf("file"), inbox.list()!!.toSet())
            assertEquals("keep", existing.readText())
        }
        check(AttachmentFailureReason.LOW_DEVICE_SPACE, { Attachments.MIN_FREE - 1 }, { 0 }, 1)
        check(AttachmentFailureReason.WORKSPACE_LIMIT, { Attachments.MIN_FREE }, { Attachments.WORKSPACE_MAX + 1 }, 1)
        var freeChecks = 0
        check(AttachmentFailureReason.LOW_DEVICE_SPACE, { if (freeChecks++ == 0) Attachments.MIN_FREE else 0 }, { 0 }, 16L shl 20)
        var usedChecks = 0
        check(AttachmentFailureReason.WORKSPACE_LIMIT, { Attachments.MIN_FREE },
            { if (usedChecks++ == 0) Attachments.WORKSPACE_MAX else Attachments.WORKSPACE_MAX + 1 }, 16L shl 20)
        check(AttachmentFailureReason.TOO_LARGE, { Attachments.MIN_FREE }, { 0 }, Attachments.MAX_BYTES + 1)
    }

    @Test fun injectedLimitsRaiseAndLowerBothCaps() = runBlocking {
        val ws = temp.newFolder()
        val twoMiB = 2L shl 20
        // 单文件上限收到 1 MiB 档:2 MiB 的文件被拒
        val lowered = AttachmentLimits(maxFileBytes = 1L shl 20, workspaceMaxBytes = Attachments.WORKSPACE_MAX)
        val refused = Attachments.importOne(ws, JVM_GATE, "a", { Attachments.MIN_FREE }, { 0 }, { bytes(twoMiB) }, limits = lowered)
        assertEquals(listOf(AttachmentFailure("a", AttachmentFailureReason.TOO_LARGE)), refused.failures)
        // 放宽到 4 MiB 档后同一个文件放行
        val raised = AttachmentLimits(maxFileBytes = 4L shl 20, workspaceMaxBytes = Attachments.WORKSPACE_MAX)
        val accepted = Attachments.importOne(ws, JVM_GATE, "a", { Attachments.MIN_FREE }, { 0 }, { bytes(twoMiB) }, limits = raised)
        assertEquals(listOf("/workspace/inbox/a" to twoMiB), accepted.added)
        assertTrue(accepted.failures.isEmpty())
        // 工作区占用 5 GB:默认 4 GB 档拒绝,调到 8 GB 档放行
        val overDefaultWorkspace = 5L shl 30
        val workspaceRefused = Attachments.importOne(ws, JVM_GATE, "b", { Attachments.MIN_FREE }, { overDefaultWorkspace }, { bytes(1) })
        assertEquals(AttachmentFailureReason.WORKSPACE_LIMIT, workspaceRefused.failures.single().reason)
        val workspaceAccepted = Attachments.importOne(
            ws, JVM_GATE, "b", { Attachments.MIN_FREE }, { overDefaultWorkspace }, { bytes(1) },
            limits = AttachmentLimits(maxFileBytes = 4L shl 20, workspaceMaxBytes = 8L shl 30),
        )
        assertEquals(listOf("/workspace/inbox/b" to 1L), workspaceAccepted.added)
    }

    @Test fun handlesSuccessIoFailureAndCancellation() = runBlocking {
        val ws = temp.newFolder()
        suspend fun copy(open: () -> InputStream) = Attachments.importOne(ws, JVM_GATE, "input", { Attachments.MIN_FREE }, { 0 }, open)
        val success = copy { bytes(3) }
        assertEquals(listOf("/workspace/inbox/input" to 3L), success.added)
        assertTrue(success.failures.isEmpty())
        val failure = copy { throw IOException("read failed") }
        assertEquals(listOf(AttachmentFailure("input", AttachmentFailureReason.IO_ERROR)), failure.failures)
        val cancelled = runCatching { copy { throw CancellationException("cancelled") } }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
        assertEquals(setOf("input"), File(ws, "inbox").list()!!.toSet())
    }

    @Test fun collisionNamesReferToTheRightBytesAndRefusedTasksRollBackOnlyTheirOwnCopies() = runBlocking {
        val ws = temp.newFolder()
        val inbox = File(ws, "inbox").apply { mkdir() }
        val earlier = File(inbox, "清单.csv").apply { writeText("original") }
        val items = listOf("A", "B").map { "清单.csv" to temp.newFile().apply { writeText(it) } }
        val tree = SandboxTree()
        val result = Attachments.withTaskFiles(items, ws, JVM_GATE, tree, { true }) { records ->
            records.map { (path, size) ->
                assertEquals(1L, size)
                File(ws, path.removePrefix("/workspace/")).readText()
            }
        }
        assertEquals(listOf("A", "B"), result)
        val before = inbox.list()!!.toSet()
        assertEquals(setOf("清单.csv", "清单 (2).csv", "清单 (3).csv"), before)
        val refused = runCatching {
            Attachments.withTaskFiles(items, ws, JVM_GATE, tree, { true }) { throw IOException("refused") }
        }.exceptionOrNull()
        assertTrue(refused is IOException)
        assertEquals(before, inbox.list()!!.toSet())
        assertEquals("original", earlier.readText())
    }
}
