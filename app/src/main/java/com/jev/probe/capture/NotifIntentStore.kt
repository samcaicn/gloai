package com.jev.probe.capture

import android.app.PendingIntent

/**
 * 后台通知直驱：把每条微信通知自带的 contentIntent（[PendingIntent]）按会话
 * （conv = 群名 / 单聊对方昵称）暂存下来。
 *
 * 为什么需要它：多个会话同时来消息时，自动回复要一条条填进**各自正确**的会话窗口。
 * 微信 8.0.78 把聊天标题从无障碍树里剪掉（FLAG_SECURE），App 无法靠"读标题"判断
 * 前台是哪个会话——于是**通知自带的 PendingIntent 成了唯一可靠的"定位到那个会话"的
 * 锚点**。探针在每条回复发送前重放它，就能把微信重开到正确会话再发。
 *
 * PendingIntent 不能跨广播 extra 传递，但通知监听器（[WxNotificationListener]）与
 * 探针（ChatCaptureService）是**同一个 App 进程**，故用进程内单例直接持有。
 */
object NotifIntentStore {
    // 锚点保鲜期：超过这个时长的 PendingIntent 视为**不可信**。
    // 真实场景：群改名、会话重建、通知被清空后复用旧 intent——这时照着重放会把
    // 微信开到别的会话，而探针读不到标题（8.0.78）无从校验，等于"发错人"。
    private val FRESH_MS = 30 * 60 * 1000L

    private data class Holder(val pi: PendingIntent, val at: Long)

    // access-order LRU：最近用过的会话排到末尾，超量时从最旧开始淘汰。
    private val map = LinkedHashMap<String, Holder>(16, 0.75f, true)

    @Synchronized
    fun put(conv: String?, pi: PendingIntent?) {
        if (conv.isNullOrBlank() || pi == null) return
        map[conv] = Holder(pi, System.currentTimeMillis())
        if (map.size > 32) {
            val iter = map.keys.iterator()
            repeat(map.size - 16) { iter.next(); iter.remove() }
        }
    }

    @Synchronized
    fun get(conv: String?): PendingIntent? =
        if (conv.isNullOrBlank()) null else map[conv]?.pi

    /**
     * 只返回**新鲜**的锚点；过期返回 null，让调用方走"无锚点不自动发送"的保守路径。
     * 宁可不回，也不能把回复发到别的会话。
     */
    @Synchronized
    fun fresh(conv: String?): PendingIntent? {
        if (conv.isNullOrBlank()) return null
        val h = map[conv] ?: return null
        return if (System.currentTimeMillis() - h.at <= FRESH_MS) h.pi else null
    }

    @Synchronized
    fun remove(conv: String?) {
        if (!conv.isNullOrBlank()) map.remove(conv)
    }

    @Synchronized
    fun clear() = map.clear()
}
