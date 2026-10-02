package com.jev.probe.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.Executors

/**
 * 跨来源的会话历史，供"自动监听 / 无人值守"模式使用。
 *
 * 消息来自两条路径，互补后才完整：
 *  - 通知预览（sender + text）：微信在后台也能拿到，第一时间累积，不依赖屏幕可见；
 *  - 屏幕快照（带 side 的完整气泡）：聊天在前台时由无障碍树 / 本地 OCR 读出。
 *
 * judge / draftAndRank 通过 [mergeInto] 拿到"历史 + 当前屏"合起来的整段对话，
 * 而不是只有当前可见一屏——这才是"自动监听出结果"的关键。
 *
 * 进程被 ColorOS 冻死/回收后内存会丢，所以每次写入都异步落盘（filesDir/history.json），
 * 下次启动 [attach] 时恢复。
 */
object ConversationHistory {

    private const val MAX_PER_CONV = 60          // 单会话最多保留多少条
    private const val MAX_CONV = 50              // 最多保留多少个会话
    private const val DUP_WINDOW_MS = 3000L      // 同一文本 3 秒内不重复计
    // 整屏去重：同一屏内容在短时间内被反复 OCR 时，整屏跳过，避免逐条膨胀
    private const val SCREEN_DUP_MS = 60_000L

    private data class Entry(val side: String, val text: String, val t: Long)

    private val store = LinkedHashMap<String, MutableList<Entry>>(MAX_CONV, 0.75f, true)
    // 每会话"上一次整屏签名 + 时间"，用于整屏级去重
    private val lastScreen = HashMap<String, Pair<Long, String>>()
    private var file: File? = null
    private val disk = Executors.newSingleThreadExecutor()

    fun attach(ctx: Context) {
        file = File(ctx.filesDir, "history.json")
        disk.execute { runCatching { load() } }
    }

    @Synchronized
    private fun key(pkg: String, title: String?): String = "$pkg|${title ?: ""}"

    /** 前台屏幕读到的完整气泡（带 me/other），合并进历史。 */
    @Synchronized
    fun appendFromSnapshot(pkg: String, title: String?, msgs: List<Msg>) {
        if (msgs.isEmpty()) return
        val k = key(pkg, title)
        // 整屏级去重：同一屏（内容签名相同）在窗口内重复 OCR 时整屏跳过，
        // 否则 addUnique 只跟"上一条"比，整屏会逐次线性膨胀成几十条。
        val sig = msgs.joinToString("\u0000") { "${it.side}:${it.text}" }
        val prev = lastScreen[k]
        if (prev != null && prev.second == sig &&
            System.currentTimeMillis() - prev.first < SCREEN_DUP_MS) return
        lastScreen[k] = System.currentTimeMillis() to sig
        val list = store.getOrPut(k) { ArrayList() }
        for (m in msgs) addUnique(list, m.side, m.text)
        trim(list)
        persist()
    }

    /** 微信通知预览（含群名/发送者/正文）。即使聊天没打开也累积，side 一律记对方。 */
    @Synchronized
    fun appendFromNotification(pkg: String, title: String?, sender: String?, text: String?, isImage: Boolean) {
        val body = text ?: return
        val k = key(pkg, title ?: sender)
        val list = store.getOrPut(k) { ArrayList() }
        val label = if (sender.isNullOrBlank()) "对方" else sender
        addUnique(list, "other", if (isImage) "[图片] $body" else body)
        trim(list)
        persist()
    }

    /** 取最近 limit 条，按时间正序，给 judge 当上下文。 */
    @Synchronized
    fun threadFor(pkg: String, title: String?, limit: Int = MAX_PER_CONV): List<Msg> {
        val list = store[key(pkg, title)] ?: return emptyList()
        return list.takeLast(limit).map { Msg(it.side, it.text) }
    }

    /**
     * 把历史里"屏幕上看不到的更早消息"拼到快照前面，得到完整线程。
     * 屏幕可见的 messages 已是最新，用签名去重避免重复计入。
     */
    @Synchronized
    fun mergeInto(snapshot: ChatSnapshot, pkg: String, limit: Int = MAX_PER_CONV): ChatSnapshot {
        val hist = threadFor(pkg, snapshot.title, limit)
        if (hist.isEmpty()) return snapshot
        val visible = snapshot.messages
        val visibleSig = visible.map { "${it.side}:${it.text}" }.toSet()
        val older = hist.filter { "${it.side}:${it.text}" !in visibleSig }
        if (older.isEmpty()) return snapshot
        return snapshot.copy(messages = older + visible)
    }

    @Synchronized
    fun clear() {
        store.clear()
        persist()
    }

    private fun addUnique(list: MutableList<Entry>, side: String, text: String) {
        val t = System.currentTimeMillis()
        val last = list.lastOrNull()
        if (last != null && last.text == text && t - last.t < DUP_WINDOW_MS) return
        list.add(Entry(side, text, t))
    }

    private fun trim(list: MutableList<Entry>) {
        while (list.size > MAX_PER_CONV) list.removeAt(0)
        while (store.size > MAX_CONV) {
            val it = store.keys.iterator(); it.next(); it.remove()
        }
    }

    private fun persist() {
        val f = file ?: return
        val snap = synchronized(this) {
            JSONArray().apply {
                for ((k, entries) in store) {
                    val arr = JSONArray()
                    for (e in entries) arr.put(JSONObject().apply {
                        put("s", e.side); put("t", e.text); put("tm", e.t)
                    })
                    put(JSONObject().apply { put("k", k); put("m", arr) })
                }
            }.toString()
        }
        disk.execute { runCatching { f.writeText(snap) } }
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val root = JSONArray(f.readText())
            synchronized(this) {
                store.clear()
                for (i in 0 until root.length()) {
                    val obj = root.getJSONObject(i)
                    val k = obj.getString("k")
                    val arr = obj.getJSONArray("m")
                    val list = ArrayList<Entry>()
                    for (j in 0 until arr.length()) {
                        val e = arr.getJSONObject(j)
                        list.add(Entry(e.getString("s"), e.getString("t"), e.optLong("tm", 0L)))
                    }
                    store[k] = list
                }
            }
        }
    }
}
