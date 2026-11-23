package com.wangzan101.mika.cap.root

import android.os.SystemClock
import android.util.Base64
import com.wangzan101.mika.core.cap.root.RootCommandClass
import com.wangzan101.mika.core.cap.root.RootPort
import com.wangzan101.mika.core.cap.root.RootRun
import com.wangzan101.mika.core.device.shell.RootCommandSession
import com.wangzan101.mika.core.device.shell.SuAnswer
import com.wangzan101.mika.core.device.shell.SuProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Agent 的 root 命令:经 `su -c` 以 root 跑,标准输出、标准错误分开收(各最多 1 MB),到时限结束。
 * 命令先编成 base64 再在 root 的 sh 里解开交给 `sh -c`:外层那一行总是合法的 sh,命令里的引号、换行原样到达,
 * 写错的命令由里层 sh 报错,和「su 拒绝了」分得开(外层先打一行标记,拿不到标记就是 su 没跑)。
 * Root 侧独立监督命令进程组。App 到时限或取消时关闭标准输入，通知 Root 侧结束进程组。
 * [allowed] 每次执行前都重新检查:任务装着端口时用户关掉设置,后面的调用也不能再启动 su。
 * [su] 给出 su 的完整路径(找不到为 null);su 没跑这条命令时经 [refused] 报出去(null 是 su 不在了),由设置那边记下并关掉这一项。
 */
class RootCommands(
    private val su: () -> String?,
    private val allowed: () -> Boolean,
    private val refused: (SuAnswer?) -> Unit,
) : RootPort {
    override suspend fun run(command: String, timeoutMs: Long): RootRun = runInterruptible(Dispatchers.IO) {
        // 设置是任务运行期间也能改的;端口只是装配结果,不是长期有效的授权凭证
        if (!allowed()) return@runInterruptible RootRun.Denied
        val su = su() ?: return@runInterruptible RootRun.Unavailable.also { refused(null) }
        val started = SystemClock.elapsedRealtime()
        val p = runCatching { ProcessBuilder(SuProbe.rootArgs(su, script(command))).start() }
            .getOrElse { return@runInterruptible RootRun.Denied.also { refused(SuAnswer.Denied) } }
        val out = onThread { drain(p.inputStream) }
        val err = onThread { drain(p.errorStream) }
        val exited = try {
            p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            stop(p)
        }
        val duration = SystemClock.elapsedRealtime() - started
        val (stdout, outDropped) = awaitOutput(out)
        val (stderr, errDropped) = awaitOutput(err)
        if (!stdout.startsWith(MARK)) {
            // 标记都没打出来:su 没跑这条命令
            val a = if (!exited) SuAnswer.NoAnswer else SuAnswer.Denied
            refused(a)
            return@runInterruptible if (a == SuAnswer.NoAnswer) RootRun.NoAnswer else RootRun.Denied
        }
        val exit = if (exited) p.exitValue() else -1
        RootRun.Done(exit, !exited, stdout.removePrefix(MARK).removePrefix("\n"), stderr, outDropped + errDropped, duration)
    }

    private fun stop(p: Process) {
        // 即使 su 是守护进程的客户端，关闭管道也会通知实际执行命令的 Root 端。
        runCatching { p.outputStream.close() }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_GRACE_MS)
        var interrupted = false
        try {
            while (p.isAlive) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    p.destroyForcibly()
                    return
                }
                try {
                    if (p.waitFor(remaining, TimeUnit.NANOSECONDS)) return
                } catch (_: InterruptedException) {
                    // 给监督脚本时间处理 EOF，避免先杀掉还没开始监听的 su。
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun script(command: String): String {
        val b64 = Base64.encodeToString(command.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val path = if (RootCommandClass.classify(command) == RootCommandClass.Kind.ReadOnly) {
            "export PATH=${RootCommandClass.SYSTEM_COMMAND_PATH}; "
        } else ""
        return path + "echo $MARK; c=\"\$(echo $b64 | base64 -d)\"; " +
            RootCommandSession.androidScript
    }

    private fun awaitOutput(output: CompletableFuture<Pair<String, Long>>): Pair<String, Long> = try {
        output.get(2, TimeUnit.SECONDS)
    } catch (e: InterruptedException) {
        throw e
    } catch (_: Exception) {
        "" to 0L
    }

    /** 在单独的线程上读(不占公共线程池)。 */
    private fun <T> onThread(block: () -> T): CompletableFuture<T> = CompletableFuture<T>().also { f ->
        Thread({ runCatching(block).fold({ f.complete(it) }, { f.completeExceptionally(it) }) }, "mika-root").apply { isDaemon = true }.start()
    }

    /** 读完一个流,最多留 [MAX_BYTES],多的数着丢掉。 */
    private fun drain(stream: InputStream): Pair<String, Long> {
        val buf = ByteArrayOutputStream()
        var dropped = 0L
        val chunk = ByteArray(8192)
        runCatching {
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                val keep = minOf(n, MAX_BYTES - buf.size())
                if (keep > 0) buf.write(chunk, 0, keep)
                dropped += n - keep
            }
        }
        return buf.toString(Charsets.UTF_8.name()) to dropped
    }

    private companion object {
        const val MARK = "__mika_root_ok__"
        const val MAX_BYTES = 1 shl 20
        const val STOP_GRACE_MS = 2_000L
    }
}
