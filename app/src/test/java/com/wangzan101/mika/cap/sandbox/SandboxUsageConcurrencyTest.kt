package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.FdStat
import com.wangzan101.mika.core.workspace.FdSys
import com.wangzan101.mika.core.workspace.NioFdSys
import com.wangzan101.mika.core.workspace.OpenFd
import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxUsageConcurrencyTest {
    private val tmp = Files.createTempDirectory("usage-concurrency").toFile()
    private val active = CopyOnWriteArrayList<String>()
    private val attempts = CopyOnWriteArrayList<String>()
    private var onDelete: (String) -> Unit = {}
    // Record entry into the real file gate; pretend the entry has already disappeared.
    private val gate = WorkspaceFileGate(object : FdSys by NioFdSys {
        override fun lstat(dir: OpenFd, name: String): FdStat? {
            attempts += name
            onDelete(name)
            return null
        }
    })
    private val dirs = SandboxDirs(File(tmp, "sandbox"), File(tmp, "cache"), File(tmp, "lib"), gate, SandboxTree())
    private val reader = SandboxUsageReader(dirs, { emptyList() }, { active })

    private fun workspace(id: String) = dirs.workspace(WorkspaceKey.Conversation(id)).apply { mkdirs() }

    @Test(timeout = 10_000) fun singleClearRechecksBusyAfterWaitingForTheTree() = runBlocking {
        val a = workspace("a")
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch { dirs.tree.exclusive { locked.complete(Unit); release.await() } }
        locked.await()
        val clearing = async(start = CoroutineStart.UNDISPATCHED) { reader.clear(a.name) }
        assertFalse(clearing.isCompleted)
        active += "a"
        release.complete(Unit)
        holder.join()
        assertFalse("A task started while cleanup was waiting", clearing.await())
        assertTrue(attempts.isEmpty())
    }

    @Test(timeout = 10_000) fun bulkClearRechecksBusyAfterWaitingForTheTree() = runBlocking {
        val a = workspace("a")
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch { dirs.tree.exclusive { locked.complete(Unit); release.await() } }
        locked.await()
        val clearing = async(start = CoroutineStart.UNDISPATCHED) { reader.clearAll() }
        assertFalse(clearing.isCompleted)
        active += "a"
        release.complete(Unit)
        holder.join()
        assertEquals(1, clearing.await())
        assertFalse(attempts.contains(a.name))
    }

    @Test(timeout = 10_000) fun cleanupWaitsForACommandHoldingASharedPermit() = runBlocking {
        val a = workspace("a")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val command = launch { dirs.tree.shared { entered.complete(Unit); release.await() } }
        entered.await()
        val clearing = async(start = CoroutineStart.UNDISPATCHED) { reader.clear(a.name) }
        active += "a"
        release.complete(Unit)
        command.join()
        assertFalse(clearing.await())
        assertTrue(attempts.isEmpty())
    }

    @Test(timeout = 10_000) fun bulkClearRechecksEachWorkspaceBeforeDeletingIt() = runBlocking {
        workspace("a")
        workspace("b")
        val names = dirs.work.listFiles()!!.map { it.name }
        val second = names[1]
        onDelete = { if (it == names[0]) active += second.removePrefix("c-") }
        assertEquals(1, reader.clearAll())
        assertEquals(listOf(names[0]), attempts.toList())
    }

    @Test(timeout = 10_000) fun singleClearBlocksNewWorkspaceWrites() = runBlocking {
        verifyWritesWaitForCleanup { reader.clear(it.name) }
    }

    @Test(timeout = 10_000) fun bulkClearBlocksNewWorkspaceWrites() = runBlocking {
        verifyWritesWaitForCleanup { reader.clearAll() == 0 }
    }

    // 删对话时删工作区走同一条独占的路
    @Test(timeout = 10_000) fun conversationDeleteBlocksNewWorkspaceWrites() = runBlocking {
        verifyWritesWaitForCleanup { dirs.deleteWorkspace(WorkspaceKey.Conversation("a")); true }
    }

    // 删一个没往工作区写过东西的对话(没有工作区):没东西可删,不拿独占许可,也就不收回别的对话开着的终端、stdio MCP server 拿着的租约
    @Test(timeout = 10_000) fun deletingAConversationWithoutAWorkspaceKeepsOtherLeases() = runBlocking {
        workspace("b")
        var revoked = false
        val lease = dirs.tree.lease(onRevoke = { revoked = true })
        try {
            dirs.deleteWorkspace(WorkspaceKey.Conversation("a"))
            assertFalse("No workspace to delete, nothing to revoke", revoked)
            assertTrue(attempts.isEmpty())
            dirs.deleteWorkspace(WorkspaceKey.Conversation("b"))
            assertTrue(revoked)
            assertEquals(listOf(dirs.workspace(WorkspaceKey.Conversation("b")).name), attempts.toList())
        } finally {
            lease.close()
        }
    }

    @Test(timeout = 10_000) fun conversationDeleteWaitsForACommandHoldingASharedPermit() = runBlocking {
        val a = workspace("a")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val command = launch { dirs.tree.shared { entered.complete(Unit); release.await() } }
        entered.await()
        val deleting = async(start = CoroutineStart.UNDISPATCHED) { dirs.deleteWorkspace(WorkspaceKey.Conversation("a")) }
        assertFalse(deleting.isCompleted)
        assertTrue("Deletion must not start while a command still writes", attempts.isEmpty())
        release.complete(Unit)
        command.join()
        deleting.await()
        assertEquals(listOf(a.name), attempts.toList())
    }

    private suspend fun verifyWritesWaitForCleanup(clean: suspend (File) -> Boolean) = coroutineScope {
        val a = workspace("a")
        val deleting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        onDelete = {
            deleting.complete(Unit)
            // Model a slow synchronous file operation on the IO dispatcher.
            runBlocking { release.await() }
        }
        val clearing = async { clean(a) }
        deleting.await()
        val writer = async(start = CoroutineStart.UNDISPATCHED) { dirs.tree.shared { true } }
        try {
            assertFalse("New commands and imports must wait until deletion finishes", writer.isCompleted)
        } finally {
            release.complete(Unit)
        }
        assertTrue(clearing.await())
        assertTrue(writer.await())
    }
}
