package com.jev.probe.capture

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jev.probe.core.ConversationHistory
import com.jev.probe.core.Prefs

/**
 * WeChat notification listener. Its job in the unattended loop is the RECEIVE
 * side: when a new WeChat message lands it tells [ChatCaptureService] (via a
 * same-app broadcast) to re-read the open chat and, if the message was a picture,
 * to OCR it. It also optionally auto-opens the conversation so the accessibility
 * service can take over even when WeChat was in the background.
 *
 * It does NOT read message bodies into any network/disk store — only enough to
 * dedupe and to decide "is this an image" / "is this a real message vs. a
 * summary tile". The actual chat text still comes from the accessibility tree.
 */
class WxNotificationListener : NotificationListenerService() {

    /** (title|text) -> last post time, for de-duplicating re-posted notifications. */
    private val seen = LinkedHashMap<String, Long>(128, 0.75f, true)

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Make sure history persistence is wired even if the capture service has
        // not connected yet (the listener can receive events first at boot).
        ConversationHistory.attach(this)
        Log.i(TAG, "onListenerConnected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "onListenerDisconnected (system rebound / revoke?)")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: run { Log.d(TAG, "onNotificationPosted: sbn==null"); return }
        if (s.packageName != PKG_WECHAT) return
        val n = s.notification ?: run { Log.d(TAG, "onNotificationPosted: notification==null"); return }
        val extras = n.extras ?: run { Log.d(TAG, "onNotificationPosted: extras==null"); return }

        val title = extras.getString(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        Log.d(TAG, "onNotificationPosted: title='$title' textLen=${text?.length} head='${text?.take(24)}'")
        if (title.isNullOrBlank() && text.isNullOrBlank()) {
            Log.d(TAG, "onNotificationPosted: blank title+text -> skip")
            return
        }

        // WeChat's "微信 · N 条新消息" summary tile — no single conversation to act on.
        if (isSummary(title, text)) { Log.d(TAG, "onNotificationPosted: summary tile -> skip"); return }

        // De-dupe repeated posts of the exact same content within 2s.
        val now = System.currentTimeMillis()
        val key = "$title $text"
        val last = seen[key]
        if (last != null && now - last < 2000) {
            Log.d(TAG, "onNotificationPosted: dedupe <2s -> skip")
            return
        }
        seen[key] = now
        if (seen.size > 200) {
            val iter = seen.keys.iterator()
            repeat(seen.size - 100) { iter.next(); iter.remove() }
        }

        val isImage = isImageMessage(text)

        // 群消息 text 形如「张三：在吗」——把个人发送人解析出来，正文去掉前缀，
        // 否则 AI 既看不到谁说的、又会把「张三：」重复写进提示词。
        // 单聊 text 无前缀，发送人就是会话名（title=对方昵称）。
        val (speaker, body) = parseSpeaker(text)
        val sender = speaker ?: title
        val conv = title

        // Accumulate the preview into cross-session history immediately — this works
        // even when WeChat is in the background and the chat never opens, so the
        // judge later sees the full thread instead of only the visible screen.
        ConversationHistory.appendFromNotification(packageName, conv, sender, body, isImage)

        // Tell the capture service a message arrived; it re-reads the live chat.
        // Explicit package: keeps the broadcast inside our own app.
        // 分两个 extra 传：conv=会话(群名/单人)，sender=个人发送人，text=正文。
        Log.d(TAG, "onNotificationPosted: broadcast WX_MSG conv='$conv' sender='$sender' textLen=${body.length} image=$isImage")
        runCatching {
            sendBroadcast(Intent(ChatCaptureService.ACTION_WX_MSG).apply {
                setPackage(packageName)
                putExtra("conv", conv)
                putExtra("sender", sender)
                putExtra("text", body)
                putExtra("image", isImage)
            })
        }

        // 多会话同时来消息：先把这条通知的 contentIntent 按 conv 暂存，探针的通知轮队列
        // 会在每条回复发送前重放它、把微信重开到"这个"会话（8.0.78 读不到标题，PendingIntent
        // 是唯一可靠的定位锚点，见 NotifIntentStore）。下面的 warm-start 开窗保留作首条兜底。
        NotifIntentStore.put(conv, n.contentIntent)

        // Unattended mode: if enabled, bring the conversation to the foreground so
        // the accessibility service can read + reply even when the phone is idle.
        if (Prefs(this).autoOpenChat && !isWeChatForeground()) {
            Log.d(TAG, "onNotificationPosted: autoOpenChat ON & WeChat not fg -> contentIntent.send()")
            runCatching { n.contentIntent?.send() }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    /** A grouped summary tile rather than a concrete message. */
    private fun isSummary(title: String?, text: String?): Boolean {
        if (title == "微信" || title == "WeChat") {
            val t = text ?: ""
            if (t.contains("条新消息") || t.contains("new messages") || t.contains("条消息")) return true
        }
        return false
    }

    /** WeChat marks image/video/voice messages with a placeholder in the text.
     *  That placeholder is the reliable signal here; a MessagingStyle data-mime
     *  probe is intentionally avoided (it depends on an API level where
     *  Notification.MessagingStyle.Message exposes a `data` member and is fragile
     *  across Android versions). */
    private fun isImageMessage(text: String?): Boolean {
        val t = text ?: ""
        return t.contains("[图片]") || t.contains("[图像]") || t.contains("[视频]") ||
            t.contains("[相册]") || t.contains("[照片]") || t.contains("[Image]") ||
            t.contains("[Sticker]") || t.contains("[表情]")
    }

    /** Whether WeChat is the foreground app right now. The capture service keeps
     *  this live (it sees rootInActiveWindow on every event), so we don't need a
     *  restricted usage/running-tasks permission here. */
    private fun isWeChatForeground(): Boolean = ChatCaptureService.weChatForeground

    /**
     * 从通知预览文本里拆出个人发送人。
     * 群消息：「张三：在吗」/「张三: 在吗」→ ("张三", "在吗")；
     * 单聊：纯正文、无「短名：」前缀 → (null, 原文)。
     * 仅当冒号前是 ≤12 字的非空白片段、且冒号后有内容时才算群格式，
     * 避免把「http://...」或正文里的冒号误判成发送人。
     */
    private fun parseSpeaker(text: String?): Pair<String?, String> {
        val t = text ?: return null to ""
        val m = Regex("^([^\\s：:]{1,12})[：:]\\s*(.*)$").find(t)
        if (m != null) {
            val name = m.groupValues[1]
            val rest = m.groupValues[2]
            if (rest.isNotBlank()) return name to rest
        }
        return null to t
    }

    companion object {
        private const val PKG_WECHAT = "com.tencent.mm"
        private const val TAG = "WxNotif"
    }
}
