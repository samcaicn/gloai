package com.wangzan101.mika.plugin

import com.wangzan101.mika.core.plugin.ScriptResult
import com.wangzan101.mika.core.plugin.ScriptRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 在 App 进程内跑插件脚本:`sh <script> <argsFile>`。stdout/stderr 全收,超时用 [destroyForcibly] 杀掉。
 * [shell] 默认 `/system/bin/sh`,测试里注入 `sh`;`MIKA_ARGS_FILE` 与 [envProvider] 的配置(环境变量页)都进进程环境。
 */
class PluginScriptRunner(
    private val shell: String = "/system/bin/sh",
    private val envProvider: () -> Map<String, String> = { emptyMap() },
) : ScriptRunner {
    override suspend fun run(script: File, argsFile: File, env: Map<String, String>, timeoutMs: Long): ScriptResult =
        withContext(Dispatchers.IO) {
            val pb = ProcessBuilder(shell, script.absolutePath, argsFile.absolutePath)
            pb.environment().apply {
                put("MIKA_ARGS_FILE", argsFile.absolutePath)
                putAll(envProvider())
                putAll(env)
            }
            val process = pb.start()
            val outF = CompletableFuture.supplyAsync { process.inputStream.readBytes().toString(Charsets.UTF_8) }
            val errF = CompletableFuture.supplyAsync { process.errorStream.readBytes().toString(Charsets.UTF_8) }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
            val exitCode = if (finished) process.exitValue() else -1
            fun read(f: CompletableFuture<String>): String = runCatching { f.get(2, TimeUnit.SECONDS) }.getOrDefault("")
            ScriptResult(exitCode, read(outF), read(errF), !finished)
        }
}
