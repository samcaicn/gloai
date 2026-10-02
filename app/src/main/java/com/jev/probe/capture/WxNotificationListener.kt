package com.jev.probe.capture

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        if (s.packageName != PKG_WECHAT) return
        val n = s.notification ?: return
        val extras = n.extras ?: return

        val title = extras.getString(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        // WeChat's "微信 · N 条新消息" summary tile — no single conversation to act on.
        if (isSummary(title, text)) return

        // De-dupe repeated posts of the exact same content within 2s.
        val now = System.currentTimeMillis()
        val key = "$title $text"
        val last = seen[key]
        if (last != null && now - last < 2000) return
        seen[key] = now
        if (seen.size > 200) {
            val iter = seen.keys.iterator()
            repeat(seen.size - 100) { iter.next(); iter.remove() }
        }

        val isImage = isImageMessage(text)

        // Accumulate the preview into cross-session history immediately — this works
        // even when WeChat is in the background and the chat never opens, so the
        // judge later sees the full thread instead of only the visible screen.
        ConversationHistory.appendFromNotification(packageName, title, title, text, isImage)

        // Tell the capture service a message arrived; it re-reads the live chat.
        // Explicit package: keeps the broadcast inside our own app instead of
        // relying on the receiver's NOT_EXPORTED flag alone.
        runCatching {
            sendBroadcast(Intent(ChatCaptureService.ACTION_WX_MSG).apply {
                setPackage(packageName)
                putExtra("sender", title)
                putExtra("text", text)
                putExtra("image", isImage)
            })
        }

        // Unattended mode: if enabled, bring the conversation to the foreground so
        // the accessibility service can read + reply even when the phone is idle.
        // The tree read still gates on the live chat, so if this fails to open we
        // simply do nothing rather than acting on a stale conversation.
        if (Prefs(this).autoOpenChat && !isWeChatForeground()) {
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

    companion object {
        private const val PKG_WECHAT = "com.tencent.mm"
    }
}
