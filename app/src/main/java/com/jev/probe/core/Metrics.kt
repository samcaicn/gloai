package com.jev.probe.core

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 本地事件日志（P0-1 可观测）。
 *
 * 三条规定，改动时不能破：
 * 1. **默认关闭**。只有用户在设置里显式打开「帮助改进（只存本机）」才写。
 * 2. **只写本机** `filesDir/metrics/events.log`，不上传、不随备份外发。
 * 3. **绝不记录聊天正文、会话标题明文、密钥**。需要标识会话时用 [hash] 取摘要。
 *
 * 用法：`Metrics.log("analysis_end", "ok" to true, "ms" to 1234)`。
 * 计时用 [markStart] / [markEnd]，后者会自动补上耗时。
 */
object Metrics {

    private const val TAG = "JEVASSIST"
    private const val FILE = "events.log"
    /** 内存里最多留多少条（[recent] / 诊断卡用）。 */
    private const val CAP = 400
    /** 落盘文件超过这个大小就截掉一半，避免长期增长。 */
    private const val MAX_FILE_BYTES = 200_000L

    @Volatile
    private var dir: File? = null

    @Volatile
    private var on = false

    private val buf = CopyOnWriteArrayList<String>()
    private val starts = ConcurrentHashMap<String, Long>()
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "metrics-io").apply { isDaemon = true }
    }

    /** 在 Application / Service 起来时调一次；[enabled] 来自 [Prefs.metricsEnabled]。 */
    fun init(ctx: Context, enabled: Boolean) {
        dir = File(ctx.applicationContext.filesDir, "metrics").apply {
            runCatching { mkdirs() }
        }
        on = enabled
    }

    fun setEnabled(v: Boolean) {
        on = v
        if (!v) buf.clear()
    }

    val enabled: Boolean get() = on

    /** 记一条事件。属性值为 null 时跳过该属性。 */
    fun log(event: String, vararg kv: Pair<String, Any?>) {
        if (!on) return
        val sb = StringBuilder(96)
        sb.append(System.currentTimeMillis()).append(' ').append(event)
        for ((k, v) in kv) {
            if (v == null) continue
            sb.append(' ').append(clean(k)).append('=').append(clean(v.toString()))
        }
        val line = sb.toString()
        buf.add(line)
        while (buf.size > CAP) buf.removeAt(0)
        runCatching { io.execute { appendLine(line) } }
            .onFailure { Log.w(TAG, "metrics: queue failed") }
    }

    /** 开始计时（同名 key 只保留最后一次）。 */
    fun markStart(key: String) {
        if (!on) return
        starts[key] = System.currentTimeMillis()
    }

    /**
     * 结束计时并记一条带 `ms=` 的事件；没配对的 start 则 ms 省略。
     * @return 本段耗时（毫秒），没配对或统计关闭时返回 null。
     */
    fun markEnd(key: String, event: String, vararg kv: Pair<String, Any?>): Long? {
        if (!on) return null
        val t = starts.remove(key) ?: run { log(event, *kv); return null }
        val ms = System.currentTimeMillis() - t
        log(event, *(listOf("ms" to ms) + kv).toTypedArray())
        return ms
    }

    /** 最近 n 条（新的在后），给诊断卡用。 */
    fun recent(n: Int = 80): List<String> {
        val size = buf.size
        return if (size <= n) buf.toList() else buf.subList(size - n, size).toList()
    }

    /** 事件日志的可复制文本（含文件大小与条数）。为空时说明统计没开。 */
    fun snapshotText(): String {
        val lines = recent(120)
        if (lines.isEmpty()) return "（事件日志为空：统计未开启，或还没有任何事件）"
        val head = "事件 ${lines.size} 条（本机 events.log，${logFile()?.length() ?: 0} 字节）"
        return (listOf(head) + lines).joinToString("\n")
    }

    fun logFile(): File? = dir?.let { File(it, FILE) }

    /** 清空内存与磁盘上的事件日志（设置页「清除」用）。 */
    fun clear() {
        buf.clear()
        starts.clear()
        io.execute {
            runCatching { logFile()?.delete() }
        }
    }

    /**
     * 会话/标题的不可逆摘要：诊断需要区分两个会话，但绝不能把标题明文写进日志。
     * 取 SHA-256 前 10 位 hex；null/空白统一返回 "-"。
     */
    fun hash(s: String?): String {
        if (s.isNullOrBlank()) return "-"
        return try {
            val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            d.take(5).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            "?"
        }
    }

    /** 属性清洗：去掉换行/制表（保证一行一条），并限长。 */
    private fun clean(s: String): String =
        s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').take(48)

    private fun appendLine(line: String) {
        val f = logFile() ?: return
        try {
            f.appendText(line + "\n", Charsets.UTF_8)
            if (f.length() > MAX_FILE_BYTES) trim(f)
        } catch (e: Exception) {
            Log.w(TAG, "metrics: write failed: ${e.message}")
        }
    }

    private fun trim(f: File) {
        val lines = f.readLines(Charsets.UTF_8)
        val keep = lines.takeLast(lines.size / 2)
        f.writeText(keep.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }
}
