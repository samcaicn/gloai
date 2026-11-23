package com.wangzan101.mika.cap.phone.shell

import android.os.Bundle
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TAG
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.CMD_TIMEOUT_S
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.COMMAND_OUTPUT_MAX
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.READER_JOIN_MS

// 助手服务:执行命令与解锁
internal fun ShellService.commandResult(code: Int, output: String) = Bundle().apply { putInt("exitCode", code); putString("output", output) }

/** 以 shell 身份跑 `sh -c`,合并输出,限时限长;[displayId] 不为空时放进 MIKA_DISPLAY_ID。 */
internal fun ShellService.exec(command: String, displayId: Int?): Bundle {
    fun result(code: Int, output: String) = commandResult(code, output)
    if (command.isBlank() || command.length > 4_000) return result(-1, "Invalid command length.")
    val process = try {
        ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true)
            .apply { if (displayId != null) environment()["MIKA_DISPLAY_ID"] = displayId.toString() }.start()
    } catch (t: Throwable) {
        return result(-1, t.message ?: "Command could not start.")
    }
    val bytes = ByteArrayOutputStream()
    val reader = Thread {
        runCatching {
            process.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() < COMMAND_OUTPUT_MAX) bytes.write(buffer, 0, minOf(count, COMMAND_OUTPUT_MAX - bytes.size()))
                }
            }
        }
    }.apply { isDaemon = true; start() }
    val finished = process.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)
    if (!finished) process.destroyForcibly()
    reader.join(READER_JOIN_MS)
    val output = bytes.toString(Charsets.UTF_8.name()) + if (bytes.size() >= COMMAND_OUTPUT_MAX) "\n[output truncated]" else ""
    return result(if (finished) process.exitValue() else -1, output)
}

internal fun ShellService.exec(vararg cmd: String): Int = sh(*cmd).first

/**
 * 跑一条 shell 命令,返回 (退出码, 输出);超时返回 -1。
 * 输出在单独线程里读,卡住的命令不会把 binder 线程永远堵住。
 */
internal fun ShellService.sh(vararg cmd: String): Pair<Int, String> {
    val p = try {
        ProcessBuilder(*cmd).redirectErrorStream(true).start()
    } catch (t: Throwable) {
        Log.w(TAG, "exec ${cmd.joinToString(" ")} failed", t)
        return -1 to ""
    }
    val output = AtomicReference("")
    val reader = Thread { output.set(runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")) }
        .apply { isDaemon = true; start() }
    if (!p.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)) {
        p.destroyForcibly()
        Log.w(TAG, "exec ${cmd.joinToString(" ")} timed out")
        return -1 to ""
    }
    reader.join(READER_JOIN_MS)
    return p.exitValue() to output.get()
}
