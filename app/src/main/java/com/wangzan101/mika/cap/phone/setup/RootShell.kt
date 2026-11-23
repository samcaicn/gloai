package com.wangzan101.mika.cap.phone.setup

import com.wangzan101.mika.core.device.shell.SuAnswer
import com.wangzan101.mika.core.device.shell.SuProbe
import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 手机上的 su:找它、问它、经它跑进程。只按能力判断(找得到 su 文件、跑出来的 uid),不认是哪种 root。
 * 所有方法都会阻塞(su 可能正弹着授权框等用户),只在 IO 线程上调。
 */
object RootShell {
    /** su 的完整路径;找不到为 null。 */
    fun su(): String? = SuProbe.find(System.getenv("PATH")) { runCatching { File(it).exists() }.getOrDefault(false) }

    /**
     * 问 su 一次:以 [uid](0 是 root,2000 是 shell)跑 `id`,最多等 [timeoutMs](第一次会弹授权框,要给用户时间)。
     * 找不到 su 为 null。
     */
    fun ask(uid: Int, timeoutMs: Long): SuAnswer? {
        val su = su() ?: return null
        val args = if (uid == 0) SuProbe.rootArgs(su, "id") else SuProbe.shellArgs(su, "id")
        val p = runCatching { start(args) }.getOrElse { return SuAnswer.Denied }
        val out = collect(p.inputStream)
        val exited = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!exited) p.destroyForcibly()
        val text = runCatching { out.get(1, TimeUnit.SECONDS) }.getOrDefault("")
        return SuProbe.answer(text, timedOut = !exited, expectUid = uid)
    }

    /** 起一个 su 进程;标准错误并进标准输出(拒绝时 su 的说明在那里)。 */
    fun start(args: List<String>): Process = ProcessBuilder(args).redirectErrorStream(true).start()

    /**
     * 读进程输出,直到读到一行 uid(返回它)、流结束(null:su 没跑命令就退出了)或到 [timeoutMs](抛 java.util.concurrent.TimeoutException)。
     * 超时由调用方结束进程。
     */
    fun readUid(p: Process, timeoutMs: Long): Int? {
        val f = onThread {
            val r = p.inputStream.bufferedReader()
            generateSequence { runCatching { r.readLine() }.getOrNull() }.firstNotNullOfOrNull(SuProbe::uid)
        }
        return f.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** 在单独的线程上做阻塞的读(不占公共线程池)。 */
    fun <T> onThread(block: () -> T): CompletableFuture<T> = CompletableFuture<T>().also { f ->
        Thread({ runCatching(block).fold({ f.complete(it) }, { f.completeExceptionally(it) }) }, "mika-su").apply { isDaemon = true }.start()
    }

    /** 在另一条线程上把 [stream] 读完(最多 [max] 字节,多的丢掉)。 */
    fun collect(stream: InputStream, max: Int = 16 * 1024): CompletableFuture<String> = onThread {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        runCatching {
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                if (buf.size() < max) buf.write(chunk, 0, minOf(n, max - buf.size()))
            }
        }
        buf.toString(Charsets.UTF_8.name())
    }
}
