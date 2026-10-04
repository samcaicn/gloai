package com.jev.probe.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

// 崩溃 / ANR 的本地记录（P0-5）。
//
// 只写 filesDir/crash/crash.log，不上传、不进 logcat 正文。存在的意义是：
// 无 root 的真机上 ANR 目录是 system:system 属主，adb pull 拉不出来
// （2026-10-05 卡死排查就卡在这里），而 ANR 发生几小时后 logcat 也会被滚掉。
// 有自己的记录，事后才有得查。
//
// 堆栈里可能出现会话标题之类的字串，所以这个文件只随用户手动导出的诊断卡
// 离开设备，绝不自动外发。
object CrashLog {

    private const val TAG = "JEVASSIST"
    private const val FILE = "crash.log"
    private const val MAX_BYTES = 150_000L

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "crashlog-io").apply { isDaemon = true }
    }

    fun record(ctx: Context, kind: String, threadName: String, e: Throwable) {
        val stack = e.stackTrace.take(20).joinToString("\n") { "  at $it" }
        val cause = e.cause?.let { "\ncause: ${it.javaClass.name}: ${it.message}" }.orEmpty()
        recordRaw(ctx, kind, "$threadName: ${e.javaClass.name}: ${e.message}$cause\n$stack")
    }

    fun recordRaw(ctx: Context, kind: String, body: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val line = "[$ts] $kind\n$body\n"
        Log.w(TAG, "$kind recorded to $FILE")
        runCatching {
            io.execute {
                try {
                    val f = file(ctx)
                    f.appendText(line, Charsets.UTF_8)
                    if (f.length() > MAX_BYTES) {
                        val keep = f.readLines(Charsets.UTF_8).takeLast(200)
                        f.writeText(keep.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "crash log write failed: ${e.message}")
                }
            }
        }
    }

    fun file(ctx: Context): File =
        File(File(ctx.applicationContext.filesDir, "crash").apply { runCatching { mkdirs() } }, FILE)

    // 诊断卡用；没有记录时返回 null（比返回空串更好判断）。
    fun text(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists() || f.length() == 0L) return null
        return runCatching { f.readText(Charsets.UTF_8).takeLast(4000) }.getOrNull()
    }

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }
}
