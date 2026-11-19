package com.wangzan101.mika.ui

import com.wangzan101.mika.core.cap.sandbox.SandboxPort
import com.wangzan101.mika.core.workspace.SandboxPaths
import com.wangzan101.mika.ui.viewer.ViewerKind
import com.wangzan101.mika.ui.viewer.ViewerKinds
import java.io.File

/** 一条 `/workspace/...` 产出引用:原路径、解析出的宿主文件、按扩展名认出的可预览类型(认不出为 null)。 */
data class WorkspaceRef(val path: String, val host: File, val kind: ViewerKind?)

/** 把回复里抽出的 `/workspace/...` 引用解析成宿主文件;越界(`..`)、链接逃逸、文件不存在的一律丢弃。 */
fun resolveWorkspaceRefs(sandbox: SandboxPort, workspace: String, refs: List<String>): List<WorkspaceRef> =
    refs.mapNotNull { ref ->
        val normalized = SandboxPaths.normalize(ref) ?: return@mapNotNull null
        val host = sandbox.hostPath(workspace, normalized, forWrite = false) ?: return@mapNotNull null
        WorkspaceRef(ref, host, ViewerKinds.of(null, host.name))
    }
