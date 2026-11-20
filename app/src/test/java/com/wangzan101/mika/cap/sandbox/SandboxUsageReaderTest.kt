package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.chat.Project
import com.wangzan101.mika.core.workspace.SandboxTree
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 已用空间:大小不跟进链接;清理按句柄删、不跟进链接;有任务在跑的对话不清。 */
class SandboxUsageReaderTest {
    private val tmp: File = Files.createTempDirectory("usage").toFile()
    private val dirs = SandboxDirs(File(tmp, "files/sandbox"), File(tmp, "cache"), File(tmp, "lib"), JVM_GATE, SandboxTree())
    private val active = mutableListOf<String>()
    private val reader = SandboxUsageReader(dirs, { listOf(ConversationSummary("a", "查快递", 0), ConversationSummary("b", "整理表格", 0)) }, { active })

    /** 工作区外面一个大文件,工作区里有指向它(和它所在目录)的链接。 */
    private val outside = File(tmp, "outside").apply { mkdirs() }
    private val big = File(outside, "big.bin").apply { writeBytes(ByteArray(50_000)) }

    private fun workspace(id: String, bytes: Int): File = dirs.workspace(WorkspaceKey.Conversation(id)).apply {
        mkdirs()
        File(this, "inbox").mkdirs()
        File(this, "inbox/a.txt").writeBytes(ByteArray(bytes))
        Files.createSymbolicLink(File(this, "link-file").toPath(), big.toPath())
        Files.createSymbolicLink(File(this, "link-dir").toPath(), outside.toPath())
    }

    @Test fun sizesDoNotFollowLinksAndNameTheChat() {
        workspace("a", 300)
        workspace("b", 1_000)
        File(dirs.work, "c-gone").apply { mkdirs(); File(this, "x").writeBytes(ByteArray(10)) }
        File(dirs.rootfs, "bin").apply { mkdirs(); File(this, "sh").writeBytes(ByteArray(70)) }
        active += "a"
        val u = reader.read()
        assertEquals(listOf(1_000L, 300L, 10L), u.workspaces.map { it.bytes })
        assertEquals(listOf("整理表格", "查快递", null), u.workspaces.map { it.title })
        assertEquals(listOf(false, true, false), u.workspaces.map { it.busy })
        assertNull("对话删掉了的认不出来", u.workspaces.last().conversationId)
        assertEquals(70L, u.runtime)
        assertEquals(70L + 1_310L, u.total)
    }

    @Test fun clearingDeletesOnlyTheWorkspaceNotWhatLinksPointTo() = runBlocking {
        val b = workspace("b", 1_000)
        assertTrue(reader.clear(b.name))
        assertFalse(b.exists())
        assertTrue("链接指向的文件还在", big.isFile)
        assertEquals(50_000L, big.length())
    }

    @Test fun aChatWithARunningTaskIsNotCleared() = runBlocking {
        val a = workspace("a", 300)
        val b = workspace("b", 1_000)
        active += "a"
        assertFalse(reader.clear(a.name))
        assertTrue(File(a, "inbox/a.txt").isFile)
        assertEquals("留下了一个在跑的", 1, reader.clearAll())
        assertTrue(a.exists())
        assertFalse(b.exists())
        assertTrue(big.isFile)
    }

    @Test fun projectWorkspacesShowProjectNameAndBusyAcrossMemberChats() = runBlocking {
        val proj = Project(id = "proj-1", name = "科研项目")
        val projDir = dirs.workspace(WorkspaceKey.Project(proj.id)).apply {
            mkdirs()
            File(this, "data.bin").writeBytes(ByteArray(400))
        }
        val soloDir = dirs.workspace(WorkspaceKey.Conversation("solo")).apply {
            mkdirs()
            File(this, "note.txt").writeBytes(ByteArray(120))
        }
        val activeMembers = mutableListOf("c1")
        val readerWithProjects = SandboxUsageReader(
            dirs,
            conversations = {
                listOf(
                    ConversationSummary("c1", "检索", 0, projectId = proj.id),
                    ConversationSummary("c2", "总结", 0, projectId = proj.id),
                    ConversationSummary("solo", "独立对话", 0),
                )
            },
            active = { activeMembers },
            projects = { listOf(proj) },
        )

        val u = readerWithProjects.read()
        assertEquals(listOf("科研项目", "独立对话"), u.workspaces.map { it.title })
        assertEquals(listOf(proj.id, null), u.workspaces.map { it.projectId })
        assertEquals(listOf(null, "solo"), u.workspaces.map { it.conversationId })
        assertEquals(listOf(true, false), u.workspaces.map { it.busy })
        assertFalse(readerWithProjects.clear(projDir.name))

        activeMembers.clear()
        assertTrue(readerWithProjects.clear(projDir.name))
        assertFalse(projDir.exists())
        assertTrue(soloDir.exists())
    }
}
