package com.wangzan101.mika.cap.sandbox

import android.content.Context
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import com.wangzan101.mika.core.workspace.SandboxTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 沙箱在宿主上的目录:`files/sandbox/` 下的根文件系统、工作区、技能、/proc 替身;PRoot 的临时目录在缓存里;
 * 可执行文件只在 [nativeDir](安卓 10 起数据目录里的文件不能 execve)。
 */
class SandboxDirs internal constructor(
    val root: File, val tmp: File, val nativeDir: File,
    /** 宿主侧读写这些目录(沙箱碰得到)都过它;手机上是 [AndroidFdSys.gate],JVM 测试传按路径的那份。 */
    val gate: WorkspaceFileGate,
    /** 往这棵树里写都先拿它的许可;手机上是进程里那一份([SandboxOwner]),测试自己给一份。不设默认:私有的一份锁不住别人。 */
    val tree: SandboxTree,
) {
    constructor(context: Context) : this(
        File(context.filesDir, "sandbox"), File(context.cacheDir, "proot-tmp"), File(context.applicationInfo.nativeLibraryDir),
        AndroidFdSys.gate, SandboxOwner.of(context).sandboxTree,
    )

    val rootfs: File get() = File(root, "rootfs")
    val work: File get() = File(root, "work")
    val skills: File get() = File(root, "skills")
    val procStandIns: File get() = File(root, "proc")

    /** 一个工作区对应一个目录:项目为 `p-<ID>`,对话为 `c-<ID>` 或 `h-<哈希>`(与 `p-` 永不撞名)。 */
    fun workspace(key: WorkspaceKey): File = File(work, dirName(key))

    fun lib(name: String): File = File(nativeDir, name)

    /**
     * 删、换沙箱里的命令写得到的整棵目录(工作区、运行环境)都经这里:拿着 [tree] 的独占许可在 IO 线程里做,
     * 等在跑的命令和别的写入都结束才开始,做完之前新的命令、导入也等着,免得一边删一边有人往里写。
     * 代价:删一个大目录要多久,所有对话的沙箱命令就要等多久。等许可时情况可能变了(比如对话又有了任务),
     * 要看的条件在 [block] 里拿到许可后再查。
     */
    suspend fun <T> exclusiveWrite(block: suspend () -> T): T = tree.exclusive { withContext(Dispatchers.IO) { block() } }

    /** 删掉 [key] 对应的整个工作区(按句柄删、不跟进链接,见 [exclusiveWrite])。 */
    suspend fun deleteWorkspace(key: WorkspaceKey) = deleteWorkDir(workspace(key).name)

    /**
     * 删掉 `work/` 下名为 [dirName] 的工作区。本来就没有(没往里写过东西)时直接返回,不拿独占许可:
     * 拿独占许可要收回所有租约([SandboxTree.lease]),别的对话开着的终端、在跑的 stdio MCP server 都会被停掉,而这里什么也不用删。
     * 这一查在许可之外:已经拿到共享许可、还没建出目录的写入(命令、导入附件、存屏幕)不再等它做完,
     * 它建出的工作区会留下来,在设置的「已用空间」里列成「已删除的对话」,可以清掉。
     */
    suspend fun deleteWorkDir(dirName: String) {
        if (withContext(Dispatchers.IO) { !Files.exists(File(work, dirName).toPath(), LinkOption.NOFOLLOW_LINKS) }) return
        exclusiveWrite { gate.deleteTree(work, dirName) }
    }

    /**
     * 绑定前把要绑的目录都建好:源目录不存在时 PRoot 会往标准错误写一句警告,
     * 而标准错误并进了命令输出,模型会看到。技能目录还没同步时就是空目录。
     */
    fun ensureBindSources(workspace: File) {
        for (d in listOf(workspace, skills, tmp)) d.mkdirs()
    }

    private fun dirName(key: WorkspaceKey): String = when (key) {
        is WorkspaceKey.Project -> "p-${key.id}"
        is WorkspaceKey.Conversation -> conversationDirName(key.id)
    }

    private fun conversationDirName(id: String): String =
        if (SAFE_ID.matches(id)) "c-$id"
        else "h-" + MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    private companion object {
        val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,100}$")
    }
}
