package com.wangzan101.mika.task

import android.content.SharedPreferences
import com.wangzan101.mika.cap.sandbox.Attachments
import com.wangzan101.mika.cap.sandbox.JVM_GATE
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream

class DeviceWorkspaceImportsTest {
    @get:Rule val temp = TemporaryFolder()
    private val dirs by lazy { SandboxDirs(temp.newFolder(), temp.newFolder(), temp.newFolder(), JVM_GATE, SandboxTree()) }
    private val store = DeviceImportStore(FeatureFlagsCapsuleTest.MemoryPrefs())
    private val workspace = WorkspaceKey.Project("test-project")
    private val path = "/folders/abc123/report.pdf"

    private fun importer(history: DeviceImportStore = store) = DeviceWorkspaceImports(dirs, { Attachments.MIN_FREE }, history,
        resolveWorkspace = WorkspaceKey::parse, conversationIdFor = { "chat" })

    private fun requireUnixGate() = assumeTrue("The JVM workspace gate uses Unix file attributes",
        "unix" in temp.root.toPath().fileSystem.supportedFileAttributeViews())

    @Test fun binaryCopiesUseTheProjectInboxAndKeepSeparateRecordsWhenSourceVersionsAreUnknown() = runBlocking {
        requireUnixGate()
        val imports = importer()
        val first = imports.fromFolder(path, workspace.wire) { selected ->
            assertEquals(path, selected)
            byteArrayOf(0, -1).inputStream()
        }
        val second = imports.fromFolder(path, workspace.wire) { byteArrayOf(0, 1).inputStream() }
        assertEquals("/workspace/inbox/report.pdf", first.path)
        assertEquals("/workspace/inbox/report (2).pdf", second.path)
        assertArrayEquals(byteArrayOf(0, -1), File(dirs.workspace(workspace), "inbox/report.pdf").readBytes())
        assertArrayEquals(byteArrayOf(0, 1), File(dirs.workspace(workspace), "inbox/report (2).pdf").readBytes())
        val records = store.listByWorkspace(workspace.wire)
        assertEquals(listOf(first.path, second.path), records.map { it.workspacePath })
        assertTrue(records.all { it.sourcePath == path && it.conversationId == "chat" && it.size == 2L &&
            it.sourceModifiedSeconds == null && it.sourceModifiedNanos == null })
    }

    @Test fun aLiveAuthorizationFailurePreservesTheProviderError() = runBlocking {
        val imports = importer()
        val denied = runCatching {
            imports.fromFolder(path, workspace.wire) { throw SandboxError("folder_access_denied", "Revoked") }
        }.exceptionOrNull()
        assertEquals("folder_access_denied", (denied as SandboxError).code)
        assertTrue(store.allRecords().isEmpty())
        assertFalse(dirs.workspace(workspace).exists())
    }

    @Test fun anInterruptedCopyLeavesNoHistoryOrPartialFile() = runBlocking {
        requireUnixGate()
        val imports = importer()
        val cancelled = runCatching {
            imports.fromFolder(path, workspace.wire) { object : InputStream() {
                var readOnce = false
                override fun read(): Int = throw AssertionError("Expected a bulk read")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (readOnce) throw CancellationException("cancelled")
                    readOnce = true
                    b[off] = 1
                    return 1
                }
            } }
        }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
        assertTrue(store.allRecords().isEmpty())
        assertTrue(File(dirs.workspace(workspace), "inbox").list().isNullOrEmpty())
    }

    @Test fun tieredLimitsApplyPerImportAndRereadEachTime() = runBlocking {
        requireUnixGate()
        var maxFileBytes = 2L
        val imports = DeviceWorkspaceImports(dirs, { Attachments.MIN_FREE }, store,
            resolveWorkspace = WorkspaceKey::parse, conversationIdFor = { "chat" },
            limits = { com.wangzan101.mika.cap.sandbox.AttachmentLimits(maxFileBytes, Attachments.WORKSPACE_MAX) })
        val denied = runCatching {
            imports.fromFolder(path, workspace.wire) { byteArrayOf(1, 2, 3).inputStream() }
        }.exceptionOrNull()
        assertEquals("too_large", (denied as SandboxError).code)
        assertTrue(store.allRecords().isEmpty())
        // 同一个导入器实例,档位调大后下一次导入就按新值,无需重建
        maxFileBytes = 1024
        val allowed = imports.fromFolder(path, workspace.wire) { byteArrayOf(1, 2, 3).inputStream() }
        assertEquals("/workspace/inbox/report.pdf", allowed.path)
        assertEquals(3L, allowed.size)
    }

    @Test fun aFailedHistoryCommitRollsBackOnlyTheNewCopy() = runBlocking {
        requireUnixGate()
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val rejected = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val delegate = prefs.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String, value: String?): SharedPreferences.Editor {
                        delegate.putString(key, value)
                        return this
                    }
                    override fun commit() = false
                }
            }
        }
        val inbox = File(dirs.workspace(workspace), "inbox").apply { mkdirs() }
        val old = File(inbox, "report.pdf").apply { writeText("original") }
        val error = runCatching {
            importer(DeviceImportStore(rejected)).fromFolder(path, workspace.wire) { byteArrayOf(42).inputStream() }
        }.exceptionOrNull()
        assertEquals("record_error", (error as SandboxError).code)
        assertEquals(listOf("report.pdf"), inbox.list()!!.toList())
        assertEquals("original", old.readText())
    }
}
