package com.wangzan101.mika.terminal

import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.ProjectStore
import com.wangzan101.mika.chat.WorkspaceResolver
import com.wangzan101.mika.core.cap.sandbox.SandboxShell
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.workspace.SandboxError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class TerminalHubTest {
    private companion object {
        // 等的是异步链(SharedFlow 收集 → 会话关闭 → 守护线程关 shell),CI 满载时 2s 不够;
        // 条件满足即返回,预算只影响失败路径。
        const val CLOSE_WAIT_MS = 15_000L
    }
    @get:Rule val tmp = TemporaryFolder()

    private class FakeShell : SandboxShell {
        private val toApp = PipedInputStream(16 * 1024)
        private val fromApp = PipedInputStream(16 * 1024)
        private val writer = PipedOutputStream(toApp)
        private val appWriter = PipedOutputStream(fromApp)
        override val input: OutputStream = appWriter
        override val output: InputStream = toApp
        override val exited = CompletableDeferred<Int>()
        override val revoked = MutableStateFlow(false)
        @Volatile var closed = false

        override fun close() {
            closed = true
            runCatching { writer.close() }
            runCatching { appWriter.close() }
            runCatching { toApp.close() }
            runCatching { fromApp.close() }
        }
    }

    private data class Harness(
        val conversations: ConversationStore,
        val projects: ProjectStore,
        val opened: MutableList<WorkspaceKey>,
        val shells: MutableList<FakeShell>,
        val scope: CoroutineScope,
        val hub: TerminalHub,
    )

    private fun harness(): Harness {
        val conversations = ConversationStore(tmp.newFolder("conversations"))
        val projects = ProjectStore(tmp.newFolder("projects"))
        val resolver = WorkspaceResolver(conversations, projects)
        val opened = mutableListOf<WorkspaceKey>()
        val shells = mutableListOf<FakeShell>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val hub = TerminalHub(
            conversations = conversations,
            projects = projects,
            resolveWorkspace = resolver::resolve,
            awaitReady = { SandboxStatus.Ready(network = true) },
            isolationIssue = { null },
            openShell = { key, _, _ ->
                opened += key
                FakeShell().also { shells += it }
            },
            scope = scope,
        )
        return Harness(conversations, projects, opened, shells, scope, hub)
    }

    @Test fun projectConversationsReuseSingleTerminalSession() = runBlocking {
        val h = harness()
        try {
            val project = h.projects.create("研发项目").getOrThrow()
            h.conversations.append("c1", Turn("hi", "ok", "succeeded"))
            h.conversations.append("c2", Turn("hi", "ok", "succeeded"))
            h.conversations.setProject("c1", project.id)
            h.conversations.setProject("c2", project.id)

            val s1 = h.hub.open("c1", "对话一")
            val s2 = h.hub.open("c2", "对话二")

            assertSame(s1, s2)
            assertEquals(listOf(WorkspaceKey.Project(project.id)), h.opened)
            assertEquals(project.name, s1.title)
            assertSame(s1, h.hub.session("c2"))
        } finally {
            h.hub.closeAll()
            h.scope.cancel()
        }
    }

    @Test fun projectTerminalCountsAsSingleSlotTowardLimit() = runBlocking {
        val h = harness()
        try {
            val project = h.projects.create("研发项目").getOrThrow()
            h.conversations.setProject("c1", project.id)
            h.conversations.setProject("c2", project.id)

            h.hub.open("c1", "对话一")
            h.hub.open("c2", "对话二")
            h.hub.open("solo-1", "独立一")
            assertEquals(TerminalHub.MAX_SESSIONS, h.hub.sessions.value.size)

            try {
                h.hub.open("solo-2", "独立二")
                fail("Expected terminal_limit error")
            } catch (e: SandboxError) {
                assertEquals("terminal_limit", e.code)
            }
        } finally {
            h.hub.closeAll()
            h.scope.cancel()
        }
    }

    @Test fun deletingMemberConversationKeepsSharedProjectTerminalAlive() = runBlocking {
        val h = harness()
        try {
            val project = h.projects.create("研发项目").getOrThrow()
            h.conversations.append("c1", Turn("hi", "ok", "succeeded"))
            h.conversations.append("solo", Turn("hi", "ok", "succeeded"))
            h.conversations.setProject("c1", project.id)

            val projectSession = h.hub.open("c1", "对话一")
            val soloSession = h.hub.open("solo", "独立")

            h.conversations.delete("c1")
            delay(50)
            assertNotNull(h.hub.sessionForWorkspace(WorkspaceKey.Project(project.id)))
            assertEquals(TerminalSession.Phase.Running, projectSession.phase.value)
            assertTrue(h.shells.none { it === h.shells.first() && it.closed })

            h.conversations.delete("solo")
            withTimeout(CLOSE_WAIT_MS) {
                while (h.hub.session("solo") != null) delay(10)
            }
            assertEquals(TerminalSession.Phase.Closed, soloSession.phase.value)
            assertNotNull(h.hub.sessionForWorkspace(WorkspaceKey.Project(project.id)))
        } finally {
            h.hub.closeAll()
            h.scope.cancel()
        }
    }

    @Test fun deletingProjectClosesSharedProjectTerminal() = runBlocking {
        val h = harness()
        try {
            val project = h.projects.create("研发项目").getOrThrow()
            h.conversations.setProject("c1", project.id)

            val session = h.hub.open("c1", "对话一")
            assertNotNull(h.hub.sessionForWorkspace(WorkspaceKey.Project(project.id)))

            h.projects.delete(project.id)
            withTimeout(CLOSE_WAIT_MS) {
                while (h.hub.sessionForWorkspace(WorkspaceKey.Project(project.id)) != null || !h.shells.single().closed) delay(10)
            }
            assertNull(h.hub.sessionForWorkspace(WorkspaceKey.Project(project.id)))
            assertEquals(TerminalSession.Phase.Closed, session.phase.value)
            assertTrue(h.shells.single().closed)
        } finally {
            h.hub.closeAll()
            h.scope.cancel()
        }
    }

    @Test fun closeAllClosesProjectAndConversationTerminals() = runBlocking {
        val h = harness()
        try {
            val project = h.projects.create("研发项目").getOrThrow()
            h.conversations.setProject("c1", project.id)

            val pSession = h.hub.open("c1", "对话一")
            val cSession = h.hub.open("solo", "独立")

            h.hub.closeAll()

            withTimeout(CLOSE_WAIT_MS) {
                while (!h.shells.all { it.closed }) delay(10)
            }
            assertTrue(h.hub.sessions.value.isEmpty())
            assertEquals(TerminalSession.Phase.Closed, pSession.phase.value)
            assertEquals(TerminalSession.Phase.Closed, cSession.phase.value)
            assertTrue(h.shells.all { it.closed })
        } finally {
            h.scope.cancel()
        }
    }
}
