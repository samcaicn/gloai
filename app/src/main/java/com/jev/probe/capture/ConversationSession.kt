package com.jev.probe.capture

/** Main-thread session state. A return to the same chat never revives an old request. */
internal class ConversationSession {
    data class Target(
        val pkg: String,
        val windowId: Int,
        val title: String?,
        val messagesSignature: String? = null
    )
    data class Token(val target: Target, val revision: Long)

    var target: Target? = null
        private set
    private var revision = 0L

    fun observe(next: Target?): Boolean {
        if (target == next) return false
        target = next
        invalidate()
        return true
    }

    fun invalidate() { revision++ }

    fun token(): Token? = target?.let { Token(it, revision) }

    fun begin(): Token? {
        invalidate()
        return token()
    }

    /**
     * 后台纯通知场景专用：微信在后台、无障碍没抓到聊天窗口，[target] 可能为 null，
     * [token] 会因此返回 null 而把通知直驱分析整个吞掉。这里用通知里的会话名构造一个
     * 逻辑 target（windowId = [NOTIF_WINDOW] 作标记），[isCurrent] 见到该标记就跳过
     * 「前台窗口必须匹配」的检查——文本来自通知，本就不依赖实时窗口。
     */
    fun beginNotification(pkg: String, title: String?): Token {
        val t = Target(pkg, NOTIF_WINDOW, title, null)
        target = t
        revision++
        return Token(t, revision)
    }

    fun accepts(token: Token): Boolean =
        token.target == target && token.revision == revision

    companion object {
        /** 后台通知驱动 target 的 windowId 标记，见 [beginNotification]。 */
        const val NOTIF_WINDOW = -1
    }
}
