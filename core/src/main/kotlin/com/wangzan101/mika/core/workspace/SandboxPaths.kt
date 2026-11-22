package com.wangzan101.mika.core.workspace

import java.io.File
import java.nio.file.Files

/** 模型只看到沙箱里的路径;这里规范化并判断越界。 */
object SandboxPaths {
    const val WORKSPACE = "/workspace"
    const val SKILLS = "/opt/mika/skills"
    /** 各工作区共享、沙箱可写的 MCP 包缓存(只经绑定挂载由命令写,不进 normalize/isWritable 的桥分享)。 */
    const val MCP_CACHE = "/opt/mika/mcp-cache"

    /** 单个文件名:不含路径分隔符、控制字符、格式字符(改写方向、零宽等,能让显示的名字和实际的对不上)与行/段分隔符。 */
    fun plainName(n: String): Boolean =
        n.isNotEmpty() && '/' !in n && '\\' !in n && n != "." && n != ".." &&
            n.codePoints().noneMatch(::hiddenChar)

    /** 控制字符、格式字符(Cf)与 U+2028/U+2029。 */
    fun hiddenChar(cp: Int): Boolean =
        Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt() || cp == 0x2028 || cp == 0x2029

    /** 相对路径以 [cwd] 为基;规范化后不在 [WORKSPACE] 或 [SKILLS] 下返回 null。 */
    fun normalize(path: String, cwd: String = WORKSPACE): String? {
        if (path.isEmpty() || '\u0000' in path) return null
        val parts = ArrayDeque<String>()
        for (seg in (if (path.startsWith("/")) path else "$cwd/$path").split('/')) when (seg) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull() ?: return null
            else -> parts.addLast(seg)
        }
        val n = "/" + parts.joinToString("/")
        return n.takeIf { under(it, WORKSPACE) || under(it, SKILLS) }
    }

    /** 只有工作区可写;技能目录只读。 */
    fun isWritable(normalized: String): Boolean = under(normalized, WORKSPACE)

    /**
     * [root] 下的 [sandboxRel];逐段检查符号链接:指到 [root] 外面的、目标不存在的(写下去会落到哪里看不出来)一律 null,
     * 最终的 canonicalFile 也必须仍在 [root] 下。
     */
    fun resolveHost(root: File, sandboxRel: String): File? {
        val base = root.canonicalFile
        var cur = base
        for (seg in sandboxRel.split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") return null
            cur = File(cur, seg)
            val p = cur.toPath()
            if (Files.isSymbolicLink(p)) {
                if (!Files.exists(p)) return null
                cur = cur.canonicalFile
                if (!inside(cur, base)) return null
            }
        }
        return cur.canonicalFile.takeIf { inside(it, base) }
    }

    private fun under(p: String, root: String) = p == root || p.startsWith("$root/")
    private fun inside(f: File, base: File) = f == base || f.path.startsWith(base.path + File.separator)
}
