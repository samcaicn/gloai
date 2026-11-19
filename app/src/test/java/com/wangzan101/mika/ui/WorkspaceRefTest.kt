package com.wangzan101.mika.ui

import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.SandboxPort
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.knowledge.ScriptEntry
import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import com.wangzan101.mika.ui.viewer.ViewerKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class WorkspaceRefTest {
    private class Fake : SandboxPort {
        override val tree = SandboxTree()
        override val files: WorkspaceFileGate get() = TODO("not used")
        override fun status(): SandboxStatus = SandboxStatus.Ready(network = false)
        override suspend fun run(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long, onProgress: (String) -> Unit): CommandResult =
            TODO("not used")
        // 存在的路径给一个宿主文件；名字带 "missing" 的当不存在返回 null（模拟文件被清掉）。
        override fun hostPath(workspace: String, sandboxPath: String, forWrite: Boolean): File? =
            if ("missing" in sandboxPath) null else File("/fake/$sandboxPath")
        override fun hostRoot(workspace: String, normalized: String, forWrite: Boolean): Pair<File, String>? = TODO("not used")
        override suspend fun share(file: File, name: String): String = TODO("not used")
        override fun freeBytes(): Long = TODO("not used")
        override fun workspaceBytes(workspace: String): Long = TODO("not used")
        override fun saveOutput(workspace: String, text: String): String = TODO("not used")
        override fun scriptManifest(skillPath: String): List<ScriptEntry> = TODO("not used")
    }

    @Test
    fun resolveWorkspaceRefsDropsInvalid() {
        val r = resolveWorkspaceRefs(Fake(), "conv1", listOf("/workspace/a.png", "/workspace/../x", "/workspace/missing.txt"))
        assertEquals(listOf("/workspace/a.png"), r.map { it.path })
        assertEquals(ViewerKind.Image, r.single().kind)
    }

    @Test
    fun resolveWorkspaceRefsClassifiesNonImage() {
        val r = resolveWorkspaceRefs(Fake(), "conv1", listOf("/workspace/report.csv"))
        assertEquals(ViewerKind.Text, r.single().kind)
    }
}
