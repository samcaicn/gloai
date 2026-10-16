package com.jev.probe.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 持久化的「未发送回复」队列。
 *
 * 为什么需要它：微信 8.0.78 黑屏 + 系统/用户切走会话 + 探针进程被 kill，都可能让一条
 * 本该自动发出的回复半路失败。原先失败只把草稿留在输入框、靠 dwell 超时把轮次丢弃，
 * 这条回复就永久丢了（用户看到「没回复」）。这里把「会话 + 文本 + 已尝试次数 + 首次时间」
 * 落盘到 SharedPreferences，进程死/重启都不丢，会话下次活跃（来新通知 / 回到前台）时
 * 由 [com.jev.probe.capture.ChatCaptureService.drainPendingSends] 重新驱动发送。
 */
data class PendingSend(
    val conv: String,
    val text: String,
    val tries: Int,
    val firstAt: Long
)

object SendQueueStore {
    private const val SP = "send_queue_v1"
    private const val KEY = "pending"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
    }

    @Synchronized fun all(): List<PendingSend> {
        val p = prefs ?: return emptyList()
        val raw = p.getString(KEY, "[]") ?: "[]"
        val out = mutableListOf<PendingSend>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    PendingSend(
                        conv = o.getString("conv"),
                        text = o.getString("text"),
                        tries = o.optInt("tries", 0),
                        firstAt = o.optLong("firstAt", 0L)
                    )
                )
            }
        }
        return out
    }

    /** 按会话 upsert：同一会话只保留最新一条未发（文本可能被新分析覆盖）。 */
    @Synchronized fun add(p: PendingSend) {
        val cur = prefs ?: return
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.conv == p.conv }
        if (idx >= 0) list[idx] = p else list.add(p)
        save(list)
    }

    @Synchronized fun remove(conv: String) {
        val cur = prefs ?: return
        save(all().filter { it.conv != conv })
    }

    @Synchronized fun clear() {
        prefs?.edit()?.putString(KEY, "[]")?.apply()
    }

    private fun save(list: List<PendingSend>) {
        val arr = JSONArray()
        list.forEach { ps ->
            JSONObject().apply {
                put("conv", ps.conv)
                put("text", ps.text)
                put("tries", ps.tries)
                put("firstAt", ps.firstAt)
                arr.put(this)
            }
        }
        prefs?.edit()?.putString(KEY, arr.toString())?.apply()
    }
}
