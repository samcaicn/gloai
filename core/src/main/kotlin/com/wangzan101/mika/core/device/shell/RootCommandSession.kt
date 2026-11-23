package com.wangzan101.mika.core.device.shell

/** 在 su 的执行端监督命令。标准输入留给 App，关闭时结束这次命令的进程组。 */
object RootCommandSession {
    private const val SYSTEM_SHELL = "/system/bin/sh"

    /**
     * 只探测固定系统路径，不从命令的环境或用户目录取监督程序。
     * 没有可用的 setsid 或 kill 时仍按原方式执行，由 App 结束 su 兜底。
     */
    val androidScript = script(
        shell = SYSTEM_SHELL,
        setsid = listOf(
            listOf("/system/bin/setsid"),
            listOf("/system/bin/toybox", "setsid"),
        ),
        kill = listOf(
            listOf("/system/bin/kill"),
            listOf("kill"),
            listOf("/system/bin/toybox", "kill"),
        ),
    )

    /**
     * 调用方把命令放在变量 c 中。setsid 会派生进程时由 -w 等待，监督脚本在新会话内取得实际组号。
     * 参数只接受代码里固定的可执行文件和参数，不用于拼接用户输入。
     */
    internal fun script(shell: String, setsid: List<List<String>>, kill: List<List<String>>): String {
        require(setsid.all { it.isNotEmpty() } && kill.all { it.isNotEmpty() })
        val supervisor = """
            set +m
            group=${'$'}${'$'}
            exec 3<&0
            "${'$'}3" -c "${'$'}1" </dev/null 3<&- &
            child=${'$'}!
            (
                IFS= read -r stop <&3
                ${'$'}2 -s KILL -- "-${'$'}group" 2>/dev/null
            ) &
            watcher=${'$'}!
            exec 3<&-
            wait "${'$'}child"
            status=${'$'}?
            ${'$'}2 -s KILL -- "${'$'}watcher" 2>/dev/null
            wait "${'$'}watcher" 2>/dev/null
            exit "${'$'}status"
        """.trimIndent()
        val b = StringBuilder()
        b.append("mika_supervise='").append(supervisor).append("'; mika_kill=; ")
        kill.forEachIndexed { index, candidate ->
            val invocation = candidate.joinToString(" ")
            b.append(if (index == 0) "if " else "elif ")
                .append(available(candidate)).append(" && ")
                .append(invocation).append(" -s 0 -- \"${'$'}${'$'}\" >/dev/null 2>&1; then ")
                .append("mika_kill=\"").append(invocation).append("\"; ")
        }
        if (kill.isNotEmpty()) b.append("fi; ")
        if (setsid.isNotEmpty()) {
            b.append("if [ -n \"${'$'}mika_kill\" ]; then ")
            setsid.forEachIndexed { index, candidate ->
                val invocation = candidate.joinToString(" ")
                b.append(if (index == 0) "if " else "elif ")
                    .append(available(candidate)).append(" && ")
                    .append(invocation).append(" -w \"").append(shell).append("\" -c 'exit 0' </dev/null >/dev/null 2>&1; then ")
                    .append("exec ").append(invocation).append(" -w \"").append(shell)
                    .append("\" -c \"${'$'}mika_supervise\" mika-root \"${'$'}c\" \"${'$'}mika_kill\" \"").append(shell).append("\"; ")
            }
            b.append("fi; fi; ")
        }
        b.append("exec \"").append(shell).append("\" -c \"${'$'}c\" </dev/null")
        return b.toString()
    }

    private fun available(command: List<String>): String =
        if (command[0].startsWith('/')) "[ -x ${command[0]} ]"
        else "command -v ${command[0]} >/dev/null 2>&1"
}
