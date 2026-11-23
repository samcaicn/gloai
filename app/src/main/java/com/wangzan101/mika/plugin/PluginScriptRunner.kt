package com.wangzan101.mika.plugin

import com.wangzan101.mika.core.tool.ScriptResult
import com.wangzan101.mika.core.tool.ScriptRunner
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
            val outF = CompletableFuture.supplyAsync { readCapped(process.inputStream, MAX_OUTPUT_BYTES) }
            val errF = CompletableFuture.supplyAsync { readCapped(process.errorStream, MAX_OUTPUT_BYTES) }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
            val exitCode = if (finished) process.exitValue() else -1
            fun read(f: CompletableFuture<String>): String = runCatching { f.get(2, TimeUnit.SECONDS) }.getOrDefault("")
            ScriptResult(exitCode, read(outF), read(errF), !finished)
        }

    override suspend fun runStdin(script: File, stdin: String, env: Map<String, String>, timeoutMs: Long): ScriptResult =
        withContext(Dispatchers.IO) {
            val pb = ProcessBuilder(shell, script.absolutePath)
            pb.environment().apply {
                putAll(envProvider())
                putAll(env)
            }
            val process = pb.start()
            val outF = CompletableFuture.supplyAsync { readCapped(process.inputStream, MAX_OUTPUT_BYTES) }
            val errF = CompletableFuture.supplyAsync { readCapped(process.errorStream, MAX_OUTPUT_BYTES) }
            CompletableFuture.runAsync { runCatching { process.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) } } }
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

/** 插件脚本输出最多进内存这么多;再多的丢弃(免得一个脚本把内存写爆)。 */
private const val MAX_OUTPUT_BYTES = 1_000_000

/** 读 [input] 最多 [maxBytes] 字节,其余读完丢弃后返回。 */
internal fun readCapped(input: java.io.InputStream, maxBytes: Int): String {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var total = 0
    while (total < maxBytes) {
        val n = input.read(chunk, 0, minOf(chunk.size, maxBytes - total))
        if (n < 0) break
        out.write(chunk, 0, n)
        total += n
    }
    while (input.read(chunk) >= 0) { /* drain the rest so the process isn't blocked on a full pipe */ }
    return out.toString(Charsets.UTF_8)
}
